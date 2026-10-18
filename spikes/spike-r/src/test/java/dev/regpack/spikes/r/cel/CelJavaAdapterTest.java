package dev.regpack.spikes.r.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.exceptions.CelBadFormatException;
import dev.cel.common.exceptions.CelDivideByZeroException;
import dev.cel.common.exceptions.CelIndexOutOfBoundsException;
import dev.cel.common.exceptions.CelIterationLimitExceededException;
import dev.cel.common.exceptions.CelNumericOverflowException;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.spikes.r.vectors.Outcome;
import dev.regpack.spikes.r.vectors.VectorResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CelJavaAdapterTest {
  private static final CelJavaLayer LAYER = new CelJavaLayer();

  private static Outcome eval(String expression) {
    return eval(expression, Map.of());
  }

  private static Outcome eval(String expression, Map<String, Value> bindings) {
    return LAYER.evaluate(expression, bindings, 1_000_000L, 10_000L);
  }

  private static Outcome ok(Value value) {
    return new Outcome.Ok(value);
  }

  private static Outcome error(String kind) {
    return new Outcome.Error(kind);
  }

  @Test
  void doubleLiteralsAreRejected() {
    assertThat(eval("0.1 + 0.2 == 0.3")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("1.5")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("double(1)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("2u")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("uint(2)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("1 + 1 == 2")).isEqualTo(ok(new BoolValue(true)));
  }

  @Test
  void nullLiteralIsRejected() {
    assertThat(eval("null")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("event.venue == null", Map.of("event.venue", new StringValue("XPAR"))))
        .isInstanceOf(Outcome.CompileError.class);
  }

  @Test
  void absentBindingIsExplicitAbsence() {
    assertThat(eval("has(event.venue)")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("event.venue == 'X'")).isEqualTo(error("ABSENT"));
    Map<String, Value> bound = Map.of("event.venue", new StringValue("X"));
    assertThat(eval("has(event.venue)", bound)).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("event.venue == 'X'", bound)).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("has(event.venue) && event.venue == 'X'")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("has(event.venue) ? event.venue : 'NONE'"))
        .isEqualTo(ok(new StringValue("NONE")));
    assertThat(eval("has(client.lei)", Map.of("client.kind", new StringValue("NATURAL_PERSON"))))
        .isEqualTo(ok(new BoolValue(false)));
  }

  @Test
  void anUnboundBareVariableIsAbsentAndAnUndeclaredOneDoesNotCompile() {
    CelJavaExpression expression = new CelJavaExpression();
    CompiledExpression compiled =
        expression.compile("size(items) == 1", Map.of("items", ValueType.LIST));
    Bindings none = path -> Optional.empty();
    assertThatThrownBy(() -> compiled.evaluate(none, new CostMeter(new CostBudget(1_000))))
        .isInstanceOfSatisfying(
            CelJavaExpression.EvalException.class, e -> assertThat(e.kind()).isEqualTo("ABSENT"));
    assertThatThrownBy(() -> expression.compile("size(items) == 1", Map.of()))
        .isInstanceOf(ExpressionException.class)
        .isNotInstanceOf(CelJavaExpression.EvalException.class);
  }

  @Test
  void timestampsAreWholeMicrosecondsOrError() {
    assertThat(eval("isoMicros(timestamp('2026-10-09T09:30:00.123456789Z'))"))
        .isEqualTo(error("RANGE"));
    assertThat(eval("isoMicros(timestamp('2026-10-09T09:30:00.123456Z'))"))
        .isEqualTo(ok(new StringValue("2026-10-09T09:30:00.123456Z")));
    assertThat(eval("isoMicros(timestamp('2026-10-09T11:30:00+02:00'))"))
        .isEqualTo(ok(new StringValue("2026-10-09T09:30:00.000000Z")));
    Instant nanos = Instant.parse("2026-10-09T09:30:00.123456789Z");
    assertThat(eval("isoMicros(t)", Map.of("t", new TimestampValue(nanos))))
        .isEqualTo(error("RANGE"));
    Instant micros = Instant.parse("2026-10-09T09:30:00.000001Z");
    assertThat(eval("isoMicros(t)", Map.of("t", new TimestampValue(micros))))
        .isEqualTo(ok(new StringValue("2026-10-09T09:30:00.000001Z")));
    assertThat(
            eval(
                "t < timestamp('2026-10-09T09:30:00.000002Z')",
                Map.of("t", new TimestampValue(micros))))
        .isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("timestamp('2026-10-09T09:30:00')")).isEqualTo(error("PARSE"));
  }

  @Test
  void costIsDeterministic() {
    CelJavaExpression expression = new CelJavaExpression();
    CelJavaExpression.Compiled compiled =
        expression.compile(
            "size(items) > 1 && event.kind == 'NEW'",
            Map.of("items", ValueType.LIST, "event.kind", ValueType.STRING));
    Map<String, Value> bound =
        Map.of(
            "items",
            new ListValue(List.of(new StringValue("a"), new StringValue("b"))),
            "event.kind",
            new StringValue("NEW"));
    Bindings bindings = path -> Optional.ofNullable(bound.get(path));
    long first = -1;
    for (int i = 0; i < 1_000; i++) {
      CostMeter meter = new CostMeter(new CostBudget(1_000));
      assertThat(compiled.evaluate(bindings, meter)).isEqualTo(new BoolValue(true));
      if (i == 0) {
        first = meter.used();
      }
      assertThat(meter.used()).as("repeat " + i).isEqualTo(first);
    }
    // node count once, plus one unit per list item and one per 256 code points of a bound string
    assertThat(first).isEqualTo(compiled.nodeCount() + 2);
  }

  @Test
  void costIsTheNodeCountPlusSizeUnitsOfMaterialisedBindings() {
    CelJavaExpression expression = new CelJavaExpression();
    assertThat(cost(expression, "1 + 2", Map.of())).isEqualTo(3);
    assertThat(cost(expression, "size(s) == 1", Map.of("s", new StringValue("a".repeat(1_024)))))
        .as("4 nodes plus 1,024 / 256")
        .isEqualTo(4 + 4);
    assertThat(cost(expression, "size(s) == 1", Map.of("s", new StringValue("a".repeat(255)))))
        .isEqualTo(4);
    assertThat(cost(expression, "contains(items, 'x')", Map.of("items", items(10))))
        .as("3 nodes plus 10 items")
        .isEqualTo(13);
  }

  private static ListValue items(int n) {
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      items.add(new StringValue("a"));
    }
    return new ListValue(items);
  }

  private static long cost(CelJavaExpression expression, String source, Map<String, Value> bound) {
    Map<String, ValueType> types = new java.util.TreeMap<>();
    bound.forEach(
        (path, value) ->
            types.put(path, value instanceof ListValue ? ValueType.LIST : ValueType.STRING));
    CompiledExpression compiled = expression.compile(source, types);
    CostMeter meter = new CostMeter(new CostBudget(1_000_000));
    compiled.evaluate(path -> Optional.ofNullable(bound.get(path)), meter);
    return meter.used();
  }

  @Test
  void aBudgetAndACeilingAreOutcomes() {
    assertThat(
            LAYER.evaluate("contains(items, 'x')", Map.of("items", items(10_001)), 10_000, 10_000))
        .isEqualTo(error("BUDGET"));
    assertThat(LAYER.evaluate("contains(items, 'x')", Map.of("items", items(100)), 10_000, 10_000))
        .isEqualTo(ok(new BoolValue(false)));
    assertThat(LAYER.evaluate("1 + 2 + 3 + 4 + 5", Map.of(), 1_000, 5))
        .isInstanceOf(Outcome.CompileError.class);
    assertThat(LAYER.evaluate("1 + 2 + 3 + 4 + 5", Map.of(), 1_000, 100))
        .isEqualTo(ok(new IntValue(15)));
  }

  @Test
  void noLocaleDependenceUnderTurkishLocale() {
    // the test JVM runs with the Turkish locale (root pom test.argLine): a default-locale
    // toUpperCase of "title" would give a dotted capital I
    assertThat(eval("upper('title') == 'TITLE'")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("lower('TITLE I') == 'title i'")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("dayOfWeek(date('2026-10-09')) == 'FRIDAY'"))
        .isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("date('2026-02-29')")).isEqualTo(error("PARSE"));
    assertThat(eval("string(dec('12.50'))")).isEqualTo(ok(new StringValue("12.5")));
  }

  @Test
  void decimalSupportIsClassified() {
    List<VectorResult> real =
        dev.regpack.spikes.r.vectors.ConformanceHarness.run(
            LAYER, dev.regpack.spikes.r.vectors.VectorFile.load());
    assertThat(CelJavaLayer.decimalSupport(real)).isEqualTo("NATIVE");

    assertThat(CelJavaLayer.decimalSupport(decimalResults("PASS"))).isEqualTo("NATIVE");
    assertThat(CelJavaLayer.decimalSupport(decimalResults("FUNCTIONAL"))).isEqualTo("FUNCTIONAL");
    assertThat(CelJavaLayer.decimalSupport(decimalResults("FAIL"))).isEqualTo("UNSUPPORTED");
    List<VectorResult> oneFunctional = decimalResults("PASS");
    oneFunctional.set(2, new VectorResult("D03", "MUST", "FUNCTIONAL", "via rewrite"));
    assertThat(CelJavaLayer.decimalSupport(oneFunctional)).isEqualTo("FUNCTIONAL");
    oneFunctional.set(4, new VectorResult("D05", "MUST", "FAIL", "wrong"));
    assertThat(CelJavaLayer.decimalSupport(oneFunctional)).isEqualTo("UNSUPPORTED");
    assertThat(CelJavaLayer.decimalSupport(List.of())).isEqualTo("UNSUPPORTED");
  }

  private static List<VectorResult> decimalResults(String result) {
    List<VectorResult> results = new ArrayList<>();
    for (String id : List.of("D01", "D02", "D03", "D04", "D05", "D06", "D10")) {
      results.add(new VectorResult(id, "MUST", result, "x"));
    }
    results.add(new VectorResult("D07", "MUST", "FAIL", "not part of the classification"));
    return results;
  }

  @Test
  void everyRewriteIsValidAndMeetsItsVectorsExpectation() {
    List<dev.regpack.spikes.r.vectors.Vector> vectors =
        dev.regpack.spikes.r.vectors.VectorFile.load();
    int checked = 0;
    for (dev.regpack.spikes.r.vectors.Vector v : vectors) {
      Optional<String> rewrite = LAYER.rewriteFor(v.id());
      if (rewrite.isEmpty()) {
        continue;
      }
      checked++;
      Outcome outcome = eval(rewrite.get());
      assertThat(dev.regpack.spikes.r.vectors.ConformanceHarness.matches(v.expected(), outcome))
          .as(v.id() + " via " + rewrite.get() + " gave " + outcome)
          .isTrue();
    }
    assertThat(checked).isGreaterThanOrEqualTo(7);
    assertThat(LAYER.rewriteFor("T01")).isEmpty();
  }

  @Test
  void decimalsCompareByValueAndOperateExactly() {
    assertThat(eval("dec('1.10') == dec('1.1')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("dec('1.10') != dec('1.1')")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("dec('0.1') + dec('0.2') == dec('0.3')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("dec('1234.5') / dec('100')"))
        .isEqualTo(ok(new DecimalValue(new BigDecimal("12.345"))));
    assertThat(eval("dec('1') / dec('3')")).isEqualTo(error("NON_TERMINATING"));
    assertThat(eval("dec('1') / dec('0')")).isEqualTo(error("DIV_ZERO"));
    assertThat(eval("dec('1e5000')")).isEqualTo(error("SCALE"));
    assertThat(eval("dec('abc')")).isEqualTo(error("PARSE"));
    assertThat(eval("round(dec('2.5'), 0, 'HALF_EVEN') == dec('2')"))
        .isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("round(dec('1.005'), 2, 'HALF_UP')"))
        .isEqualTo(ok(new DecimalValue(new BigDecimal("1.01"))));
    assertThat(eval("dec('0.30') < dec('0.4') && dec('2') >= dec('2.0')"))
        .isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("1 + dec('0.5') == dec('1.5')")).isInstanceOf(Outcome.CompileError.class);
    Map<String, Value> bound = Map.of("amount", new DecimalValue(new BigDecimal("100.00")));
    assertThat(eval("amount == dec('100')", bound)).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("amount + dec('0.5')", bound))
        .isEqualTo(ok(new DecimalValue(new BigDecimal("100.50"))));
  }

  @Test
  void celShortCircuitAbsorbsAnErrorOnTheLeftAndThatIsRecordedNotHidden() {
    // spec-relevant difference (vector E06, DECIDE): CEL absorbs the error, the in-house layer does
    // not
    assertThat(eval("(1 / 0 == 1) || true")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("true || (1 / 0 == 1)")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("1 / 0 == 1")).isEqualTo(error("DIV_ZERO"));
  }

  @Test
  void twoDynamicOperandsOfAnOperatorAreRecordedAsATypeErrorNotAWrongAnswer() {
    Map<String, Value> bound = Map.of("a.n", new IntValue(3), "a.m", new IntValue(4));
    assertThat(eval("a.n + 1", bound)).isEqualTo(ok(new IntValue(4)));
    assertThat(eval("a.n + a.m", bound)).isInstanceOf(Outcome.Error.class);
  }

  @Test
  void aCompiledExpressionIsSafeToShareBetweenThreads() throws Exception {
    CompiledExpression compiled =
        new CelJavaExpression()
            .compile(
                "n + 1 == m && has(event.venue)", Map.of("n", ValueType.INT, "m", ValueType.INT));
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(4);
    try {
      List<java.util.concurrent.Future<Boolean>> runs = new ArrayList<>();
      for (int t = 0; t < 4; t++) {
        final int thread = t;
        runs.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 2_000; i++) {
                    long n = thread * 10_000L + i;
                    Map<String, Value> bound =
                        Map.of(
                            "n", new IntValue(n),
                            "m", new IntValue(n + 1),
                            "event.venue", new StringValue("XPAR"));
                    CostMeter meter = new CostMeter(new CostBudget(1_000));
                    Value result =
                        compiled.evaluate(path -> Optional.ofNullable(bound.get(path)), meter);
                    if (!result.equals(new BoolValue(true))) {
                      return false;
                    }
                  }
                  return true;
                }));
      }
      for (java.util.concurrent.Future<Boolean> run : runs) {
        assertThat(run.get()).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void errorKindsAreMappedInOnePlace() {
    assertThat(CelJavaExpression.errorKind(new CelDivideByZeroException())).isEqualTo("DIV_ZERO");
    assertThat(CelJavaExpression.errorKind(new CelNumericOverflowException("long overflow")))
        .isEqualTo("OVERFLOW");
    assertThat(CelJavaExpression.errorKind(new CelBadFormatException("bad"))).isEqualTo("PARSE");
    assertThat(CelJavaExpression.errorKind(CelAttributeNotFoundException.of("x")))
        .isEqualTo("ABSENT");
    assertThat(CelJavaExpression.errorKind(new CelOverloadNotFoundException("f")))
        .isEqualTo("TYPE");
    assertThat(CelJavaExpression.errorKind(new CelIndexOutOfBoundsException(3))).isEqualTo("RANGE");
    assertThat(CelJavaExpression.errorKind(new CelIterationLimitExceededException(5)))
        .isEqualTo("BUDGET");
    assertThat(CelJavaExpression.errorKind(new BudgetExceededException("r", 1, 2)))
        .isEqualTo("BUDGET");
    assertThat(CelJavaExpression.errorKind(new CelJavaExpression.EvalException("SCALE", "x")))
        .isEqualTo("SCALE");
    // a cause chain is searched, and an unknown failure is OTHER, never a silent pass
    assertThat(
            CelJavaExpression.errorKind(
                new IllegalStateException("outer", new CelDivideByZeroException())))
        .isEqualTo("DIV_ZERO");
    assertThat(CelJavaExpression.errorKind(new IllegalStateException("x"))).isEqualTo("OTHER");
    assertThat(CelJavaExpression.errorKind(new StackOverflowError())).isEqualTo("OTHER");

    assertThat(eval("9223372036854775807 + 1")).isEqualTo(error("OVERFLOW"));
    assertThat(eval("1 / 0")).isEqualTo(error("DIV_ZERO"));
    assertThat(eval("int('abc')")).isEqualTo(error("PARSE"));
  }

  @Test
  void theLayerNeverThrowsAndNamesItself() {
    assertThat(LAYER.name()).isEqualTo("cel");
    assertThat(eval("))((")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("undeclaredFunction(1)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("[1, 2, 3].all(x, x > 0)")).isInstanceOf(Outcome.CompileError.class);
  }
}
