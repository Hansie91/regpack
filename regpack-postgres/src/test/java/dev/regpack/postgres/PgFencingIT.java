package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.attemptIntent;
import static dev.regpack.testkit.contract.LedgerFixtures.begin;
import static dev.regpack.testkit.contract.LedgerFixtures.endpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.queuedDelivery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.Transport.OutboundFile;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.DeliveryLedger.TransitionStatus;
import dev.regpack.ports.ledger.DuplicateKeyException;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseGrant;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult.Granted;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult.Held;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.postgres.PgErrors.Violation;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.contract.FencedSender;
import dev.regpack.testkit.sim.DestinationSimulator;
import dev.regpack.testkit.sim.Scenarios;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Real-store tests of leases, deliveries and fencing (T17, Convention 4, review focus LF11, LF12,
 * LF19 and LF22). They are the properties that the in-memory adapter has trivially because it has
 * one lock: the token pair compared as a whole, a takeover that waits for a unit that is already
 * past its fence check, and a lost connection at the marker commit. The units that must stay open
 * are run through the package-private overloads of {@link PgLeases} and {@link PgDeliveries}, which
 * work in a transaction the test owns. Every wait is bounded. Skipped without {@code
 * REGPACK_PG_URL}; first run at CP-2.
 */
class PgFencingIT extends AbstractPgStoreTest {
  private static final EndpointKey EP = endpoint("IT");
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final long WAIT_SECONDS = 30;

  private Config config() {
    return Config.forSchema(schema());
  }

  private PgLeases leases() {
    return new PgLeases(dataSource(), clock(), config());
  }

  private PgDeliveries deliveries() {
    return new PgDeliveries(dataSource(), clock(), config());
  }

  /** An adapter whose units wait up to 30 s for a lock: these tests hold locks on purpose. */
  private PgLedgerStore patient() {
    return new PgLedgerStore(
        dataSource(), clock(), config().withLockTimeout(Duration.ofSeconds(WAIT_SECONDS)));
  }

  private static LeaseToken tokenOf(LeaseResult result) {
    assertThat(result).isInstanceOf(Granted.class);
    return ((Granted) result).grant().token();
  }

  private static AttemptId applied(SendingResult result) {
    assertThat(result.status()).isEqualTo(SendingStatus.APPLIED);
    return result.attempt().orElseThrow();
  }

