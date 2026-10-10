package dev.regpack.spikes.r.inhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InHouseCostTest {
  private static final Bindings NONE = path -> Optional.empty();

  private static long cost(String source, Map<String, Value> bindings) {
    CompiledExpression compiled = new InHouseExpression().compile(source, Map.of());
    CostMeter meter = new CostMeter(new CostBudget(10_000_000L));
    compiled.evaluate(path -> Optional.ofNullable(bindings.get(path)), meter);
    return meter.used();
  }

  private static long cost(String source) {
    return cost(source, Map.of());
  }

  private static String text(int codePoints) {
    return "a".repeat(codePoints);
  }

  @Test
  void costIsOneUnitPerNodeAndDeterministic() {
    CompiledExpression compiled = new InHouseExpression().compile("1 + 2", Map.of());
    for (int i = 0; i < 1_000; i++) {
      CostMeter meter = new CostMeter(new CostBudget(100));
      assertThat(compiled.evaluate(NONE, meter)).isEqualTo(new IntValue(3));
      assertThat(meter.used()).as("repeat " + i).isEqualTo(3L);
    }
    assertThat(cost("true")).isEqualTo(1);
    assertThat(cost("1 + 2 * 3")).isEqualTo(5);
    assertThat(cost("has(event.venue)")).isEqualTo(1);
  }

  @Test
  void onlyEvaluatedNodesAreCharged() {
    assertThat(cost("false && (1 / 0 == 1)")).as("right side skipped").isEqualTo(2);
    assertThat(cost("true ? 1 : 2")).isEqualTo(3);
  }

  @Test
  void listScanChargesTheListLength() {
    Map<String, Value> ten = Map.of("items", list(10));
    Map<String, Value> twenty = Map.of("items", list(20));
    long base = cost("contains(items, 'x')", Map.of("items", list(0)));
    assertThat(base).isEqualTo(3);
    assertThat(cost("contains(items, 'x')", ten)).isEqualTo(base + 10);
    assertThat(cost("contains(items, 'x')", twenty)).isEqualTo(base + 20);
    assertThat(cost("'x' in items", ten)).isEqualTo(3 + 10);
    assertThat(cost("items + items", ten)).as("list + charges the result length").isEqualTo(3 + 20);
    assertThat(cost("'DEAL' in ['DEAL', 'MTCH', 'AOTC']")).isEqualTo(1 + 1 + 1 + 3 + 3);
  }

  @Test
  void stringOpsChargeOneUnitPer256CodePoints() {
    assertThat(cost("upper(s)", Map.of("s", new StringValue(text(255))))).isEqualTo(2);
    assertThat(cost("upper(s)", Map.of("s", new StringValue(text(256))))).isEqualTo(3);
    assertThat(cost("upper(s)", Map.of("s", new StringValue(text(1024))))).isEqualTo(2 + 4);
    assertThat(cost("lower(s)", Map.of("s", new StringValue(text(511))))).isEqualTo(2 + 1);
    assertThat(cost("s + s", Map.of("s", new StringValue(text(512)))))
        .as("concatenation")
        .isEqualTo(3 + 4);
    assertThat(cost("size(s)", Map.of("s", new StringValue(text(2560))))).isEqualTo(2 + 10);
    String emoji = new String(Character.toChars(0x1F600));
    assertThat(cost("size(s)", Map.of("s", new StringValue(emoji.repeat(256)))))
        .as("code points, not UTF-16 units")
        .isEqualTo(2 + 1);
  }

  @Test
  void fixedChargesForDecimalAndTemporalCalls() {
    assertThat(cost("dec('1')")).isEqualTo(2 + 1);
    assertThat(cost("dec('1') / dec('2')")).isEqualTo(5 + 2 + 1);
    assertThat(cost("round(dec('1.5'), 0, 'HALF_UP')")).isEqualTo(5 + 1 + 1);
    assertThat(cost("date('2026-10-09')")).isEqualTo(2 + 1);
    assertThat(cost("timestamp('2026-10-09T09:30:00Z')")).isEqualTo(2 + 1);
  }

  @Test
  void budgetIsCheckedBeforeTheWork() {
    CompiledExpression compiled = new InHouseExpression().compile("lower(big)", Map.of());
    Bindings big = path -> Optional.of(new StringValue(text(1_048_576)));
    CostMeter meter = new CostMeter(new CostBudget(1_000));
    assertThatThrownBy(() -> compiled.evaluate(big, meter))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(meter.used()).as("the failing charge is not added").isEqualTo(2);
  }

  @Test
  void perRuleCeilingRejectsAtCompile() {
    InHouseExpression strict = InHouseExpression.withCeiling(5);
    assertThat(strict.compile("1 + 2 + 3", Map.of()).source()).isEqualTo("1 + 2 + 3");
    assertThatThrownBy(() -> strict.compile("1 + 2 + 3 + 4", Map.of()))
        .isInstanceOf(ExpressionException.class)
        .hasMessageContaining("ceiling");
    StringBuilder chain = new StringBuilder("1");
    for (int i = 1; i < 3_000; i++) {
      chain.append(" + 1");
    }
    assertThatThrownBy(
            () -> InHouseExpression.withCeiling(1_000).compile(chain.toString(), Map.of()))
        .isInstanceOf(ExpressionException.class);
    String shortChain = "1" + " + 1".repeat(499);
    assertThat(InHouseExpression.withCeiling(10_000).compile(shortChain, Map.of())).isNotNull();
  }

  @Test
  void nestingDepthIsBounded() {
    String deep = "(".repeat(5_000) + "1" + ")".repeat(5_000);
    assertThatThrownBy(() -> InHouseExpression.withCeiling(1_000_000).compile(deep, Map.of()))
        .isInstanceOf(ExpressionException.class);
    String longChain = "1" + " + 1".repeat(20_000);
    assertThatThrownBy(() -> InHouseExpression.withCeiling(1_000_000).compile(longChain, Map.of()))
        .isInstanceOf(ExpressionException.class);
  }

  private static ListValue list(int size) {
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      items.add(new StringValue("v" + i));
    }
    return new ListValue(items);
  }
}
