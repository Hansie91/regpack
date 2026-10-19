package dev.regpack.postgres;

import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The unit of work (Convention 11, review focus LF19): one connection, one transaction, started
 * through one prologue, committed or rolled back, and always closed.
 *
 * <p>The prologue makes a unit independent of what the connection or the pool brings: auto-commit
 * off, {@code READ COMMITTED} set for this transaction, and, as {@code SET LOCAL} through {@code
 * set_config(..., true)} so that nothing outlives the transaction, the time zone UTC, the search
 * path (the schema of the {@link Config}), the lock and statement timeouts and, unless the config
 * says otherwise, {@code synchronous_commit = on}. Every value is a bind parameter (Convention 12).
 *
 * <p>A {@link java.sql.SQLException} becomes the {@link dev.regpack.ports.ledger.LedgerException}
 * of {@link PgErrors#wrap}, so a lost connection is an exception and never an answer; any other
 * exception of the work passes through unchanged after the rollback. Auto-commit is restored only
 * after a successful commit: after a failure the transaction state is unknown, and switching
 * auto-commit on over an open transaction would commit it.
 */
final class Unit {
  private static final String SET_ISOLATION = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED";

  private static final String SET_SESSION =
      "SELECT set_config('TimeZone', 'UTC', true), set_config('search_path', ?, true),"
          + " set_config('lock_timeout', ?, true), set_config('statement_timeout', ?, true)";

  private static final String SET_SESSION_SYNCHRONOUS =
      SET_SESSION + ", set_config('synchronous_commit', 'on', true)";

  /** The work of a unit; it runs on the connection after the prologue and before the commit. */
  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private Unit() {}

  /** Runs the work in one transaction on a connection of its own. */
  static <T> T inUnit(DataSource dataSource, Config config, Work<T> work) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(work, "work");
    Connection connection = null;
    boolean committed = false;
    try {
      connection = dataSource.getConnection();
      begin(connection, config);
      T result = work.run(connection);
      connection.commit();
      committed = true;
      return result;
    } catch (SQLException e) {
      throw PgErrors.wrap(e);
    } finally {
      release(connection, committed);
    }
  }

  /** The prologue: after it the transaction is open and the session settings are in force. */
  static void begin(Connection connection, Config config) throws SQLException {
    connection.setAutoCommit(false);
    try (Statement isolation = connection.createStatement()) {
      isolation.execute(SET_ISOLATION);
    }
    String sql = config.forceSynchronousCommit() ? SET_SESSION_SYNCHRONOUS : SET_SESSION;
    try (PreparedStatement settings = connection.prepareStatement(sql)) {
      settings.setString(1, config.schema());
      settings.setString(2, milliseconds(config.lockTimeout()));
      settings.setString(3, milliseconds(config.statementTimeout()));
      settings.execute();
    }
  }

  private static String milliseconds(Duration duration) {
    return duration.toMillis() + "ms";
  }

  /**
   * Ends the use of a connection: after a commit the auto-commit mode is restored, otherwise the
   * transaction is rolled back and auto-commit is left alone; the connection is closed either way.
   * Also used by the read-only snapshot of {@link LedgerIntegrity}.
   */
  static void release(Connection connection, boolean committed) {
    if (connection == null) {
      return;
    }
    if (committed) {
      restoreAutoCommitQuietly(connection);
    } else {
      rollbackQuietly(connection);
    }
    closeQuietly(connection);
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // the failure that got us here is the one to report; closing ends the transaction anyway
    }
  }

  private static void restoreAutoCommitQuietly(Connection connection) {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException ignored) {
      // the unit is committed; a pooled connection that cannot be reset is the pool's to drop
    }
  }

  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // nothing to report: the result of the unit is already decided
    }
  }
}
