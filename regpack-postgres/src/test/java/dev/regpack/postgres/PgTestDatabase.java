package dev.regpack.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The real-store test harness (Convention 4). {@code REGPACK_PG_URL} is a JDBC URL without a schema
 * (for example {@code jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres}). Unset, or
 * unreachable: the calling test is skipped by an assumption. With {@code REGPACK_PG_REQUIRED=true}
 * an unset or unreachable URL is a failure instead, so a CI job that must run the real store never
 * passes by skipping (review focus LF2). Each test works in its own schema.
 *
 * <p>No message of this class repeats the URL, which may carry a password.
 */
public final class PgTestDatabase {
  private static final String URL_VARIABLE = "REGPACK_PG_URL";
  private static final String REQUIRED_VARIABLE = "REGPACK_PG_REQUIRED";
  private static final String STANDBY_URL =
      "jdbc:postgresql://127.0.0.1:5433/postgres?user=postgres";
  private static final AtomicInteger COUNTER = new AtomicInteger();
  private static final Object LOCK = new Object();
  private static final Pattern FRESH_SCHEMA = Pattern.compile("t[0-9]+_[0-9]+");
  private static DataSource shared;
  private static String unavailableReason;

  private PgTestDatabase() {}

  /** True when {@code REGPACK_PG_REQUIRED} is exactly {@code true}. */
  public static boolean required() {
    return "true".equals(System.getenv(REQUIRED_VARIABLE));
  }

  /**
   * A data source for the server of {@code REGPACK_PG_URL}, checked once by opening a connection.
   * Skips the calling test when there is no usable server and the real store is not required; fails
   * it otherwise.
   */
  public static DataSource dataSource() {
    synchronized (LOCK) {
      if (shared != null) {
        return shared;
      }
      if (unavailableReason == null) {
        String url = System.getenv(URL_VARIABLE);
        String reason = probe(url);
        if (reason == null) {
          shared = connect(url);
          return shared;
        }
        unavailableReason = reason;
      }
      // the first check already failed: later calls do not wait for the connect timeout again
      return unavailable(unavailableReason, required());
    }
  }

  /**
   * The pure decision of the harness: a data source for {@code url} when the server answers;
   * otherwise a failure naming {@code REGPACK_PG_REQUIRED} when {@code required} is set, and a skip
   * by assumption when it is not. It reads no environment variable and keeps no state, so it can be
   * tested without a server (with an unreachable URL). The message never repeats the URL, which may
   * carry a password.
   */
  public static DataSource requireOrAbort(String url, boolean required) {
    String reason = probe(url);
    if (reason == null) {
      return connect(url);
    }
    return unavailable(reason, required);
  }

  /** Null when the server answers; otherwise the reason it cannot be used, without the URL. */
  private static String probe(String url) {
    if (url == null || url.isBlank()) {
      return URL_VARIABLE + " is not set";
    }
    PGSimpleDataSource candidate = new PGSimpleDataSource();
    try {
      candidate.setUrl(url);
    } catch (IllegalArgumentException e) {
      // the driver's message repeats the whole URL, password included: use neither it nor the
      // cause
      return URL_VARIABLE + " is set but is not a valid JDBC URL";
    }
    try (Connection connection = candidate.getConnection()) {
      if (connection.isValid(10)) {
        return null;
      }
      return URL_VARIABLE + " is set but the connection is not valid";
    } catch (SQLException e) {
      return URL_VARIABLE
          + " is set but the server is unreachable (sqlstate="
          + e.getSQLState()
          + ")";
    }
  }

  private static DataSource connect(String url) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    try {
      dataSource.setUrl(url);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(URL_VARIABLE + " is not a valid JDBC URL");
    }
    return dataSource;
  }

  private static DataSource unavailable(String reason, boolean required) {
    if (required) {
      Assertions.fail(REQUIRED_VARIABLE + "=true and " + reason);
    }
    return Assumptions.abort("real-store test skipped: " + reason);
  }

  /**
   * A data source on the same server as {@link #dataSource()} whose connections start with these
   * server options (the {@code options} connection parameter of the driver, for example {@code -c
   * synchronous_commit=local}). Skips or fails like {@link #dataSource()} when there is no server.
   * The job {@code ledger-durability} uses it to give fixtures a session that commits locally and
   * the store under test a session with another default than the server's.
   */
  public static DataSource dataSourceWithOptions(String options) {
    return withOptions(dataSource(), options);
  }

  /**
   * The copy of a {@link PGSimpleDataSource} with these server options: same server, database,
   * user, password and other URL parameters. No connection is made.
   */
  static DataSource withOptions(DataSource base, String options) {
    if (!(base instanceof PGSimpleDataSource simple)) {
      throw new IllegalStateException(
          "the data source of the real store is not a PGSimpleDataSource");
    }
    PGSimpleDataSource copy = new PGSimpleDataSource();
    try {
      copy.setUrl(simple.getUrl());
    } catch (IllegalArgumentException e) {
      // the message of the driver repeats the URL, which may carry a password
      throw new IllegalStateException(URL_VARIABLE + " could not be copied");
    }
    // the URL that a data source generates leaves out the user and the password
    copy.setUser(simple.getUser());
    copy.setPassword(simple.getPassword());
    copy.setOptions(options);
    return copy;
  }

  /**
   * The hot standby of the job {@code ledger-durability}: {@code 127.0.0.1:5433}, database {@code
   * postgres}, user {@code postgres}, {@code trust} (the container of the standby runs on the host
   * network of the runner, see {@code scripts/ci/pg-quorum-up.sh}). Skips like {@link
   * #dataSource()} when there is no primary or the standby does not answer and the real store is
   * not required; fails when it is.
   */
  public static DataSource standbyDataSource() {
    dataSource(); // no primary, no standby: skip, or fail when the real store is required
    return requireOrAbort(STANDBY_URL, required());
  }

  /** A schema name unique per JVM: the process id and a counter, matching {@link SqlIdentifier}. */
  public static String freshSchema() {
    return SqlIdentifier.require(
        "t" + ProcessHandle.current().pid() + "_" + COUNTER.incrementAndGet());
  }

  /**
   * Drops a schema made by {@link #freshSchema()} and everything in it; refuses any other name. The
   * wait for the table locks is bounded (30 seconds), so a connection that a failed test left in an
   * open transaction fails the cleanup instead of hanging the run.
   */
  public static void dropSchema(String schema) {
    String identifier = SqlIdentifier.require(schema);
    if (!FRESH_SCHEMA.matcher(identifier).matches()) {
      throw new IllegalArgumentException("not a schema made by freshSchema(): refusing to drop it");
    }
    try (Connection connection = dataSource().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET lock_timeout = '30s'");
      // On a primary with a synchronous standby that is down (the job ledger-durability), a plain
      // DROP would wait for the standby for ever. A test schema needs no standby, and this session
      // commits locally. Harmless on a server without one.
      statement.execute("SET synchronous_commit = local");
      statement.execute("DROP SCHEMA IF EXISTS " + identifier + " CASCADE");
    } catch (SQLException e) {
      throw new IllegalStateException(
          "could not drop a test schema (sqlstate=" + e.getSQLState() + ")", e);
    }
  }
}
