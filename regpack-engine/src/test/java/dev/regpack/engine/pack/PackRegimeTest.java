package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Outcome;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.regime.RegimeProvider.Descriptor;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PackRegimeTest {
  private static final String REPORT = "sample.s0";
  private static final String INPUT = "model/canonical-input.yaml";
  private static final MeteredExecutor EXECUTOR = new MeteredExecutor();

  private static PackRegime create(LoadedPack pack, String reportId) {
    return PackRegime.create(pack, reportId, EXECUTOR, Rulings.load());
  }

  private static CanonicalExecution execution(AssetClass assetClass) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        LifecycleKind.NEW,
        Instant.parse("2026-10-09T09:30:00.123456Z"),
        assetClass,
        "ZZ0000000001",
        true,
        PartyKind.LEGAL_ENTITY,
        Optional.of("LEI-CLIENT-0001"),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR");
  }

  private static DecisionBody decide(PackRegime regime, CanonicalExecution event) {
    EvaluationContext context =
        new EvaluationContext(
            regime.decisionContext(),
            new IngestKey("toy-source", "rec-1", 1),
            Instant.parse("2026-10-09T10:00:00Z"),
            regime.recordBudget());
    return regime.evaluate(event, context).decision();
  }

  @Test
  void describeNamesTheReportRegimeAndEngineMin() {
    PackRegime regime = create(SamplePacks.load(), REPORT);

    Descriptor descriptor = regime.describe();

    assertThat(descriptor.regime().id()).isEqualTo("sample.s0");
    assertThat(descriptor.packVersion()).isEqualTo("0.1.0");
    assertThat(descriptor.engineMin()).isEqualTo("1.0.0+lib1");
    assertThat(regime.regime()).isEqualTo(descriptor.regime());
    assertThat(regime.compiled().reportId()).isEqualTo(REPORT);
    assertThat(regime.recordBudget()).isEqualTo(new CostBudget(40));
  }

  @Test
  void onlyTheCommittedRulingsAreAcceptedBecauseTheContextPinsTheBuildAndNotTheRulings() {
    // decision_context_id carries the engine build but not the rulings, so a regime whose
    // rulings differ from rulings.tsv would put different semantics under one context id (I3)
    Rulings alternative =
        new Rulings(
            Rulings.IntDecimalMix.DENY,
            Rulings.load().andLeftError(),
            Rulings.load().orLeftError(),
            Rulings.load().hasBelowAbsent());
    assertThatThrownBy(() -> PackRegime.create(SamplePacks.load(), REPORT, EXECUTOR, alternative))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rulings.tsv")
        .hasMessageContaining("Rulings.load()");

    PackRegime committed = PackRegime.create(SamplePacks.load(), REPORT, EXECUTOR, Rulings.load());
    assertThat(committed.decisionContext())
        .isEqualTo(create(SamplePacks.load(), REPORT).decisionContext());
  }

  @Test
  void decisionContextPinsTheDigestBuildAndExecutor() {
    LoadedPack pack = SamplePacks.load();

    DecisionContext context = create(pack, REPORT).decisionContext();

    assertThat(context.packDigest()).isEqualTo(pack.digest());
    assertThat(context.overlayDigest()).isEmpty();
    assertThat(context.interpretationPins()).isEmpty();
    assertThat(context.referenceSnapshotIds()).isEmpty();
    assertThat(context.enrichmentSnapshotIds()).isEmpty();
    assertThat(context.firmProfileVersion()).isEqualTo("none");
    assertThat(context.mappingVersion()).isEqualTo("none");
    assertThat(context.engineBuild()).isEqualTo(EngineInfo.BUILD.toString());
    assertThat(context.engineBuild()).isEqualTo("1.0.0+lib1");
    assertThat(context.executor()).isEqualTo(MeteredExecutor.ID);
  }

  private static EvaluationContext contextOf(PackRegime regime, DecisionContext stamped) {
    return new EvaluationContext(
        stamped,
        new IngestKey("toy-source", "rec-1", 1),
        Instant.parse("2026-10-09T10:00:00Z"),
        regime.recordBudget());
  }

  @Test
  void aDecisionIsStampedWithTheProvidersOwnContext() {
    PackRegime regime = create(SamplePacks.load(), REPORT);

    DecisionBody body = decide(regime, execution(AssetClass.EQUITY));

    assertThat(body.decisionContextId()).isEqualTo(regime.decisionContext().id());
    assertThat(body.decisionContextId().hex()).hasSize(64);
  }

  @Test
  void aContextOfAnotherPackDigestEngineBuildOrExecutorIsRefused() {
    PackRegime regime = create(SamplePacks.load(), REPORT);
    DecisionContext own = regime.decisionContext();
    DecisionContext otherDigest =
        new DecisionContext(
            new Sha256("0".repeat(64)),
            own.overlayDigest(),
            own.interpretationPins(),
            own.referenceSnapshotIds(),
            own.enrichmentSnapshotIds(),
            own.firmProfileVersion(),
            own.mappingVersion(),
            own.engineBuild(),
            own.executor());
    DecisionContext otherBuild = with(own, own.packDigest(), "9.9.9+lib9", own.executor());
    DecisionContext otherExecutor =
        with(own, own.packDigest(), own.engineBuild(), "other-executor");
    CanonicalExecution event = execution(AssetClass.EQUITY);

    assertThatThrownBy(() -> regime.evaluate(event, contextOf(regime, otherDigest)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pack digest");
    assertThatThrownBy(() -> regime.evaluate(event, contextOf(regime, otherBuild)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("engine build")
        .hasMessageContaining("9.9.9+lib9");
    assertThatThrownBy(() -> regime.evaluate(event, contextOf(regime, otherExecutor)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("executor")
        .hasMessageContaining("other-executor");
    assertThatThrownBy(() -> regime.evaluateTraced(event, contextOf(regime, otherDigest)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pack digest");
  }

  @Test
  void aContextThatDiffersOnlyInTheCallersPlaceholdersIsRefusedUntilP2a2() {
    PackRegime regime = create(SamplePacks.load(), REPORT);
    DecisionContext own = regime.decisionContext();
    DecisionContext profiled =
        new DecisionContext(
            own.packDigest(),
            own.overlayDigest(),
            own.interpretationPins(),
            own.referenceSnapshotIds(),
            own.enrichmentSnapshotIds(),
            "profile-7",
            own.mappingVersion(),
            own.engineBuild(),
            own.executor());

    assertThatThrownBy(
            () -> regime.evaluate(execution(AssetClass.EQUITY), contextOf(regime, profiled)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs");
  }

  private static PackRegime createWithCircuit(RuleCircuit circuit) {
    return PackRegime.create(
        SamplePacks.load(), REPORT, EXECUTOR, Rulings.load(), Optional.of(circuit));
  }

  private static void openAndClose(RuleCircuit circuit, String ruleId) {
    circuit.record(ruleId, true);
    assertThat(circuit.isOpen(ruleId)).isTrue();
    circuit.close(ruleId);
  }

  @Test
  void decisionContextCarriesTheCurrentCircuitEpoch() {
    RuleCircuit circuit = new RuleCircuit(1, 50);
    PackRegime regime = createWithCircuit(circuit);
    DecisionContext before = regime.decisionContext();
    assertThat(before.circuitEpoch()).isZero();
    assertThat(before).isEqualTo(create(SamplePacks.load(), REPORT).decisionContext());
    assertThat(before.id()).isEqualTo(create(SamplePacks.load(), REPORT).decisionContext().id());

    openAndClose(circuit, "r1");
    DecisionContext afterOne = regime.decisionContext();
    openAndClose(circuit, "r2");
    DecisionContext afterTwo = regime.decisionContext();

    assertThat(afterOne.circuitEpoch()).isEqualTo(1);
    assertThat(afterTwo.circuitEpoch()).isEqualTo(2);
    assertThat(afterOne.id()).isNotEqualTo(before.id());
    assertThat(afterTwo.id()).isNotEqualTo(afterOne.id());
    assertThat(afterOne.packDigest()).isEqualTo(before.packDigest());
    assertThat(afterOne.engineBuild()).isEqualTo(before.engineBuild());
    assertThat(afterOne.executor()).isEqualTo(before.executor());
    DecisionBody stamped = decide(regime, execution(AssetClass.EQUITY));
    assertThat(stamped.decisionContextId()).isEqualTo(afterTwo.id());
  }

  @Test
  void aCallerContextFromBeforeTheCloseIsRefusedAsStale() {
    RuleCircuit circuit = new RuleCircuit(1, 50);
    PackRegime regime = createWithCircuit(circuit);
    DecisionContext beforeClose = regime.decisionContext();
    CanonicalExecution event = execution(AssetClass.EQUITY);
    assertThat(regime.evaluate(event, contextOf(regime, beforeClose)).decision()).isNotNull();

    openAndClose(circuit, "r1");

    assertThatThrownBy(() -> regime.evaluate(event, contextOf(regime, beforeClose)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stale decision context");
    assertThatThrownBy(() -> regime.evaluateTraced(event, contextOf(regime, beforeClose)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stale decision context");
    assertThat(regime.evaluate(event, contextOf(regime, regime.decisionContext())).decision())
        .isNotNull();
  }

  private static DecisionContext with(
      DecisionContext own, Sha256 digest, String build, String executor) {
    return new DecisionContext(
        digest,
        own.overlayDigest(),
        own.interpretationPins(),
        own.referenceSnapshotIds(),
        own.enrichmentSnapshotIds(),
        own.firmProfileVersion(),
        own.mappingVersion(),
        build,
        executor);
  }

  @Test
  void refusesAPackWhoseEngineMinIsNotSatisfied() {
    for (String engineMin : new String[] {"2.0.0+lib1", "1.0.0+lib2", "1.1.0+lib1", "0.9.0+lib1"}) {
      LoadedPack pack = PackBuilder.sample().engineMin(engineMin).load();
      assertThatThrownBy(() -> create(pack, REPORT))
          .as(engineMin)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ENGINE_MIN_NOT_SATISFIED");
    }
    // the running build itself is satisfied
    assertThat(create(PackBuilder.sample().engineMin("1.0.0+lib1").load(), REPORT)).isNotNull();
  }

  @Test
  void refusesAPackWhoseCanonicalInputIsNotSupported() {
    String input = SamplePacks.files().get(INPUT) + "  trade.venueName: {type: STRING}\n";
    LoadedPack pack = PackBuilder.sample().file(INPUT, input).load();

    assertThatThrownBy(() -> create(pack, REPORT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("INPUT_PATH_UNSUPPORTED");
  }

  @Test
  void refusesAPackThatDoesNotCompile() {
    LoadedPack pack = PackBuilder.sample().limits("rule_nodes: 3").load();

    assertThatThrownBy(() -> create(pack, REPORT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RULE_OVER_CEILING");
    assertThatThrownBy(() -> create(SamplePacks.load(), "sample.other"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("REPORT_UNKNOWN");
  }

  @Test
  void createsOneRegimePerReportDefinition() {
    LoadedPack pack =
        PackBuilder.sample()
            .secondReport(
                "sample.second", PackBuilder.Rule.of("R-DEBT", "instrument.assetClass == 'DEBT'"))
            .load();

    PackRegime main = create(pack, "sample.s0");
    PackRegime second = create(pack, "sample.second");

    assertThat(main.regime().id()).isEqualTo("sample.s0");
    assertThat(second.regime().id()).isEqualTo("sample.second");
    DecisionBody onMain = decide(main, execution(AssetClass.DEBT));
    DecisionBody onSecond = decide(second, execution(AssetClass.DEBT));
    assertThat(onMain.regime()).isEqualTo(main.regime());
    assertThat(onMain.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(onSecond.regime()).isEqualTo(second.regime());
    assertThat(onSecond.outcome()).isEqualTo(Outcome.REPORT);
    assertThat(onSecond.ruleIdsHit()).containsExactly("R-DEBT");
  }
}
