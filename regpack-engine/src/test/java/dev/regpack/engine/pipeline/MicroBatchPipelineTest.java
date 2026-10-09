package dev.regpack.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.skeleton.WalkingSkeletonRegime;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.inmemory.InMemorySourceConnector;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Outcome;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MicroBatchPipelineTest {
  private final ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
  private final InMemoryEvidenceStore evidence = new InMemoryEvidenceStore(clock);
  private final InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
  private final MicroBatchPipeline pipeline =
      new MicroBatchPipeline(
          clock,
          evidence,
          ledger,
          WalkingSkeletonRegime.standard(),
          WalkingSkeletonRegime.skeletonContext(),
          new Retention(
              new RetentionClass("landing"), clock.now().plus(Duration.ofDays(365 * 10L))),
          new CostBudget(100));

  private List<DecisionRecord> chain(EventIdentity identity) {
    return ledger.decisionChain(WalkingSkeletonRegime.REGIME, identity);
  }

  private static List<Outcome> outcomes(List<DecisionRecord> chain) {
    return chain.stream().map(record -> record.body().outcome()).toList();
  }

  @Test
  void happyPathProducesDecisionsAndAcknowledgesTheSource() {
    InMemorySourceConnector source = ToyBatches.standardSource();
    try (BatchStream stream = source.read(ToyBatches.fromStart())) {
      assertThat(pipeline.pump(stream, ToyBatches.SOURCE_ID, 10)).isEqualTo(2);
    }

    List<DecisionRecord> t1 = chain(ToyBatches.T1);
    assertThat(outcomes(t1)).containsExactly(Outcome.REPORT, Outcome.DO_NOT_REPORT);
    assertThat(t1).extracting(DecisionRecord::decisionSeq).containsExactly(1L, 2L);
    assertThat(t1.get(1).supersedesDecisionId()).contains(t1.get(0).decisionId());
    assertThat(outcomes(chain(ToyBatches.T2))).containsExactly(Outcome.DO_NOT_REPORT);
    assertThat(outcomes(chain(ToyBatches.T3))).containsExactly(Outcome.UNDETERMINED);
    assertThat(source.acknowledgedSeq()).isEqualTo(2);
    assertThat(evidence.list("landing/", Optional.empty(), 10)).hasSize(2);
    assertThat(ledger.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }

  @Test
  void staleVersionIsNotDecided() {
    byte[] replay =
        ToyBatches.payload(
            ToyBatches.line(
                "T-1",
                1,
                ToyBatches.execution("EX-0001", LifecycleKind.NEW, PartyKind.LEGAL_ENTITY)));
    InMemorySourceConnector source =
        ToyBatches.source(ToyBatches.batch1(), ToyBatches.batch2(), replay);
    try (BatchStream stream = source.read(ToyBatches.fromStart())) {
      assertThat(pipeline.pump(stream, ToyBatches.SOURCE_ID, 10)).isEqualTo(3);
    }

    assertThat(outcomes(chain(ToyBatches.T1)))
        .containsExactly(Outcome.REPORT, Outcome.DO_NOT_REPORT);
    assertThat(evidence.list("landing/", Optional.empty(), 10)).hasSize(3);
    assertThat(ledger.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }
}
