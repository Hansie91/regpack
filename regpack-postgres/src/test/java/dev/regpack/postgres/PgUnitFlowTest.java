package dev.regpack.postgres;

import static dev.regpack.model.Leg.MARKET;
import static dev.regpack.testkit.contract.LedgerFixtures.context;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static dev.regpack.testkit.contract.LedgerFixtures.withContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * The order of the statements and transactions of a unit, and what a failed statement becomes, with
 * no server: a fake server (see {@link SqlSpy}) answers the statements of one shape of unit, so
 * that the tests can look at the recorded events. The statements themselves first run on a real
 * server at the CI checkpoints.
 *
 * <p>Covered: the dictionary rows (regime, context, endpoint) are committed in short transactions
 * before the unit opens and are then cached (SQL-1); a redelivery reads its version again under the
 * subject lock (SQL-3, ADAPTER-3); a deadlock or serialization failure on a head statement becomes
 * {@link DecisionHeadConflictException} after the rollback, and the same codes on any other
 * statement stay a plain {@link LedgerException} (ADAPTER-2).
 */
class PgUnitFlowTest {
  private static final Config CONFIG = Config.forSchema("t42_7").withPartitionsOnDemand(false);
  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

  private static final NewDecision FIRST = reportDecision(0, "E1", "ctx", "d1", MARKET);
  private static final NewDecision SECOND = reportDecision(1, "E1", "ctx", "d2", MARKET);
  private static final String EVENT = FIRST.body().eventIdentity().value();
  private static final NewVersion VERSION = newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1");

  private final SqlSpy spy = new SqlSpy();
  private final PgDecisions decisions = new PgDecisions(spy.over(null), CONFIG);
  private final PgReports reports =
      new PgReports(spy.over(null), new ManualClock(NOW), CONFIG, decisions);

  // ------------------------------------------------------------------------------------------
  // the fake server

  private static List<Object[]> none() {
    return List.of();
  }

  private static List<Object[]> row(Object... values) {
    return Collections.<Object[]>singletonList(values);
  }

  private static boolean done(List<String> events, String sql) {
    return events.contains("execute: " + sql);
  }

  /**
   * A server for one decision of the event {@code E1}. A head row exists when {@code headSeq} is
   * above 0; the decision row is new when {@code inserted} (else a redelivery whose stored record
   * is {@code stored} at sequence {@code headSeq}). The dictionary rows appear once their insert
   * has been executed; the version, once the subject row has been locked, exists.
   */
  static SqlSpy.Answers server(long headSeq, boolean inserted, DecisionBody stored) {
    return (sql, events) -> {
      if (sql.equals(PgDictionaries.REGIME_SELECT_SQL)) {
        return done(events, PgDictionaries.REGIME_INSERT_SQL) ? row((short) 1) : none();
      }
      if (sql.equals(PgDictionaries.CONTEXT_SELECT_SQL)) {
        return done(events, PgDictionaries.CONTEXT_INSERT_SQL) ? row(7, true) : none();
      }
      if (sql.equals(PgLeases.ENDPOINT_SELECT_SQL)) {
        return done(events, PgLeases.ENDPOINT_INSERT_SQL) ? row(3) : none();
      }
      if (sql.equals(PgDecisions.HEADS_SQL)) {
        return headSeq > 0 ? row((short) 1, EVENT, headSeq, 5L, LocalDate.of(2026, 10, 9)) : none();
      }
      if (sql.equals(PgDecisions.INSERT_SQL)) {
        return inserted ? row((short) 1, EVENT, 6L) : none();
      }
      if (sql.equals(PgDecisions.STORED_SQL)) {
        return row(5L, headSeq, null, PgDecisions.BODY_ENCODING, DecisionBodyCodec.encode(stored));
      }
      if (sql.equals(PgReports.HEAD_SHARE_SQL)) {
        return row(headSeq);
      }
      if (sql.equals(PgReports.SUBJECT_LOCK_SQL)) {
        return row(9L, 0L);
      }
      if (sql.equals(PgReports.EXISTING_SQL)) {
        return done(events, PgReports.SUBJECT_LOCK_SQL) ? row(9L, 1L, "TRN1", 77L) : none();
      }
      if (sql.equals(Unit.BARRIER_SQL)) {
        return row(1);
      }
      return none();
    };
  }

