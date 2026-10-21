package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerDurability.Status;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.contract.LedgerDurabilityContract;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * I1 on the primary with a streaming quorum standby (P3b1 T17, review focus EF1, EF14): the open
 * side of the durability gate. The job {@code ledger-durability} starts a primary whose {@code
 * synchronous_standby_names} is {@code ANY 1 (standby1)} and a hot standby on {@code
 * 127.0.0.1:5433}, and runs this class first and {@link PgDurabilityDownIT} after it has stopped
 * the standby. A real-store test: skipped without {@code REGPACK_PG_URL}; tagged {@code
 * durability}, so {@code verify} does not run it (the job selects it with {@code
 * -Dgroups=durability -DexcludedGroups=none -Dit.test=PgDurabilityUpIT}). Like every store test it
 * works in a schema of its own ({@link AbstractPgStoreTest}).
 *
 * <p>What this phase shows and what it does not: that the status reads a synchronous quorum standby
 * from the server, that a SENDING commit under {@code remote_apply} waits for the standby to replay
 * it (with replay paused on the standby the commit is blocked in the wait for synchronous
 * replication, a commit under {@code on} is not) and is visible on the standby when the call
 * returns, and that the production gate is open and a full seal is clear before the first byte. It
 * cannot show that {@code on} waits for the standby's flush; that is the Down phase, where a commit
 * that cannot get its acknowledgement is the evidence.
 *
 * <p>This class is also the proof, on a server, of the synchronous branch of {@code
 * LedgerDurabilityContract#aSynchronousStatusNamesAtLeastOneStandby}: in the job {@code ledger-pg}
 * the ledger has no standby and that contract method has nothing to check.
 *
 * <p>Every assertion carries the observed value in its description: the failure annotation of the
 * job keeps the first line of a failure message only. The class is bounded by a timeout in a thread
 * of its own, because a commit that waits for a standby blocks in a socket read, which a timeout in
 * the thread of the test cannot end. A normal test takes under 15 seconds.
 */
@Tag("durability")
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PgDurabilityUpIT extends AbstractPgStoreTest {
  private static final Duration CATCH_UP_LIMIT = Duration.ofSeconds(30);
  private static final Duration CONTROL_LIMIT = Duration.ofSeconds(20);
  private static final Duration BLOCKED_LIMIT = Duration.ofSeconds(15);
  private static final Duration CALL_LIMIT = Duration.ofSeconds(30);

  /** A backend of this database that waits for a standby; the text of its statement. */
  private static final String SYNC_REP_WAITER_SQL =
      "SELECT query FROM pg_stat_activity WHERE datname = current_database()"
          + " AND pid <> pg_backend_pid() AND wait_event = 'SyncRep' ORDER BY pid LIMIT 1";

  /** What the other backends of this database do, for the message of a failure. */
  private static final String ACTIVITY_SQL =
      "SELECT string_agg(pid || ':' || coalesce(state, '-') || ':' || coalesce(wait_event, '-')"
          + " || ':' || left(query, 40), ' | ' ORDER BY pid) FROM pg_stat_activity"
          + " WHERE datname = current_database() AND pid <> pg_backend_pid()";

  @TempDir Path root;

  private SealedEvidenceStore evidence() throws IOException {
    return DurabilityHarness.twoDomainEvidence(root, clock());
  }

  @Test
  void statusReportsASynchronousQuorumStandby() {
    Status status = store().durability();

    assertThat(status.synchronous()).as(status.detail()).isTrue();
    assertThat(status.synchronousStandbys())
        .as("synchronous standbys streaming: %s", status.detail())
        .isEqualTo(1);
    assertThat(status.detail()).as(status.detail()).contains("ANY 1 (standby1)");
    // the synchronous branch of the contract method, on the status of a real server
    LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(status);
  }

  /**
   * The store under test takes {@code remote_apply} from its connection options and does not force
   * the setting, so the commit of {@code beginSending} returns only when the standby has replayed
   * it: the hot standby then shows the delivery in SENDING and the attempt row at once, with no
   * polling. A fixture of the primary (the lease, the delivery) commits with {@code on}, which
   * waits for the standby's flush; the WAL is replayed in order, so everything before the SENDING
   * commit, the schema included, is visible on the standby too.
   *
   * <p>The fixture alone could not tell {@code remote_apply} from {@code on}: a standby replays a
   * one-record commit within about a millisecond of flushing it, faster than a fresh connection to
   * the standby is opened. So the test builds the difference with {@code pg_wal_replay_pause()} on
   * the standby, which stops the replay and not the receiving and flushing of WAL: (1) the status
   * detail names {@code remote_apply}, so the connection option reached the server and the store
   * reads it; (2) once the standby has replayed everything the primary wrote so far, replay is
   * paused; (3) the control: a commit with {@code synchronous_commit = on} returns while replay is
   * paused, and its row is not visible on the standby, which shows that the pause works and that
   * {@code on} does not wait for apply; (4) {@code beginSending} of the store under test is then
   * blocked in the wait for synchronous replication, seen in {@code pg_stat_activity} of the
   * primary; (5) replay is resumed, the call returns, and the SENDING row and the attempt row are
   * visible on the standby at once. Replay is resumed in a {@code finally}, whatever happened.
   *
   * <p>Assumed of the server (PostgreSQL 18, the fixture of {@code scripts/ci/pg-quorum-up.sh}):
   * the options of the connection become the reset value of {@code synchronous_commit}; the user
   * may call {@code pg_wal_replay_pause} and {@code pg_wal_replay_resume}; the WAL receiver keeps
   * flushing and acknowledging while replay is paused; a commit that waits for a standby shows the
   * wait event {@code SyncRep}.
   */
  @Test
  void aSendingCommitIsVisibleOnTheStandbyTheMomentTheCallReturns() throws Exception {
    DataSource remoteApply =
        PgTestDatabase.dataSourceWithOptions("-c synchronous_commit=remote_apply");
    DataSource onOnly = PgTestDatabase.dataSourceWithOptions("-c synchronous_commit=on");
    PgLedgerStore underTest =
        new PgLedgerStore(
            remoteApply, clock(), Config.forSchema(schema()).withForceSynchronousCommit(false));
    DataSource standby = PgTestDatabase.standbyDataSource();
    EndpointKey endpoint = LedgerFixtures.endpoint("VISIBLE");
    LeaseToken token = SealScenarioContract.lease(store(), endpoint, "A");
    DeliveryId delivery = LedgerFixtures.queuedDelivery(store(), endpoint, "BK-VISIBLE", 1);
    AttemptId attempt = store().allocateAttemptId();
    // a table for the control commit, created before the standby is brought up to date
    execute(dataSource(), "CREATE TABLE " + schema() + ".pause_probe (id bigint PRIMARY KEY)");

    // (1) the option reached the server, and the store reads it as the setting it commits with
    Status status = underTest.durability();
    assertThat(status.synchronous()).as(status.detail()).isTrue();
    assertThat(status.detail())
        .as("the option reached the server: %s", status.detail())
        .contains("synchronous_commit is 'remote_apply'");

    // (2) the standby has replayed everything written so far, then replay is paused
    String written = firstText(dataSource(), "SELECT pg_current_wal_flush_lsn()::text").orElse("");
    awaitReplayedUpTo(standby, written);

    SendingResult result;
    Throwable failure = null;
    try {
      result = sendWhileReplayIsPaused(standby, onOnly, underTest, delivery, attempt, token);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      try {
        execute(standby, "SELECT pg_wal_replay_resume()");
      } catch (SQLException resumeFailed) {
        if (failure == null) {
          throw resumeFailed;
        }
        failure.addSuppressed(resumeFailed);
      }
    }

    // (5) the call has returned: its commit is visible on the standby at once, with no polling
    assertThat(result.status())
        .as("the result of beginSending: %s", result)
        .isEqualTo(SendingStatus.APPLIED);
    long deliveryState;
    long attemptRows;
    try (Connection connection = standby.getConnection()) {
      deliveryState =
          longOf(
              connection,
              "SELECT state FROM " + schema() + ".delivery WHERE delivery_id = ?",
              delivery.value());
      attemptRows =
          longOf(
              connection,
              "SELECT count(*) FROM " + schema() + ".attempt WHERE attempt_id = ?",
              attempt.value());
    }
    assertThat(deliveryState)
        .as(
            "the delivery state on the standby, immediately: %s, wanted %s",
            deliveryState, Codes.of(DeliveryState.SENDING))
        .isEqualTo(Codes.of(DeliveryState.SENDING));
    assertThat(attemptRows)
        .as("the attempt rows on the standby, immediately: %s, wanted 1", attemptRows)
        .isEqualTo(1);
    printLsns();
  }

  /**
   * Replay is paused on the standby, the control commit and the call under test are made, replay is
   * resumed and the call is awaited. The caller resumes replay again in its {@code finally}.
   */
  private SendingResult sendWhileReplayIsPaused(
      DataSource standby,
      DataSource onOnly,
      PgLedgerStore underTest,
      DeliveryId delivery,
      AttemptId attempt,
      LeaseToken token)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      execute(standby, "SELECT pg_wal_replay_pause()");

      // (3) the control: an on commit returns, and its row is not visible while replay is paused
      Callable<Integer> insert = () -> insertProbe(onOnly);
      int inserted =
          await(pool.submit(insert), CONTROL_LIMIT, "the commit with synchronous_commit=on");
      String pauseState =
          firstText(standby, "SELECT pg_get_wal_replay_pause_state()").orElse("(no row)");
      long probeRows;
      try (Connection connection = standby.getConnection()) {
        probeRows =
            longOf(connection, "SELECT count(*) FROM " + schema() + ".pause_probe WHERE id = ?", 1);
      }
      assertThat(inserted).as("rows inserted by the control commit: %s", inserted).isEqualTo(1);
      assertThat(probeRows)
          .as(
              "rows of the on commit visible on the standby while replay is paused: %s, wanted 0"
                  + " (pause state %s)",
              probeRows, pauseState)
          .isZero();

      // (4) the call under test is blocked in the wait for synchronous replication
      Callable<SendingResult> sending =
          () -> underTest.beginSending(delivery, attempt, token, LedgerFixtures.attemptIntent());
      Future<SendingResult> call = pool.submit(sending);
      String waitingStatement = awaitSyncRepWaiter(call);
      assertThat(call.isDone())
          .as(
              "beginSending returned while replay is paused (%s); the waiting backend ran '%s'",
              outcomeOf(call), waitingStatement)
          .isFalse();
      DurabilityHarness.notice(
          "remote-apply-blocked",
          "beginSending is blocked in SyncRep while replay is paused; backend ran '"
              + waitingStatement
              + "'; pause state "
              + pauseState);

      execute(standby, "SELECT pg_wal_replay_resume()");
      return await(call, CALL_LIMIT, "beginSending after replay was resumed");
    } finally {
      pool.shutdownNow();
    }
  }

  private int insertProbe(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      return statement.executeUpdate("INSERT INTO " + schema() + ".pause_probe (id) VALUES (1)");
    }
  }

  /**
   * Polls the standby (every 100 ms, for at most {@link #CATCH_UP_LIMIT}) until its replay position
   * has reached {@code lsn}.
   */
  private static void awaitReplayedUpTo(DataSource standby, String lsn) throws SQLException {
    long deadline = System.nanoTime() + CATCH_UP_LIMIT.toNanos();
    String replayed = "(none)";
    while (true) {
      try (Connection connection = standby.getConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT r::text, r >= ?::pg_lsn FROM (SELECT pg_last_wal_replay_lsn() AS r) s")) {
        statement.setString(1, lsn);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).as("a row for the replay position of the standby").isTrue();
          replayed = String.valueOf(rows.getString(1));
          if (rows.getBoolean(2)) {
            return;
          }
        }
      }
      if (System.nanoTime() >= deadline) {
        throw new AssertionError(
            "the standby has not replayed up to "
                + lsn
                + " within "
                + CATCH_UP_LIMIT.toSeconds()
                + " s; its replay position is "
                + replayed);
      }
      pause(Duration.ofMillis(100));
    }
  }

  /**
   * Polls the primary (every 100 ms, for at most {@link #BLOCKED_LIMIT}) until a backend of the
   * database waits for synchronous replication; fails at once if the call has returned instead.
   *
   * @return the statement of the waiting backend
   */
  private String awaitSyncRepWaiter(Future<SendingResult> call) throws SQLException {
    long deadline = System.nanoTime() + BLOCKED_LIMIT.toNanos();
    while (true) {
      if (call.isDone()) {
        throw new AssertionError(
            "beginSending returned while replay was paused, so it did not wait for the standby to"
                + " apply: "
                + outcomeOf(call));
      }
      Optional<String> waiting = firstText(dataSource(), SYNC_REP_WAITER_SQL);
      if (waiting.isPresent()) {
        return waiting.get();
      }
      if (System.nanoTime() >= deadline) {
        throw new AssertionError(
            "no backend waited for synchronous replication within "
                + BLOCKED_LIMIT.toSeconds()
                + " s while replay was paused; beginSending: "
                + outcomeOf(call)
                + "; backends of the database: "
                + firstText(dataSource(), ACTIVITY_SQL).orElse("(none)"));
      }
      pause(Duration.ofMillis(100));
    }
  }

  private static String outcomeOf(Future<SendingResult> call) {
    if (!call.isDone()) {
      return "still running";
    }
    try {
      return "returned " + call.get(0, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      return "failed with " + e.getCause();
    } catch (TimeoutException e) {
      return "still running";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted";
    }
  }

  private static <T> T await(Future<T> future, Duration limit, String what) throws Exception {
    try {
      return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new AssertionError(what + " did not return within " + limit.toSeconds() + " s", e);
    } catch (ExecutionException e) {
      throw new AssertionError(what + " failed: " + e.getCause(), e.getCause());
    }
  }

  private static void pause(Duration duration) {
    try {
      TimeUnit.MILLISECONDS.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", e);
    }
  }

  private static void execute(DataSource source, String sql) throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** The first column of the first row of a query, as text; empty when there is no row or null. */
  private static Optional<String> firstText(DataSource source, String sql) throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
    }
  }

  @Test
  void theProductionGateIsOpenForAPSealedStoreOnTwoDomainsAndTheQuorumStandby() throws IOException {
    SendGate production = new SendGate(SendGate.Requirement.production());

    SendGate.Result open = production.check(store(), evidence());

    assertThat(open.reasons()).as("reasons the gate is closed: %s", open.reasons()).isEmpty();
    assertThat(open.open()).as("gate open, reasons: %s", open.reasons()).isTrue();
    // the same ledger with an evidence store of one failure domain: closed, for that reason only
    SendGate.Result oneDomain =
        production.check(store(), DurabilityHarness.oneDomainEvidence(root, clock()));
    assertThat(oneDomain.open())
        .as("gate open with one failure domain, reasons: %s", oneDomain.reasons())
        .isFalse();
    assertThat(oneDomain.reasons())
        .as("reasons with one failure domain: %s", oneDomain.reasons())
        .doesNotContain(SendGate.LEDGER_NOT_SYNCHRONOUS, SendGate.LEDGER_UNREADABLE)
        .hasSize(1);
  }

  @Test
  void aFullSealWithTheProductionGateIsClearBeforeTheFirstByte() throws IOException {
    DurableSeal sealing =
        new DurableSeal(
            store(),
            evidence(),
            clock(),
            RetentionPolicy.fixedDays(1826),
            new SendGate(SendGate.Requirement.production()));
    EndpointKey endpoint = LedgerFixtures.endpoint("FULLSEAL");
    LeaseToken token = SealScenarioContract.lease(store(), endpoint, "A");
    DeliveryId delivery = LedgerFixtures.queuedDelivery(store(), endpoint, "BK-FULL", 1);

    SealOutcome outcome =
        sealing.seal(
            SealScenarioContract.request(
                endpoint, token, delivery, "BK-FULL", SealScenarioContract.reportOf("BK-FULL")));

    assertThat(outcome.status()).as(outcome.detail()).isEqualTo(SealStatus.SEALED);
    AttemptId attempt = outcome.attempt().orElseThrow();
    PreSendStatus beforeFirstByte = sealing.beforeFirstByte(endpoint, attempt, token);
    assertThat(beforeFirstByte)
        .as("the status before the first byte: %s", beforeFirstByte)
        .isEqualTo(PreSendStatus.CLEAR);
    boolean commitIssued = store().attempt(attempt).orElseThrow().commitIssued();
    assertThat(commitIssued).as("commit_issued of the attempt: %s", commitIssued).isTrue();
    DeliveryState state = store().delivery(delivery).orElseThrow().state();
    assertThat(state).as("the delivery state: %s", state).isEqualTo(DeliveryState.SENDING);
    var manifestRows = store().manifestRows("", Optional.empty(), 1_000);
    assertThat(manifestRows)
        .as("the sealed file and the intent object: %s rows", manifestRows.size())
        .hasSize(2);
  }

  // ---------------------------------------------------------------------------------------------

  private static long longOf(Connection connection, String sql, long parameter)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).as("a row for %s", sql).isTrue();
        return rows.getLong(1);
      }
    }
  }

  /**
   * The log position of the primary and what the standby has acknowledged, printed for the reader
   * of the log; a diagnostic only, nothing compares them.
   */
  private void printLsns() throws SQLException {
    try (Connection connection = dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT pg_current_wal_lsn()::text, flush_lsn::text, replay_lsn::text,"
                    + " sync_state FROM pg_stat_replication WHERE application_name = 'standby1'")) {
      if (rows.next()) {
        DurabilityHarness.notice(
            "standby-lsn",
            "primary="
                + rows.getString(1)
                + " standby-flush="
                + rows.getString(2)
                + " standby-replay="
                + rows.getString(3)
                + " sync_state="
                + rows.getString(4));
      }
    }
  }
}
