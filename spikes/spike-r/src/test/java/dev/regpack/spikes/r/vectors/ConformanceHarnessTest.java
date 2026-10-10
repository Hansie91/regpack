package dev.regpack.spikes.r.vectors;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ConformanceHarnessTest {
  /** A layer whose answer is a function of the expression, for exercising the harness. */
  private record FakeLayer(
      Function<String, Outcome> direct, Function<String, Optional<String>> rewrite)
      implements ExpressionLayer {
    @Override
    public String name() {
      return "fake";
    }

    @Override
    public Outcome evaluate(
        String expression, Map<String, Value> bindings, long budget, long ceiling) {
      return direct.apply(expression);
    }

    @Override
    public Optional<String> rewriteFor(String vectorId) {
      return rewrite.apply(vectorId);
    }
  }

  private static ExpressionLayer constant(Outcome outcome) {
    return new FakeLayer(e -> outcome, id -> Optional.empty());
  }

  private static Vector vector(String id, String vectorClass, String expression, String expected) {
    return new Vector(
        id,
        vectorClass,
        expression,
        Map.of(),
        expected,
        OptionalLong.empty(),
        OptionalLong.empty());
  }

  private static boolean acceptsFalse(String expected) {
    return expected.equals("F")
        || (expected.startsWith("ONEOF:")
            && Arrays.asList(expected.substring(6).split("\\|")).contains("F"));
  }

  @Test
  void reportsFailuresPerVector() {
    List<Vector> vectors = VectorFile.load();
    List<VectorResult> results =
        ConformanceHarness.run(constant(new Outcome.Ok(new BoolValue(false))), vectors);
    assertThat(results).hasSize(47);
    int fails = 0;
    for (int i = 0; i < vectors.size(); i++) {
      Vector v = vectors.get(i);
      VectorResult r = results.get(i);
      assertThat(r.id()).isEqualTo(v.id());
      assertThat(r.vectorClass()).isEqualTo(v.vectorClass());
      assertThat(r.result()).as(v.id()).isEqualTo(acceptsFalse(v.expected()) ? "PASS" : "FAIL");
      if (r.result().equals("FAIL")) {
        fails++;
      }
    }
    assertThat(fails).as("a constant-false layer must fail most vectors").isGreaterThan(35);
    assertThat(ConformanceHarness.failures(results, "MUST")).isGreaterThan(25);
  }

  @Test
  void errorKindsAreComparedNotMessages() {
    assertThat(ConformanceHarness.matches("ERR:DIV_ZERO", new Outcome.Error("DIV_ZERO"))).isTrue();
    assertThat(ConformanceHarness.matches("ERR:DIV_ZERO", new Outcome.Error("ABSENT"))).isFalse();
    assertThat(ConformanceHarness.matches("ERR", new Outcome.Error("OTHER"))).isTrue();
    assertThat(ConformanceHarness.matches("ERR", new Outcome.CompileError("boom"))).isFalse();
    assertThat(ConformanceHarness.matches("ERR:PARSE", new Outcome.Ok(new BoolValue(true))))
        .isFalse();
    assertThat(ConformanceHarness.matches("COMPILE_ERROR", new Outcome.CompileError("any reason")))
        .isTrue();
    assertThat(ConformanceHarness.matches("COMPILE_ERROR", new Outcome.Error("PARSE"))).isFalse();
    assertThat(ConformanceHarness.matches("ERR", new Outcome.Unsupported("no"))).isFalse();
    assertThat(ConformanceHarness.matches("COMPILE_ERROR", new Outcome.Unsupported("no")))
        .isFalse();
  }

  @Test
  void valueExpectationsAreMatchedByType() {
    assertThat(ConformanceHarness.matches("T", new Outcome.Ok(new BoolValue(true)))).isTrue();
    assertThat(ConformanceHarness.matches("T", new Outcome.Ok(new BoolValue(false)))).isFalse();
    assertThat(ConformanceHarness.matches("F", new Outcome.Ok(new BoolValue(false)))).isTrue();
    assertThat(ConformanceHarness.matches("i:42", new Outcome.Ok(new IntValue(42)))).isTrue();
    assertThat(ConformanceHarness.matches("i:42", new Outcome.Ok(new StringValue("42")))).isFalse();
    assertThat(
            ConformanceHarness.matches(
                "d:12.345", new Outcome.Ok(new Value.DecimalValue(new BigDecimal("12.3450")))))
        .isTrue();
    assertThat(ConformanceHarness.matches("s:12.5", new Outcome.Ok(new StringValue("12.5"))))
        .isTrue();
    assertThat(ConformanceHarness.matches("s:12.5", new Outcome.Ok(new StringValue("12.50"))))
        .isFalse();
    assertThat(
            ConformanceHarness.matches(
                "t:2026-10-09T09:30:00Z",
                new Outcome.Ok(
                    new Value.TimestampValue(java.time.Instant.parse("2026-10-09T09:30:00Z")))))
        .isTrue();
  }

  @Test
  void oneOfExpectationsAcceptEitherOutcome() {
    List<Vector> d12 = VectorFile.load().stream().filter(v -> v.id().equals("D12")).toList();
    assertThat(d12).hasSize(1);
    assertThat(ConformanceHarness.run(constant(new Outcome.Ok(new BoolValue(true))), d12))
        .extracting(VectorResult::result)
        .containsExactly("PASS");
    assertThat(ConformanceHarness.run(constant(new Outcome.CompileError("double")), d12))
        .extracting(VectorResult::result)
        .containsExactly("PASS");
    assertThat(ConformanceHarness.run(constant(new Outcome.Ok(new BoolValue(false))), d12))
        .extracting(VectorResult::result)
        .containsExactly("FAIL");
    assertThat(ConformanceHarness.run(constant(new Outcome.Error("OTHER")), d12))
        .extracting(VectorResult::result)
        .containsExactly("FAIL");
  }

  @Test
  void unsupportedCountsAsAFailureForMustAndShould() {
    List<VectorResult> results =
        ConformanceHarness.run(
            constant(new Outcome.Unsupported("no such feature")), VectorFile.load());
    assertThat(results).extracting(VectorResult::result).containsOnly("UNSUPPORTED");
    assertThat(ConformanceHarness.failures(results, "MUST")).isEqualTo(38);
    assertThat(ConformanceHarness.failures(results, "MUST", "SHOULD")).isEqualTo(41);
    assertThat(ConformanceHarness.failures(results, "DECIDE")).isEqualTo(6);
    assertThat(results.get(0).actual()).contains("no such feature");
  }

  @Test
  void functionalRewriteIsClassifiedNotHidden() {
    Vector d01 = vector("D01", "MUST", "1 + 1 == 2", "T");
    Vector d02 = vector("D02", "MUST", "2 + 2 == 4", "T");
    Vector d03 = vector("D03", "MUST", "3 + 3 == 6", "T");
    Function<String, Outcome> direct =
        e ->
            switch (e) {
              case "3 + 3 == 6" -> new Outcome.Ok(new BoolValue(true));
              case "add(1, 1) == 2" -> new Outcome.Ok(new BoolValue(true));
              case "add(2, 2) == 4" -> new Outcome.Ok(new BoolValue(false));
              default -> new Outcome.CompileError("operator not supported");
            };
    Function<String, Optional<String>> rewrite =
        id ->
            switch (id) {
              case "D01" -> Optional.of("add(1, 1) == 2");
              case "D02" -> Optional.of("add(2, 2) == 4");
              case "D03" -> Optional.of("add(3, 3) == 6");
              default -> Optional.empty();
            };
    List<VectorResult> results =
        ConformanceHarness.run(new FakeLayer(direct, rewrite), List.of(d01, d02, d03));
    assertThat(results.get(0).result()).isEqualTo("FUNCTIONAL");
    assertThat(results.get(0).actual()).contains("add(1, 1) == 2");
    assertThat(results.get(1).result())
        .as("a rewrite that is still wrong stays a failure")
        .isEqualTo("FAIL");
    assertThat(results.get(2).result()).as("a direct pass is never FUNCTIONAL").isEqualTo("PASS");
    assertThat(ConformanceHarness.failures(results, "MUST")).isEqualTo(1);
  }

  @Test
  void aLayerThatThrowsIsAFailureNotACrash() {
    ExpressionLayer thrower =
        new FakeLayer(
            e -> {
              throw new IllegalStateException("layer bug");
            },
            id -> Optional.empty());
    List<VectorResult> results =
        ConformanceHarness.run(thrower, List.of(vector("X1", "MUST", "true", "T")));
    assertThat(results.get(0).result()).isEqualTo("FAIL");
    assertThat(results.get(0).actual()).contains("IllegalStateException");
  }

  @Test
  void expandsGenAndChainMarkers() {
    List<String> seenExpressions = new ArrayList<>();
    List<Map<String, Value>> seenBindings = new ArrayList<>();
    List<long[]> seenLimits = new ArrayList<>();
    ExpressionLayer recorder =
        new ExpressionLayer() {
          @Override
          public String name() {
            return "recorder";
          }

          @Override
          public Outcome evaluate(
              String expression, Map<String, Value> bindings, long budget, long ceiling) {
            seenExpressions.add(expression);
            seenBindings.add(bindings);
            seenLimits.add(new long[] {budget, ceiling});
            return new Outcome.Unsupported("recorder");
          }
        };
    List<Vector> all = VectorFile.load();
    ConformanceHarness.run(recorder, all);
    int c01 = indexOf(all, "C01");
    int c02 = indexOf(all, "C02");
    int c03 = indexOf(all, "C03");
    assertThat(seenBindings.get(c01).get("items")).isInstanceOf(ListValue.class);
    assertThat(((ListValue) seenBindings.get(c01).get("items")).items()).hasSize(10_001);
    assertThat(((StringValue) seenBindings.get(c02).get("big")).value()).hasSize(1_048_576);
    assertThat(seenExpressions.get(c03).split(" \\+ ")).hasSize(3_000).containsOnly("1");
    assertThat(seenLimits.get(c01)[0]).isEqualTo(10_000L);
    assertThat(seenLimits.get(c03)[1]).isEqualTo(1_000L);
    assertThat(seenLimits.get(0)[0])
        .as("default budget")
        .isEqualTo(ConformanceHarness.DEFAULT_BUDGET);
    assertThat(seenLimits.get(0)[1])
        .as("default ceiling")
        .isEqualTo(ConformanceHarness.DEFAULT_CEILING);
  }

  @Test
  void typedBindingPrefixesAreDecoded() {
    Vector v =
        new Vector(
            "B1",
            "MUST",
            "true",
            Map.of(
                "a", "s:text",
                "b", "i:7",
                "c", "d:1.50",
                "d", "b:true",
                "e", "t:2026-10-09T09:30:00Z",
                "f", "D:2026-10-09",
                "g", "e:LifecycleKind.NEW",
                "h", "l:[\"i:1\",\"s:x\"]"),
            "T",
            OptionalLong.empty(),
            OptionalLong.empty());
    List<Map<String, Value>> seen = new ArrayList<>();
    ExpressionLayer recorder =
        new ExpressionLayer() {
          @Override
          public String name() {
            return "recorder";
          }

          @Override
          public Outcome evaluate(
              String expression, Map<String, Value> bindings, long budget, long ceiling) {
            seen.add(bindings);
            return new Outcome.Ok(new BoolValue(true));
          }
        };
    ConformanceHarness.run(recorder, List.of(v));
    Map<String, Value> b = seen.get(0);
    assertThat(b.get("a")).isEqualTo(new StringValue("text"));
    assertThat(b.get("b")).isEqualTo(new IntValue(7));
    assertThat(((Value.DecimalValue) b.get("c")).value().scale()).isEqualTo(2);
    assertThat(b.get("d")).isEqualTo(new BoolValue(true));
    assertThat(b.get("e"))
        .isEqualTo(new Value.TimestampValue(java.time.Instant.parse("2026-10-09T09:30:00Z")));
    assertThat(b.get("f")).isEqualTo(new Value.DateValue(java.time.LocalDate.parse("2026-10-09")));
    assertThat(b.get("g")).isEqualTo(new Value.EnumValue("LifecycleKind", "NEW"));
    assertThat(b.get("h")).isEqualTo(new ListValue(List.of(new IntValue(1), new StringValue("x"))));
  }

  private static int indexOf(List<Vector> vectors, String id) {
    for (int i = 0; i < vectors.size(); i++) {
      if (vectors.get(i).id().equals(id)) {
        return i;
      }
    }
    throw new AssertionError(id);
  }
}
