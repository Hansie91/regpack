package dev.regpack.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.pack.PackRegime;
import dev.regpack.engine.pack.SamplePacks;
import dev.regpack.engine.skeleton.WalkingSkeletonRegime;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.inmemory.InMemorySourceConnector;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.fault.FaultInjector;
import dev.regpack.testkit.fault.FaultPlan;
import dev.regpack.testkit.fault.SimulatedCrash;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The deterministic toy crash test of {@link ToyCrashIT}, run through the pack-driven regime: a
 * {@link PackRegime} built from the committed sample pack {@code packs/sample.s0} replaces the
 * walking-skeleton constants. A crash at any port call of the pipeline, followed by a restart,
 * converges to the state of the uncrashed run (spec 7.2, I1). The baseline digest is written to
 * {@code pack-toy-digest.txt} for the CI comparison across operating systems; it is a new pin, not
 * a change of the skeleton digest of {@code toy-digest.txt}.
 */
class PackToyCrashIT {
  /** The digest that {@link ToyCrashIT} writes for the skeleton regime (P0 pin, unchanged). */
  private static final String SKELETON_TOY_DIGEST =
      "70382303f446476321d38d50817bdd8d541e0dcb61d8f33d92435c676bc116c9";

  private static final List<String> METHODS =
      List.of(
          "EvidenceStore.put",
          "LedgerStore.commitIngest",
          "BatchStream.acknowledge",
          "LedgerStore.commitDecisions");
  private static final List<String> POINTS =
      METHODS.stream().flatMap(m -> List.of(m + ":BEFORE", m + ":AFTER").stream()).toList();

  /** The provider is immutable and thread-safe; one instance serves every run of this class. */
  private static final PackRegime REGIME =
      PackRegime.create(
          SamplePacks.load(), "sample.s0", new MeteredExecutor(), Rulings.provisional());

  private record Run(
      Sha256 stateDigest,
      Sha256 traceDigest,
      List<String> trace,
      boolean crashed,
      long acknowledgedSeqAfterCrash,
      InMemoryLedgerStore ledger) {}

  private static MicroBatchPipeline pipeline(
      ManualClock clock, EvidenceStore evidence, LedgerStore ledger) {
    return new MicroBatchPipeline(
        clock,
        evidence,
        ledger,
        REGIME,
        REGIME.decisionContext(),
        new Retention(new RetentionClass("landing"), clock.now().plus(Duration.ofDays(3650))),
        REGIME.recordBudget());
  }

  private static Run run(FaultPlan plan, boolean resumeFromCheckpoint) {
    ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
    InMemoryEvidenceStore evidence = new InMemoryEvidenceStore(clock);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    InMemorySourceConnector source = ToyBatches.standardSource();
    FaultInjector injector = new FaultInjector(plan);

    boolean crashed = false;
    BatchStream raw = source.read(ToyBatches.fromStart());
    try {
      BatchStream stream = injector.wrap(BatchStream.class, raw);
      MicroBatchPipeline first =
          pipeline(
              clock,
              injector.wrap(EvidenceStore.class, evidence),
              injector.wrap(LedgerStore.class, ledger));
      while (!stream.exhausted()) {
        first.pump(stream, ToyBatches.SOURCE_ID, 1);
      }
    } catch (SimulatedCrash e) {
      crashed = true;
    } finally {
      raw.close();
    }
    long acknowledgedAfterCrash = source.acknowledgedSeq();

    // restart: a new pipeline over the unwrapped stores and a new read of the same source
    MicroBatchPipeline restarted = pipeline(clock, evidence, ledger);
    Optional<Checkpoint> resume =
        resumeFromCheckpoint
            ? ledger.latestCheckpoint(ToyBatches.SOURCE_ID, ToyBatches.STREAM)
            : Optional.empty();
    restarted.recoverPending();
    try (BatchStream stream =
        source.read(new ReadRequest(resume, ToyBatches.fromStart().limits()))) {
      while (!stream.exhausted()) {
        restarted.pump(stream, ToyBatches.SOURCE_ID, 1);
      }
    }
    return new Run(
        stateDigest(ledger),
        injector.traceDigest(),
        injector.trace(),
        crashed,
        acknowledgedAfterCrash,
        ledger);
  }

  private static Sha256 stateDigest(LedgerStore ledger) {
    List<EventIdentity> identities =
        new ArrayList<>(List.of(ToyBatches.T1, ToyBatches.T2, ToyBatches.T3));
    identities.sort((a, b) -> a.value().compareTo(b.value()));
    List<String> lines = new ArrayList<>();
    for (EventIdentity identity : identities) {
      for (DecisionRecord record : ledger.decisionChain(REGIME.regime(), identity)) {
        lines.add(
            record.decisionSeq()
                + "|"
                + record.body().outcome()
                + "|"
                + record.body().reasonCode().value()
                + "|"
                + record.body().replayDigest().hex());
      }
    }
    return Sha256.ofUtf8(String.join("\n", lines));
  }

