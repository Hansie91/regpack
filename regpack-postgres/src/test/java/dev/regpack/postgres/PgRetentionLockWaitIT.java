package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The order, the reach and the life of the artifact lock of {@code PgRetention} on a real server
 * (decision D28), shown without a race: a second connection plays the other writer, keeps the lock
 * or a row, and the test asks the server (pg_stat_activity, pg_locks) what the call under test is
 * waiting for and holds while it waits. Beside {@code PgRetentionIT} (a held lock blocks the
 * writers of that artifact and nothing else) and the two race tests of {@code
 * RetentionLedgerContract}, which can pass by luck, this is what shows on a server that the lock is
 * taken first, before every write, for every artifact of the call, in key order, in the unit that
 * writes, and let go with the transaction.
 *
 * <p>The lock is taken here with a literal statement, the purpose 4 and the text schema ':'
 * artifact, as in {@code PgRetentionIT}. The call under test runs in a thread of its own on a data
 * source whose connections carry an application name of the test, so that its backend is found in
 * {@code pg_stat_activity}. Real-store test: skipped without {@code REGPACK_PG_URL}, a failure with
 * {@code REGPACK_PG_REQUIRED=true}.
 */
class PgRetentionLockWaitIT extends AbstractPgStoreTest {
  private static final Instant UNTIL = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2031-01-01T00:00:00Z");
  private static final String A = "sealed/wait/a";
  private static final String B = "sealed/wait/b";
  private static final long WAIT_SECONDS = 30;

  private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(?, hashtext(?))";

  /** granted of the advisory lock (purpose 4, text) of a backend, if it asked for it at all. */
  private static final String ADVISORY_SQL =
      "SELECT granted FROM pg_locks WHERE locktype = 'advisory' AND pid = ? AND classid = 4"
          + " AND objsubid = 2 AND objid::text = ((hashtext(?)::bigint + 4294967296) % 4294967296)::text";

  private static final String BLOCKED_SQL =
      "SELECT pid FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'"
          + " AND wait_event = ANY (?)";

  /** The threads of the calls under test; none is started before a call is submitted. */
  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void stopPool() {
    pool.shutdownNow();
  }

  private static RetentionClaim claim(String artifact, String regime, Instant until) {
    return new RetentionClaim(
        new EvidenceKey(artifact),
        new Regime(regime),
        AnchorEvent.OBJECT_WRITTEN,
        Optional.of(until),
        "sealed");
  }

  private String application() {
    return "pgwait_" + schema();
  }

  /**
   * The adapter under test: connections named for the test, and a lock timeout that outlasts it.
   */
  private PgLedgerStore patient() {
    PGSimpleDataSource base = (PGSimpleDataSource) dataSource();
    PGSimpleDataSource named = new PGSimpleDataSource();
    try {
      named.setUrl(base.getUrl());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("the URL of the server could not be copied");
    }
    named.setUser(base.getUser());
    named.setPassword(base.getPassword());
    named.setApplicationName(application());
    return new PgLedgerStore(
        named, clock(), Config.forSchema(schema()).withLockTimeout(Duration.ofSeconds(60)));
  }

  /** Takes the artifact lock for the rest of the transaction of {@code holder}. */
  private void lock(Connection holder, String artifact) throws SQLException {
    try (PreparedStatement statement = holder.prepareStatement(LOCK_SQL)) {
      statement.setInt(1, 4);
      statement.setString(2, schema() + ":" + artifact);
      statement.execute();
    }
  }

