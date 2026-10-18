package dev.regpack.spikes.r.inhouse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The static worst case is an upper bound of the cost of every operator at the declared limits. */
class StaticCostTest {
  private static final int LIST = 256;
  private static final int STRING = 4_096;

  private static final Map<String, Value> BINDINGS = bindings();

  private static Map<String, Value> bindings() {
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < LIST; i++) {
      items.add(new StringValue("U"));
    }
    return Map.of(
        "xs",
        new ListValue(items),
        "s",
        new StringValue("1".repeat(STRING)),
        "t",
        new StringValue("1"));
  }

  /** The units one evaluation charges, also when it ends in an error. */
  private static long used(InHouseExpression.Compiled compiled) {
    CostMeter meter = new CostMeter(new CostBudget(10_000_000L));
    try {
      compiled.evaluate(path -> Optional.ofNullable(BINDINGS.get(path)), meter);
    } catch (ExpressionException expected) {
      // an error after the charge still paid for it
    }
    return meter.used();
  }

  private static void assertBounded(String source) {
    InHouseExpression.Compiled compiled = new InHouseExpression().compile(source, Map.of());
    long actual = used(compiled);
    long bound = compiled.staticWorstCaseUnits(LIST, STRING);
    assertThat(bound).as(source).isGreaterThanOrEqualTo(actual);
  }

  @Test
  void everyOperatorAtTheLimitsIsBoundedByTheStaticWorstCase() {
    for (String source :
        List.of(
            "'U' in xs",
            "contains(xs, 'U')",
            "xs + xs == xs",
            "s + s == s",
            "upper(s) == s",
            "lower(s) == s",
            "size(s) == 1",
            "size(xs) == 1",
            "string(s) == s",
            "int(s) == 1",
            "dec(s) == dec('1')",
            "timestamp(s) == timestamp('2026-10-09T09:30:00Z')",
            "date(s) == date('2026-10-09')",
            "s.startsWith(s)",
            "s.endsWith(s)",
            "s.contains(s)",
            "contains(s, s)",
            "dec('1') / dec('2') == dec('0.5')",
            "round(dec('1.005'), 2, 'HALF_UP') == dec('1.01')",
            "true ? ('U' in xs) : contains(xs, 'U')",
            "!(('U' in xs) || ('U' in ['U', 'V']))",
            "isoMicros(timestamp('2026-10-09T09:30:00Z')) == 'x'")) {
      assertBounded(source);
    }
  }

  @Test
  void aListLiteralIsBoundedByItsOwnLength() {
    InHouseExpression.Compiled compiled =
        new InHouseExpression().compile("'U' in ['A', 'B', 'C']", Map.of());
    assertThat(compiled.staticWorstCaseUnits(LIST, STRING))
        .as("6 nodes and 3 items scanned, not 256")
        .isEqualTo(6 + 3);
    assertThat(used(compiled)).isEqualTo(6 + 3);
  }

  @Test
  void withoutSurchargesTheBoundIsTheNodeCount() {
    for (String source :
        List.of("1 + 2 * 3", "event.kind == 'NEW' && has(a.b)", "true ? 1 : 2", "-(1 - 2) < 0")) {
      InHouseExpression.Compiled compiled = new InHouseExpression().compile(source, Map.of());
      assertThat(compiled.staticWorstCaseUnits(0, 0)).as(source).isEqualTo(compiled.nodeCount());
    }
  }

  @Test
  void theBoundGrowsWithTheLimits() {
    InHouseExpression.Compiled compiled =
        new InHouseExpression().compile("contains(s, 'U') && ('U' in xs)", Map.of());
    assertThat(compiled.staticWorstCaseUnits(256, 4_096))
        .isGreaterThan(compiled.staticWorstCaseUnits(16, 256))
        .isGreaterThan(compiled.staticWorstCaseUnits(0, 0));
  }
}
