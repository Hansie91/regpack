package dev.regpack.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.skeleton.WalkingSkeletonRegime;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.inmemory.InMemorySourceConnector;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Outcome;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchPayload;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.Manifest;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    // The landing key shape is fixed by the spec: landing/<digest hex>/0, holding the batch bytes.
    Optional<StoredObject> landed =
        evidence.get(EvidenceKey.of("landing", Sha256.of(ToyBatches.batch1()).hex(), "0"));
    assertThat(landed).isPresent();
    assertThat(landed.get().bytes()).isEqualTo(ToyBatches.batch1());
  }

  @Test
  void theDecisionContextIsStoredWithTheFirstDecision() {
    InMemorySourceConnector source = ToyBatches.standardSource();
    Sha256 contextId = WalkingSkeletonRegime.skeletonContext().id();
    assertThat(ledger.decisionContext(contextId)).isEmpty();

    try (BatchStream stream = source.read(ToyBatches.fromStart())) {
      assertThat(pipeline.pump(stream, ToyBatches.SOURCE_ID, 1)).isEqualTo(1);
    }

    assertThat(chain(ToyBatches.T1)).isNotEmpty();
    assertThat(chain(ToyBatches.T1).get(0).body().decisionContextId()).isEqualTo(contextId);
    assertThat(ledger.decisionContext(contextId)).contains(WalkingSkeletonRegime.skeletonContext());
  }

  @Test
  void twoVersionsOfOneIdentityInOneBatchChainInOrder() {
    byte[] both =
        ToyBatches.payload(
            ToyBatches.line(
                "T-1",
                1,
                ToyBatches.execution("EX-0001", LifecycleKind.NEW, PartyKind.LEGAL_ENTITY)),
            ToyBatches.line(
                "T-1",
                2,
                ToyBatches.execution("EX-0001", LifecycleKind.NOVATION, PartyKind.LEGAL_ENTITY)));
    InMemorySourceConnector source = ToyBatches.source(both);
    try (BatchStream stream = source.read(ToyBatches.fromStart())) {
      assertThat(pipeline.pump(stream, ToyBatches.SOURCE_ID, 10)).isEqualTo(1);
    }

    List<DecisionRecord> t1 = chain(ToyBatches.T1);
    assertThat(outcomes(t1)).containsExactly(Outcome.REPORT, Outcome.DO_NOT_REPORT);
    assertThat(t1).extracting(DecisionRecord::decisionSeq).containsExactly(1L, 2L);
    assertThat(t1.get(1).supersedesDecisionId()).contains(t1.get(0).decisionId());
    assertThat(ledger.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }

  @Test
  void manifestDigestMismatchIsRejectedBeforeAnythingIsLandedOrCommitted() {
    byte[] bytes = ToyBatches.batch1();
    Batch tampered =
        new Batch(
            ToyBatches.STREAM,
            1,
            new BatchPayload(
                bytes,
                ToyBatches.MEDIA_TYPE,
                new Manifest(3, Sha256.of(ToyBatches.batch2()), Map.of())),
            "mem:trades:1",
            new Checkpoint(ToyBatches.STREAM, new byte[] {0, 0, 0, 0, 0, 0, 0, 1}));
    List<Checkpoint> acknowledged = new ArrayList<>();
    BatchStream stream =
        new BatchStream() {
          @Override
          public List<Batch> next(int credits) {
            return List.of(tampered);
          }

          @Override
          public void acknowledge(Checkpoint checkpoint) {
            acknowledged.add(checkpoint);
          }

          @Override
          public boolean exhausted() {
            return false;
          }

          @Override
          public void close() {}
        };

    assertThatThrownBy(() -> pipeline.pump(stream, ToyBatches.SOURCE_ID, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256");

    assertThat(evidence.list("landing/", Optional.empty(), 10)).isEmpty();
    assertThat(ledger.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
    assertThat(chain(ToyBatches.T1)).isEmpty();
    assertThat(acknowledged).isEmpty();
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
