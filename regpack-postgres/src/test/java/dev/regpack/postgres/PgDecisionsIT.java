package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.bodyAt;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static dev.regpack.testkit.contract.LedgerFixtures.withContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.contract.DecisionLedgerContract;
import dev.regpack.testkit.contract.LedgerFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Real-store tests of the decision side (T16, Convention 4): the properties that the in-memory
 * adapter passes trivially because it has one lock, the partitions, and the seeded duplicates of
 * the constraint clause of the P3 exit criterion (a duplicate {@code decision_seq} across
 * partitions). Skipped without {@code REGPACK_PG_URL}; first run at CP-2.
 */
class PgDecisionsIT extends AbstractPgStoreTest {
  private static final String MIFIR = DecisionLedgerContract.MIFIR;
  private static final Instant EVAL = DecisionLedgerContract.EVAL;

  /** 2026-10-09T09:30:00Z. */
  private static final Instant DAY1 = DecisionLedgerContract.TRADE_DATE_1;

  /** 2026-10-10T09:30:00Z. */
  private static final Instant DAY2 = DecisionLedgerContract.TRADE_DATE_2;

  private static final Pattern PARTITION_NAME = Pattern.compile("decision_p[0-9]{8}");

  private static EventIdentity event(String name) {
    return EventIdentity.of("LEI1", "XPAR", name);
  }

  private static DecisionBody body(String event, String digest, Instant asAt) {
    return bodyAt(event, MIFIR, "ctx", digest, Outcome.REPORT, EVAL, asAt);
  }

  private static BatchCommit batch(NewDecision... decisions) {
    return new BatchCommit(List.of(decisions), Optional.empty());
  }

  private Committed commitOne(DecisionBody body, long expectedHeadSeq) {
    return store().commitDecisions(batch(decision(body, expectedHeadSeq))).get(0);
  }

  private PgLedgerStore anotherStore() {
    return new PgLedgerStore(dataSource(), clock(), Config.forSchema(schema()));
  }

  /** The name of the partition that holds the rows of this event, read through the tableoid. */
  private List<String> partitionsOf(String eventName) throws SQLException {
    return strings(
        "SELECT DISTINCT c.relname FROM decision d JOIN pg_class c ON c.oid = d.tableoid"
            + " WHERE d.event_identity = ? ORDER BY 1",
        event(eventName).value());
  }

  // ------------------------------------------------------------------------------------------
  // the seeded duplicates of the constraint clause

