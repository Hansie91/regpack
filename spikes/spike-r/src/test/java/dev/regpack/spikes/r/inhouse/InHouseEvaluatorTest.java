package dev.regpack.spikes.r.inhouse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.spikes.r.vectors.Outcome;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InHouseEvaluatorTest {
  private static final InHouseLayer LAYER = new InHouseLayer();

  private static Outcome eval(String expression) {
    return eval(expression, Map.of());
  }

  private static Outcome eval(String expression, Map<String, Value> bindings) {
    return LAYER.evaluate(expression, bindings, 1_000_000L, 100_000L);
  }

  private static Outcome ok(Value value) {
    return new Outcome.Ok(value);
  }

  private static Outcome error(String kind) {
    return new Outcome.Error(kind);
  }

  @Test
  void decimalArithmeticIsExact() {
    assertThat(eval("0.1 + 0.2 == 0.3")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("dec('0.1') + dec('0.2') == dec('0.3')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("dec('1.10') == dec('1.1')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("dec('1234.5') / dec('100')"))
        .isEqualTo(ok(new DecimalValue(new BigDecimal("12.345"))));
    assertThat(eval("dec('1') / dec('3')")).isEqualTo(error("NON_TERMINATING"));
    assertThat(eval("dec('1') / dec('0')")).isEqualTo(error("DIV_ZERO"));
    assertThat(eval("dec('1e5000')")).isEqualTo(error("SCALE"));
    assertThat(eval("dec('abc')")).isEqualTo(error("PARSE"));
    assertThat(eval("round(dec('2.5'), 0, 'HALF_EVEN') == dec('2')"))
        .isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("round(dec('2.5'), 0, 'HALF_UP') == dec('3')"))
        .isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("round(dec('1.005'), 2, 'HALF_UP')"))
        .isEqualTo(ok(new DecimalValue(new BigDecimal("1.01"))));
    assertThat(eval("string(dec('12.50'))")).isEqualTo(ok(new StringValue("12.5")));
    assertThat(eval("1 + dec('0.5') == dec('1.5')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("round(dec('1'), 0, 'UP')")).isEqualTo(error("OTHER"));
    assertThat(eval("round(dec('1'), 99, 'HALF_UP')")).isEqualTo(error("RANGE"));
  }

  @Test
  void integerOverflowIsAnError() {
    assertThat(eval("9223372036854775807 + 1")).isEqualTo(error("OVERFLOW"));
    assertThat(eval("-9223372036854775807 - 2")).isEqualTo(error("OVERFLOW"));
    assertThat(eval("3037000500 * 3037000500")).isEqualTo(error("OVERFLOW"));
    assertThat(eval("9223372036854775807 + 0")).isEqualTo(ok(new IntValue(Long.MAX_VALUE)));
    assertThat(eval("1 / 0")).isEqualTo(error("DIV_ZERO"));
    assertThat(eval("7 / 2")).isEqualTo(ok(new IntValue(3)));
    assertThat(eval("int('abc')")).isEqualTo(error("PARSE"));
    assertThat(eval("int('42')")).isEqualTo(ok(new IntValue(42)));
    assertThat(eval("int('9223372036854775808')")).isEqualTo(error("PARSE"));
    assertThat(eval("1 < 'a'")).isEqualTo(error("TYPE"));
  }

  @Test
  void absenceRaisesAbsentUnlessGuarded() {
    assertThat(eval("has(event.venue)")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("event.venue == 'XPAR'")).isEqualTo(error("ABSENT"));
    assertThat(eval("has(event.venue) && event.venue == 'XPAR'"))
        .isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("event.venue == 'XPAR' && has(event.venue)")).isEqualTo(error("ABSENT"));
    assertThat(eval("has(event.venue) ? event.venue : 'NONE'"))
        .isEqualTo(ok(new StringValue("NONE")));
    Map<String, Value> bound = Map.of("event.venue", new StringValue("XPAR"));
    assertThat(eval("has(event.venue)", bound)).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("has(event.venue) ? event.venue : 'NONE'", bound))
        .isEqualTo(ok(new StringValue("XPAR")));
    assertThat(
            eval(
                "has(client.parent.lei)", Map.of("client.kind", new StringValue("NATURAL_PERSON"))))
        .isEqualTo(ok(new BoolValue(false)));
  }

  @Test
  void andOrShortCircuitLeftToRight() {
    assertThat(eval("false && (1 / 0 == 1)")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("true || (1 / 0 == 1)")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("(1 / 0 == 1) || true")).isEqualTo(error("DIV_ZERO"));
    assertThat(eval("(1 / 0 == 1) && false")).isEqualTo(error("DIV_ZERO"));
    assertThat(eval("true && false")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("false || true")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("1 && true")).isEqualTo(error("TYPE"));
    assertThat(eval("true ? 1 : 1 / 0")).isEqualTo(ok(new IntValue(1)));
    assertThat(eval("!false")).isEqualTo(ok(new BoolValue(true)));
  }

  @Test
  void codePointSizeNotUtf16() {
    String emoji = new String(Character.toChars(0x1F600));
    assertThat(eval("size('" + emoji + "') == 1")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("size('a" + emoji + "b')")).isEqualTo(ok(new IntValue(3)));
    assertThat(eval("size('é') == 1")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("size([1, 2, 3]) == 3")).isEqualTo(ok(new BoolValue(true)));
  }

  @Test
  void timestampsAreMicrosecondUtc() {
    assertThat(eval("isoMicros(timestamp('2026-10-09T09:30:00Z'))"))
        .isEqualTo(ok(new StringValue("2026-10-09T09:30:00.000000Z")));
    assertThat(eval("isoMicros(timestamp('2026-10-09T11:30:00+02:00'))"))
        .isEqualTo(ok(new StringValue("2026-10-09T09:30:00.000000Z")));
    assertThat(eval("isoMicros(timestamp('2026-10-09T09:30:00.123456Z'))"))
        .isEqualTo(ok(new StringValue("2026-10-09T09:30:00.123456Z")));
    assertThat(eval("isoMicros(timestamp('2026-10-09T09:30:00.123456789Z'))"))
        .isEqualTo(error("RANGE"));
    assertThat(eval("timestamp('2026-10-09T09:30:00')")).isEqualTo(error("PARSE"));
    assertThat(
            eval(
                "timestamp('2026-10-09T09:30:00.123456Z') < timestamp('2026-10-09T09:30:00.123457Z')"))
        .isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("date('2026-02-29')")).isEqualTo(error("PARSE"));
    assertThat(eval("dayOfWeek(date('2026-10-09'))")).isEqualTo(ok(new StringValue("FRIDAY")));
    assertThat(
            eval(
                "isoMicros(t)",
                Map.of(
                    "t",
                    new Value.TimestampValue(java.time.Instant.parse("0001-01-01T00:00:00Z")))))
        .isEqualTo(ok(new StringValue("0001-01-01T00:00:00.000000Z")));
  }

  @Test
  void enumComparesWithAStringLiteral() {
    Map<String, Value> bound = Map.of("event.kind", new EnumValue("LifecycleKind", "NEW"));
    assertThat(eval("event.kind == 'NEW'", bound)).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("event.kind == 'MODIFY'", bound)).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("event.kind != 'MODIFY'", bound)).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("event.kind in ['NEW', 'MODIFY']", bound)).isEqualTo(ok(new BoolValue(true)));
  }

  @Test
  void rejectsNullLiteralAndLoopMacros() {
    assertThat(eval("event.venue == null", Map.of("event.venue", new StringValue("XPAR"))))
        .isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("[1, 2, 3].all(x, x > 0)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("[1, 2, 3].exists(x, x > 2)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("[1, 2, 3].map(x, x + 1)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("[1, 2, 3].filter(x, x > 1)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("nosuchfunction(1)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("size(1, 2)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("has(1 + 1)")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("1 +")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("(1")).isInstanceOf(Outcome.CompileError.class);
    assertThat(eval("")).isInstanceOf(Outcome.CompileError.class);
  }

  @Test
  void stringsAndListsBehave() {
    assertThat(eval("upper('title') == 'TITLE'")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("lower('TITLE')")).isEqualTo(ok(new StringValue("title")));
    assertThat(eval("'A' + 'B' == 'AB'")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("'DEAL' in ['DEAL', 'MTCH', 'AOTC']")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("'XXXX' in ['DEAL', 'MTCH', 'AOTC']")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("[1, 2] + [3] == [1, 2, 3]")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("'LEI-FIRM-0001'.startsWith('LEI-')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("'LEI-FIRM-0001'.endsWith('0001')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("'LEI-FIRM-0001'.contains('FIRM')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("contains(['a', 'b'], 'b')")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("contains('abc', 'z')")).isEqualTo(ok(new BoolValue(false)));
    assertThat(eval("'a' < 'b'")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("1 + 2 * 3 == 7 && (1 + 2) * 3 == 9")).isEqualTo(ok(new BoolValue(true)));
    assertThat(eval("-3 + 5")).isEqualTo(ok(new IntValue(2)));
  }
}
