package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.InputDigest;
import dev.regpack.model.decision.PathStep;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The I5 suite: the sample pack runs from its tables to decision records. Every budget in here is a
 * number of cost units; no clock is read.
 */
class PackScopeEngineTest {
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");
  private static final Instant EVALUATED_AT = Instant.parse("2026-10-09T10:00:00Z");
  private static final IngestKey INGEST_KEY = new IngestKey("toy-source", "rec-1", 1);
  private static final Citation CATCH_ALL_CITATION =
      new Citation("regpack-spec", "I5", CitationRole.GUIDANCE, Optional.empty());

  private final PackRegime regime =
      PackRegime.create(
          SamplePacks.load(), "sample.s0", new MeteredExecutor(), Rulings.provisional());

  private static CanonicalExecution execution(
      LifecycleKind kind, AssetClass assetClass, boolean tradedOnVenue, PartyKind clientKind) {
    return execution(kind, assetClass, tradedOnVenue, clientKind, "ZZ0000000001");
  }

  private static CanonicalExecution execution(
      LifecycleKind kind,
      AssetClass assetClass,
      boolean tradedOnVenue,
      PartyKind clientKind,
      String isin) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        kind,
        TRADED_AT,
        assetClass,
        isin,
        tradedOnVenue,
        clientKind,
        clientKind == PartyKind.LEGAL_ENTITY ? Optional.of("LEI-CLIENT-0001") : Optional.empty(),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR");
  }

  private static CanonicalExecution equityOnVenue() {
    return execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY);
  }

  private static EvaluationContext context(PackRegime provider, long budget) {
    return new EvaluationContext(
        provider.decisionContext(), INGEST_KEY, EVALUATED_AT, new CostBudget(budget));
  }

  private static DecisionBody decide(PackRegime provider, CanonicalExecution e, long budget) {
    return provider.evaluate(e, context(provider, budget)).decision();
  }

  private DecisionBody decide(CanonicalExecution e) {
    return decide(regime, e, 100);
  }

  private static PackRegime regimeOf(LoadedPack pack) {
    return PackRegime.create(pack, "sample.s0", new MeteredExecutor(), Rulings.provisional());
  }

  @Test
  void novationIsExcludedAndStopsAtTheFirstRow() {
    DecisionBody body =
        decide(execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY));

    assertThat(body.outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N03.EXCLUDED"));
    assertThat(body.node()).isEqualTo("N03");
    assertThat(body.ruleIdsHit()).containsExactly("S-N03-novation");
    assertThat(body.path()).containsExactly(new PathStep("S-N03-novation", true, 8));
    assertThat(body.legs()).isEmpty();
    assertThat(body.flags()).isEmpty();
    assertThat(body.attributes()).isEmpty();
  }

  @Test
  void naturalPersonIsUndeterminedNeverDoNotReport() {
    DecisionBody body =
        decide(execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N13.NATURAL_PERSON"));
    assertThat(body.ruleIdsHit()).containsExactly("S-D14-natural-person");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 8),
            new PathStep("S-D14-natural-person", true, 4));
    assertThat(body.legs()).isEmpty();
  }

  @Test
  void equityOnVenueIsReportedWithMarketLeg() {
    DecisionBody body = decide(equityOnVenue());

    assertThat(body.outcome()).isEqualTo(Outcome.REPORT);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N08.IN_SCOPE"));
    assertThat(body.node()).isEqualTo("N08");
    assertThat(body.ruleIdsHit()).containsExactly("S-N08-equity-on-venue");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 8),
            new PathStep("S-D14-natural-person", false, 4),
            new PathStep("S-N08-equity-on-venue", true, 6));
    assertThat(body.legs()).containsExactly(Leg.MARKET);
    assertThat(body.attributes()).isEmpty();
    assertThat(body.citations()).hasSize(1);
    assertThat(body.citations().get(0).sourceId()).isEqualTo("sample-law");
  }

  @Test
  void unmatchedInstrumentFallsToTheCatchAll() {
    DecisionBody body =
        decide(execution(LifecycleKind.NEW, AssetClass.DEBT, true, PartyKind.LEGAL_ENTITY));

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.DEFAULT.NO_RULE_HIT"));
    assertThat(body.node()).isEqualTo("DEFAULT");
    assertThat(body.ruleIdsHit()).containsExactly("S-DEFAULT-catch-all");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 8),
            new PathStep("S-D14-natural-person", false, 4),
            new PathStep("S-N08-equity-on-venue", false, 5),
            new PathStep("S-DEFAULT-catch-all", true, 2));
  }

  @Test
  void exclusionPrecedesThePartyRule() {
    DecisionBody body =
        decide(
            execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));

    assertThat(body.outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(body.ruleIdsHit()).containsExactly("S-N03-novation");
  }

  @Test
  void budgetExhaustionYieldsUndeterminedExecBudget() {
    DecisionBody body = decide(regime, equityOnVenue(), 10);

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_BUDGET"));
    assertThat(body.node()).isEqualTo("EXEC");
    assertThat(body.flags())
        .containsExactly("rule_id=S-D14-natural-person", "cost_units=10", "limit=10");
    assertThat(body.ruleIdsHit()).isEmpty();
    assertThat(body.path()).containsExactly(new PathStep("S-N03-novation", false, 8));
    assertThat(body.legs()).isEmpty();
    assertThat(body.citations()).containsExactly(CATCH_ALL_CITATION);
  }

  @Test
  void everyFailureModeIsUndeterminedWithReasonAndCitation() {
    Map<String, String> whens =
        Map.of(
            "ABSENT", "client.lei == 'X'",
            "DIV_ZERO", "1 / 0 == 1",
            "TYPE", "1 + 1");
    for (Map.Entry<String, String> mode : whens.entrySet()) {
      PackRegime provider =
          regimeOf(
              PackBuilder.sample().scope(PackBuilder.Rule.of("R-FAIL", mode.getValue())).load());

      DecisionBody body =
          decide(
              provider,
              execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON),
              100);

      assertThat(body.outcome()).as(mode.getKey()).isEqualTo(Outcome.UNDETERMINED);
      assertThat(body.outcome()).isNotEqualTo(Outcome.DO_NOT_REPORT).isNotEqualTo(Outcome.REPORT);
      assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
      assertThat(body.node()).isEqualTo("EXEC");
      assertThat(body.ruleIdsHit()).isEmpty();
      assertThat(body.citations()).containsExactly(CATCH_ALL_CITATION);
      assertThat(body.flags()).contains("error_kind=" + mode.getKey(), "rule_id=R-FAIL");
    }
  }

  @Test
  void setIsStoredInAttributes() {
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(PackBuilder.Rule.of("R-SET", "event.kind == 'NEW'").set("action: NEWT"))
                .load());

    DecisionBody body = decide(provider, equityOnVenue(), 100);

    assertThat(body.ruleIdsHit()).containsExactly("R-SET");
    assertThat(body.attributes()).isEqualTo(Map.of("action", "NEWT"));
  }

  @Test
  void citationsCarryTheQuoteHashOfTheRegistry() {
    DecisionBody excluded =
        decide(execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY));
    assertThat(excluded.citations())
        .containsExactly(
            new Citation(
                "sample-law",
                "sample/1",
                CitationRole.BASIS,
                Optional.of(
                    new Sha256(
                        "ef008e0c36ec6f5e6a2866208eb972bc5dbfc6ccb6bd891c06e3fa61da07efef"))));

    DecisionBody natural =
        decide(execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));
    assertThat(natural.citations())
        .containsExactly(
            new Citation("regpack-spec", "D14", CitationRole.INTERPRETATION, Optional.empty()));
  }

  @Test
  void sameInputGivesTheSameReplayDigestOneThousandTimes() {
    DecisionBody first = decide(equityOnVenue());
    for (int i = 0; i < 1000; i++) {
      DecisionBody again = decide(equityOnVenue());
      assertThat(again.replayDigest()).isEqualTo(first.replayDigest());
      assertThat(again).isEqualTo(first);
    }
  }

  @Test
  void decisionIsStampedWithContextIdAndIngestKeyDigest() {
    CanonicalExecution execution = equityOnVenue();

    DecisionBody body = decide(execution);

    assertThat(body.decisionContextId()).isEqualTo(regime.decisionContext().id());
    assertThat(body.inputDigest()).isEqualTo(InputDigest.of(execution, INGEST_KEY));
    assertThat(body.eventIdentity()).isEqualTo(execution.eventIdentity());
    assertThat(body.regime()).isEqualTo(regime.regime());
    assertThat(body.regime().id()).isEqualTo("sample.s0");
    assertThat(body.asAt()).isEqualTo(TRADED_AT);
    assertThat(body.evaluatedAt()).isEqualTo(EVALUATED_AT);
  }

  @Test
  void execFlagsNeverEchoRecordValues() {
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(PackBuilder.Rule.of("R-ISIN", "instrument.isin + 1"))
                .load());

    DecisionBody body =
        decide(
            provider,
            execution(
                LifecycleKind.NEW,
                AssetClass.EQUITY,
                true,
                PartyKind.LEGAL_ENTITY,
                "SECRET-ISIN-7"),
            100);

    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    // exactly the keys of the contract (companion section 5.6), in this order
    assertThat(body.flags().stream().map(f -> f.substring(0, f.indexOf('='))).toList())
        .containsExactly("rule_id", "error_class", "error_kind", "cost_units");
    assertThat(body.toString()).doesNotContain("SECRET-ISIN-7");
    assertThat(String.join("|", body.flags())).doesNotContain("SECRET");
    assertThat(body.path()).extracting(PathStep::ruleId).noneMatch(id -> id.contains("SECRET"));
    assertThat(List.copyOf(body.citations()).toString()).doesNotContain("SECRET");
    // the PackExplainer.explainEvent assertion is added in T18, which creates the class
  }
}
