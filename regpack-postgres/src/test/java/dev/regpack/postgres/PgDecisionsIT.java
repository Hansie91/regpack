package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.bodyAt;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static dev.regpack.testkit.contract.LedgerFixtures.withContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
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
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.VersionResult;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import dev.regpack.ports.ledger.WithdrawalRequiredException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
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
   * FP5 (SQL-6): the same pruning for the statement the adapter really runs, with bound values and
   * not literals, and for a plan that is made once and reused.
   *
   * <ol>
   *   <li>{@link PgDecisions#PROBE_SQL} as it stands, bound through JDBC the way {@code probe}
   *       binds it (a {@code long} and a {@code LocalDate}), under {@code EXPLAIN (ANALYZE, COSTS
   *       OFF)}, executed seven times on one connection: more than the five executions after which
   *       the driver prepares a statement on the server (prepareThreshold 5) and the server may
   *       switch to a generic plan. Only the partition of the day may be named by each plan.
   *   <li>The same statement as a server-side prepared statement under {@code plan_cache_mode =
   *       force_generic_plan}, executed seven times under {@code EXPLAIN (ANALYZE, COSTS OFF)
   *       EXECUTE}: a generic plan cannot know the day when it is made, so the other partitions
   *       must be removed when the executor starts ({@code Subplans Removed}) and only the
   *       partition of the day may be named.
   * </ol>
   *
   * A plan is printed in every failure message.
   */
  @Test
  void theBoundProbeStatementTouchesOnePartitionAlsoAsAReusedAndAGenericPlan() throws SQLException {
    long lastId = 0;
    for (int i = 0; i < 3; i++) {
      Instant asAt = Instant.parse("2026-10-09T09:30:00Z").plus(Duration.ofDays(i));
      lastId = commitOne(body("P2", "d" + i, asAt), i).record().decisionId().value();
    }
    assertThat(partitionsOf("P2"))
        .containsExactly("decision_p20261009", "decision_p20261010", "decision_p20261011");
    LocalDate day = LocalDate.of(2026, 10, 11);

    try (Connection connection = raw();
        PreparedStatement explain =
            connection.prepareStatement("EXPLAIN (ANALYZE, COSTS OFF) " + PgDecisions.PROBE_SQL)) {
      for (int execution = 1; execution <= 7; execution++) {
        explain.setLong(1, lastId);
        explain.setObject(2, day);
        String plan = planOf(explain);
        assertThat(partitionsNamedBy(plan))
            .as("bound statement, execution %d, plan:%n%s", execution, plan)
            .containsExactly("decision_p20261011");
      }
    }

    String numbered = numbered(PgDecisions.PROBE_SQL);
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      statement.execute("SET plan_cache_mode = force_generic_plan");
      statement.execute("PREPARE probe_generic (int8, date) AS " + numbered);
      for (int execution = 1; execution <= 7; execution++) {
        String plan;
        try (ResultSet rows =
            statement.executeQuery(
                "EXPLAIN (ANALYZE, COSTS OFF) EXECUTE probe_generic("
                    + lastId
                    + ", DATE '"
                    + day
                    + "')")) {
          plan = planText(rows);
        }
        assertThat(partitionsNamedBy(plan))
            .as("generic plan, execution %d, plan:%n%s", execution, plan)
            .containsExactly("decision_p20261011");
        assertThat(plan)
            .as("generic plan, execution %d: the other partitions are removed at start", execution)
            .contains("Subplans Removed");
      }
    }
  }

  /** {@code ?} to {@code $1}, {@code $2}, ... in a statement without a literal that holds one. */
  private static String numbered(String sql) {
    StringBuilder out = new StringBuilder();
    int next = 1;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      out.append(c == '?' ? "$" + next++ : String.valueOf(c));
    }
    return out.toString();
  }

  private static String planOf(PreparedStatement explain) throws SQLException {
    try (ResultSet rows = explain.executeQuery()) {
      return planText(rows);
    }
  }

  private static String planText(ResultSet rows) throws SQLException {
    StringBuilder plan = new StringBuilder();
    while (rows.next()) {
      plan.append(rows.getString(1)).append('\n');
    }
    return plan.toString();
  }

  private static Set<String> partitionsNamedBy(String plan) {
    Set<String> named = new HashSet<>();
    Matcher matcher = PARTITION_NAME.matcher(plan);
    while (matcher.find()) {
      named.add(matcher.group());
    }
    return named;
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
    // the dictionary rows are resolved before the unit, which is what fails: they stay, as orphans
    assertThat(count("SELECT count(*) FROM regime")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM decision_context")).isEqualTo(1);

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
   * A partition that was dropped (retention) after the adapter cached its day: the unit of the next
   * call fails with the missing-partition error and is rolled back, the adapter forgets its cache,
   * creates the partition again and runs the unit once more (FP5), so the call succeeds. (Before
   * FP5 that call failed once and the call after it succeeded. With creation on demand switched off
   * the failure stays typed: {@link #aMissingPartitionWithCreationOffFailsTypedAndWritesNothing}.)
   */
  @Test
  void aDroppedPartitionIsCreatedAgainAndTheCallThatFoundItMissingSucceeds() throws SQLException {
    commitOne(body("D1", "d1", DAY1), 0);
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      // The way an operator removes a daily piece: report_version_decision_fk references the
      // partitioned table, so PostgreSQL keeps one dependent constraint object per partition and
      // refuses a plain DROP TABLE of a partition ("other objects depend on it", CP-2 run
      // 39155154276). Detach the piece first (allowed: nothing references its rows), then drop the
      // now standalone table; CASCADE removes only constraint objects of this throwaway schema.
      statement.execute("ALTER TABLE decision DETACH PARTITION decision_p20261009");
      statement.execute("DROP TABLE decision_p20261009 CASCADE");
    }
    DecisionBody next = body("D2", "d1", DAY1);
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

  /** How long {@link #creatingAPartitionDelaysNoWriterOfAnotherDayBeyondTheCreation} watches. */
  private static final Duration OBSERVE = Duration.ofSeconds(3);

  /**
   * FP5 (SPEC-5, SQL-5). Attaching a partition to {@code decision} may take stronger locks on the
   * tables that reference it or that it references than on {@code decision} itself ({@code
   * report_version} through {@code report_version_decision_fk}, {@code decision_context} through
   * {@code decision_context_fk}); the test above writes only a decision with an existing context
   * and would not see it. Here the creating transaction stays open (after {@code CREATE TABLE} and
   * {@code ATTACH PARTITION}, before the commit) while three writers of an existing day run: a
   * decision with an existing context, a {@code commitReportable} that inserts a {@code
   * report_version}, and a decision with a new context (a {@code decision_context} insert). What
   * matters to operations is that a writer is delayed no longer than the creation takes, so the
   * test does not assert a lock mode. It watches for {@link #OBSERVE}, prints for each writer
   * whether it finished while the creating transaction was open together with the locks that the
   * creator holds and the locks the waiting ones wait for, then commits the creation and requires
   * that every writer succeeded and finished within ten seconds of that commit (no deadlock, no
   * pile-up, no failure). A writer that was delayed names its waits in the output: then
   * pre-creation off-peak ({@link PgLedgerStore#ensurePartitionsAhead}) is not only advice but
   * required, and the notes say so.
   */
  @Test
  void creatingAPartitionDelaysNoWriterOfAnotherDayBeyondTheCreation() throws Exception {
    PgLedgerStore patient =
        new PgLedgerStore(
            dataSource(),
            clock(),
            Config.forSchema(schema()).withLockTimeout(Duration.ofSeconds(30)));
    patient.commitDecisions(batch(decision(body("W0", "d0", DAY1), 0)));
    LocalDate newDay = LocalDate.of(2026, 10, 25);
    NewDecision reported = LedgerFixtures.reportDecision(0, "W2", "ctx", "d1", Leg.MARKET);
    NewVersion version =
        LedgerFixtures.newVersion("W2", Leg.MARKET, "EXEC1", "TRNW2", "NEWT", "h1");

    Map<String, Callable<Long>> writers = new LinkedHashMap<>();
    writers.put(
        "decision with an existing context",
        () -> {
          patient.commitDecisions(batch(decision(body("W1", "d1", DAY1), 0)));
          return System.nanoTime();
        });
    writers.put(
        "commitReportable with a new report_version",
        () -> {
          ReportableCommitted done = patient.commitReportable(reportable(reported, version)).get(0);
          assertThat(done.versions().get(0).status()).isEqualTo(VersionStatus.CREATED);
          return System.nanoTime();
        });
    writers.put(
        "decision with a new context",
        () -> {
          patient.commitDecisions(
              batch(decision(bodyAt("W3", MIFIR, "ctx-new", "d1", Outcome.REPORT, EVAL, DAY1), 0)));
          return System.nanoTime();
        });

    ExecutorService pool = Executors.newFixedThreadPool(writers.size());
    try (Connection creator = raw()) {
      creator.setAutoCommit(false);
      int creatorPid = backendPid(creator);
      try (Statement statement = creator.createStatement()) {
        for (String sql : Partitions.createStatements(schema(), newDay)) {
          statement.execute(sql);
        }
      }
      List<String> held = locksHeldBy(creatorPid);
      Map<String, Future<Long>> running = new LinkedHashMap<>();
      writers.forEach((name, writer) -> running.put(name, pool.submit(writer)));
      long until = System.nanoTime() + OBSERVE.toNanos();
      while (System.nanoTime() < until
          && running.values().stream().anyMatch(future -> !future.isDone())) {
        Thread.sleep(25);
      }
      Set<String> finishedWhileOpen = new HashSet<>();
      running.forEach(
          (name, future) -> {
            if (future.isDone()) {
              finishedWhileOpen.add(name);
            }
          });
      List<String> waiting =
          finishedWhileOpen.size() < running.size() ? locksWaitingFor(creatorPid) : List.of();
      creator.commit();
      long committedAt = System.nanoTime();

      String locks =
          "locks held by the creating transaction: "
              + held
              + "; locks that the delayed writers wait for: "
              + waiting;
      for (Map.Entry<String, Future<Long>> entry : running.entrySet()) {
        long finishedAt;
        try {
          finishedAt = entry.getValue().get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
          throw new AssertionError(
              "writer '" + entry.getKey() + "' failed: " + e.getCause() + "; " + locks, e);
        }
        boolean open = finishedWhileOpen.contains(entry.getKey());
        long afterCommit = TimeUnit.NANOSECONDS.toMillis(finishedAt - committedAt);
        System.out.println(
            "creatingAPartition: '"
                + entry.getKey()
                + (open
                    ? "' finished while the creating transaction was open"
                    : "' was delayed by the creating transaction and finished "
                        + afterCommit
                        + " ms after its commit"));
        if (!open) {
          assertThat(afterCommit)
              .as("'%s' finished this long after the creation; %s", entry.getKey(), locks)
              .isLessThan(10_000);
        }
      }
      System.out.println("creatingAPartition: " + locks);
      assertThat(
              strings(
                  "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                      + " WHERE i.inhparent = 'decision'::regclass AND c.relname = ?",
                  "decision_p20261025"))
          .hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * FP5 (ADAPTER-1, e). The creation of a daily partition needs SHARE UPDATE EXCLUSIVE on {@code
   * decision} (the manual for {@code ALTER TABLE ... ATTACH PARTITION}; the premise of AP-7, now
   * measured), which conflicts with itself, and so does {@code ANALYZE} of the partitioned parent.
   * A second connection holds {@code LOCK TABLE decision IN SHARE UPDATE EXCLUSIVE MODE} while the
   * first batch of a new UTC day arrives: after the bounded retries the call fails with 55P03,
   * nothing is created or written, and the same call succeeds once the lock is released.
   */
  @Test
  void aHolderOfShareUpdateExclusiveOnDecisionFailsTheNewDayClosedUntilItLetsGo() throws Exception {
    commitOne(body("H0", "d0", DAY1), 0);
    PgLedgerStore impatient =
        new PgLedgerStore(
            dataSource(),
            clock(),
            Config.forSchema(schema()).withLockTimeout(Duration.ofMillis(500)));
    DecisionBody candidate = body("H1", "d1", Instant.parse("2026-10-22T09:30:00Z"));
    long regimesBefore = count("SELECT count(*) FROM regime");
    long contextsBefore = count("SELECT count(*) FROM decision_context");

    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute("LOCK TABLE decision IN SHARE UPDATE EXCLUSIVE MODE");
      }
      List<String> held = locksHeldBy(backendPid(holder));
      Throwable thrown =
          catchThrowable(() -> impatient.commitDecisions(batch(decision(candidate, 0))));
      assertThat(thrown)
          .as(
              "creating a partition should wait for a SHARE UPDATE EXCLUSIVE holder on decision;"
                  + " locks held by the holder: %s",
              held)
          .isInstanceOf(LedgerException.class)
          .hasMessage("ledger statement failed: sqlstate=55P03 constraint=none");
      assertThat(
              strings(
                  "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                      + " WHERE i.inhparent = 'decision'::regclass AND c.relname = ?",
                  "decision_p20261022"))
          .isEmpty();
      assertThat(count("SELECT count(*) FROM decision")).isEqualTo(1);
      assertThat(count("SELECT count(*) FROM regime")).isEqualTo(regimesBefore);
      assertThat(count("SELECT count(*) FROM decision_context")).isEqualTo(contextsBefore);
      holder.rollback();
    }

    assertThat(impatient.commitDecisions(batch(decision(candidate, 0))).get(0).status())
        .isEqualTo(CommitStatus.INSERTED);
    assertThat(partitionsOf("H1")).containsExactly("decision_p20261022");
  }

  /**
   * FP5: a creation that waits for a holder of SHARE UPDATE EXCLUSIVE on {@code decision} succeeds
   * on a retry once the holder lets go, within the bounded retries: here the pre-creation entry,
   * for two days, while the lock is released after the first attempt has been seen waiting.
   */
  @Test
  void theCreationRetriesAndSucceedsOnceTheHolderOfTheLockLetsGo() throws Exception {
    PgLedgerStore scheduler =
        new PgLedgerStore(
            dataSource(),
            clock(),
            Config.forSchema(schema()).withLockTimeout(Duration.ofMillis(500)));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute("LOCK TABLE decision IN SHARE UPDATE EXCLUSIVE MODE");
      }
      int holderPid = backendPid(holder);
      Future<List<LocalDate>> created =
          pool.submit(() -> scheduler.ensurePartitionsAhead(LocalDate.of(2026, 10, 23), 2));
      awaitBackendWaitingFor(holderPid);
      assertThat(created.isDone()).as("the attach waits for the holder").isFalse();
      holder.commit();
      assertThat(created.get(60, TimeUnit.SECONDS))
          .containsExactly(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24));
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            count(
                "SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                    + " WHERE i.inhparent = 'decision'::regclass"
                    + " AND c.relname IN ('decision_p20261023', 'decision_p20261024')"))
        .isEqualTo(2);
    // asked again, the entry finds both and creates nothing
    assertThat(scheduler.ensurePartitionsAhead(LocalDate.of(2026, 10, 23), 2)).isEmpty();
  }

  /**
   * FP5: with the day made ahead of time, the first batch of that day needs no lock that a holder
   * of SHARE UPDATE EXCLUSIVE on {@code decision} (an {@code ANALYZE} of the parent) can block: it
   * finds the partition and only inserts, which is compatible with that lock.
   */
  @Test
  void aPreCreatedDayIsWrittenWhileAnotherSessionHoldsShareUpdateExclusiveOnDecision()
      throws Exception {
    store().ensurePartitionsAhead(LocalDate.of(2026, 10, 26), 1);
    PgLedgerStore hot =
        new PgLedgerStore(
            dataSource(),
            clock(),
            Config.forSchema(schema()).withLockTimeout(Duration.ofSeconds(2)));
    try (Connection holder = raw()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute("LOCK TABLE decision IN SHARE UPDATE EXCLUSIVE MODE");
      }
      Committed committed =
          hot.commitDecisions(
                  batch(decision(body("A1", "d1", Instant.parse("2026-10-26T09:30:00Z")), 0)))
              .get(0);
      holder.rollback();
      assertThat(committed.status()).isEqualTo(CommitStatus.INSERTED);
    }
    assertThat(partitionsOf("A1")).containsExactly("decision_p20261026");
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
    // the dictionary rows are resolved before the unit and stay (an orphan row is harmless)
    assertThat(count("SELECT count(*) FROM regime")).isEqualTo(1);
  }

  /**
   * The dictionary rows are committed before the unit starts, so a unit that fails leaves them: the
   * regime, and the context in the state the batch supplied (SQL-1; D-S7 says "in the same unit", a
   * deliberate and recorded deviation). Everything else of the unit is rolled back.
   */
  @Test
  void aRolledBackBatchLeavesItsDictionaryRowsAndNothingElse() throws SQLException {
    commitOne(body("X1", "d1", DAY1), 0);
    DecisionContext context = LedgerFixtures.context("b-orphan");
    DecisionBody fresh = withContext(body("X2", "d1", DAY1), context);
    BatchCommit stale = batch(decision(fresh, 0, context), decision(body("X1", "d2", DAY1), 0));
    assertThatThrownBy(() -> store().commitDecisions(stale))
        .isInstanceOf(DecisionHeadConflictException.class);

    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM decision_head")).isEqualTo(1);
    assertThat(store().decisionChain(new Regime(MIFIR), event("X2"))).isEmpty();
    assertThat(count("SELECT count(*) FROM regime")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM decision_context WHERE body IS NULL")).isEqualTo(1);
    assertThat(store().decisionContext(context.id())).contains(context);
  }

  /**
   * The withdrawal guard (rule 8) runs after the head compare-and-set of its own decision. Unit c1
   * redelivers decision 1, which has no version yet, and creates the version: it holds the head row
   * shared (rule 3) and has an uncommitted version. Unit c2 commits decision 2 as DO_NOT_REPORT;
   * its compare-and-set waits behind the share lock, and only after c1 has committed does the guard
   * run and see the version, so c2 fails closed. A guard that ran before the compare-and-set would
   * have seen no version and withdrawn nothing.
   */
  @Test
  void aVersionCreatedByARedeliveryBlocksAConcurrentWithdrawal() throws Exception {
    Config patientConfig =
        Config.forSchema(schema()).withLockTimeout(Duration.ofSeconds(WAIT_SECONDS));
    PgLedgerStore patient = new PgLedgerStore(dataSource(), clock(), patientConfig);
    NewDecision first = LedgerFixtures.reportDecision(0, "G1", "ctx", "d1", Leg.MARKET);
    patient.commitDecisions(batch(first));
    ReportableCommit redelivery =
        new ReportableCommit(
            List.of(
                new ReportableDecision(
                    first,
                    List.of(
                        LedgerFixtures.newVersion(
                            "G1", Leg.MARKET, "EXEC1", "TRNG1", "NEWT", "h1")))),
            Optional.empty(),
            Optional.empty());
    NewDecision withdrawal =
        decision(
            LedgerFixtures.body(
                "G1", LedgerFixtures.MIFIR_REGIME, "ctx", "d2", Outcome.DO_NOT_REPORT, EVAL),
            1);
    Config config = Config.forSchema(schema());
    PgReports reports =
        new PgReports(dataSource(), clock(), config, new PgDecisions(dataSource(), config));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection c1 = dataSource().getConnection()) {
      Unit.begin(c1, config);
      List<ReportableCommitted> created = reports.commitReportableIn(c1, redelivery);
      assertThat(created.get(0).decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
      assertThat(created.get(0).versions().get(0).status()).isEqualTo(VersionStatus.CREATED);
      int pid = backendPid(c1);

      Future<List<Committed>> blocked =
          pool.submit(() -> patient.commitDecisions(batch(withdrawal)));
      awaitBackendWaitingFor(pid);
      assertThat(blocked.isDone()).as("the compare-and-set waits for the share lock").isFalse();
      c1.commit();
      assertThatThrownBy(() -> blocked.get(WAIT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(WithdrawalRequiredException.class);
    } finally {
      pool.shutdownNow();
    }
    // c2 rolled back: the chain is at 1, the head too, and c1's version is there
    assertThat(store().decisionChain(new Regime(MIFIR), event("G1"))).hasSize(1);
    assertThat(count("SELECT count(*) FROM decision_head WHERE decision_seq = 1")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM report_version")).isEqualTo(1);
  }

  private static final long WAIT_SECONDS = 30;

  /** Polls pg_stat_activity until a backend waits for a lock of the given one; fails after 30 s. */
  private void awaitBackendWaitingFor(int pid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      try (Connection connection = dataSource().getConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                      + " AND ? = ANY (pg_blocking_pids(pid))")) {
        statement.setInt(1, pid);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          if (rows.getLong(1) > 0) {
            return;
          }
        }
      }
      Thread.sleep(25);
    }
    fail("no backend waited for a lock of backend " + pid + " within " + WAIT_SECONDS + " s");
  }

  private static ReportableCommit reportable(NewDecision decision, NewVersion... versions) {
    return new ReportableCommit(
        List.of(new ReportableDecision(decision, List.of(versions))),
        Optional.empty(),
        Optional.empty());
  }

  private PgReports reportsOn(Config config) {
    return new PgReports(dataSource(), clock(), config, new PgDecisions(dataSource(), config));
  }

  /**
   * SQL-1. The first use of a regime, a context and an endpoint must not make another unit wait for
   * the unit that uses them first. Unit 1 runs on a connection of the test and stays uncommitted
   * after it has written a version for a new regime, a new context and a new endpoint. Unit 2 needs
   * the same three rows, with a lock timeout of one second: when the rows were created inside unit
   * 1 it would wait for unit 1's commit on the insert of the first of them, and fail with 55P03
   * after one second. It must instead find the rows, because they were committed before unit 1
   * started, and finish while unit 1 is still open.
   */
  @Test
  void aUnitThatUsesNewDictionaryRowsDoesNotMakeAnotherUnitWaitForItsCommit() throws Exception {
    // the daily piece exists (made by a decision of another regime), so no partition is involved
    store()
        .commitDecisions(
            batch(
                decision(
                    LedgerFixtures.body(
                        "S0", LedgerFixtures.EMIR_REGIME, "ctxS", "dS", Outcome.REPORT, EVAL),
                    0)));
    DecisionContext context = LedgerFixtures.context("b-new");
    NewDecision first =
        decision(
            withContext(
                LedgerFixtures.reportBody(
                    LedgerFixtures.MIFIR_REGIME, "N1", "ctxN", "d1", Leg.MARKET),
                context),
            0,
            context);
    NewDecision second =
        decision(
            withContext(
                LedgerFixtures.reportBody(
                    LedgerFixtures.MIFIR_REGIME, "N2", "ctxN", "d1", Leg.MARKET),
                context),
            0,
            context);
    ReportableCommit unit1 =
        reportable(
            first, LedgerFixtures.newVersion("N1", Leg.MARKET, "EXEC1", "TRNN1", "NEWT", "h1"));
    ReportableCommit unit2 =
        reportable(
            second, LedgerFixtures.newVersion("N2", Leg.MARKET, "EXEC1", "TRNN2", "NEWT", "h1"));
    Config config = Config.forSchema(schema());
    PgReports held = reportsOn(config);
    PgLedgerStore impatient =
        new PgLedgerStore(dataSource(), clock(), config.withLockTimeout(Duration.ofSeconds(1)));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection c1 = dataSource().getConnection()) {
      Unit.begin(c1, config);
      List<ReportableCommitted> created = held.commitReportableIn(c1, unit1);
      assertThat(created.get(0).versions().get(0).status()).isEqualTo(VersionStatus.CREATED);
      // unit 1 is not committed, its dictionary rows are: another session sees all three
      assertThat(count("SELECT count(*) FROM report_version")).isZero();
      assertThat(count("SELECT count(*) FROM regime WHERE regime = '" + MIFIR + "'")).isEqualTo(1);
      assertThat(count("SELECT count(*) FROM decision_context WHERE body IS NOT NULL"))
          .isEqualTo(1);
      assertThat(count("SELECT count(*) FROM endpoint")).isEqualTo(1);

      Future<List<ReportableCommitted>> other =
          pool.submit(() -> impatient.commitReportable(unit2));
      List<ReportableCommitted> done = other.get(20, TimeUnit.SECONDS);
      assertThat(done.get(0).versions().get(0).status()).isEqualTo(VersionStatus.CREATED);
      assertThat(count("SELECT count(*) FROM report_version"))
          .as("unit 1 is still open")
          .isEqualTo(1);
      c1.commit();
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("SELECT count(*) FROM report_version")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM regime WHERE regime = '" + MIFIR + "'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM endpoint")).isEqualTo(1);
  }

  /**
   * SQL-3 and ADAPTER-3. Two workers get the same redelivery of a decision that has no version yet.
   * Worker 1 creates the version and is held before its commit; worker 2 misses the version (it is
   * not committed), passes the shared lock on the head and waits for the subject. After worker 1
   * has committed, worker 2 reads the version under the subject lock and answers {@code EXISTING}
   * with the delivery of worker 1, also when its request carries other content; it neither
   * suppresses nor adds a second version of the decision.
   */
  @Test
  void aSecondRedeliveryWaitsForTheSubjectLockAndThenAnswersExisting() throws Exception {
    assertSecondRedeliveryAnswersExisting("RD1", "TRNRD1", "h1");
  }

  @Test
  void aSecondRedeliveryWithOtherContentAlsoAnswersExisting() throws Exception {
    assertSecondRedeliveryAnswersExisting("RD2", "TRNRD2", "h2");
  }

  private void assertSecondRedeliveryAnswersExisting(String event, String trn, String secondHash)
      throws Exception {
    Config config = Config.forSchema(schema());
    PgLedgerStore patient =
        new PgLedgerStore(
            dataSource(), clock(), config.withLockTimeout(Duration.ofSeconds(WAIT_SECONDS)));
    NewDecision decision = LedgerFixtures.reportDecision(0, event, "ctx", "d1", Leg.MARKET);
    patient.commitDecisions(batch(decision));
    ReportableCommit one =
        reportable(
            decision, LedgerFixtures.newVersion(event, Leg.MARKET, "EXEC1", trn, "NEWT", "h1"));
    ReportableCommit other =
        reportable(
            decision,
            LedgerFixtures.newVersion(event, Leg.MARKET, "EXEC1", trn, "NEWT", secondHash));
    PgReports reports = reportsOn(config);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    VersionResult createdVersion;
    VersionResult answer;
    try (Connection c1 = dataSource().getConnection()) {
      Unit.begin(c1, config);
      List<ReportableCommitted> created = reports.commitReportableIn(c1, one);
      assertThat(created.get(0).decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
      createdVersion = created.get(0).versions().get(0);
      assertThat(createdVersion.status()).isEqualTo(VersionStatus.CREATED);
      int pid = backendPid(c1);

      Future<List<ReportableCommitted>> blocked =
          pool.submit(() -> patient.commitReportable(other));
      awaitBackendWaitingFor(pid);
      assertThat(blocked.isDone()).as("worker 2 waits for the subject of worker 1").isFalse();
      c1.commit();
      List<ReportableCommitted> finished = blocked.get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertThat(finished.get(0).decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
      answer = finished.get(0).versions().get(0);
    } finally {
      pool.shutdownNow();
    }
    assertThat(answer.status()).isEqualTo(VersionStatus.EXISTING);
    assertThat(answer.subject()).isEqualTo(createdVersion.subject());
    assertThat(answer.versionSeq()).isEqualTo(createdVersion.versionSeq()).isEqualTo(1);
    assertThat(answer.trn()).isEqualTo(trn);
    assertThat(answer.delivery()).isPresent().isEqualTo(createdVersion.delivery());
    assertThat(store().versions(createdVersion.subject())).hasSize(1);
    assertThat(
            count(
                "SELECT count(*) FROM delivery WHERE subject_id = "
                    + createdVersion.subject().value()))
        .isEqualTo(1);
  }

  /**
   * ADAPTER-2. The documented mixed deadlock: a unit that supersedes chain A and redelivers chain B
   * (with a version, which takes the head of B shared) against a session that holds the head of B
   * and then asks for the head of A. The unit waits first, so its deadlock check fires first and it
   * is the victim (the server's {@code deadlock_timeout} of one second is below the store's lock
   * timeout of five). It must reach the caller as {@link DecisionHeadConflictException} with the
   * deadlock as cause, after the rollback, and leave chain A at its old head; a plain {@code
   * LedgerException} would not be retried by type.
   */
  @Test
  void aDeadlockOnTheSharedHeadReadReachesTheCallerAsAHeadConflict() throws Exception {
    NewDecision chainA = LedgerFixtures.reportDecision(0, "MA", "ctx", "d1", Leg.MARKET);
    NewDecision chainB = LedgerFixtures.reportDecision(0, "MB", "ctx", "d1", Leg.MARKET);
    store().commitDecisions(batch(chainA, chainB));
    NewDecision supersedesA = LedgerFixtures.reportDecision(1, "MA", "ctx", "d2", Leg.MARKET);
    ReportableCommit unit =
        new ReportableCommit(
            List.of(
                new ReportableDecision(supersedesA, List.of()),
                new ReportableDecision(
                    chainB,
                    List.of(
                        LedgerFixtures.newVersion(
                            "MB", Leg.MARKET, "EXEC1", "TRNMB", "NEWT", "h1")))),
            Optional.empty(),
            Optional.empty());
    String headOf =
        " FROM decision_head WHERE regime_id = (SELECT regime_id FROM regime WHERE regime = ?)"
            + " AND event_identity = ?";

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection session = raw()) {
      session.setAutoCommit(false);
      int pid = backendPid(session);
      try (PreparedStatement hold =
          session.prepareStatement(
              "UPDATE decision_head SET decision_seq = decision_seq"
                  + " WHERE regime_id = (SELECT regime_id FROM regime WHERE regime = ?)"
                  + " AND event_identity = ?")) {
        hold.setString(1, MIFIR);
        hold.setString(2, event("MB").value());
        assertThat(hold.executeUpdate()).isEqualTo(1);
      }
      Future<List<ReportableCommitted>> victim = pool.submit(() -> store().commitReportable(unit));
      awaitBackendWaitingFor(pid);
      // the session now asks for the head that the unit has advanced: the cycle is closed, and the
      // unit, which has waited longer, is the one the server aborts; this statement then proceeds
      try (PreparedStatement ask = session.prepareStatement("SELECT 1" + headOf + " FOR SHARE")) {
        ask.setString(1, MIFIR);
        ask.setString(2, event("MA").value());
        try (ResultSet rows = ask.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
      session.rollback();
      Throwable failure = catchThrowable(() -> victim.get(WAIT_SECONDS, TimeUnit.SECONDS));
      assertThat(failure).isInstanceOf(ExecutionException.class);
      Throwable conflict = failure.getCause();
      assertThat(conflict).isInstanceOf(DecisionHeadConflictException.class);
      assertThat(conflict.getCause()).isInstanceOf(SQLException.class);
      assertThat(PgErrors.classify((SQLException) conflict.getCause()))
          .isEqualTo(PgErrors.Violation.DEADLOCK);
    } finally {
      pool.shutdownNow();
    }
    assertThat(store().decisionChain(new Regime(MIFIR), event("MA"))).hasSize(1);
    assertThat(count("SELECT count(*) FROM report_version")).isZero();
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
