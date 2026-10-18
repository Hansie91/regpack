package dev.regpack.engine.expr.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.expr.InHouseExpression;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.expr.conformance.ConformanceHarness.Bound;
import dev.regpack.engine.expr.conformance.ConformanceHarness.Layer;
import dev.regpack.engine.expr.conformance.ConformanceHarness.Outcome;
import dev.regpack.engine.expr.conformance.ConformanceHarness.Result;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The conformance vectors run on the production layer, and the harness itself has teeth. */
class ConformanceSuiteTest {
  private static final Layer ALWAYS_FALSE =
      (expression, bound, budget, ceiling) -> new Outcome.Ok(new BoolValue(false));
  private static final Layer UNSUPPORTED =
      (expression, bound, budget, ceiling) -> new Outcome.Unsupported("cannot express");

  private static Outcome run(String expression, String bindings) {
    return ConformanceHarness.production(Rulings.load())
        .evaluate(
            ConformanceHarness.expand(expression),
            ConformanceHarness.decode(bindings),
            1_000_000L,
            0L);
  }

  @Test
  void everyVectorPassesOnTheProductionLayer() {
    List<Vector> vectors = VectorFile.load();
    List<Result> results =
        ConformanceHarness.run(
            ConformanceHarness.production(Rulings.load()), vectors, RulingsFile.expectations());
    Map<String, Map<String, Long>> counts =
        results.stream()
            .collect(
                Collectors.groupingBy(
                    Result::vectorClass,
                    TreeMap::new,
                    Collectors.groupingBy(Result::result, TreeMap::new, Collectors.counting())));
    System.out.println("conformance results per class: " + counts);
    assertThat(results).hasSize(46);
    assertThat(results.stream().filter(r -> !r.result().equals("PASS")))
        .as("failing vectors")
        .isEmpty();
    assertThat(ConformanceHarness.failures(results, "MUST", "SHOULD", "DECIDE", "SPIKE-DESIGN"))
        .isZero();
  }

  @Test
  void theHarnessHasTeeth() {
    List<Vector> vectors = VectorFile.load();
    Map<String, String> rulings = RulingsFile.expectations();
    List<Result> constant = ConformanceHarness.run(ALWAYS_FALSE, vectors, rulings);
    for (int i = 0; i < vectors.size(); i++) {
      Vector v = vectors.get(i);
      boolean expectsFalse = ConformanceHarness.expectedOf(v, rulings).equals("F");
      assertThat(constant.get(i).result()).as(v.id()).isEqualTo(expectsFalse ? "PASS" : "FAIL");
    }
    // the layer that answers false is right on very few rows, so a green suite means something
    assertThat(constant.stream().filter(r -> r.result().equals("PASS")).count())
        .isLessThanOrEqualTo(5L);
    List<Result> unsupported = ConformanceHarness.run(UNSUPPORTED, vectors, rulings);
    assertThat(unsupported).hasSize(46).allMatch(r -> r.result().equals("UNSUPPORTED"));
    // a layer that throws is a FAIL, not a crash of the suite
    Layer throwing =
        (expression, bound, budget, ceiling) -> {
          throw new IllegalStateException("boom");
        };
    assertThat(ConformanceHarness.run(throwing, vectors, rulings))
        .allMatch(r -> r.result().equals("FAIL") && r.actual().contains("boom"));
  }