  private static void assertConverged(Run run, Sha256 baseline, String description) {
    assertThat(run.stateDigest()).as(description).isEqualTo(baseline);
    assertThat(run.ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).as(description).isEmpty();
    assertThat(run.ledger().decisionChain(REGIME.regime(), ToyBatches.T1))
        .as(description)
        .hasSize(2);
  }

  @Test
  void everyCrashPointConvergesToTheUncrashedBaseline() {
    Sha256 baseline = run(FaultPlan.none(), true).stateDigest();
    int plans = 0;
    for (String method : METHODS) {
      for (String phase : List.of("BEFORE", "AFTER")) {
        for (int hit = 1; hit <= 2; hit++) {
          FaultPlan plan = FaultPlan.crashAt(method + ":" + phase, hit);
          Run run = run(plan, true);
          assertThat(run.crashed()).as(plan.describe() + " fires").isTrue();
          assertConverged(run, baseline, plan.describe());
          plans++;
        }
      }
    }
    assertThat(plans).isEqualTo(16);
  }

  @Test
  void sameFaultPlanGivesIdenticalTraceAndDigest() throws IOException {
    FaultPlan plan = FaultPlan.crashAt("LedgerStore.commitDecisions:BEFORE", 2);
    Run a = run(plan, true);
    Run b = run(plan, true);
    assertThat(a.traceDigest()).isEqualTo(b.traceDigest());
    assertThat(a.trace()).isEqualTo(b.trace());
    assertThat(a.stateDigest()).isEqualTo(b.stateDigest());

    Sha256 baseline = run(FaultPlan.none(), true).stateDigest();
    assertThat(a.stateDigest()).isEqualTo(baseline);
    Path out = Path.of(System.getProperty("regpack.build.dir"), "pack-toy-digest.txt");
    Files.writeString(out, baseline.hex() + "\n", StandardCharsets.UTF_8);
  }

  @Test
  void acknowledgementFollowsIngestCommit() {
    List<String> trace = run(FaultPlan.none(), true).trace();
    int put = trace.indexOf("EvidenceStore.put:AFTER#1");
    int commitBefore = trace.indexOf("LedgerStore.commitIngest:BEFORE#1");
    int commitAfter = trace.indexOf("LedgerStore.commitIngest:AFTER#1");
    int acknowledge = trace.indexOf("BatchStream.acknowledge:BEFORE#1");
    assertThat(put).isNotNegative();
    assertThat(put).isLessThan(commitBefore);
    assertThat(commitBefore).isLessThan(commitAfter);
    assertThat(commitAfter).isLessThan(acknowledge);

    Run beforeCommit = run(FaultPlan.crashAt("LedgerStore.commitIngest:BEFORE", 1), true);
    assertThat(beforeCommit.acknowledgedSeqAfterCrash()).isZero();

    Run beforePut = run(FaultPlan.crashAt("EvidenceStore.put:BEFORE", 1), true);
    assertThat(beforePut.trace()).doesNotContain("LedgerStore.commitIngest:BEFORE#1");
    assertThat(beforePut.acknowledgedSeqAfterCrash()).isZero();
  }

  @Test
  void redeliveryAfterAckLossIsNoop() {
    Sha256 baseline = run(FaultPlan.none(), true).stateDigest();
    Run run = run(FaultPlan.crashAt("BatchStream.acknowledge:BEFORE", 1), false);
    assertThat(run.crashed()).isTrue();
    assertThat(run.stateDigest()).isEqualTo(baseline);
    assertThat(run.ledger().outboxItem(new OutboxId(3))).isEmpty();
  }

  @Test
  void seededPlansAlsoConverge() {
    Sha256 baseline = run(FaultPlan.none(), true).stateDigest();
    for (long seed = 1; seed <= 25; seed++) {
      FaultPlan plan = FaultPlan.seeded(seed, POINTS, 2);
      assertConverged(run(plan, true), baseline, "seed " + seed + ": " + plan.describe());
    }
  }

  @Test
  void theBaselineDigestDiffersFromTheSkeletonDigest() {
    Sha256 baseline = run(FaultPlan.none(), true).stateDigest();
    assertThat(baseline.hex()).hasSize(64).isNotEqualTo(SKELETON_TOY_DIGEST);
    // the chains are read under the regime of the pack, not under the skeleton regime
    assertThat(REGIME.regime().id())
        .isEqualTo("sample.s0")
        .isNotEqualTo(WalkingSkeletonRegime.REGIME.id());
    assertThat(REGIME.decisionContext().id())
        .isNotEqualTo(WalkingSkeletonRegime.skeletonContext().id());
  }
}
