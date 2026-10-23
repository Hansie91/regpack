package dev.regpack.postgres;

import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
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
 *
 * <p><b>A commit that is not confirmed (I1, assumption A11, review focus EF15).</b> On a primary
 * with a synchronous standby, a commit whose wait for the standby is cancelled (a query cancel; a
 * terminated session ends the connection instead) returns normally: the server sends a {@code
 * WARNING}, the transaction has already committed locally and may not be on the standby. The driver
 * hands that warning to {@link Connection#getWarnings}. So the warnings are cleared when the unit
 * begins and once more immediately before {@code commit()}, and after the commit the chain is
 * walked: any warning whose SQLState is missing or not of class {@code 00} (the driver reports a
 * {@code NOTICE} as {@code 00000}) fails the unit with a {@link
 * dev.regpack.ports.ledger.LedgerException} that says so ({@link PgErrors#commitNotConfirmed}). No
 * list of SQLStates and no message text is matched (the text depends on {@code lc_messages}): any
 * warning after COMMIT counts, because the only thing a commit can warn about is the durability of
 * what it just did.
 *
 * <p>The caller must read that exception as <em>unknown</em>: the unit is committed here, so the
 * connection is released as committed and nothing is rolled back, but the standby may not have it.
 * It must not send anything on the strength of it; a SENDING row without a marker is requeued by
 * recovery (spec 11.5 A.3 a). The same holds when the warnings cannot be read after the commit.
 *
 * <p><b>The rule covers units that write.</b> The server makes a commit wait for the standby only
 * when the transaction has written WAL. A unit that wrote nothing has no transaction id and no WAL:
 * its commit returns at once, with no wait and no warning, whatever the state of the standby. That
 * is right for a plain read, but not for a unit whose success is read by the caller as "the thing
 * is stored" while the unit only found it stored: the replay of an idempotent write (a redelivered
 * ingest batch, a manifest call whose keys are all there, a redelivered decision). If the first
 * attempt was reported as "not confirmed" and the caller retries, the retry would succeed at once,
 * and a caller that acknowledges a source or goes on to decide or send on that success would act on
 * rows that exist on the primary only. So a unit whose success may be acknowledged although it
 * wrote nothing must end with {@link #durabilityBarrier} (or lock the row it read, as the ingest
 * does): its commit then waits, and warns when the wait is cancelled, exactly like the first
 * attempt's, and because the log is ordered a standby that confirms it has the first attempt's
 * commit as well. The read-only units (lookups, the durability reading, the integrity queries) stay
 * free of it by design: their results are readings of the primary and no one may take them for a
 * confirmation. A refusal (a failed compare-and-set, {@code STATE_MISMATCH}, {@code STALE_LEASE},
 * {@code Held}) authorises nothing, so it needs no barrier.
 *
 * <p><b>Bounding the wait.</b> A cancelled wait is the only way a commit that waits comes back
 * without the standby ({@code statement_timeout} and {@code lock_timeout} do not apply to the
 * COMMIT). A client that gives up on the socket does not end it: the backend goes on waiting in
 * {@code SyncRepWaitForLSN}, which looks at the cancel and die flags and the postmaster and not at
 * the client connection, and it keeps the row locks of its transaction (the delivery row, the lease
 * share lock, the manifest keys) until the standby returns or someone cancels it. So the bound that
 * a deployment adds is a watchdog that cancels the backend from a second connection ({@code
 * pg_cancel_backend}, or the driver's cancel request on the statement) and then reads the {@code
 * 01000} warning as "unknown", which this class does; a socket timeout alone is not a bound and
 * must be paired with a server-side cancel or terminate.
 */
final class Unit {
  private static final String SET_ISOLATION = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED";

  private static final String SET_SESSION =
      "SELECT set_config('TimeZone', 'UTC', true), set_config('search_path', ?, true),"
          + " set_config('lock_timeout', ?, true), set_config('statement_timeout', ?, true)";

  private static final String SET_SESSION_SYNCHRONOUS =
      SET_SESSION + ", set_config('synchronous_commit', 'on', true)";

  /**
   * The durability barrier: a share lock on the row of the restore-epoch singleton, which exists
   * from the first migration and which the ledger role can lock because it updates it ({@link
   * PgLeases#DECLARE_SQL}). See {@link #durabilityBarrier}.
   */
  static final String BARRIER_SQL = "SELECT 1 FROM restore_epoch WHERE singleton FOR SHARE";

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
      // a warning of a statement of the work is not a warning of the commit
      connection.clearWarnings();
      connection.commit();
      // the transaction has committed locally: from here on the connection is released as
      // committed, whatever the check below finds
      committed = true;
      requireConfirmed(connection);
      return result;
    } catch (SQLException e) {
      throw PgErrors.wrap(e);
    } finally {
      release(connection, committed);
    }
  }

  /**
   * Makes the commit of a unit that has written nothing wait for the standby like the commit of a
   * unit that has written (I1, acknowledgement limb). Call it as the last statement of the unit,
   * before the commit, on every branch that returns success on the strength of rows that this unit
   * did not write and that a caller may answer with an acknowledgement, a decision or a send: the
   * replay of an idempotent write (see the class comment).
   *
   * <p>Why a statement is needed at all: the server makes a commit wait for the standby only when
   * the transaction has written WAL (a transaction id alone is not enough: assigning one writes no
   * WAL, and {@code RecordTransactionCommit} decides whether to wait before it writes its own
   * commit record). A share lock on a row is WAL-logged ({@code XLOG_HEAP_LOCK}) and so is the
   * commit that follows it, and because the commit record of this unit comes later in the log than
   * the commit of the earlier attempt, a standby that has confirmed it has received that one as
   * well. The lock is taken with {@code FOR SHARE} on the singleton of {@code restore_epoch}: the
   * one row that always exists and that the ledger role may lock (it updates the row to declare an
   * epoch); a share lock excludes only a declaration of a restore epoch, which is rare. The barrier
   * belongs in a unit that holds no lease or delivery row: in the lock order of {@link PgLeases}
   * the singleton comes first.
   *
   * @throws dev.regpack.ports.ledger.LedgerException when the row is missing: no lock was taken, so
   *     there is no barrier, and the unit must fail
   */
  static void durabilityBarrier(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(BARRIER_SQL);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new LedgerException("the restore epoch row is missing");
      }
    }
  }

  /**
   * After a commit: every warning of the chain must be a notice (SQLState of class {@code 00}). A
   * warning without a SQLState is not a notice.
   *
   * @throws dev.regpack.ports.ledger.LedgerException for the first warning that is not
   * @throws SQLException when the warnings cannot be read: the outcome is unknown then as well
   */
  private static void requireConfirmed(Connection connection) throws SQLException {
    for (SQLWarning warning = connection.getWarnings();
        warning != null;
        warning = warning.getNextWarning()) {
      String state = warning.getSQLState();
      if (state == null || !state.startsWith("00")) {
        throw PgErrors.commitNotConfirmed(warning);
      }
    }
  }

  /**
   * The prologue: after it the transaction is open and the session settings are in force. The
   * warnings of the connection are cleared first, so that what an earlier use of a pooled
   * connection left behind is not read as the commit's.
   */
  static void begin(Connection connection, Config config) throws SQLException {
    connection.setAutoCommit(false);
    connection.clearWarnings();
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
