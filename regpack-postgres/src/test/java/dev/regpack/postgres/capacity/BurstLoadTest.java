package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Outcome;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.IngestLedger;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.ReportLedger;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.ports.ledger.ReportLedger.VersionResult;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import dev.regpack.postgres.capacity.ProbeSummary.Batch;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The load driver of the capacity probe (T21), run against a fake ledger so that its schedule, its
 * keys and its handling of a slow or failing ledger are tested without a server. The shape of the
 * generated batches is the shape of SP-L: half of the decisions REPORT with one version each.
 */
class BurstLoadTest {
  /** A ledger that answers every call at once and keeps what it was sent. */
  private static final class FakeLedger {
    final List<IngestLedger.Commit> ingests = new CopyOnWriteArrayList<>();
    final List<ReportableCommit> reportables = new CopyOnWriteArrayList<>();
    final List<Long> outboxIds = new CopyOnWriteArrayList<>();
    private final AtomicLong outbox = new AtomicLong();
    private final AtomicLong ids = new AtomicLong();
    volatile long delayMillis;
    volatile int failOnCall = -1;
    private final AtomicLong calls = new AtomicLong();

    LedgerStore store() {
      return (LedgerStore)
          Proxy.newProxyInstance(
              BurstLoadTest.class.getClassLoader(),
              new Class<?>[] {LedgerStore.class},
              (proxy, method, args) -> {
                long call = calls.incrementAndGet();
                if (failOnCall > 0 && call == failOnCall) {
                  throw new LedgerException(
                      "ledger statement failed: sqlstate=57P01 constraint=none");
                }
                if (delayMillis > 0) {
                  Thread.sleep(delayMillis);
                }
                return switch (method.getName()) {
                  case "commitIngest" -> ingest((IngestLedger.Commit) args[0]);
                  case "commitReportable" -> reportable((ReportableCommit) args[0]);
                  default -> throw new UnsupportedOperationException(method.getName());
                };
              });
    }

    private IngestLedger.Result ingest(IngestLedger.Commit commit) {
      ingests.add(commit);
      long id = outbox.incrementAndGet();
      outboxIds.add(id);
      List<EntryResult> results =
          commit.entries().stream()
              .map(e -> new EntryResult(e.key(), Disposition.NEW_VERSION))
              .toList();
      return new IngestLedger.Result(
          commit.batchId(), false, results, Optional.of(new OutboxId(id)));
    }

    private List<ReportableCommitted> reportable(ReportableCommit commit) {
      reportables.add(commit);
      List<ReportableCommitted> out = new ArrayList<>();
      for (ReportLedger.ReportableDecision d : commit.decisions()) {
        DecisionRecord record =
            new DecisionRecord(
                new DecisionId(ids.incrementAndGet()), 1, Optional.empty(), d.decision().body());
        List<VersionResult> versions =
            d.versions().stream()
                .map(
                    v ->
                        new VersionResult(
                            new SubjectId(ids.incrementAndGet()),
                            v.trn().orElseThrow().trn(),
                            1,
                            VersionStatus.CREATED,
                            Optional.empty()))
                .toList();
        out.add(new ReportableCommitted(new Committed(CommitStatus.INSERTED, record), versions));
      }
      return out;
    }
  }

  private static BurstLoad.Settings settings(int batchSize, int writers, long rate, long seconds) {
    return new BurstLoad.Settings(batchSize, writers, rate, seconds, "t");
  }

  private static BurstLoad load(FakeLedger ledger, BurstLoad.Settings settings) {
    return new BurstLoad(ledger.store(), settings, () -> new CountingDataSource.Counts(0, 0));
  }

  @Test
  void aDryRunAgainstAFakeLedgerCompletesEveryBatchOnTheSchedule() throws Exception {
    FakeLedger ledger = new FakeLedger();
    // 100 batches of 20 records, one every 10 ms, two writers (two sources)
    BurstLoad.Outcome outcome = load(ledger, settings(20, 2, 2_000, 1)).run();

    assertThat(outcome.failure()).isEmpty();
    assertThat(outcome.scheduledBatches()).isEqualTo(100);
    assertThat(outcome.observations()).hasSize(100);
    for (Batch batch : outcome.observations()) {
      assertThat(batch.started()).isTrue();
      assertThat(batch.completed()).isTrue();
      assertThat(batch.records()).isEqualTo(20);
      assertThat(batch.source()).isEqualTo((int) (batch.index() % 2));
      // open loop: a batch is never taken before it is due
      assertThat(batch.startNanos()).isGreaterThanOrEqualTo(batch.arrivalNanos());
      assertThat(batch.endNanos()).isGreaterThanOrEqualTo(batch.startNanos());
    }
    assertThat(outcome.observations().stream().mapToLong(Batch::arrivalNanos).max().orElseThrow())
        .isEqualTo(outcome.coveredNanos());
    assertThat(ledger.ingests).hasSize(100);
    assertThat(ledger.reportables).hasSize(100);
  }

