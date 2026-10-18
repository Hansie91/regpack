package dev.regpack.spikes.r.diff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.engine.skeleton.SkeletonExpression;
import dev.regpack.engine.skeleton.WalkingSkeletonRegime;
import dev.regpack.engine.skeleton.WalkingSkeletonRules;
import dev.regpack.model.IngestKey;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import dev.regpack.spikes.r.cel.CelJavaExpression;
import dev.regpack.spikes.r.diff.DifferentialCheck.Mismatch;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import dev.regpack.spikes.r.table.TestRecords;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DifferentialTest {
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");

  private record Golden(TradeRecord record, CanonicalExecution execution) {}

  private static Golden golden(
      long seq, LifecycleKind kind, AssetClass assetClass, PartyKind clientKind) {
    TradeRecord record =
        TestRecords.record(seq, kind.name(), assetClass.name(), true, clientKind.name());
    CanonicalExecution execution =
        new CanonicalExecution(
            "LEI-FIRM-0001",
            "XPAR",
            "EX-" + seq,
            kind,
            TRADED_AT,
            assetClass,
            "ZZ0000000001",
            true,
            clientKind,
            clientKind == PartyKind.LEGAL_ENTITY
                ? Optional.of("LEI-CLIENT-0001")
                : Optional.empty(),
            new BigDecimal("100"),
            new BigDecimal("12.25"),
            "EUR");
    return new Golden(record, execution);
  }

  /** The four golden cases of the P0 scope engine test. */
  private static List<Golden> goldenCases() {
    return List.of(
        golden(1, LifecycleKind.NOVATION, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY),
        golden(2, LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.NATURAL_PERSON),
        golden(3, LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY),
        golden(4, LifecycleKind.NEW, AssetClass.DEBT, PartyKind.LEGAL_ENTITY));
  }

  /** The four rows of the P0 walking-skeleton rules as one decision table. */
  private static GeneratedPack skeletonPack() {
    List<GeneratedRule> rows = new ArrayList<>();
    for (WalkingSkeletonRules.Row row : WalkingSkeletonRules.rows()) {
      rows.add(
          new GeneratedRule(
              row.id(), 1, row.priority(), row.when(), row.outcome().name(), row.reason().value()));
    }
    return TestRecords.pack("p0-skeleton", List.of(rows));
  }

  @Test
  void inHouseAgreesWithTheP0SkeletonOnFourRules() {
    WalkingSkeletonRegime regime = WalkingSkeletonRegime.standard();
    GeneratedPack pack = skeletonPack();
    List<TradeRecord> records = new ArrayList<>();
    for (Golden g : goldenCases()) {
      records.add(g.record());
      DecisionBody body =
          regime
              .evaluate(
                  g.execution(),
                  new EvaluationContext(
                      WalkingSkeletonRegime.skeletonContext(),
                      new IngestKey("toy-source", "rec-1", 1),
                      Instant.parse("2026-10-09T10:00:00Z"),
                      new CostBudget(100)))
              .decision();
      String expected = body.outcome().name() + "/" + body.ruleIdsHit().get(0);
      assertThat(DifferentialCheck.decisionOf(new InHouseExpression(), pack, g.record()))
          .as("golden case " + g.record().seq())
          .isEqualTo(expected);
    }
    assertThat(
            DifferentialCheck.run(
                new InHouseExpression(), new SkeletonExpression(), pack, records, 10))
        .as("the in-house layer against the skeleton expression on the same four rows")
        .isEmpty();
  }

  @Test
  void inHouseAndCelAgreeOnSmallGeneratedPacks() {
    for (PackShape shape : PackShape.values()) {
      GeneratedPack pack = RulePackGen.generate(11L, 50, shape);
      TradeRecordGen gen = new TradeRecordGen(3L);
      List<TradeRecord> records = new ArrayList<>();
      for (int i = 0; i < 500; i++) {
        records.add(gen.next());
      }
      assertThat(
              DifferentialCheck.run(
                  new InHouseExpression(), new CelJavaExpression(), pack, records, 10))
          .as(shape.name())
          .isEmpty();
    }
  }

  /** A layer whose every non-catch-all {@code when} answers the given way. */
  private static Expression fake(java.util.function.Predicate<Bindings> answer) {
    return new Expression() {
      @Override
      public CompiledExpression compile(String source, Map<String, ValueType> variables) {
        return new CompiledExpression() {
          @Override
          public String source() {
            return source;
          }

          @Override
          public Value evaluate(Bindings bindings, CostMeter meter) {
            meter.charge(1);
            return new BoolValue(source.equals("true") || answer.test(bindings));
          }
        };
      }
    };
  }

  @Test
  void detectsAnInjectedDisagreement() {
    GeneratedPack pack =
        TestRecords.pack(
            "injected",
            List.of(
                List.of(
                    TestRecords.rule("HIT", 1, 100, "venue.mic == 'XAMS'", "REPORT"),
                    TestRecords.rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    Expression never = fake(b -> false);
    Expression onlyAmsterdam =
        fake(b -> b.get("venue.mic").map(v -> v.equals(new StringValue("XAMS"))).orElse(false));
    List<TradeRecord> records =
        List.of(
            TestRecords.record(1, "NEW", "EQUITY", true, "LEGAL_ENTITY", "XPAR"),
            TestRecords.record(2, "NEW", "EQUITY", true, "LEGAL_ENTITY", "XAMS"),
            TestRecords.record(3, "NEW", "EQUITY", true, "LEGAL_ENTITY", "XBRU"));

    List<Mismatch> mismatches = DifferentialCheck.run(never, onlyAmsterdam, pack, records, 10);

    assertThat(mismatches).hasSize(1);
    assertThat(mismatches.get(0).seq()).isEqualTo(2L);
    assertThat(mismatches.get(0).left()).isEqualTo("UNDETERMINED/CATCH");
    assertThat(mismatches.get(0).right()).isEqualTo("REPORT/HIT");
    assertThat(DifferentialCheck.run(never, never, pack, records, 10)).isEmpty();
  }

  @Test
  void aLayerThatErrorsDisagreesWithOneThatDoesNot() {
    GeneratedPack pack =
        TestRecords.pack(
            "error",
            List.of(
                List.of(
                    TestRecords.rule("BAD", 1, 100, "trade.price / dec('0') > dec('1')", "REPORT"),
                    TestRecords.rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    List<TradeRecord> records =
        List.of(TestRecords.record(1, "NEW", "EQUITY", true, "LEGAL_ENTITY"));
    List<Mismatch> mismatches =
        DifferentialCheck.run(new InHouseExpression(), fake(b -> false), pack, records, 10);
    assertThat(mismatches).hasSize(1);
    assertThat(mismatches.get(0).left()).isEqualTo("EXEC_ERROR/BAD");
    assertThat(mismatches.get(0).right()).isEqualTo("UNDETERMINED/CATCH");
  }

  @Test
  void theLimitCapsTheNumberOfMismatchesReturned() {
    GeneratedPack pack =
        TestRecords.pack(
            "cap",
            List.of(
                List.of(
                    TestRecords.rule("HIT", 1, 100, "venue.mic == 'XAMS'", "REPORT"),
                    TestRecords.rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    List<TradeRecord> records = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      records.add(TestRecords.record(i, "NEW", "EQUITY", true, "LEGAL_ENTITY", "XAMS"));
    }
    assertThat(DifferentialCheck.run(fake(b -> false), fake(b -> true), pack, records, 3))
        .hasSize(3);
  }
}
