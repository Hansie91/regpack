package dev.regpack.postgres;

import static dev.regpack.model.Leg.MARKET;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * FP5 (ADAPTER-1): the creation of a daily partition of {@code decision} with no server. A fake
 * server (see {@link SqlSpy}) answers the statements and fails chosen ones, so that the tests can
 * look at the recorded events: a lock timeout on the creation is retried a bounded number of times
 * with a pause between the attempts, never with a connection open; a missing partition after the
 * partition cache was filled recreates the partition and runs the rolled-back unit once more; and
 * {@link PgDecisions#ensurePartitionsAhead} is the entry that a scheduler calls ahead of time. The
 * statements themselves first run on a real server at the CI checkpoints.
 */
class PgPartitionFlowTest {
  private static final Config CONFIG = Config.forSchema("t42_7");
  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
  private static final LocalDate DAY = LocalDate.of(2026, 10, 9);

  /** The default as-at of a fixture decision is 2026-10-09T09:30:00Z: the day above. */
  private static final NewDecision FIRST = reportDecision(0, "E1", "ctx", "d1", MARKET);

  private static final NewVersion VERSION = newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1");

  private static final String CREATE = "execute: CREATE TABLE ";
  private static final String ATTACH = "execute: ALTER TABLE t42_7.decision ATTACH PARTITION ";
  private static final String INSERT = "execute: " + PgDecisions.INSERT_SQL;

  private static final Duration FIRST_PAUSE = Duration.ofMillis(100);
  private static final Duration SECOND_PAUSE = Duration.ofMillis(300);
  private static final Duration THIRD_PAUSE = Duration.ofMillis(900);

  /** A pause that waits for nothing and records what it was asked, and what was open then. */
  private static final class Pauses implements PgDecisions.Pause {
    private final SqlSpy spy;
    final List<Duration> asked = new ArrayList<>();
    final List<Integer> openThen = new ArrayList<>();
    boolean interrupted;

    Pauses(SqlSpy spy) {
      this.spy = spy;
    }

    @Override
    public boolean pause(Duration duration) {
      asked.add(duration);
      openThen.add(spy.openConnections());
      spy.mark("pause");
      return !interrupted;
    }
  }

  private final SqlSpy spy = new SqlSpy();
  private final Pauses pauses = new Pauses(spy);

  private PgDecisions decisions(Config config) {
    return new PgDecisions(spy.over(null), config, pauses);
  }

  private static BatchCommit batch(NewDecision... list) {
    return new BatchCommit(List.of(list), Optional.empty());
  }

  private static long count(List<String> events, String prefix) {
    return events.stream().filter(event -> event.startsWith(prefix)).count();
  }

  private static List<String> creations(List<String> events) {
    return events.stream().filter(event -> event.startsWith(CREATE)).toList();
  }

  private static boolean mentions(String event, String partition) {
    return event.contains("t42_7." + partition + " ");
  }

  // ------------------------------------------------------------------------------------------
  // the bounded retry of a lock timeout on the creation

  @Test
  void aLockTimeoutOnTheCreationIsRetriedAfterTheRollbackAndWithoutAnOpenConnection() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()))
        .failStatementWith("CREATE TABLE", PgUnitFlowTest.serverError("55P03"), 2);
    PgDecisions decisions = decisions(CONFIG);

    List<Committed> committed = decisions.commitDecisions(batch(FIRST));

    assertThat(committed.get(0).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    List<String> events = spy.events();
    // two failed attempts and the third one that worked
    assertThat(creations(events)).hasSize(3);
    assertThat(count(events, ATTACH)).isEqualTo(1);
    assertThat(pauses.asked).containsExactly(FIRST_PAUSE, SECOND_PAUSE);
    // each pause comes after the rollback of the failed attempt, with no connection held
    assertThat(pauses.openThen).containsExactly(0, 0);
    int firstPause = events.indexOf("pause");
    assertThat(events.subList(0, firstPause)).contains("rollback");
    assertThat(spy.openConnections()).isZero();

    // the day is known now: the next batch creates and pauses nothing
    spy.mark("second batch");
    decisions.commitDecisions(batch(FIRST));
    List<String> after = spy.events();
    assertThat(after.subList(after.indexOf("second batch"), after.size()))
        .noneMatch(event -> event.startsWith(CREATE));
    assertThat(pauses.asked).hasSize(2);
  }

  @Test
  void aLockTimeoutThatLastsFailsAfterAFixedNumberOfAttemptsAndWritesNothing() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()))
        .failStatementWith("CREATE TABLE", PgUnitFlowTest.serverError("55P03"));
    PgDecisions decisions = decisions(CONFIG);

    Throwable thrown = catchThrowable(() -> decisions.commitDecisions(batch(FIRST)));

    assertThat(thrown)
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=55P03 constraint=none");
    List<String> events = spy.events();
    assertThat(creations(events)).hasSize(PgDecisions.PARTITION_ATTEMPTS).hasSize(4);
    assertThat(pauses.asked).containsExactly(FIRST_PAUSE, SECOND_PAUSE, THIRD_PAUSE);
    assertThat(pauses.openThen).containsOnly(0);
    assertThat(count(events, ATTACH)).isZero();
    // the call failed before any dictionary row or unit: nothing of the batch was written
    assertThat(events)
        .doesNotContain(
            "execute: " + PgDictionaries.REGIME_INSERT_SQL,
            "execute: " + PgDictionaries.CONTEXT_INSERT_SQL,
            "execute: " + PgDecisions.HEADS_SQL,
            INSERT);
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void theWholeWaitOfTheRetriesIsBoundedByTheAttemptsTheLockTimeoutAndThePauses() {
    Duration pauseTotal = Duration.ZERO;
    for (Duration pause : PgDecisions.PARTITION_BACKOFF) {
      pauseTotal = pauseTotal.plus(pause);
    }
    assertThat(PgDecisions.PARTITION_BACKOFF)
        .hasSize(PgDecisions.PARTITION_ATTEMPTS - 1)
        .containsExactly(FIRST_PAUSE, SECOND_PAUSE, THIRD_PAUSE);
    Duration worst =
        CONFIG.lockTimeout().multipliedBy(PgDecisions.PARTITION_ATTEMPTS).plus(pauseTotal);
    // the default lock timeout of 5 s: 4 attempts and 1.3 s of pauses, below 25 s
    assertThat(worst).isEqualTo(Duration.ofMillis(21_300)).isLessThan(Duration.ofSeconds(25));
  }

  @Test
  void aFailureThatIsNotALockTimeoutIsNotRetried() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()))
        .failStatementWith("CREATE TABLE", PgUnitFlowTest.serverError("42P07"));
    PgDecisions decisions = decisions(CONFIG);

    assertThatThrownBy(() -> decisions.commitDecisions(batch(FIRST)))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=42P07 constraint=none");

    assertThat(creations(spy.events())).hasSize(1);
    assertThat(pauses.asked).isEmpty();
  }

  @Test
  void aPauseThatWasInterruptedEndsTheRetriesWithTheLockTimeout() {
    pauses.interrupted = true;
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()))
        .failStatementWith("CREATE TABLE", PgUnitFlowTest.serverError("55P03"));
    PgDecisions decisions = decisions(CONFIG);

    assertThatThrownBy(() -> decisions.commitDecisions(batch(FIRST)))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=55P03 constraint=none");

    assertThat(creations(spy.events())).hasSize(1);
    assertThat(pauses.asked).hasSize(1);
  }

  // ------------------------------------------------------------------------------------------
  // a missing partition after the cache was filled: the rolled-back unit runs once more

  @Test
  void aMissingPartitionRecreatesTheDayAndRunsTheRolledBackUnitOnceMore() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()));
    PgDecisions decisions = decisions(CONFIG);
    decisions.commitDecisions(batch(FIRST)); // the day is created and cached
    spy.failStatementWith(PgDecisions.INSERT_SQL, PgUnitFlowTest.serverError("23514"), 1);
    spy.mark("second batch");

    List<Committed> committed = decisions.commitDecisions(batch(FIRST));

    assertThat(committed.get(0).status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    List<String> events = spy.events();
    List<String> second = events.subList(events.indexOf("second batch"), events.size());
    assertThat(count(second, INSERT)).isEqualTo(2);
    assertThat(creations(second)).hasSize(1);
    // failed unit, rollback, the creation in its own transaction, then the unit again
    int firstInsert = second.indexOf(INSERT);
    int rollback = second.indexOf("rollback");
    int creation = second.indexOf(creations(second).get(0));
    int secondInsert = second.lastIndexOf(INSERT);
    assertThat(firstInsert).isLessThan(rollback);
    assertThat(rollback).isLessThan(creation);
    assertThat(creation).isLessThan(secondInsert);
    assertThat(pauses.asked).isEmpty();
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aMissingPartitionIsRetriedOnlyOnce() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()));
    PgDecisions decisions = decisions(CONFIG);
    decisions.commitDecisions(batch(FIRST));
    spy.failStatementWith(PgDecisions.INSERT_SQL, PgUnitFlowTest.serverError("23514"));
    spy.mark("second batch");

    assertThatThrownBy(() -> decisions.commitDecisions(batch(FIRST)))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=23514 constraint=none");

    List<String> events = spy.events();
    List<String> second = events.subList(events.indexOf("second batch"), events.size());
    assertThat(count(second, INSERT)).isEqualTo(2);
    assertThat(creations(second)).hasSize(1);
    assertThat(spy.openConnections()).isZero();

    // the cache is forgotten again: the next call checks the day once more
    spy.mark("third batch");
    spy.failStatementWith(PgDecisions.INSERT_SQL, PgUnitFlowTest.serverError("40001"), 0);
    decisions.commitDecisions(batch(FIRST));
    List<String> all = spy.events();
    assertThat(
            all.subList(all.indexOf("third batch"), all.size()).stream()
                .filter(event -> event.equals("execute: " + PgDecisions.PARTITION_LISTED_SQL))
                .count())
        .isEqualTo(1);
  }

  @Test
  void aMissingPartitionIsNotRetriedWhenTheAdapterDoesNotCreatePartitions() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()))
        .failStatementWith(PgDecisions.INSERT_SQL, PgUnitFlowTest.serverError("23514"));
    PgDecisions decisions = decisions(CONFIG.withPartitionsOnDemand(false));

    assertThatThrownBy(() -> decisions.commitDecisions(batch(FIRST)))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=23514 constraint=none");

    assertThat(count(spy.events(), INSERT)).isEqualTo(1);
    assertThat(creations(spy.events())).isEmpty();
  }

  @Test
  void anotherFailureOfTheUnitIsNotRetried() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()))
        .failStatementWith(PgDecisions.INSERT_SQL, PgUnitFlowTest.serverError("55P03"));
    PgDecisions decisions = decisions(CONFIG);

    assertThatThrownBy(() -> decisions.commitDecisions(batch(FIRST)))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=55P03 constraint=none");

    assertThat(count(spy.events(), INSERT)).isEqualTo(1);
    assertThat(pauses.asked).isEmpty();
  }

  @Test
  void theReportUnitRetriesAMissingPartitionToo() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()));
    PgDecisions decisions = decisions(CONFIG);
    PgReports reports = new PgReports(spy.over(null), new ManualClock(NOW), CONFIG, decisions);
    ReportableCommit commit =
        new ReportableCommit(
            List.of(new ReportableDecision(FIRST, List.of(VERSION))),
            Optional.empty(),
            Optional.empty());
    reports.commitReportable(commit); // the day is created and cached
    spy.failStatementWith(PgDecisions.INSERT_SQL, PgUnitFlowTest.serverError("23514"), 1);
    spy.mark("second commit");

    ReportableCommitted committed = reports.commitReportable(commit).get(0);

    assertThat(committed.versions().get(0).status()).isEqualTo(VersionStatus.EXISTING);
    List<String> events = spy.events();
    List<String> second = events.subList(events.indexOf("second commit"), events.size());
    assertThat(count(second, INSERT)).isEqualTo(2);
    assertThat(creations(second)).hasSize(1);
    assertThat(spy.openConnections()).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // the pre-creation entry

  @Test
  void ensurePartitionsAheadCreatesEachDayInATransactionOfItsOwnInAscendingOrder() {
    spy.answering(PgUnitFlowTest.server(1, false, FIRST.body()));
    PgDecisions decisions = decisions(CONFIG);

    List<LocalDate> created = decisions.ensurePartitionsAhead(DAY, 3);

    assertThat(created).containsExactly(DAY, DAY.plusDays(1), DAY.plusDays(2));
    List<String> events = spy.events();
    List<String> creations = creations(events);
    assertThat(creations).hasSize(3);
    assertThat(mentions(creations.get(0), "decision_p20261009")).isTrue();
    assertThat(mentions(creations.get(1), "decision_p20261010")).isTrue();
    assertThat(mentions(creations.get(2), "decision_p20261011")).isTrue();
    assertThat(count(events, ATTACH)).isEqualTo(3);
    assertThat(events.stream().filter("commit"::equals).count()).isEqualTo(3);
    assertThat(spy.openConnections()).isZero();

    // the days are known now: a commit for the first of them creates nothing
    spy.mark("later batch");
    decisions.commitDecisions(batch(FIRST));
    List<String> after = spy.events();
    assertThat(after.subList(after.indexOf("later batch"), after.size()))
        .noneMatch(event -> event.startsWith(CREATE));
  }

  @Test
  void ensurePartitionsAheadChecksTheServerAndSkipsADayThatIsListed() {
    spy.answering(
        (sql, events) ->
            sql.equals(PgDecisions.PARTITION_LISTED_SQL)
                ? Collections.<Object[]>singletonList(new Object[] {1})
                : List.of());
    PgDecisions decisions = decisions(CONFIG);

    assertThat(decisions.ensurePartitionsAhead(DAY, 2)).isEmpty();
    assertThat(decisions.ensurePartitionsAhead(DAY, 2)).isEmpty();

    // asked again although the first call cached the days: a dropped piece must be found
    assertThat(count(spy.events(), "execute: " + PgDecisions.PARTITION_LISTED_SQL)).isEqualTo(4);
    assertThat(creations(spy.events())).isEmpty();
  }

  @Test
  void ensurePartitionsAheadCreatesEvenWhenTheAdapterDoesNotCreateOnDemand() {
    PgDecisions decisions = decisions(CONFIG.withPartitionsOnDemand(false));

    assertThat(decisions.ensurePartitionsAhead(DAY, 1)).containsExactly(DAY);

    assertThat(creations(spy.events())).hasSize(1);
  }

  @Test
  void ensurePartitionsAheadRetriesALockTimeoutLikeTheOnDemandPath() {
    spy.failStatementWith("CREATE TABLE", PgUnitFlowTest.serverError("55P03"), 1);
    PgDecisions decisions = decisions(CONFIG);

    assertThat(decisions.ensurePartitionsAhead(DAY, 1)).containsExactly(DAY);

    assertThat(creations(spy.events())).hasSize(2);
    assertThat(pauses.asked).containsExactly(FIRST_PAUSE);
  }

  @Test
  void ensurePartitionsAheadRefusesAnUnusableWindowBeforeAnyStatement() {
    PgDecisions decisions = decisions(CONFIG);

    assertThatThrownBy(() -> decisions.ensurePartitionsAhead(DAY, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> decisions.ensurePartitionsAhead(DAY, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> decisions.ensurePartitionsAhead(DAY, PgDecisions.MAX_DAYS_AHEAD + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> decisions.ensurePartitionsAhead(null, 1))
        .isInstanceOf(NullPointerException.class);
    // before the supported range, and a window that ends beyond it
    assertThatThrownBy(() -> decisions.ensurePartitionsAhead(LocalDate.of(1999, 12, 31), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> decisions.ensurePartitionsAhead(LocalDate.of(2199, 12, 31), 2))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(spy.events()).isEmpty();
  }

  @Test
  void theLastSupportedDayCanBePreCreated() {
    PgDecisions decisions = decisions(CONFIG);

    assertThat(decisions.ensurePartitionsAhead(LocalDate.of(2199, 12, 31), 1))
        .containsExactly(LocalDate.of(2199, 12, 31));
  }

  @Test
  void theStoreOffersTheEntryToAScheduler() {
    PgLedgerStore store = new PgLedgerStore(spy.over(null), new ManualClock(NOW), CONFIG);

    assertThat(store.ensurePartitionsAhead(DAY, 2)).containsExactly(DAY, DAY.plusDays(1));

    assertThat(creations(spy.events())).hasSize(2);
  }
}