  @Test
  void errorKindsAreComparedNotMessages() {
    assertThat(run("dec('1') / dec('0')", "")).isEqualTo(new Outcome.Error("DIV_ZERO"));
    assertThat(run("1 / 0", "")).isEqualTo(new Outcome.Error("DIV_ZERO"));
    assertThat(ConformanceHarness.matches("ERR:DIV_ZERO", run("1 / 0", ""))).isTrue();
    assertThat(ConformanceHarness.matches("ERR:TYPE", run("1 / 0", ""))).isFalse();
    assertThat(ConformanceHarness.matches("ERR", run("1 / 0", ""))).isTrue();
    assertThat(ConformanceHarness.matches("ERR", new Outcome.Ok(new BoolValue(true)))).isFalse();
    assertThat(ConformanceHarness.matches("ERR", new Outcome.CompileError("x"))).isFalse();
    assertThat(ConformanceHarness.matches("COMPILE_ERROR", run("null", ""))).isTrue();
    assertThat(ConformanceHarness.matches("ERR:BUDGET", run("1 + 2 + 3", ""))).isFalse();
    // a budget overrun is its own kind, never an expression error kind
    Outcome budget =
        ConformanceHarness.production(Rulings.load())
            .evaluate("1 + 2", ConformanceHarness.decode(""), 2L, 0L);
    assertThat(budget).isEqualTo(new Outcome.Error("BUDGET"));
    // the unsupported answer satisfies nothing, not even ONEOF
    assertThat(ConformanceHarness.matches("ONEOF:T|F|ERR", new Outcome.Unsupported("x"))).isFalse();
  }

  @Test
  void oneOfAcceptsEitherOutcome() {
    String expected = "ONEOF:T|COMPILE_ERROR";
    assertThat(ConformanceHarness.matches(expected, new Outcome.Ok(new BoolValue(true)))).isTrue();
    assertThat(ConformanceHarness.matches(expected, new Outcome.CompileError("no"))).isTrue();
    assertThat(ConformanceHarness.matches(expected, new Outcome.Ok(new BoolValue(false))))
        .isFalse();
    assertThat(ConformanceHarness.matches(expected, new Outcome.Error("TYPE"))).isFalse();
    assertThat(ConformanceHarness.matches("ONEOF:COMPILE_ERROR|ERR", new Outcome.Error("TYPE")))
        .isTrue();
    assertThat(ConformanceHarness.matches("i:3", new Outcome.Ok(new IntValue(3)))).isTrue();
    assertThat(ConformanceHarness.matches("s:ab", new Outcome.Ok(new StringValue("ab")))).isTrue();
  }

  @Test
  void expansionOfGenAndChain() {
    assertThat(ConformanceHarness.expand("@chain(3)")).isEqualTo("1 + 1 + 1");
    assertThat(ConformanceHarness.expand("1 + 2")).isEqualTo("1 + 2");
    Bound bound =
        ConformanceHarness.decode(
            "big=s:@gen(5);items=l:@gen(3);mixed=l:i:1|s:x;empty=l:;gone=absent:DECIMAL;kind=e:K.NEW");
    assertThat(bound.values().get("big")).isEqualTo(new StringValue("aaaaa"));
    assertThat(bound.values().get("items")).isInstanceOf(ListValue.class);
    assertThat(((ListValue) bound.values().get("items")).items()).hasSize(3);
    assertThat(((ListValue) bound.values().get("mixed")).items())
        .containsExactly(new IntValue(1), new StringValue("x"));
    assertThat(((ListValue) bound.values().get("empty")).items()).isEmpty();
    assertThat(bound.values()).doesNotContainKey("gone");
    assertThat(bound.declared())
        .containsEntry("gone", ValueType.DECIMAL)
        .containsEntry("kind", ValueType.ENUM)
        .containsEntry("items", ValueType.LIST)
        .containsEntry("big", ValueType.STRING);
    assertThat(ConformanceHarness.decode("").declared()).isEmpty();
  }

  @Test
  void costFixturesChargeTheDocumentedUnits() {
    for (CostFixtures.Fixture fixture : CostFixtures.load()) {
      Bound bound = ConformanceHarness.decode(fixture.bindings());
      var compiled =
          new InHouseExpression()
              .compile(ConformanceHarness.expand(fixture.expression()), bound.declared());
      CostMeter meter = new CostMeter(new CostBudget(1_000_000_000L));
      Map<String, Value> values = bound.values();
      compiled.evaluate(path -> Optional.ofNullable(values.get(path)), meter);
      assertThat(meter.used()).as(fixture.expression()).isEqualTo(fixture.units());
    }
  }
}
