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
      if (unavailableReason != null) {
        // the first check already failed: later calls do not wait for the connect timeout again
        unavailable(unavailableReason);
      }
      String url = System.getenv(URL_VARIABLE);
      if (url == null || url.isBlank()) {
        unavailable(URL_VARIABLE + " is not set");
      }
      PGSimpleDataSource candidate = new PGSimpleDataSource();
      try {
        candidate.setUrl(url);
      } catch (IllegalArgumentException e) {
        // the driver's message repeats the whole URL, password included: use neither it nor the
        // cause
        unavailable(URL_VARIABLE + " is set but is not a valid JDBC URL");
      }
      try (Connection connection = candidate.getConnection()) {
        if (connection.isValid(10)) {
          shared = candidate;
          return candidate;
        }
        unavailable(URL_VARIABLE + " is set but the connection is not valid");
      } catch (SQLException e) {
        unavailable(
            URL_VARIABLE
                + " is set but the server is unreachable (sqlstate="
                + e.getSQLState()
                + ")");
      }
      throw new IllegalStateException("unreachable: unavailable() always throws");
    }
  }

  private static void unavailable(String reason) {
    if (unavailableReason == null) {
      unavailableReason = reason;
    }
    if (required()) {
      Assertions.fail(REQUIRED_VARIABLE + "=true and " + reason);
    }
    Assumptions.abort("real-store test skipped: " + reason);
  }

  /** A schema name unique per JVM: the process id and a counter, matching {@link SqlIdentifier}. */
  public static String freshSchema() {
    return SqlIdentifier.require(
        "t" + ProcessHandle.current().pid() + "_" + COUNTER.incrementAndGet());
  }

  /** Drops a schema made by {@link #freshSchema()} and everything in it; refuses any other name. */
  public static void dropSchema(String schema) {
    String identifier = SqlIdentifier.require(schema);
    if (!FRESH_SCHEMA.matcher(identifier).matches()) {
      throw new IllegalArgumentException("not a schema made by freshSchema(): refusing to drop it");
    }
    try (Connection connection = dataSource().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + identifier + " CASCADE");
    } catch (SQLException e) {
      throw new IllegalStateException(
          "could not drop a test schema (sqlstate=" + e.getSQLState() + ")", e);
    }
  }
}
