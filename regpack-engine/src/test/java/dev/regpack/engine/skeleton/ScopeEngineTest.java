package dev.regpack.engine.skeleton;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.InputDigest;
import dev.regpack.model.decision.PathStep;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.ports.regime.RegimeProvider.Descriptor;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The three scope rules and the catch-all run in memory to decision bodies. No clock is read. */
class ScopeEngineTest {
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");
  private static final Instant EVALUATED_AT = Instant.parse("2026-10-09T10:00:00Z");
  private static final IngestKey INGEST_KEY = new IngestKey("toy-source", "rec-1", 1);

  private final WalkingSkeletonRegime regime = WalkingSkeletonRegime.standard();

  private static CanonicalExecution execution(
      LifecycleKind kind, AssetClass assetClass, boolean tradedOnVenue, PartyKind clientKind) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        kind,
        TRADED_AT,
        assetClass,
        "ZZ0000000001",
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

  private static EvaluationContext context(long budget) {
    return new EvaluationContext(
        WalkingSkeletonRegime.skeletonContext(), INGEST_KEY, EVALUATED_AT, new CostBudget(budget));
  }

  private static DecisionBody decide(
      WalkingSkeletonRegime provider, CanonicalExecution e, long budget) {
    return provider.evaluate(e, context(budget)).decision();
  }

  private DecisionBody decide(CanonicalExecution e) {
    return decide(regime, e, 100);
  }

  @Test
  void novationIsExcludedAndStopsAtTheFirstRow() {
    DecisionBody body =
        decide(execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY));

