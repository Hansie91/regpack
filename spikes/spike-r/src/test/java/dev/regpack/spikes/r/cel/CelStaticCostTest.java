package dev.regpack.spikes.r.cel;

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

/** The static worst case of the cel-java adapter bounds what a record at the limits is charged. */
class CelStaticCostTest {
  private static final int LIST = 256;
  private static final int STRING = 4_096;

  @Test
  void aBoundListAndAStringAtTheLimitsAreCoveredByTheStaticWorstCase() {
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < LIST; i++) {
      items.add(new StringValue("U"));
    }
    Map<String, Value> bindings =
        Map.of("a.xs", new ListValue(items), "a.s", new StringValue("1".repeat(STRING)));
    for (String source :
        List.of("'U' in a.xs", "size(a.s) == 1", "has(a.s) && size(a.xs) == 256", "true")) {
      CelJavaExpression.Compiled compiled = new CelJavaExpression().compile(source, Map.of());
      CostMeter meter = new CostMeter(new CostBudget(10_000_000L));
      try {
        compiled.evaluate(path -> Optional.ofNullable(bindings.get(path)), meter);
      } catch (ExpressionException expected) {
        // an error after the charge still paid for it
      }
      assertThat(compiled.staticWorstCaseUnits(LIST, STRING))
          .as(source)
          .isGreaterThanOrEqualTo(meter.used());
    }
  }

  @Test
  void aBodyWithoutVariablesCostsItsNodeCount() {
    CelJavaExpression.Compiled compiled = new CelJavaExpression().compile("1 + 2 == 3", Map.of());
    assertThat(compiled.staticWorstCaseUnits(LIST, STRING)).isEqualTo(compiled.nodeCount());
  }
}