  /** A row as an uncommitted claim would write it. */
  private static void insertRow(Connection c, String artifact, String regime, boolean held)
      throws SQLException {
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO retention (object_key, regime, anchor_event, entity_id, retention_class,"
                + " retain_until, legal_hold, recorded_at) VALUES (?, ?, 1, 1, 'sealed',"
                + " '2030-01-01T00:00:00Z', ?, '2026-10-09T08:00:00Z')")) {
      insert.setString(1, artifact);
      insert.setString(2, regime);
      insert.setBoolean(3, held);
      insert.executeUpdate();
    }
  }

  /** The backend of the call under test while it waits on a lock; fails if the call ends first. */
  private long awaitBlocked(Future<?> call, String... waitEvents) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      try (Connection c = raw();
          PreparedStatement statement = c.prepareStatement(BLOCKED_SQL)) {
        Array events = c.createArrayOf("text", waitEvents);
        statement.setString(1, application());
        statement.setArray(2, events);
        try (ResultSet rows = statement.executeQuery()) {
          if (rows.next()) {
            return rows.getInt(1);
          }
        }
      }
      if (call.isDone()) {
        call.get(1, TimeUnit.SECONDS); // a failure of the call is the failure of the test
        throw new AssertionError("the call ended without waiting for a lock");
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the call did not wait for a lock within 20 seconds");
  }

  /** TRUE: the backend holds the artifact lock; FALSE: it waits for it; null: it never asked. */
  private Boolean advisory(long pid, String artifact) throws SQLException {
    try (Connection c = raw();
        PreparedStatement statement = c.prepareStatement(ADVISORY_SQL)) {
      statement.setInt(1, (int) pid);
      statement.setString(2, schema() + ":" + artifact);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getBoolean(1) : null;
      }
    }
  }

  /** The mode in which the backend holds or waits for the artifact lock, if it asked for it. */
  private String advisoryMode(long pid, String artifact) throws SQLException {
    try (Connection c = raw();
        PreparedStatement statement =
            c.prepareStatement(
                "SELECT mode FROM pg_locks WHERE locktype = 'advisory' AND pid = ? AND classid = 4"
                    + " AND objsubid = 2 AND objid::text ="
                    + " ((hashtext(?)::bigint + 4294967296) % 4294967296)::text")) {
      statement.setInt(1, (int) pid);
      statement.setString(2, schema() + ":" + artifact);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private List<RetentionRow> rows(String artifact) {
    return store().retentionRows(artifact, Optional.empty(), 10);
  }

  // ---------------------------------------------------------------------------------------------
  // the lock comes before what the call reads: no stale hold, no missed row

  /**
   * The other writer is a hold that has run and not committed. The claim of a new regime of that
   * artifact must wait for it and then see it: its row is held. A claim that reads the hold before
   * the lock, or writes before it, or does not wait, writes a row that is not held.
   */
  @Test
  void aClaimOfANewRegimeWaitsForAnUncommittedHoldAndIsHeldByIt() throws Exception {
    store().recordRetention(List.of(claim(A, "emir.eu", UNTIL)));
    PgLedgerStore patient = patient();
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      lock(holder, A);
      try (Statement update = holder.createStatement()) {
        update.executeUpdate("UPDATE retention SET legal_hold = true");
      }
      Future<?> call =
          pool.submit(() -> patient.recordRetention(List.of(claim(A, "mifir.eu.rts22", UNTIL))));
      awaitBlocked(call, "advisory");
      holder.commit();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(A))
        .as("both rows of the artifact")
        .hasSize(2)
        .allMatch(RetentionRow::legalHold);
  }

  /**
   * A claim without a date (an anchor that is not known yet, for example the termination of an EMIR
   * trade) takes the lock like any other claim, for the artifact it names: it must wait for the
   * uncommitted hold and be held by it. A lock list that leaves out the artifacts of open claims
   * writes an unheld row.
   */
  @Test
  void aClaimWithoutADateTakesTheLockToo() throws Exception {
    store().recordRetention(List.of(claim(A, "emir.eu", UNTIL)));
    RetentionClaim open =
        new RetentionClaim(
            new EvidenceKey(A),
            new Regime("emir.eu"),
            AnchorEvent.TERMINATION,
            Optional.empty(),
            "sealed");
    PgLedgerStore patient = patient();
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      lock(holder, A);
      try (Statement update = holder.createStatement()) {
        update.executeUpdate("UPDATE retention SET legal_hold = true");
      }
      Future<?> call = pool.submit(() -> patient.recordRetention(List.of(open)));
      awaitBlocked(call, "advisory");
      holder.commit();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(A))
        .as("both rows of the artifact")
        .hasSize(2)
        .allMatch(RetentionRow::legalHold);
  }

  /**
   * The other writer is a claim that has run and not committed. The hold of that artifact must wait
   * for it and then reach its row. A hold that updates before the lock, or does not wait, leaves
   * that row unheld.
   */
  @Test
  void aHoldWaitsForAClaimThatIsNotCommittedYetAndReachesItsRow() throws Exception {
    store().recordRetention(List.of(claim(A, "emir.eu", UNTIL)));
    PgLedgerStore patient = patient();
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      lock(holder, A);
      insertRow(holder, A, "mifir.eu.rts22", false);
      Future<?> call = pool.submit(() -> patient.setRetentionHold(new EvidenceKey(A), true));
      awaitBlocked(call, "advisory");
      holder.commit();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(A))
        .as("both rows of the artifact")
        .hasSize(2)
        .allMatch(RetentionRow::legalHold);
  }

  /**
   * The same for a release: the claim that has not committed took the hold that was set, and the
   * release must wait for it and reach its row too. A release that does not wait leaves a hold that
   * nobody asked for.
   */
  @Test
  void aReleaseWaitsForAClaimThatIsNotCommittedYetAndReachesItsRow() throws Exception {
    store().recordRetention(List.of(claim(A, "emir.eu", UNTIL)));
    store().setRetentionHold(new EvidenceKey(A), true);
    PgLedgerStore patient = patient();
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      lock(holder, A);
      insertRow(holder, A, "mifir.eu.rts22", true);
      Future<?> call = pool.submit(() -> patient.setRetentionHold(new EvidenceKey(A), false));
      awaitBlocked(call, "advisory");
      holder.commit();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(A))
        .as("both rows of the artifact")
        .hasSize(2)
        .noneMatch(RetentionRow::legalHold);
  }

  /**
   * The time of the ledger is read before the unit, that is before any wait for the lock (class
   * comment of {@code PgRetention}): a claim that waits a day for the lock is born when it was
   * made.
   */
  @Test
  void aClaimThatWaitsForTheLockIsRecordedWithTheTimeOfTheCall() throws Exception {
    Instant before = clock().now();
    PgLedgerStore patient = patient();
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      lock(holder, A);
      Future<?> call =
          pool.submit(() -> patient.recordRetention(List.of(claim(A, "emir.eu", UNTIL))));
      awaitBlocked(call, "advisory");
      clock().set(before.plus(Duration.ofDays(1)));
      holder.rollback();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(A)).hasSize(1);
    assertThat(rows(A).get(0).recordedAt()).isEqualTo(before);
  }

  // ---------------------------------------------------------------------------------------------
  // what the call holds, and when

  /**
   * While the write of the call waits for a row that another transaction has locked, the call holds
   * the lock of every artifact it touches: the lock is taken first, for each artifact, and in the
   * unit that writes. A write before the lock, a lock in a unit of its own, a lock for the first
   * artifact only, or no lock shows here.
   */
  @Test
  void theCallHoldsTheLockOfEveryArtifactWhileItsWriteWaitsForARow() throws Exception {
    store().recordRetention(List.of(claim(A, "emir.eu", UNTIL), claim(B, "emir.eu", UNTIL)));
    PgLedgerStore patient = patient();

    // the claim: the batch merges into A and then waits for the row of B
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      try (Statement s = holder.createStatement()) {
        s.executeQuery("SELECT 1 FROM retention WHERE object_key = '" + B + "' FOR UPDATE").close();
      }
      Future<?> call =
          pool.submit(
              () ->
                  patient.recordRetention(
                      List.of(claim(A, "emir.eu", LATER), claim(B, "emir.eu", LATER))));
      long pid = awaitBlocked(call, "transactionid", "tuple");
      assertThat(advisory(pid, A)).as("the lock of A while the claim waits for a row").isTrue();
      assertThat(advisory(pid, B)).as("the lock of B while the claim waits for a row").isTrue();
      // exclusive: a shared lock would let a hold and a claim run side by side
      assertThat(advisoryMode(pid, A)).isEqualTo("ExclusiveLock");
      assertThat(advisoryMode(pid, B)).isEqualTo("ExclusiveLock");
      holder.rollback();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(A).get(0).claim().retainUntil()).contains(LATER);
    assertThat(rows(B).get(0).claim().retainUntil()).contains(LATER);

    // the hold: the update waits for the row of A
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      try (Statement s = holder.createStatement()) {
        s.executeQuery("SELECT 1 FROM retention WHERE object_key = '" + A + "' FOR UPDATE").close();
      }
      Future<?> call = pool.submit(() -> patient.setRetentionHold(new EvidenceKey(A), true));
      long pid = awaitBlocked(call, "transactionid", "tuple");
      assertThat(advisory(pid, A)).as("the lock of A while the hold waits for a row").isTrue();
      holder.rollback();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(A)).allMatch(RetentionRow::legalHold);
  }

  /**
   * The locks are taken in the order of the keys (byte order: an upper-case letter comes before a
   * lower-case one), one after the other, whatever the order of the claims: with the lock of the
   * second artifact kept by another connection the call holds the first and waits for the second;
   * with the lock of the first kept it waits for the first and has not asked for the second.
   */
  @Test
  void theArtifactsAreLockedInKeyOrderOneAfterTheOther() throws Exception {
    String first = "sealed/wait/B";
    String second = "sealed/wait/a";
    PgLedgerStore patient = patient();
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      lock(holder, second);
      Future<?> call =
          pool.submit(
              () ->
                  patient.recordRetention(
                      List.of(claim(second, "emir.eu", UNTIL), claim(first, "emir.eu", UNTIL))));
      long pid = awaitBlocked(call, "advisory");
      assertThat(advisory(pid, first)).as("the first artifact is held").isTrue();
      assertThat(advisory(pid, second)).as("the second artifact is waited for").isFalse();
      holder.rollback();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(first)).hasSize(1);
    assertThat(rows(second)).hasSize(1);

    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      lock(holder, first);
      Future<?> call =
          pool.submit(
              () ->
                  patient.recordRetention(
                      List.of(claim(second, "emir.eu", LATER), claim(first, "emir.eu", LATER))));
      long pid = awaitBlocked(call, "advisory");
      assertThat(advisory(pid, first)).as("the first artifact is waited for").isFalse();
      assertThat(advisory(pid, second)).as("the second is not asked for before the first").isNull();
      holder.rollback();
      call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(rows(first).get(0).claim().retainUntil()).contains(LATER);
    assertThat(rows(second).get(0).claim().retainUntil()).contains(LATER);
  }

  // ---------------------------------------------------------------------------------------------
  // the lock ends with the transaction

  /**
   * A session keeps nothing when the unit is over, on a connection that a pool hands out again:
   * after a claim, a hold, a release and a refused hold on the same physical session, that session
   * holds no advisory lock. A session-level lock would stay with the pooled connection, and the
   * next call for the artifact would wait for a session that is idle. (The data source of the other
   * tests closes the session with the unit, which hides it.)
   */
  @Test
  void noLockOutlivesTheUnitOnAConnectionThatIsKept() throws Exception {
    try (Connection physical = dataSource().getConnection()) {
      int pid;
      try (Statement s = physical.createStatement();
          ResultSet r = s.executeQuery("SELECT pg_backend_pid()")) {
        r.next();
        pid = r.getInt(1);
      }
      PgLedgerStore pooled =
          new PgLedgerStore(recycling(physical), clock(), Config.forSchema(schema()));
      pooled.recordRetention(List.of(claim(A, "emir.eu", UNTIL), claim(B, "emir.eu", UNTIL)));
      pooled.setRetentionHold(new EvidenceKey(A), true);
      pooled.setRetentionHold(new EvidenceKey(A), false);
      try {
        pooled.setRetentionHold(new EvidenceKey("sealed/wait/none"), true);
      } catch (IllegalArgumentException expected) {
        // the refusal of an artifact without rows rolls the unit back
      }
      assertThat(
              count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = " + pid))
          .as("advisory locks of the kept session")
          .isZero();
      assertThat(rows(A)).hasSize(1);
    }
  }

  /** What a pool does with a connection that comes back: ends its transaction and keeps it open. */
  private static DataSource recycling(Connection physical) {
    Connection handedOut =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    if (!physical.getAutoCommit()) {
                      physical.rollback();
                      physical.setAutoCommit(true);
                    }
                    return null;
                  }
                  try {
                    return method.invoke(physical, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getConnection") && args == null) {
                return handedOut;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