  /**
   * Two writers on one chain, both expecting head 1, with decisions of different trade dates (so
   * they land in different partitions, where no unique index can see the other): the head
   * compare-and-set lets exactly one through, and exactly one row with sequence 2 exists across all
   * partitions. A loser has lost the race for the head; it is not a deadlock.
   */
  @Test
  void crossPartitionDuplicateDecisionSeqIsRejectedByTheHead() throws Exception {
    for (int round = 0; round < 10; round++) {
      String name = "X" + round;
      commitOne(body(name, "d0", DAY1), 0);
      DecisionBody a = body(name, "dA", DAY1);
      DecisionBody b = body(name, "dB", DAY2);
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      int winners = 0;
      try {
        List<Future<Committed>> futures = new ArrayList<>();
        for (DecisionBody candidate : List.of(a, b)) {
          futures.add(
              pool.submit(
                  () -> {
                    go.await();
                    return commitOne(candidate, 1);
                  }));
        }
        go.countDown();
        for (Future<Committed> future : futures) {
          try {
            Committed committed = future.get(60, TimeUnit.SECONDS);
            assertThat(committed.status()).isEqualTo(CommitStatus.INSERTED);
            assertThat(committed.record().decisionSeq()).isEqualTo(2);
            winners++;
          } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(DecisionHeadConflictException.class);
            DecisionHeadConflictException conflict = (DecisionHeadConflictException) e.getCause();
            assertThat(conflict.currentHeadSeq()).isEqualTo(2);
            // a plain lost compare-and-set, not a deadlock or a serialization failure
            assertThat(conflict.getCause()).as("round %d", round).isNull();
          }
        }
      } finally {
        pool.shutdownNow();
      }
      assertThat(winners).as("round %d", round).isEqualTo(1);
      String eventValue = event(name).value();
      assertThat(
              strings(
                  "SELECT decision_seq::text FROM decision WHERE event_identity = ?"
                      + " AND decision_seq = 2",
                  eventValue))
          .as("rows with sequence 2 of %s across all partitions", name)
          .hasSize(1);
      assertThat(store().decisionChain(new Regime(MIFIR), event(name))).hasSize(2);
    }
  }

  /**
   * The characterisation that justifies D-S4: the unique indexes of a partitioned table cannot see
   * across partitions, so raw SQL puts the same (regime, event identity, sequence) into two
   * partitions and both rows are accepted. Only the head compare-and-set stops it.
   */
  @Test
  void theUniqueIndexesAloneCannotSeeAcrossPartitions() throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      for (LocalDate day : List.of(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10))) {
        for (String sql : Partitions.createStatements(schema(), day)) {
          statement.execute(sql);
        }
      }
      statement.execute("INSERT INTO regime (regime) VALUES ('r')");
      statement.execute(
          "INSERT INTO decision_context (context_sha) VALUES (decode(repeat('00', 32), 'hex'))");
      for (String day : List.of("2026-10-09", "2026-10-10")) {
        statement.executeUpdate(
            "INSERT INTO decision (as_at, regime_id, event_identity, decision_seq, context_id,"
                + " input_digest, outcome, circuit_open, body_encoding, body)"
                + " SELECT DATE '"
                + day
                + "', r.regime_id, 'LEI1|XPAR|DUP', 2, x.context_id,"
                + " decode(repeat('01', 32), 'hex'), 1, false, 1, decode('01', 'hex')"
                + " FROM regime r, decision_context x");
      }
    }
    assertThat(
            count(
                "SELECT count(*) FROM decision WHERE event_identity = 'LEI1|XPAR|DUP'"
                    + " AND decision_seq = 2"))
        .isEqualTo(2);
  }

  @Test
  void aRedeliveryNeverAdvancesTheHead() throws SQLException {
    DecisionBody first = body("R1", "d1", DAY1);
    DecisionBody second = body("R1", "d2", DAY2);
    commitOne(first, 0);
    Committed latest = commitOne(second, 1);
    String expectedHead = "2:" + latest.record().decisionId().value();
    assertThat(headRows()).containsExactly(expectedHead);

    // a redelivery with the expected head of its first delivery, with a stale one, and of the
    // latest decision: none of them is an insert, and the head stays where it was
    assertThat(commitOne(first, 0).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(headRows()).containsExactly(expectedHead);
    assertThat(commitOne(first, 5).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(commitOne(second, 1).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(headRows()).containsExactly(expectedHead);
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(2);
  }

  private List<String> headRows() throws SQLException {
    return strings(
        "SELECT decision_seq || ':' || decision_id FROM decision_head WHERE event_identity = ?",
        event("R1").value());
  }

  // ------------------------------------------------------------------------------------------
  // partitions

  @Test
  void asAtNearUtcMidnightLandsInTheUtcPartition() throws SQLException {
    commitOne(
        bodyAt(
            "N1",
            MIFIR,
            "ctx",
            "d1",
            Outcome.REPORT,
            EVAL,
            Instant.parse("2026-10-09T23:59:59.999999Z")),
        0);
    commitOne(
        bodyAt(
            "N2", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, Instant.parse("2026-10-10T00:00:00Z")),
        0);
    assertThat(partitionsOf("N1")).containsExactly("decision_p20261009");
    assertThat(partitionsOf("N2")).containsExactly("decision_p20261010");

    // the same with a session whose time zone is far from UTC: the adapter must not depend on it
    DataSource skewed =
        SqlSpy.withSessionSetup(dataSource(), "SET TimeZone = 'Pacific/Kiritimati'");
    PgLedgerStore through = new PgLedgerStore(skewed, clock(), Config.forSchema(schema()));
    through.commitDecisions(
        batch(
            decision(
                bodyAt(
                    "N3",
                    MIFIR,
                    "ctx",
                    "d1",
                    Outcome.REPORT,
                    EVAL,
                    Instant.parse("2026-10-11T23:59:59.999999Z")),
                0),
            decision(
                bodyAt(
                    "N4",
                    MIFIR,
                    "ctx",
                    "d1",
                    Outcome.REPORT,
                    EVAL,
                    Instant.parse("2026-10-12T00:00:00Z")),
                0)));
    assertThat(partitionsOf("N3")).containsExactly("decision_p20261011");
    assertThat(partitionsOf("N4")).containsExactly("decision_p20261012");

    try (Connection connection = skewed.getConnection();
        PreparedStatement bounds =
            connection.prepareStatement(
                "SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = ? AND c.relname = ?")) {
      for (String[] expected :
          new String[][] {
            {"decision_p20261009", "2026-10-09", "2026-10-10"},
            {"decision_p20261010", "2026-10-10", "2026-10-11"},
            {"decision_p20261011", "2026-10-11", "2026-10-12"},
            {"decision_p20261012", "2026-10-12", "2026-10-13"}
          }) {
        bounds.setString(1, schema());
        bounds.setString(2, expected[0]);
        try (ResultSet rows = bounds.executeQuery()) {
          assertThat(rows.next()).as(expected[0]).isTrue();
          String bound = rows.getString(1);
          assertThat(bound)
              .as("bounds of %s", expected[0])
              .startsWith("FOR VALUES FROM")
              .contains("'" + expected[1] + "'")
              .contains("'" + expected[2] + "'");
        }
      }
    }
  }

  /**
   * The fetch by primary key and day, with the day as a constant, touches one partition: the plan
   * names exactly one. (The adapter binds the day; with a bound value the same pruning happens when
   * the plan is made or when the executor starts.)
   */
  @Test
  void currentDecisionFetchTouchesOnePartition() throws SQLException {
    long lastId = 0;
    for (int i = 0; i < 3; i++) {
      Instant asAt = Instant.parse("2026-10-09T09:30:00Z").plus(Duration.ofDays(i));
      lastId = commitOne(body("P1", "d" + i, asAt), i).record().decisionId().value();
    }
    assertThat(partitionsOf("P1"))
        .containsExactly("decision_p20261009", "decision_p20261010", "decision_p20261011");
    DecisionRecord current = store().currentDecision(new Regime(MIFIR), event("P1")).orElseThrow();
    assertThat(current.decisionId().value()).isEqualTo(lastId);

    String literal =
        PgDecisions.PROBE_SQL
            .replace("decision_id = ?", "decision_id = " + lastId)
            .replace("as_at = ?", "as_at = DATE '2026-10-11'");
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("EXPLAIN (FORMAT JSON) " + literal)) {
      assertThat(rows.next()).isTrue();
      String plan = rows.getString(1);
      Set<String> named = new HashSet<>();
      Matcher matcher = PARTITION_NAME.matcher(plan);
      while (matcher.find()) {
        named.add(matcher.group());
      }
      assertThat(named)
          .as("partitions named by the plan: %s", plan)
          .containsExactly("decision_p20261011");
    }
  }

  /**
   * Eight stores that each know nothing of the new day race to create it: the advisory lock and the
   * {@code pg_inherits} check leave exactly one partition, and every decision lands in it.
   */
  @Test
  void concurrentUnitsForANewDateCreateOnePartition() throws Exception {
    int threads = 8;
    Instant asAt = Instant.parse("2026-10-20T09:30:00Z");
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Committed>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        PgLedgerStore own = anotherStore();
        DecisionBody candidate = body("C" + t, "d", asAt);
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return own.commitDecisions(batch(decision(candidate, 0))).get(0);
                }));
      }
      go.countDown();
      for (Future<Committed> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS).status()).isEqualTo(CommitStatus.INSERTED);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            strings(
                "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                    + " WHERE i.inhparent = 'decision'::regclass AND c.relname = ?",
                "decision_p20261020"))
        .hasSize(1);
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(threads);
    assertThat(count("SELECT count(*) FROM decision_head")).isEqualTo(threads);
  }

  /**
   * A plain table that already has the name of the partition makes the creation fail (42P07), and
   * the call fails closed before any row of any table is written. Once the name is free the same
   * call succeeds.
   */
  @Test
  void aFailedPartitionCreationFailsTheUnitClosed() throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE decision_p20261012 (x int)");
    }
    DecisionBody blocked = body("F1", "d1", Instant.parse("2026-10-12T09:30:00Z"));
    assertThatThrownBy(() -> commitOne(blocked, 0))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=42P07 constraint=none");
    assertThat(count("SELECT count(*) FROM decision")).isZero();
    assertThat(count("SELECT count(*) FROM decision_head")).isZero();
    assertThat(count("SELECT count(*) FROM regime")).isZero();
    assertThat(count("SELECT count(*) FROM decision_context")).isZero();

    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE decision_p20261012");
    }
    assertThat(commitOne(blocked, 0).status()).isEqualTo(CommitStatus.INSERTED);
  }

  /** With on-demand creation off, a missing partition is a typed failure and writes nothing. */
  @Test
  void aMissingPartitionWithCreationOffFailsTypedAndWritesNothing() throws SQLException {
    PgLedgerStore off =
        new PgLedgerStore(
            dataSource(), clock(), Config.forSchema(schema()).withPartitionsOnDemand(false));
    DecisionBody candidate = body("O1", "d1", DAY1);
    assertThatThrownBy(() -> off.commitDecisions(batch(decision(candidate, 0))))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=23514 constraint=none");
    assertThat(count("SELECT count(*) FROM decision_head")).isZero();
    assertThat(count("SELECT count(*) FROM regime")).isZero();
    assertThat(count("SELECT count(*) FROM decision_context")).isZero();

    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      for (String sql : Partitions.createStatements(schema(), LocalDate.of(2026, 10, 9))) {
        statement.execute(sql);
      }
    }
    assertThat(off.commitDecisions(batch(decision(candidate, 0))).get(0).status())
        .isEqualTo(CommitStatus.INSERTED);
  }

  /**
   * A partition that was dropped (retention) after the adapter cached its day: the next call fails
   * with the typed missing-partition failure and forgets the cache, so the call after it creates
   * the partition again.
   */
  @Test
  void aDroppedPartitionFailsOnceAndIsCreatedAgain() throws SQLException {
    commitOne(body("D1", "d1", DAY1), 0);
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE decision_p20261009");
    }
    DecisionBody next = body("D2", "d1", DAY1);
    assertThatThrownBy(() -> commitOne(next, 0))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=23514 constraint=none");
    assertThat(commitOne(next, 0).status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(partitionsOf("D2")).containsExactly("decision_p20261009");
  }

  /**
   * The plan assumes (NOT CONFIRMED from the manual, AP-7) that attaching a partition takes SHARE
   * UPDATE EXCLUSIVE on the parent and so does not block inserts into other partitions, while
   * {@code PARTITION OF} would take ACCESS EXCLUSIVE. This test measures it: connection c1 runs the
   * {@code CREATE TABLE} and the {@code ATTACH PARTITION} of a creation and keeps its transaction
   * open; a decision commit into an existing partition must complete within five seconds. The lock
   * modes that c1 holds are printed either way, and a blocked insert fails the test with those
   * modes and the locks the insert waits for, so a red run states the observed lock mode instead of
   * only failing.
   */
  @Test
  void creatingAPartitionDoesNotBlockInsertsIntoOtherPartitions() throws Exception {
    // the adapter that inserts knows the existing day, so it does not take part in the creation
    PgLedgerStore patient =
        new PgLedgerStore(
            dataSource(),
            clock(),
            Config.forSchema(schema()).withLockTimeout(Duration.ofSeconds(30)));
    patient.commitDecisions(batch(decision(body("L0", "d0", DAY1), 0)));
    LocalDate newDay = LocalDate.of(2026, 10, 21);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection creator = raw()) {
      creator.setAutoCommit(false);
      int creatorPid = backendPid(creator);
      try (Statement statement = creator.createStatement()) {
        for (String sql : Partitions.createStatements(schema(), newDay)) {
          statement.execute(sql);
        }
      }
      List<String> held = locksHeldBy(creatorPid);
      long started = System.currentTimeMillis();
      Future<Committed> insert =
          pool.submit(
              () -> patient.commitDecisions(batch(decision(body("L1", "d1", DAY1), 0))).get(0));
      Committed committed = null;
      List<String> waiting = List.of();
      try {
        committed = insert.get(5, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        waiting = locksWaitingFor(creatorPid);
      } finally {
        creator.rollback();
      }
      if (committed == null) {
        // the creating transaction is gone: let the blocked insert finish before reporting
        awaitQuietly(insert);
        fail(
            "an insert into an existing partition was blocked for 5 s by a transaction that was"
                + " creating another partition. Locks held by the creating transaction: "
                + held
                + ". Locks the blocked insert waits for: "
                + waiting);
      }
      System.out.println(
          "creatingAPartition: an insert into an existing partition committed in "
              + (System.currentTimeMillis() - started)
              + " ms while the creating transaction held "
              + held);
      assertThat(committed.status()).isEqualTo(CommitStatus.INSERTED);
    } finally {
      pool.shutdownNow();
    }
  }

  private static void awaitQuietly(Future<?> future) {
    try {
      future.get(60, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      // the outcome of the blocked insert is not the subject of the test that calls this
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private int backendPid(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  /** The table-level locks that a backend holds in the test schema, as {@code table: mode}. */
  private List<String> locksHeldBy(int pid) throws SQLException {
    return lockRows(
        "SELECT c.relname || ': ' || l.mode FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE l.pid = ? AND l.granted"
            + " AND l.locktype = 'relation' AND n.nspname = ? AND c.relkind IN ('r', 'p')"
            + " ORDER BY 1",
        pid,
        schema());
  }

  /** The table-level locks that backends blocked by the given backend are waiting for. */
  private List<String> locksWaitingFor(int pid) throws SQLException {
    return lockRows(
        "SELECT c.relname || ': ' || l.mode || ' (waiting)' FROM pg_locks l"
            + " JOIN pg_class c ON c.oid = l.relation JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE NOT l.granted AND l.locktype = 'relation' AND n.nspname = ?"
            + " AND ? = ANY (pg_blocking_pids(l.pid)) ORDER BY 1",
        schema(),
        pid);
  }

  private List<String> lockRows(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        List<String> found = new ArrayList<>();
        while (rows.next()) {
          found.add(rows.getString(1));
        }
        return found;
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // chains

  @Test
  void chainIsReadThroughTheSupersedesLinks() throws SQLException {
    List<DecisionRecord> written = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Instant asAt = Instant.parse("2026-10-09T09:30:00Z").plus(Duration.ofDays(i % 4));
      written.add(commitOne(body("CH", "d" + i, asAt), i).record());
    }
    assertThat(partitionsOf("CH")).hasSize(4);
    List<DecisionRecord> chain = store().decisionChain(new Regime(MIFIR), event("CH"));
    assertThat(chain).isEqualTo(written);
    assertThat(chain)
        .extracting(DecisionRecord::decisionSeq)
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    assertThat(chain.get(0).supersedesDecisionId()).isEmpty();
    for (int i = 1; i < chain.size(); i++) {
      assertThat(chain.get(i).supersedesDecisionId()).contains(chain.get(i - 1).decisionId());
    }
    assertThat(store().currentDecision(new Regime(MIFIR), event("CH"))).contains(written.get(9));
    assertThat(store().decisionChain(new Regime(MIFIR), event("NOPE"))).isEmpty();
    assertThat(store().currentDecision(new Regime("emir.eu"), event("CH"))).isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // contexts and the fixed columns

  private static DecisionContext packContext(String pack) {
    return new DecisionContext(
        Sha256.ofUtf8(pack),
        Optional.empty(),
        List.of("pin-1"),
        List.of("ref-1"),
        List.of(),
        "fp-1",
        "map-1",
        "build-1",
        "interpreter");
  }

  private static DecisionBody undetermined(
      String event, DecisionContext context, String digest, List<String> flags, String reason) {
    return new DecisionBody(
        event(event),
        new Regime(MIFIR),
        context.id(),
        DAY1,
        EVAL,
        Outcome.UNDETERMINED,
        flags,
        new ReasonCode(reason),
        "N08",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Sha256.ofUtf8(digest));
  }

  @Test
  void circuitOpenDecisionsAreEnumeratedByPackDigest() throws SQLException {
    DecisionContext packA = packContext("pack-A");
    DecisionContext packB = packContext("pack-B");
    List<String> open = List.of("rule_id=R1", "circuit=open");
    store()
        .commitDecisions(
            batch(
                decision(undetermined("A1", packA, "d1", open, "EXEC_CIRCUIT_OPEN"), 0, packA),
                decision(undetermined("A2", packA, "d2", open, "EXEC_CIRCUIT_OPEN"), 0, packA),
                decision(
                    undetermined("A3", packA, "d3", List.of("rule_id=R2"), "EXEC_ERROR"), 0, packA),
                decision(undetermined("B1", packB, "d4", open, "EXEC_CIRCUIT_OPEN"), 0, packB)));

    String sql =
        "SELECT d.event_identity FROM decision d JOIN decision_context c"
            + " ON c.context_id = d.context_id WHERE d.circuit_open"
            + " AND c.pack_digest = decode(?, 'hex') ORDER BY d.event_identity";
    assertThat(strings(sql, packA.packDigest().hex()))
        .containsExactly(event("A1").value(), event("A2").value());
    assertThat(strings(sql, packB.packDigest().hex())).containsExactly(event("B1").value());
    assertThat(count("SELECT count(*) FROM decision WHERE NOT circuit_open")).isEqualTo(1);
    assertThat(
            strings(
                "SELECT encode(pack_digest, 'hex') FROM decision_context WHERE context_sha ="
                    + " decode(?, 'hex')",
                packA.id().hex()))
        .containsExactly(packA.packDigest().hex());

    // the partial index of the parent is the one the partitions use: the attach adopted the copy
    // that CREATE TABLE ... LIKE ... INCLUDING INDEXES made, instead of building another
    assertThat(
            count(
                "SELECT count(*) FROM pg_inherits i"
                    + " WHERE i.inhparent = 'decision_circuit_open_idx'::regclass"))
        .as("partitions with an index attached to decision_circuit_open_idx")
        .isEqualTo(
            count("SELECT count(*) FROM pg_inherits i WHERE i.inhparent = 'decision'::regclass"))
        .isEqualTo(1);
    assertThat(
            strings(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename ="
                    + " 'decision_p20261009'",
                schema()))
        .anyMatch(definition -> definition.contains("circuit_open"));
  }

  @Test
  void aDecisionWithoutAContextUsesAStubRowAndALaterDecisionWithTheContextFillsIt()
      throws SQLException {
    DecisionContext context = LedgerFixtures.context("b1");
    DecisionBody first = withContext(body("S1", "d1", DAY1), context);
    DecisionBody second = withContext(body("S1", "d2", DAY1), context);
    DecisionBody third = withContext(body("S1", "d3", DAY1), context);

    commitOne(first, 0);
    assertThat(count("SELECT count(*) FROM decision_context")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM decision_context WHERE body IS NULL")).isEqualTo(1);
    assertThat(store().decisionContext(context.id())).isEmpty();

    store().commitDecisions(batch(decision(second, 1, context)));
    assertThat(count("SELECT count(*) FROM decision_context")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM decision_context WHERE body IS NULL")).isZero();
    assertThat(store().decisionContext(context.id())).contains(context);
    String contextRow =
        "SELECT xmin::text || ':' || encode(pack_digest, 'hex') || ':' || circuit_epoch ||"
            + " ':' || engine_build FROM decision_context WHERE context_sha = decode(?, 'hex')";
    List<String> filled = strings(contextRow, context.id().hex());
    assertThat(filled).hasSize(1);
    assertThat(filled.get(0))
        .endsWith(":" + context.packDigest().hex() + ":0:" + context.engineBuild());

    // the fill happens once: another decision that carries the context leaves the row untouched
    store().commitDecisions(batch(decision(third, 2, context)));
    assertThat(strings(contextRow, context.id().hex())).isEqualTo(filled);
    assertThat(count("SELECT count(*) FROM decision_context")).isEqualTo(1);
  }

  @Test
  void concurrentCommitsOfTheSameNewContextAgreeOnOneRow() throws Exception {
    int threads = 8;
    DecisionContext context = LedgerFixtures.context("b-race");
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Committed>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        PgLedgerStore own = anotherStore();
        DecisionBody candidate = withContext(body("K" + t, "d", DAY1), context);
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return own.commitDecisions(batch(decision(candidate, 0, context))).get(0);
                }));
      }
      go.countDown();
      for (Future<Committed> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS).status()).isEqualTo(CommitStatus.INSERTED);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("SELECT count(*) FROM decision_context")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM decision_context WHERE body IS NULL")).isZero();
    assertThat(store().decisionContext(context.id())).contains(context);
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(threads);
  }

  // ------------------------------------------------------------------------------------------
  // batches

  /**
   * Two batches over the same two events in opposite order, 200 rounds: the rows are locked in one
   * canonical order, so exactly one batch wins each round and the other gets the typed conflict,
   * never a raw SQL failure and never a deadlock (a deadlock victim would carry the failure as the
   * cause of its conflict).
   */
  @Test
  void opposedBatchOrdersDoNotDeadlock() throws Exception {
    // the first commit makes the partition, the regime and the context row, so that the race is
    // about the heads
    commitOne(body("PRIME", "p", DAY1), 0);
    PgLedgerStore left = anotherStore();
    PgLedgerStore right = anotherStore();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    try {
      for (int round = 0; round < 200; round++) {
        String a = "OA" + round;
        String b = "OB" + round;
        BatchCommit forward =
            batch(decision(body(a, "f", DAY1), 0), decision(body(b, "f", DAY1), 0));
        BatchCommit backward =
            batch(decision(body(b, "g", DAY1), 0), decision(body(a, "g", DAY1), 0));
        Future<Throwable> first = pool.submit(() -> attempt(left, forward, barrier));
        Future<Throwable> second = pool.submit(() -> attempt(right, backward, barrier));
        List<Throwable> failures = new ArrayList<>();
        for (Future<Throwable> future : List.of(first, second)) {
          Throwable failure = future.get(60, TimeUnit.SECONDS);
          if (failure != null) {
            failures.add(failure);
          }
        }
        assertThat(failures).as("round %d", round).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(DecisionHeadConflictException.class);
        assertThat(failures.get(0).getCause()).as("round %d: a deadlock?", round).isNull();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static Throwable attempt(PgLedgerStore store, BatchCommit commit, CyclicBarrier barrier)
      throws Exception {
    barrier.await(30, TimeUnit.SECONDS);
    try {
      store.commitDecisions(commit);
      return null;
    } catch (DecisionHeadConflictException e) {
      return e;
    }
  }

  /** A tripwire against per-row round trips: the statement count does not grow with the batch. */
  @Test
  void aBatchOfManyIndependentDecisionsUsesAConstantNumberOfStatements() throws SQLException {
    SqlSpy spy = new SqlSpy();
    PgLedgerStore counted =
        new PgLedgerStore(spy.over(dataSource()), clock(), Config.forSchema(schema()));
    List<NewDecision> decisions = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      decisions.add(decision(body("M" + i, "d", i % 2 == 0 ? DAY1 : DAY2), 0));
    }
    List<Committed> committed =
        counted.commitDecisions(new BatchCommit(decisions, Optional.empty()));
    System.out.println(
        "statements for one decision unit of 1,000 independent decisions (two new partitions"
            + " included): "
            + spy.statements());
    assertThat(committed).hasSize(1_000);
    assertThat(committed).allMatch(c -> c.status() == CommitStatus.INSERTED);
    assertThat(spy.statements()).isPositive().isLessThanOrEqualTo(60);
    assertThat(spy.openConnections()).isZero();
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(1_000);
    assertThat(count("SELECT count(*) FROM decision_head WHERE decision_seq = 1")).isEqualTo(1_000);
  }

  /** The decisions of many chains, two per chain in one batch, are applied wave by wave. */
  @Test
  void aBatchWithTwoDecisionsOnEachOfManyChainsIsAppliedInWaves() throws SQLException {
    int chains = 50;
    List<NewDecision> decisions = new ArrayList<>();
    for (int i = 0; i < chains; i++) {
      decisions.add(decision(body("W" + i, "a", DAY1), 0));
    }
    for (int i = chains - 1; i >= 0; i--) {
      decisions.add(decision(body("W" + i, "b", DAY2), 1));
    }
    List<Committed> committed =
        store().commitDecisions(new BatchCommit(decisions, Optional.empty()));
    assertThat(committed).hasSize(2 * chains);
    assertThat(committed).allMatch(c -> c.status() == CommitStatus.INSERTED);
    for (int i = 0; i < chains; i++) {
      assertThat(committed.get(i).record().decisionSeq()).isEqualTo(1);
      Committed later = committed.get(2 * chains - 1 - i);
      assertThat(later.record().decisionSeq()).isEqualTo(2);
      assertThat(later.record().supersedesDecisionId())
          .contains(committed.get(i).record().decisionId());
    }
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(2L * chains);
    assertThat(count("SELECT count(*) FROM decision_head WHERE decision_seq = 2"))
        .isEqualTo(chains);
    assertThat(store().decisionChain(new Regime(MIFIR), event("W7"))).hasSize(2);
  }

  @Test
  void aConflictInALaterWaveRollsBackTheEarlierWaves() throws SQLException {
    BatchCommit bad =
        batch(
            decision(body("V1", "a", DAY1), 0),
            decision(body("V2", "a", DAY1), 0),
            decision(body("V1", "b", DAY1), 5));
    assertThatThrownBy(() -> store().commitDecisions(bad))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class,
            e -> {
              assertThat(e.eventIdentity()).isEqualTo(event("V1"));
              // read again after the rollback: the first wave is not there
              assertThat(e.currentHeadSeq()).isZero();
            });
    assertThat(count("SELECT count(*) FROM decision")).isZero();
    assertThat(count("SELECT count(*) FROM decision_head")).isZero();
    assertThat(count("SELECT count(*) FROM regime")).isZero();
  }

  /** A whole batch delivered twice: every decision is a no-op and nothing is written. */
  @Test
  void aRedeliveredBatchIsNoopForEveryDecisionAndWritesNothing() throws SQLException {
    List<NewDecision> decisions = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      decisions.add(decision(body("Q" + i, "d", i % 2 == 0 ? DAY1 : DAY2), 0));
    }
    BatchCommit commit = new BatchCommit(decisions, Optional.empty());
    List<Committed> first = store().commitDecisions(commit);
    List<Committed> again = store().commitDecisions(commit);
    assertThat(again).hasSize(200);
    assertThat(again).allMatch(c -> c.status() == CommitStatus.NOOP_REDELIVERY);
    for (int i = 0; i < 200; i++) {
      assertThat(again.get(i).record()).isEqualTo(first.get(i).record());
    }
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(200);
    assertThat(count("SELECT count(*) FROM decision_head WHERE decision_seq = 1")).isEqualTo(200);
  }
}