  private DeliveryState stateOf(DeliveryId id) {
    return store().delivery(id).orElseThrow().state();
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  private static OutboundFile file(String id) {
    byte[] bytes = ("file " + id).getBytes(StandardCharsets.UTF_8);
    return new OutboundFile(id, id + ".xml", bytes, Sha256.of(bytes), "application/xml");
  }

  /** Does the fence of the adapter find a row for this token pair? Raw SQL, the adapter's text. */
  private boolean fenceMatches(long epoch, long counter) throws SQLException {
    long endpointId = count("SELECT endpoint_id FROM endpoint");
    try (Connection connection = raw();
        PreparedStatement statement = connection.prepareStatement(PgLeases.FENCE_SQL)) {
      statement.setShort(1, PgLeases.ENTITY_ID);
      statement.setInt(2, (int) endpointId);
      statement.setLong(3, epoch);
      statement.setLong(4, counter);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  /** The number of backends that are waiting for a lock that the given backend holds. */
  private long waitingFor(int pid) throws SQLException {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND ? = ANY (pg_blocking_pids(pid))")) {
      statement.setInt(1, pid);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  /** Polls pg_stat_activity until a backend waits on the given one; fails after 30 s. */
  private void awaitWaitingFor(int pid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (waitingFor(pid) > 0) {
        return;
      }
      Thread.sleep(25);
    }
    fail("no backend waited for a lock of backend " + pid + " within " + WAIT_SECONDS + " s");
  }

  // ------------------------------------------------------------------------------------------
  // the token pair

  /**
   * The token is the pair (restore epoch, counter): the same counter in another epoch never
   * matches, whether the row is the live one or a lease row that a restore brought back from a
   * backup (spec 10, 14.4 restore-then-zombie).
   */
  @Test
  void theTokenPairIsComparedAsAWholeAcrossEpochs() throws Exception {
    LeaseToken zero = tokenOf(store().acquire(EP, "A", TTL));
    assertThat(zero).isEqualTo(new LeaseToken(0, 1));
    store().declareRestoreEpoch(1);
    LeaseToken one = tokenOf(store().acquire(EP, "B", TTL));
    assertThat(one).isEqualTo(new LeaseToken(1, 1));

    // raw SQL, the text of the adapter
    assertThat(fenceMatches(1, 1)).isTrue();
    assertThat(fenceMatches(0, 1)).as("same counter, other epoch").isFalse();
    assertThat(fenceMatches(1, 2)).isFalse();
    assertThat(fenceMatches(0, 2)).isFalse();
    assertThat(fenceMatches(2, 1)).isFalse();

    // the adapter
    DeliveryId d = queuedDelivery(store(), EP, "bk", 1);
    assertThat(begin(store(), d, zero).status()).isEqualTo(SendingStatus.STALE_LEASE);
    AttemptId attempt = applied(begin(store(), d, one));
    assertThat(store().markCommitIssued(attempt, zero)).isFalse();
    assertThat(store().markCommitIssued(attempt, new LeaseToken(2, 1))).isFalse();
    assertThat(store().markCommitIssued(attempt, one)).isTrue();

    // a lease row restored from a backup: the zombie's own pair is in the row, not released, and
    // the singleton says the epoch is 1, so it still fences nothing; the next acquire replaces it
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE lease SET restore_epoch = 0, counter = 1, released = false, holder = 'ZOMBIE'");
    }
    assertThat(fenceMatches(0, 1)).as("restored row, old token").isFalse();
    assertThat(store().currentLease(EP)).isEmpty();
    DeliveryId next = queuedDelivery(store(), EP, "bk-next", 1);
    assertThat(begin(store(), next, zero).status()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(tokenOf(store().acquire(EP, "C", TTL))).isEqualTo(new LeaseToken(1, 1));
  }

  @Test
  void allocatedAttemptIdsAreNeverReusedAfterARollback() throws Exception {
    LeaseToken token = tokenOf(store().acquire(EP, "A", TTL));
    DeliveryId d = queuedDelivery(store(), EP, "bk", 1);
    DeliveryId other = queuedDelivery(store(), EP, "bk-other", 1);
    PgDeliveries deliveries = deliveries();
    AttemptId first;
    try (Connection connection = raw()) {
      Unit.begin(connection, config());
      first = deliveries.allocateAttemptId(connection);
      assertThat(deliveries.beginSending(connection, d, first, token, attemptIntent()).status())
          .isEqualTo(SendingStatus.APPLIED);
      connection.rollback();
    }
    assertThat(store().attempt(first)).isEmpty();
    assertThat(stateOf(d)).isEqualTo(DeliveryState.QUEUED);
    // the sequence does not give the value out again
    AttemptId second = store().allocateAttemptId();
    assertThat(second.value()).isGreaterThan(first.value());
    // allocated and not stored: usable once; stored: never again
    assertThat(applied(store().beginSending(d, first, token, attemptIntent()))).isEqualTo(first);
    assertThatThrownBy(() -> store().beginSending(other, first, token, attemptIntent()))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(stateOf(other)).isEqualTo(DeliveryState.QUEUED);
  }

  @Test
  void ledgerTimeComesFromTheInjectedClock() throws Exception {
    clock().set(Instant.parse("2001-02-03T04:05:06.123456Z"));
    Instant start = clock().now();
    LeaseGrant grant = ((Granted) store().acquire(EP, "A", TTL)).grant();
    assertThat(grant.expiresAt()).isEqualTo(start.plus(TTL));
    assertThat(storedInstant("SELECT expires_at FROM lease")).isEqualTo(start.plus(TTL));

    // a step of the clock changes what acquire does, with no sleep
    clock().advance(TTL.minus(1, ChronoUnit.MICROS));
    assertThat(store().acquire(EP, "B", TTL)).isEqualTo(new Held("A", start.plus(TTL)));
    clock().advance(Duration.of(1, ChronoUnit.MICROS));
    assertThat(tokenOf(store().acquire(EP, "B", TTL))).isEqualTo(new LeaseToken(0, 2));
    Instant renewedAt = clock().now();
    assertThat(storedInstant("SELECT expires_at FROM lease")).isEqualTo(renewedAt.plus(TTL));

    // the rows written by the delivery side carry the same time
    DeliveryId d = queuedDelivery(store(), EP, "bk", 1);
    assertThat(storedInstant("SELECT created_at FROM delivery")).isEqualTo(renewedAt);
    applied(begin(store(), d, new LeaseToken(0, 2)));
    assertThat(storedInstant("SELECT created_at FROM attempt")).isEqualTo(renewedAt);
  }

  private Instant storedInstant(String sql) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getObject(1, OffsetDateTime.class).toInstant();
    }
  }

  // ------------------------------------------------------------------------------------------
  // the in-flight guard under concurrency

  /**
   * Sixteen deliveries of one business key, each moved into an in-flight state from its own thread:
   * the partial unique index lets exactly one in, and a loser gets a status, never an exception.
   */
  @Test
  void concurrentTransitionsIntoAnInFlightStateHaveOneWinner() throws Exception {
    raceIntoInFlight("bk-ack", DeliveryState.AWAITING_ACK);
    raceIntoInFlight("bk-doubt", DeliveryState.IN_DOUBT);
  }

  private void raceIntoInFlight(String businessKey, DeliveryState target) throws Exception {
    int threads = 16;
    PgLedgerStore patient = patient();
    List<DeliveryId> ids = new ArrayList<>();
    for (int i = 1; i <= threads; i++) {
      ids.add(queuedDelivery(store(), EP, businessKey, i));
    }
    CyclicBarrier start = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<TransitionStatus>> futures = new ArrayList<>();
      for (DeliveryId id : ids) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                  return patient.transition(id, DeliveryState.QUEUED, target);
                }));
      }
      List<TransitionStatus> results = new ArrayList<>();
      for (Future<TransitionStatus> future : futures) {
        results.add(await(future));
      }
      assertThat(results.stream().filter(r -> r == TransitionStatus.APPLIED).count())
          .as("%s: winners", target)
          .isEqualTo(1);
      assertThat(results.stream().filter(r -> r == TransitionStatus.IN_FLIGHT_CONFLICT).count())
          .as("%s: losers", target)
          .isEqualTo(threads - 1);
      assertThat(ids.stream().filter(id -> stateOf(id) == target).count()).isEqualTo(1);
      assertThat(ids.stream().filter(id -> stateOf(id) == DeliveryState.QUEUED).count())
          .isEqualTo(threads - 1);

      // when the winner leaves the in-flight set, one more may enter
      DeliveryId winner = ids.get(results.indexOf(TransitionStatus.APPLIED));
      assertThat(store().transition(winner, target, DeliveryState.ACCEPTED_PROVISIONAL))
          .isEqualTo(TransitionStatus.APPLIED);
      DeliveryId loser = ids.get(results.indexOf(TransitionStatus.IN_FLIGHT_CONFLICT));
      assertThat(store().transition(loser, DeliveryState.QUEUED, target))
          .isEqualTo(TransitionStatus.APPLIED);
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------------------------------
  // LF22: a takeover cannot slip between a fence check and its commit

  /**
   * c1 has passed the fence check and moved the delivery to SENDING but has not committed. The
   * takeover (acquire) must wait for c1, and when it gets through, its sweep sees the committed
   * SENDING row and makes it IN_DOUBT; the old token is fenced afterwards.
   */
  @Test
  void anAcquireWaitsForAnInFlightBeginSendingAndSweepsItsDelivery() throws Exception {
    LeaseToken old = tokenOf(store().acquire(EP, "A", TTL));
    DeliveryId d = queuedDelivery(store(), EP, "bk", 1);
    DeliveryId later = queuedDelivery(store(), EP, "bk-later", 1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection c1 = raw()) {
      Unit.begin(c1, config());
      int pid = backendPid(c1);
      AttemptId attempt = deliveries().allocateAttemptId(c1);
      assertThat(deliveries().beginSending(c1, d, attempt, old, attemptIntent()).status())
          .isEqualTo(SendingStatus.APPLIED);
      clock().advance(TTL);
      PgLedgerStore taker = patient();
      Future<LeaseResult> taking = pool.submit(() -> taker.acquire(EP, "B", TTL));
      awaitWaitingFor(pid);
      assertThat(taking.isDone()).as("the takeover waits for the open unit").isFalse();
      c1.commit();

      LeaseToken fresh = tokenOf(await(taking));
      assertThat(fresh).isEqualTo(new LeaseToken(0, 2));
      assertThat(stateOf(d)).isEqualTo(DeliveryState.IN_DOUBT);
      assertThat(store().attempt(attempt).orElseThrow().commitIssued()).isFalse();
      assertThat(store().markCommitIssued(attempt, old)).isFalse();
      assertThat(begin(store(), later, old).status()).isEqualTo(SendingStatus.STALE_LEASE);
      assertThat(stateOf(later)).isEqualTo(DeliveryState.QUEUED);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The same with the marker: c1 has set {@code commit_issued} and not committed. The takeover
   * waits, the marker commits true, then the sweep makes the delivery IN_DOUBT. And the reverse
   * order: a takeover that has modified the lease row and not committed makes a marker wait; when
   * the takeover commits, the marker finds a new row and returns false.
   */
  @Test
  void anAcquireWaitsForAnInFlightMarkerCas() throws Exception {
    LeaseToken first = tokenOf(store().acquire(EP, "A", TTL));
    DeliveryId d = queuedDelivery(store(), EP, "bk", 1);
    AttemptId attempt = applied(begin(store(), d, first));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // the marker first, the takeover behind it
      try (Connection c1 = raw()) {
        Unit.begin(c1, config());
        int pid = backendPid(c1);
        assertThat(deliveries().markCommitIssued(c1, attempt, first)).isTrue();
        clock().advance(TTL);
        PgLedgerStore taker = patient();
        Future<LeaseResult> taking = pool.submit(() -> taker.acquire(EP, "B", TTL));
        awaitWaitingFor(pid);
        assertThat(taking.isDone()).isFalse();
        c1.commit();
        assertThat(tokenOf(await(taking))).isEqualTo(new LeaseToken(0, 2));
      }
      assertThat(store().attempt(attempt).orElseThrow().commitIssued()).isTrue();
      assertThat(stateOf(d)).isEqualTo(DeliveryState.IN_DOUBT);

      // the takeover first, the marker behind it
      LeaseToken second = new LeaseToken(0, 2);
      DeliveryId d2 = queuedDelivery(store(), EP, "bk2", 1);
      AttemptId attempt2 = applied(begin(store(), d2, second));
      clock().advance(TTL);
      try (Connection c2 = raw()) {
        Unit.begin(c2, config());
        int pid = backendPid(c2);
        assertThat(tokenOf(leases().acquire(c2, EP, "C", TTL))).isEqualTo(new LeaseToken(0, 3));
        PgLedgerStore zombie = patient();
        Future<Boolean> marking = pool.submit(() -> zombie.markCommitIssued(attempt2, second));
        awaitWaitingFor(pid);
        assertThat(marking.isDone()).isFalse();
        c2.commit();
        assertThat(await(marking)).as("marker after a committed takeover").isFalse();
      }
      assertThat(store().attempt(attempt2).orElseThrow().commitIssued()).isFalse();
      assertThat(stateOf(d2)).isEqualTo(DeliveryState.IN_DOUBT);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The sweep is selected by the predicate of {@code delivery_sending_idx}: on a table with many
   * finished deliveries and a few SENDING ones, the plan of the sweep statement names that index.
   */
  @Test
  void theTakeoverSweepUsesTheSendingIndex() throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO delivery (endpoint_id, endpoint_epoch, business_key, version_seq, state,"
              + " created_at) SELECT 1, 1, 'done-' || g, 1, 7, now() FROM generate_series(1, 5000) g");
      statement.execute(
          "INSERT INTO delivery (endpoint_id, endpoint_epoch, business_key, version_seq, state,"
              + " created_at) SELECT 1, 1, 'live-' || g, 1, 4, now() FROM generate_series(1, 3) g");
      statement.execute("ANALYZE delivery");
    }
    String sweep =
        PgLeases.SWEEP_SQL
            .replaceFirst("\\?", Short.toString(Codes.of(DeliveryState.IN_DOUBT)))
            .replaceFirst("\\?", "1");
    String plan;
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("EXPLAIN (FORMAT JSON) " + sweep)) {
      assertThat(rows.next()).isTrue();
      plan = rows.getString(1);
    }
    System.out.println("plan of the takeover sweep: " + plan);
    assertThat(plan).contains("delivery_sending_idx");
  }

  // ------------------------------------------------------------------------------------------
  // LF19: a lost connection is an exception, never an answer

  @Test
  void aLostConnectionAtTheMarkerCommitSendsNothing() {
    SqlSpy spy = new SqlSpy();
    LedgerStore flaky = new PgLedgerStore(spy.over(dataSource()), clock(), config());
    DestinationSimulator destination =
        DestinationSimulator.withScenario(clock(), Scenarios.acpt(Duration.ofSeconds(1)));
    DeliveryId delivery = queuedDelivery(flaky, EP, "EXEC1|LOST", 1);
    FencedSender sender = new FencedSender(flaky, destination, EP, "A", TTL);
    assertThat(sender.acquire()).contains(new LeaseToken(0, 1));
    SQLException lost = new SQLException("connection reset by peer", "08006");

    // the commit of the marker unit fails: the gate throws, so the transport sends nothing
    assertThatThrownBy(() -> sender.send(delivery, file("lost"), () -> spy.failCommitWith(lost)))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=08006 constraint=none")
        .satisfies(e -> assertThat(e.getCause()).isSameAs(lost));
    assertThat(PgErrors.classify(lost)).isEqualTo(Violation.CONNECTION);
    assertThat(destination.receivedFiles()).isEmpty();
    AttemptId attempt = sender.lastAttempt().orElseThrow();
    assertThat(store().attempt(attempt).orElseThrow().commitIssued()).isFalse();
    assertThat(stateOf(delivery)).isEqualTo(DeliveryState.SENDING);
    assertThat(spy.openConnections()).isZero();

    // the next holder's acquire moves the delivery to IN_DOUBT
    clock().advance(TTL);
    assertThat(store().acquire(EP, "B", TTL)).isInstanceOf(Granted.class);
    assertThat(stateOf(delivery)).isEqualTo(DeliveryState.IN_DOUBT);
  }
}