  @Test
  void theGeneratedBatchesFollowTheSpLShape() throws Exception {
    FakeLedger ledger = new FakeLedger();
    load(ledger, settings(20, 2, 2_000, 1)).run();

    Set<String> recordKeys = new HashSet<>();
    Set<String> batchIds = new HashSet<>();
    Set<String> sources = new HashSet<>();
    Set<String> trns = new HashSet<>();
    for (IngestLedger.Commit commit : ledger.ingests) {
      assertThat(commit.entries()).hasSize(20);
      assertThat(commit.checkpoint()).isPresent();
      assertThat(batchIds.add(commit.batchId().value())).isTrue();
      sources.add(commit.sourceId());
      commit.entries().forEach(e -> assertThat(e.key().sourceId()).isEqualTo(commit.sourceId()));
      commit.entries().forEach(e -> assertThat(recordKeys.add(e.key().toString())).isTrue());
      commit.entries().forEach(e -> assertThat(e.key().sourceVersion()).isEqualTo(1));
    }
    assertThat(sources).hasSize(2);
    assertThat(recordKeys).hasSize(2_000);

    Set<Long> completed = new HashSet<>();
    for (ReportableCommit commit : ledger.reportables) {
      assertThat(commit.decisions()).hasSize(20);
      // half REPORT with exactly one version each, half DO_NOT_REPORT without
      long reports =
          commit.decisions().stream()
              .filter(d -> d.decision().body().outcome() == Outcome.REPORT)
              .count();
      assertThat(reports).isEqualTo(10);
      for (ReportLedger.ReportableDecision d : commit.decisions()) {
        assertThat(d.decision().expectedHeadSeq()).isZero();
        assertThat(d.decision().context()).isPresent();
        assertThat(d.decision().context().orElseThrow().id())
            .isEqualTo(d.decision().body().decisionContextId());
        if (d.decision().body().outcome() == Outcome.REPORT) {
          assertThat(d.versions()).hasSize(1);
          ReportLedger.NewVersion version = d.versions().get(0);
          assertThat(version.subject().eventIdentity())
              .isEqualTo(d.decision().body().eventIdentity());
          assertThat(version.subject().regime()).isEqualTo(d.decision().body().regime());
          assertThat(d.decision().body().legs()).containsExactly(version.subject().leg());
          assertThat(trns.add(version.trn().orElseThrow().trn())).isTrue();
          assertThat(version.body()).hasSize(400);
        } else {
          assertThat(d.versions()).isEmpty();
          assertThat(d.decision().body().legs()).isEmpty();
        }
      }
      // the unit completes the outbox item of its own batch and names its batch for the sequencer
      assertThat(commit.completesOutbox()).isPresent();
      assertThat(commit.sequenceBatch()).isPresent();
      completed.add(commit.completesOutbox().orElseThrow().value());
      assertThat(batchIds).contains(commit.sequenceBatch().orElseThrow().value());
    }
    assertThat(completed).hasSize(100);
    assertThat(completed).containsAll(ledger.outboxIds);
  }

  @Test
  void aSlowLedgerLeavesTheLastBatchesUnstartedAndTheRunStillEnds() throws Exception {
    FakeLedger ledger = new FakeLedger();
    ledger.delayMillis = 150;
    // 100 batches due over one second; each commit takes 150 ms, two writers cannot keep up
    long started = System.nanoTime();
    BurstLoad.Outcome outcome = load(ledger, settings(10, 2, 1_000, 1)).run();
    long tookMillis = (System.nanoTime() - started) / 1_000_000L;

    assertThat(outcome.failure()).isEmpty();
    assertThat(outcome.observations()).hasSize(100);
    long never = outcome.observations().stream().filter(b -> !b.started()).count();
    long done = outcome.observations().stream().filter(Batch::completed).count();
    assertThat(never).isGreaterThan(50);
    assertThat(done).isGreaterThan(0).isLessThan(50);
    assertThat(done + never).isLessThanOrEqualTo(100);
    // the run stops about when the schedule ends plus the batch in flight, not when the queue is
    // empty
    assertThat(tookMillis).isLessThan(8_000);
  }

  @Test
  void aFailingLedgerStopsTheRunAndReportsTheFailure() throws Exception {
    FakeLedger ledger = new FakeLedger();
    ledger.failOnCall = 7;
    BurstLoad.Outcome outcome = load(ledger, settings(20, 2, 2_000, 1)).run();

    assertThat(outcome.failure()).isPresent();
    assertThat(outcome.failure().orElseThrow()).isInstanceOf(LedgerException.class);
    // every batch still has an observation; the ones after the error never started
    assertThat(outcome.observations()).hasSize(100);
    assertThat(outcome.observations().stream().filter(b -> !b.started()).count()).isGreaterThan(0);
  }

  @Test
  void theProgressCountsCompletedBatchesAndRecords() throws Exception {
    FakeLedger ledger = new FakeLedger();
    BurstLoad load = load(ledger, settings(20, 2, 2_000, 1));
    assertThat(load.recordsCompleted()).isZero();
    BurstLoad.Outcome outcome = load.run();

    assertThat(outcome.failure()).isEmpty();
    assertThat(load.batchesCompleted()).isEqualTo(100);
    assertThat(load.recordsCompleted()).isEqualTo(2_000);
  }
}