    assertThat(body.outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("MIFIR.N03.EXCLUDED"));
    assertThat(body.node()).isEqualTo("N03");
    assertThat(body.ruleIdsHit()).containsExactly("S-N03-novation");
    assertThat(body.path()).containsExactly(new PathStep("S-N03-novation", true, 3));
    assertThat(body.legs()).isEmpty();
    assertThat(body.flags()).isEmpty();
  }

  @Test
  void naturalPersonIsUndeterminedNeverDoNotReport() {
    DecisionBody body =
        decide(execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("MIFIR.N13.NATURAL_PERSON"));
    assertThat(body.ruleIdsHit()).containsExactly("S-D14-natural-person");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 3),
            new PathStep("S-D14-natural-person", true, 2));
    assertThat(body.legs()).isEmpty();
  }

  @Test
  void equityOnVenueIsReportedWithMarketLeg() {
    DecisionBody body = decide(equityOnVenue());

    assertThat(body.outcome()).isEqualTo(Outcome.REPORT);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("MIFIR.N08.IN_SCOPE_26_2_A"));
    assertThat(body.node()).isEqualTo("N08");
    assertThat(body.ruleIdsHit()).containsExactly("S-N08-equity-on-venue");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 3),
            new PathStep("S-D14-natural-person", false, 2),
            new PathStep("S-N08-equity-on-venue", true, 3));
    assertThat(body.legs()).containsExactly(Leg.MARKET);
    assertThat(body.citations()).hasSize(1);
    assertThat(body.citations().get(0).sourceId()).isEqualTo("mifir-placeholder");
  }

  @Test
  void unmatchedInstrumentFallsToCatchAllUndetermined() {
    DecisionBody body =
        decide(execution(LifecycleKind.NEW, AssetClass.DEBT, true, PartyKind.LEGAL_ENTITY));

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("DEFAULT.NO_RULE_HIT"));
    assertThat(body.node()).isEqualTo("DEFAULT");
    assertThat(body.ruleIdsHit()).containsExactly("S-DEFAULT-catch-all");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 3),
            new PathStep("S-D14-natural-person", false, 2),
            new PathStep("S-N08-equity-on-venue", false, 3),
            new PathStep("S-DEFAULT-catch-all", true, 1));
  }

  @Test
  void exclusionPrecedesPartyRule() {
    DecisionBody body =
        decide(
            execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));

    assertThat(body.outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(body.ruleIdsHit()).containsExactly("S-N03-novation");
  }

  @Test
  void budgetExhaustionYieldsUndeterminedExecBudget() {
    DecisionBody body = decide(regime, equityOnVenue(), 5);

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_BUDGET"));
    assertThat(body.node()).isEqualTo("EXEC");
    assertThat(body.flags())
        .containsExactly("rule_id=S-N08-equity-on-venue", "cost_units=5", "limit=5");
    assertThat(body.ruleIdsHit()).isEmpty();
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 3),
            new PathStep("S-D14-natural-person", false, 2));
    assertThat(body.legs()).isEmpty();
    assertThat(body.citations()).isEqualTo(WalkingSkeletonRules.rows().get(3).citations());
  }

  @Test
  void expressionErrorYieldsUndeterminedExecError() {
    Expression failing =
        new Expression() {
          private final Expression delegate = new SkeletonExpression();

          @Override
          public CompiledExpression compile(String source, Map<String, ValueType> variables) {
            CompiledExpression compiled = delegate.compile(source, variables);
            if (!source.startsWith("client.kind")) {
              return compiled;
            }
            return new CompiledExpression() {
              @Override
              public String source() {
                return compiled.source();
              }

              @Override
              public Value evaluate(Bindings bindings, CostMeter meter) {
                throw new ExpressionException("injected");
              }
            };
          }
        };

    DecisionBody body =
        decide(new WalkingSkeletonRegime(new MeteredExecutor(), failing), equityOnVenue(), 100);

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    assertThat(body.node()).isEqualTo("EXEC");
    assertThat(body.flags())
        .containsExactly(
            "rule_id=S-D14-natural-person", "error_class=ExpressionException", "cost_units=4");
    assertThat(body.ruleIdsHit()).isEmpty();
    assertThat(body.path()).containsExactly(new PathStep("S-N03-novation", false, 3));
    assertThat(body.citations()).isEqualTo(WalkingSkeletonRules.rows().get(3).citations());
  }

  @Test
  void nonBoolWhenResultFailsClosedAsExecError() {
    Expression wrongType =
        new Expression() {
          private final Expression delegate = new SkeletonExpression();

          @Override
          public CompiledExpression compile(String source, Map<String, ValueType> variables) {
            CompiledExpression compiled = delegate.compile(source, variables);
            if (!source.startsWith("client.kind")) {
              return compiled;
            }
            return new CompiledExpression() {
              @Override
              public String source() {
                return compiled.source();
              }

              @Override
              public Value evaluate(Bindings bindings, CostMeter meter) {
                return new Value.StringValue("true");
              }
            };
          }
        };

    DecisionBody body =
        decide(new WalkingSkeletonRegime(new MeteredExecutor(), wrongType), equityOnVenue(), 100);

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    assertThat(body.node()).isEqualTo("EXEC");
    assertThat(body.flags())
        .containsExactly(
            "rule_id=S-D14-natural-person", "error_class=ExpressionException", "cost_units=4");
    assertThat(body.ruleIdsHit()).isEmpty();
    assertThat(body.path()).containsExactly(new PathStep("S-N03-novation", false, 3));
  }

  @Test
  void rowsAreInAscendingPriorityWithUniqueIdsAndACatchAllLast() {
    List<WalkingSkeletonRules.Row> rows = WalkingSkeletonRules.rows();

    for (int i = 1; i < rows.size(); i++) {
      assertThat(rows.get(i).priority()).isGreaterThan(rows.get(i - 1).priority());
    }
    assertThat(rows).extracting(WalkingSkeletonRules.Row::id).doesNotHaveDuplicates();
    WalkingSkeletonRules.Row last = rows.get(rows.size() - 1);
    assertThat(last.when()).isEqualTo("true");
    assertThat(last.node()).isEqualTo("DEFAULT");
  }

  @Test
  void sameInputGivesSameReplayDigestOneThousandTimes() {
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
    DecisionContext decisionContext = WalkingSkeletonRegime.skeletonContext();

    DecisionBody body = decide(execution);

    assertThat(body.decisionContextId()).isEqualTo(decisionContext.id());
    assertThat(body.inputDigest()).isEqualTo(InputDigest.of(execution, INGEST_KEY));
    assertThat(body.eventIdentity()).isEqualTo(execution.eventIdentity());
    assertThat(body.regime()).isEqualTo(WalkingSkeletonRegime.REGIME);
    assertThat(body.asAt()).isEqualTo(TRADED_AT);
    assertThat(body.evaluatedAt()).isEqualTo(EVALUATED_AT);
    assertThat(decisionContext.executor()).isEqualTo(MeteredExecutor.ID);
  }

  @Test
  void descriptorNamesRegimeAndPlaceholderPack() {
    Descriptor descriptor = regime.describe();

    assertThat(descriptor.regime().id()).isEqualTo("mifir.eu.rts22");
    assertThat(descriptor.packVersion()).isEqualTo("walking-skeleton-placeholder-0");
    assertThat(descriptor.engineMin()).isEqualTo("p0");
    assertThat(WalkingSkeletonRules.authorsById())
        .containsOnlyKeys(
            "S-N03-novation",
            "S-D14-natural-person",
            "S-N08-equity-on-venue",
            "S-DEFAULT-catch-all")
        .containsValue(WalkingSkeletonRules.RULE_AUTHOR);
  }
}