  /** {@link #server} for a first decision of the chain: the head row is inserted. */
  static SqlSpy.Answers serverOfAFirstDecision() {
    SqlSpy.Answers base = server(0, true, FIRST.body());
    return (sql, events) ->
        sql.equals(PgDecisions.HEAD_INSERT_SQL) ? row((short) 1, EVENT) : base.rows(sql, events);
  }

  private static BatchCommit batch(NewDecision... list) {
    return new BatchCommit(List.of(list), Optional.empty());
  }

  private static ReportableCommit reportable(NewDecision decision, NewVersion... versions) {
    return new ReportableCommit(
        List.of(new ReportableDecision(decision, List.of(versions))),
        Optional.empty(),
        Optional.empty());
  }

  private static int at(List<String> events, String sql) {
    int index = events.indexOf("execute: " + sql);
    assertThat(index).as("statement executed: %s", sql).isNotNegative();
    return index;
  }

  /** The index of the first {@code event} at or after {@code from}. */
  private static int next(List<String> events, String event, int from) {
    int index = events.subList(from, events.size()).indexOf(event);
    assertThat(index).as("%s after event %d", event, from).isNotNegative();
    return from + index;
  }

  private static long count(List<String> events, String sql) {
    return events.stream().filter(("execute: " + sql)::equals).count();
  }

  // ------------------------------------------------------------------------------------------
  // SQL-1: the dictionary rows before the unit

