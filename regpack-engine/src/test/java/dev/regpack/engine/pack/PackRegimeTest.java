package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Citation;
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
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.regime.RegimeProvider;
import dev.regpack.ports.regime.RegimeProvider.Descriptor;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
  void aContextThatDiffersOnlyInTheCallersPlaceholdersIsAcceptedAndStampsTheDecision() {
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

    DecisionBody body =
        regime.evaluate(execution(AssetClass.EQUITY), contextOf(regime, profiled)).decision();

    assertThat(body.decisionContextId()).isEqualTo(profiled.id());
    assertThat(body.decisionContextId()).isNotEqualTo(own.id());
  }

  @Test
  void aContextThatDiffersInTheOverlayOrThePinsIsStillRefused() {
    PackRegime regime = create(SamplePacks.load(), REPORT);
    DecisionContext own = regime.decisionContext();
    DecisionContext overlaid =
        new DecisionContext(
            own.packDigest(),
            Optional.of(new Sha256("1".repeat(64))),
            own.interpretationPins(),
            own.referenceSnapshotIds(),
            own.enrichmentSnapshotIds(),
            own.firmProfileVersion(),
            own.mappingVersion(),
            own.engineBuild(),
            own.executor());
    DecisionContext pinned =
        new DecisionContext(
            own.packDigest(),
            own.overlayDigest(),
            List.of("pin-1"),
            own.referenceSnapshotIds(),
            own.enrichmentSnapshotIds(),
            own.firmProfileVersion(),
            own.mappingVersion(),
            own.engineBuild(),
            own.executor());
    CanonicalExecution event = execution(AssetClass.EQUITY);

    assertThatThrownBy(() -> regime.evaluate(event, contextOf(regime, overlaid)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlay")
        .hasMessageContaining("pins");
    assertThatThrownBy(() -> regime.evaluate(event, contextOf(regime, pinned)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlay");
  }

  @Test
  void aGivenContextOutsideTheBatchInputBoundsIsRefusedAsContextForWouldRefuseIt() {
    PackRegime regime = create(SamplePacks.load(), REPORT);
    DecisionContext own = regime.decisionContext();
    List<String> sixtyFive = new ArrayList<>();
    for (int i = 0; i < 65; i++) {
      sixtyFive.add("ref-" + i);
    }
    CanonicalExecution event = execution(AssetClass.EQUITY);
    List<DecisionContext> outOfBounds =
        List.of(
            withBatch(own, "m@1", "p-1", sixtyFive, List.of()),
            withBatch(own, "m@1", "p-1", List.of(), sixtyFive),
            withBatch(own, "m".repeat(257), "p-1", List.of(), List.of()),
            withBatch(own, "m@1", " ", List.of(), List.of()),
            withBatch(own, "", "p-1", List.of(), List.of()),
            withBatch(own, "m@1", "a\u0001b", List.of(), List.of()),
            withBatch(own, "m@1", "p-1", List.of("x".repeat(257)), List.of()),
            withBatch(own, "m@1", "p-1", List.of(), List.of("a\u0000b")));

    for (DecisionContext given : outOfBounds) {
      assertThatThrownBy(() -> regime.evaluate(event, contextOf(regime, given)))
          .as(given.mappingVersion().length() + "/" + given.firmProfileVersion().length())
          .isInstanceOf(IllegalArgumentException.class);
    }
    // a context in bounds is accepted, whether built by hand or by contextFor
    DecisionContext inBounds = withBatch(own, "m@1", "p-1", List.of("r-1"), List.of("e-1"));
    assertThat(regime.evaluate(event, contextOf(regime, inBounds)).decision().decisionContextId())
        .isEqualTo(inBounds.id());
    DecisionContext built =
        regime.contextFor(new BatchInputs("m@1", "p-1", List.of("r-1"), List.of("e-1")));
    assertThat(regime.evaluate(event, contextOf(regime, built)).decision().decisionContextId())
        .isEqualTo(built.id());
    assertThat(built).isEqualTo(inBounds);
    // the provider's own context (versions "none") is unaffected
    assertThat(decide(regime, event).decisionContextId()).isEqualTo(own.id());
  }

  private static DecisionContext withBatch(
      DecisionContext own,
      String mappingVersion,
      String firmProfileVersion,
      List<String> referenceSnapshotIds,
      List<String> enrichmentSnapshotIds) {
    return new DecisionContext(
        own.packDigest(),
        own.overlayDigest(),
        own.interpretationPins(),
        referenceSnapshotIds,
        enrichmentSnapshotIds,
        firmProfileVersion,
        mappingVersion,
        own.engineBuild(),
        own.executor(),
        own.circuitEpoch());
  }

  @Test
  void contextForStampsTheFourBatchComponentsAndKeepsTheRest() {
    PackRegime regime = create(SamplePacks.load(), REPORT);
    DecisionContext own = regime.decisionContext();
    BatchInputs batch =
        new BatchInputs("mapping@abc", "profile-1", List.of("ref-1", "ref-2"), List.of("enr-1"));

    DecisionContext stamped = regime.contextFor(batch);

    assertThat(stamped.mappingVersion()).isEqualTo("mapping@abc");
    assertThat(stamped.firmProfileVersion()).isEqualTo("profile-1");
    assertThat(stamped.referenceSnapshotIds()).containsExactly("ref-1", "ref-2");
    assertThat(stamped.enrichmentSnapshotIds()).containsExactly("enr-1");
    assertThat(stamped.packDigest()).isEqualTo(own.packDigest());
    assertThat(stamped.overlayDigest()).isEqualTo(own.overlayDigest());
    assertThat(stamped.interpretationPins()).isEqualTo(own.interpretationPins());
    assertThat(stamped.engineBuild()).isEqualTo(own.engineBuild());
    assertThat(stamped.executor()).isEqualTo(own.executor());
    assertThat(stamped.circuitEpoch()).isEqualTo(own.circuitEpoch());
    DecisionBody body =
        regime.evaluate(execution(AssetClass.EQUITY), contextOf(regime, stamped)).decision();
    assertThat(body.decisionContextId()).isEqualTo(stamped.id());
  }

  @Test
  void theDecisionIdChangesWithEachOfTheFourBatchComponents() {
    PackRegime regime = create(SamplePacks.load(), REPORT);
    BatchInputs base = new BatchInputs("m@1", "p-1", List.of("r-1"), List.of("e-1"));
    List<BatchInputs> variants =
        List.of(
            new BatchInputs("m@2", "p-1", List.of("r-1"), List.of("e-1")),
            new BatchInputs("m@1", "p-2", List.of("r-1"), List.of("e-1")),
            new BatchInputs("m@1", "p-1", List.of("r-2"), List.of("e-1")),
            new BatchInputs("m@1", "p-1", List.of("r-1"), List.of("e-2")));
    CanonicalExecution event = execution(AssetClass.EQUITY);
    Sha256 baseId =
        regime
            .evaluate(event, contextOf(regime, regime.contextFor(base)))
            .decision()
            .decisionContextId();

    for (BatchInputs variant : variants) {
      Sha256 id =
          regime
              .evaluate(event, contextOf(regime, regime.contextFor(variant)))
              .decision()
              .decisionContextId();
      assertThat(id).as(variant.toString()).isNotEqualTo(baseId);
    }
    assertThat(
            regime
                .evaluate(event, contextOf(regime, regime.contextFor(base)))
                .decision()
                .decisionContextId())
        .as("the same inputs give the same id")
        .isEqualTo(baseId);
  }

  @Test
  void moreThan64SnapshotIdsOrOversizeTextAreRefused() {
    List<String> sixtyFour = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      sixtyFour.add("ref-" + i);
    }
    List<String> sixtyFive = new ArrayList<>(sixtyFour);
    sixtyFive.add("ref-64");

    assertThat(new BatchInputs("m@1", "p-1", sixtyFour, sixtyFour).referenceSnapshotIds())
        .hasSize(64);
    assertThatThrownBy(() -> new BatchInputs("m@1", "p-1", sixtyFive, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referenceSnapshotIds");
    assertThatThrownBy(() -> new BatchInputs("m@1", "p-1", List.of(), sixtyFive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("enrichmentSnapshotIds");
    assertThatThrownBy(() -> new BatchInputs("m".repeat(257), "p-1", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mappingVersion");
    assertThatThrownBy(() -> new BatchInputs("m@1", "p\n1", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("firmProfileVersion")
        .hasMessageNotContaining("p\n1");
    assertThatThrownBy(() -> new BatchInputs("m@1", "p-1", List.of("x".repeat(257)), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referenceSnapshotIds");
    assertThatThrownBy(() -> new BatchInputs("m@1", " ", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("firmProfileVersion");
    assertThatThrownBy(() -> new BatchInputs("m@1", "p-1", List.of(), List.of("a\u0000b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("enrichmentSnapshotIds");
  }

  // ---- derived fields (plan W1a, task T5) ----------------------------------------------------

  private static PackRegime deriveRegime(Map<String, String> overrides) {
    return PackRegime.create(
        DeriveFixture.loadSealed(overrides), DeriveFixture.REPORT, EXECUTOR, Rulings.load());
  }

  private static RegimeProvider.Result evaluate(PackRegime regime, CanonicalExecution event) {
    return regime.evaluate(event, contextOf(regime, regime.decisionContext()));
  }

  @Test
  void aReportGivesDerivedFieldsInRowOrderWithRuleRefAndCitationsAndANonReportNone() {
    PackRegime regime = deriveRegime(Map.of());

    RegimeProvider.Result onNew = evaluate(regime, DeriveFixture.newExecution());

    assertThat(onNew.decision().outcome()).isEqualTo(Outcome.REPORT);
    assertThat(onNew.decision().attributes()).containsEntry("action", "NEWT");
    assertThat(onNew.fields())
        .extracting(RegimeProvider.DerivedField::name)
        .containsExactly(
            "executing_lei",
            "buyer",
            "seller",
            "trading_datetime",
            "quantity",
            "price",
            "venue",
            "on_venue",
            "side",
            "execution_within_firm",
            "transmission");
    RegimeProvider.DerivedField first = onNew.fields().get(0);
    assertThat(first.value()).isEqualTo("LEI-FIRM-0001");
    assertThat(first.ruleRef()).isEqualTo("DR-0001");
    assertThat(first.citations())
        .extracting(Citation::sourceId, Citation::provisionPath)
        .containsExactly(tuple("fixture-law", "fixture/1"));
    assertThat(onNew.fields().get(3).value()).isEqualTo("2026-10-30T09:30:00.123456Z");
    assertThat(onNew.fields().get(4).value()).isEqualTo("100.5");
    assertThat(onNew.fields().get(5).value()).isEqualTo("12.25");

    RegimeProvider.Result onCancel =
        evaluate(
            regime,
            DeriveFixture.execution(
                LifecycleKind.CANCEL, new BigDecimal("12.25"), Optional.empty()));
    RegimeProvider.Result onNovation =
        evaluate(
            regime,
            DeriveFixture.execution(
                LifecycleKind.NOVATION, new BigDecimal("12.25"), Optional.empty()));
    RegimeProvider.Result onAmend =
        evaluate(
            regime,
            DeriveFixture.execution(
                LifecycleKind.AMEND, new BigDecimal("12.25"), Optional.empty()));

    assertThat(onCancel.decision().outcome()).isEqualTo(Outcome.REPORT);
    assertThat(onCancel.fields())
        .extracting(RegimeProvider.DerivedField::name)
        .containsExactly("executing_lei");
    assertThat(onNovation.decision().outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(onNovation.fields()).isEmpty();
    assertThat(onAmend.decision().outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(onAmend.fields()).isEmpty();
  }

  @Test
  void aPackWithoutDeriveTablesGivesNoFieldsAndTheBudgetOfTheTablesAlone() {
    PackRegime regime = create(SamplePacks.load(), REPORT);

    RegimeProvider.Result result = evaluate(regime, execution(AssetClass.EQUITY));

    assertThat(result.decision().outcome()).isEqualTo(Outcome.REPORT);
    assertThat(result.fields()).isEmpty();
    assertThat(regime.recordBudget()).isEqualTo(new CostBudget(40));
  }

  @Test
  void aDeriveErrorAfterAReportHitGivesUndeterminedExecError() {
    String cite = "cite: [{source_id: fixture-law, provision_path: fixture/1, role: BASIS}]";
    Map<String, String> files =
        Map.of(
            DeriveFixture.DERIVE,
            "table: {id: derive.fixture.report}\nrows:\n"
                + "  - {id: D-OK, field: executing_lei, value: \"firm.lei\", rule_ref: DR-0001, "
                + cite
                + "}\n"
                + "  - {id: D-CP, field: counterparty, value: \"trade.counterparty\","
                + " rule_ref: DR-0002, "
                + cite
                + "}\n");
    PackRegime regime = deriveRegime(files);

    RegimeProvider.Result failed = evaluate(regime, DeriveFixture.withoutCounterparty());
    RegimeProvider.Result fine = evaluate(regime, DeriveFixture.newExecution());

    assertThat(failed.decision().outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(failed.decision().reasonCode().value()).isEqualTo("EXEC_ERROR");
    assertThat(failed.decision().node()).isEqualTo("EXEC");
    assertThat(failed.decision().flags())
        .contains("rule_id=D-CP", "error_kind=ABSENT")
        .noneMatch(flag -> flag.contains("LEI-"));
    assertThat(failed.decision().ruleIdsHit()).isEmpty();
    assertThat(failed.decision().legs()).isEmpty();
    assertThat(failed.fields()).isEmpty();
    assertThat(fine.decision().outcome()).isEqualTo(Outcome.REPORT);
    assertThat(fine.fields())
        .extracting(RegimeProvider.DerivedField::name)
        .containsExactly("executing_lei", "counterparty");
  }

  @Test
  void aDeriveRowThatRunsOutOfBudgetGivesUndeterminedExecBudget() {
    PackRegime regime = deriveRegime(Map.of());
    EvaluationContext tight =
        new EvaluationContext(
            regime.decisionContext(),
            new IngestKey("toy-source", "rec-1", 1),
            Instant.parse("2026-10-30T10:00:00Z"),
            new CostBudget(20));

    RegimeProvider.Result result = regime.evaluate(DeriveFixture.newExecution(), tight);

    assertThat(result.decision().outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(result.decision().reasonCode().value()).isEqualTo("EXEC_BUDGET");
    assertThat(result.decision().flags()).anyMatch(flag -> flag.startsWith("rule_id=D-"));
    assertThat(result.fields()).isEmpty();
  }

  @Test
  void theRecordBudgetOfAPackWithDeriveTablesCoversTheDeriveRows() {
    PackRegime regime = deriveRegime(Map.of());

    RegimeProvider.Result result = evaluate(regime, DeriveFixture.newExecution());

    ExecutionResult<List<DeriveProgram.Derived>> derive =
        EXECUTOR.execute(
            new DeriveProgram(regime.compiled()),
            new CanonicalBindings(DeriveFixture.newExecution()),
            new CostBudget(1_000_000));
    long deriveUnits = ((Completed<List<DeriveProgram.Derived>>) derive).costUnits();
    assertThat(regime.recordBudget().maxUnits())
        .as("at least the scope budget")
        .isGreaterThanOrEqualTo(regime.compiled().budget().perRecordUnits());
    assertThat(regime.recordBudget().maxUnits())
        .as("and at least what the derive rows really cost")
        .isGreaterThanOrEqualTo(deriveUnits);
    assertThat(result.decision().outcome()).isEqualTo(Outcome.REPORT);
    assertThat(result.fields()).hasSize(11);
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