  @Test
  void theRegimeAndContextRowsAreCommittedInShortTransactionsBeforeTheUnitOpens() {
    spy.answering(server(1, false, FIRST.body()));

    List<Committed> committed = decisions.commitDecisions(batch(FIRST));

    assertThat(committed.get(0).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    List<String> events = spy.events();
    int regimeInsert = at(events, PgDictionaries.REGIME_INSERT_SQL);
    int contextInsert = at(events, PgDictionaries.CONTEXT_INSERT_SQL);
    int heads = at(events, PgDecisions.HEADS_SQL);
    // each kind of row is committed in a transaction of its own ...
    assertThat(next(events, "commit", regimeInsert)).isLessThan(contextInsert);
    // ... and the unit opens only after the last of them
    int contextCommit = next(events, "commit", contextInsert);
    assertThat(events.subList(contextCommit, heads)).contains("open");
    // the unit itself reads no dictionary row
    assertThat(events.subList(heads, events.size()))
        .doesNotContain(
            "execute: " + PgDictionaries.REGIME_SELECT_SQL,
            "execute: " + PgDictionaries.CONTEXT_SELECT_SQL);
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aSecondBatchTakesTheIdsFromTheCacheAndOpensNoDictionaryTransaction() {
    spy.answering(server(1, false, FIRST.body()));
    decisions.commitDecisions(batch(FIRST));
    spy.mark("second batch");

    decisions.commitDecisions(batch(FIRST));

    List<String> events = spy.events();
    List<String> second = events.subList(events.indexOf("second batch") + 1, events.size());
    assertThat(second)
        .doesNotContain(
            "execute: " + PgDictionaries.REGIME_SELECT_SQL,
            "execute: " + PgDictionaries.REGIME_INSERT_SQL,
            "execute: " + PgDictionaries.CONTEXT_SELECT_SQL,
            "execute: " + PgDictionaries.CONTEXT_INSERT_SQL);
    // one transaction only: the unit
    assertThat(second.stream().filter("open"::equals).count()).isEqualTo(1);
  }

  @Test
  void aCachedStubIsResolvedAgainOnlyOnceAContextIsSupplied() {
    DecisionContext given = context("b1");
    DecisionBody stub = withContext(FIRST.body(), given);
    spy.answering(server(1, false, stub));

    decisions.commitDecisions(batch(decision(stub, 0))); // no context: a stub row
    assertThat(count(spy.events(), PgDictionaries.CONTEXT_FILL_SQL)).isZero();
    decisions.commitDecisions(batch(decision(stub, 0, given))); // supplied: the fill
    assertThat(count(spy.events(), PgDictionaries.CONTEXT_FILL_SQL)).isEqualTo(1);
    spy.mark("third batch");
    decisions.commitDecisions(batch(decision(stub, 0, given))); // filled: from the cache

    List<String> events = spy.events();
    List<String> third = events.subList(events.indexOf("third batch") + 1, events.size());
    assertThat(third)
        .doesNotContain(
            "execute: " + PgDictionaries.CONTEXT_SELECT_SQL,
            "execute: " + PgDictionaries.CONTEXT_FILL_SQL);
    assertThat(count(events, PgDictionaries.CONTEXT_FILL_SQL)).isEqualTo(1);
  }

  @Test
  void theEndpointRowIsCommittedBeforeTheReportUnitOpensAndTheUnitDoesNotResolveIt() {
    spy.answering(server(1, false, FIRST.body()));

    List<ReportableCommitted> committed = reports.commitReportable(reportable(FIRST, VERSION));

    assertThat(committed.get(0).versions().get(0).status()).isEqualTo(VersionStatus.EXISTING);
    List<String> events = spy.events();
    int endpointInsert = at(events, PgLeases.ENDPOINT_INSERT_SQL);
    int heads = at(events, PgDecisions.HEADS_SQL);
    int endpointCommit = next(events, "commit", endpointInsert);
    assertThat(endpointCommit).isLessThan(heads);
    assertThat(events.subList(endpointCommit, heads)).contains("open");
    assertThat(events.subList(heads, events.size()))
        .doesNotContain(
            "execute: " + PgLeases.ENDPOINT_SELECT_SQL, "execute: " + PgLeases.ENDPOINT_INSERT_SQL);
  }

  // ------------------------------------------------------------------------------------------
  // SQL-3 and ADAPTER-3: the redelivery reads its version again under the subject lock

  @Test
  void aRedeliveryThatMissesItsVersionReadsItAgainUnderTheSubjectLockAndAnswersExisting() {
    spy.answering(server(1, false, FIRST.body()));

    ReportableCommitted committed = reports.commitReportable(reportable(FIRST, VERSION)).get(0);

    assertThat(committed.decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(committed.versions().get(0).status()).isEqualTo(VersionStatus.EXISTING);
    assertThat(committed.versions().get(0).versionSeq()).isEqualTo(1);
    assertThat(committed.versions().get(0).delivery()).isPresent();
    List<String> events = spy.events();
    // the cheap read, the head, the subject lock, the read under the lock
    assertThat(events)
        .containsSubsequence(
            "execute: " + PgReports.EXISTING_SQL,
            "execute: " + PgReports.HEAD_SHARE_SQL,
            "execute: " + PgReports.SUBJECT_INSERT_SQL,
            "execute: " + PgReports.SUBJECT_LOCK_SQL,
            "execute: " + PgReports.EXISTING_SQL);
    assertThat(count(events, PgReports.EXISTING_SQL)).isEqualTo(2);
    // and nothing after it: no TRN, no version, no delivery
    assertThat(events)
        .doesNotContain(
            "execute: " + PgReports.TRN_READ_SQL,
            "execute: " + PgReports.TRN_INSERT_SQL,
            "execute: " + PgReports.VERSION_INSERT_SQL,
            "execute: " + PgReports.SUBJECT_BUMP_SQL,
            "execute: " + PgDeliveries.CREATE_SQL);
  }

  // ------------------------------------------------------------------------------------------
  // I1, acknowledgement limb: a replay that writes nothing must still wait for the standby

  /** The statements between the barrier and the commit that follows it: there must be none. */
  private static void assertBarrierIsTheLastStatementBeforeTheCommit(List<String> events) {
    int barrier = at(events, Unit.BARRIER_SQL);
    int commit = next(events, "commit", barrier);
    assertThat(events.subList(barrier + 1, commit))
        .as("the statements between the barrier and the commit")
        .noneMatch(event -> event.startsWith("execute: "));
    assertThat(count(events, Unit.BARRIER_SQL)).as("barriers").isEqualTo(1);
  }

  @Test
  void aRedeliveredDecisionBatchEndsWithTheDurabilityBarrierSoThatItsCommitWaitsForTheStandby() {
    spy.answering(server(1, false, FIRST.body()));

    List<Committed> committed = decisions.commitDecisions(batch(FIRST));

    assertThat(committed.get(0).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    List<String> events = spy.events();
    assertThat(at(events, Unit.BARRIER_SQL)).isGreaterThan(at(events, PgDecisions.STORED_SQL));
    assertBarrierIsTheLastStatementBeforeTheCommit(events);
  }

  @Test
  void aBatchThatInsertsADecisionAddsNoBarrier() {
    spy.answering(serverOfAFirstDecision());

    List<Committed> committed = decisions.commitDecisions(batch(FIRST));

    assertThat(committed.get(0).status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(spy.events()).contains("commit").doesNotContain("execute: " + Unit.BARRIER_SQL);
  }

  @Test
  void aRedeliveredBatchThatCompletesAnOutboxItemAddsNoBarrierBecauseTheCompletionWrites() {
    spy.updatingRows(1).answering(server(1, false, FIRST.body()));
    BatchCommit completing = new BatchCommit(List.of(FIRST), Optional.of(new OutboxId(4)));

    List<Committed> committed = decisions.commitDecisions(completing);

    assertThat(committed.get(0).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(spy.events())
        .contains("execute: " + PgDecisions.OUTBOX_DONE_SQL, "commit")
        .doesNotContain("execute: " + Unit.BARRIER_SQL);
  }

  @Test
  void aReportableRedeliveryThatOnlyFindsItsVersionsEndsWithTheBarrier() {
    spy.answering(server(1, false, FIRST.body()));

    ReportableCommitted committed = reports.commitReportable(reportable(FIRST, VERSION)).get(0);

    assertThat(committed.versions().get(0).status()).isEqualTo(VersionStatus.EXISTING);
    assertBarrierIsTheLastStatementBeforeTheCommit(spy.events());
  }

  @Test
  void aReportableCommitThatInsertsADecisionAddsNoBarrier() {
    spy.answering(serverOfAFirstDecision());

    ReportableCommitted committed = reports.commitReportable(reportable(FIRST)).get(0);

    assertThat(committed.decision().status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(spy.events()).contains("commit").doesNotContain("execute: " + Unit.BARRIER_SQL);
  }

  // ------------------------------------------------------------------------------------------
  // ADAPTER-2: what a deadlock or a serialization failure becomes

  /** A unit on a fake server in which every statement that contains {@code failing} fails. */
  private record Rig(SqlSpy spy, PgDecisions decisions, PgReports reports) {
    static Rig failing(SqlSpy.Answers answers, String failing, SQLException failure) {
      SqlSpy spy = new SqlSpy().answering(answers).failStatementWith(failing, failure);
      PgDecisions decisions = new PgDecisions(spy.over(null), CONFIG);
      return new Rig(
          spy, decisions, new PgReports(spy.over(null), new ManualClock(NOW), CONFIG, decisions));
    }
  }

  static PSQLException serverError(String sqlState) {
    return new PSQLException(new ServerErrorMessage("SERROR\0C" + sqlState + "\0Mfailure\0"));
  }

  /** What the driver throws from executeBatch: the server error is the cause of the batch error. */
  private static BatchUpdateException batchError(String sqlState) {
    return new BatchUpdateException(
        "Batch entry 0 was aborted", sqlState, 0, new int[0], serverError(sqlState));
  }

  private static void assertConflictAfterTheRollback(
      Rig rig, Throwable thrown, SQLException cause) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class,
            e -> {
              assertThat(e.eventIdentity().value()).isEqualTo(EVENT);
              assertThat(e.getCause()).isSameAs(cause);
            });
    List<String> events = rig.spy().events();
    // the unit is rolled back first, and the head is read again in a unit of its own
    assertThat(events.indexOf("rollback")).isNotNegative();
    assertThat(events.lastIndexOf("open")).isGreaterThan(events.indexOf("rollback"));
    assertThat(rig.spy().openConnections()).isZero();
  }

  private static void assertPlainFailure(Rig rig, Throwable thrown, String sqlState) {
    assertThat(thrown)
        .isInstanceOf(LedgerException.class)
        .isNotInstanceOf(DecisionHeadConflictException.class)
        .hasMessage("ledger statement failed: sqlstate=" + sqlState + " constraint=none");
    assertThat(rig.spy().events()).contains("rollback");
    assertThat(rig.spy().openConnections()).isZero();
  }

  private static final List<String> LOST_RACE_STATES = List.of("40P01", "40001");

  @Test
  void aDeadlockOrSerializationFailureOnTheHeadUpdateIsAHeadConflict() {
    for (String state : LOST_RACE_STATES) {
      BatchUpdateException failure = batchError(state);
      Rig rig = Rig.failing(server(1, true, SECOND.body()), PgDecisions.HEAD_UPDATE_SQL, failure);

      Throwable thrown = catchThrowable(() -> rig.decisions().commitDecisions(batch(SECOND)));

      assertConflictAfterTheRollback(rig, thrown, failure);
      assertThat(rig.spy().events()).contains("execute: " + PgDecisions.HEAD_UPDATE_SQL);
    }
  }

  @Test
  void aDeadlockOrSerializationFailureOnTheHeadInsertIsAHeadConflict() {
    for (String state : LOST_RACE_STATES) {
      PSQLException failure = serverError(state);
      Rig rig = Rig.failing(server(0, true, FIRST.body()), PgDecisions.HEAD_INSERT_SQL, failure);

      Throwable thrown = catchThrowable(() -> rig.decisions().commitDecisions(batch(FIRST)));

      assertConflictAfterTheRollback(rig, thrown, failure);
    }
  }

  @Test
  void aDeadlockOrSerializationFailureOnTheSharedHeadReadOfARedeliveryIsAHeadConflict() {
    for (String state : LOST_RACE_STATES) {
      PSQLException failure = serverError(state);
      Rig rig = Rig.failing(server(1, false, FIRST.body()), PgReports.HEAD_SHARE_SQL, failure);

      Throwable thrown =
          catchThrowable(() -> rig.reports().commitReportable(reportable(FIRST, VERSION)));

      assertConflictAfterTheRollback(rig, thrown, failure);
    }
  }

  @Test
  void theSameFailuresOnTheDecisionInsertStayAPlainLedgerException() {
    for (String state : LOST_RACE_STATES) {
      Rig rig =
          Rig.failing(server(1, true, SECOND.body()), PgDecisions.INSERT_SQL, serverError(state));

      Throwable thrown = catchThrowable(() -> rig.decisions().commitDecisions(batch(SECOND)));

      assertPlainFailure(rig, thrown, state);
    }
  }

  @Test
  void theSameFailuresOnTheSubjectLockStayAPlainLedgerException() {
    for (String state : LOST_RACE_STATES) {
      Rig rig =
          Rig.failing(
              server(1, false, FIRST.body()), PgReports.SUBJECT_INSERT_SQL, serverError(state));

      Throwable thrown =
          catchThrowable(() -> rig.reports().commitReportable(reportable(FIRST, VERSION)));

      assertPlainFailure(rig, thrown, state);
    }
  }
}
