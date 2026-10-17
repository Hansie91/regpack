package dev.regpack.engine.expr;

import static dev.regpack.engine.expr.ExprTestSupport.bind;
import static dev.regpack.engine.expr.ExprTestSupport.integer;
import static dev.regpack.engine.expr.ExprTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.ExprTestSupport.Recording;
import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.ListValue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Allocation tests. Charging a surcharge before the allocation it pays for (PF9) is observable only
 * where the budget stops the allocation; the meter cannot tell "charged, then allocated" from
 * "allocated, then charged" when the work succeeds. So {@link
 * #concatenationOverTheBudgetFailsFastWithBudgetExceeded} uses strings whose full chain would be 20
 * MB, and the order inside {@code Evaluator.add} is held by that structure and by code review.
 */
class AmplificationTest {
  @Test
  void concatenationOverTheBudgetFailsFastWithBudgetExceeded() {
    // 201 terms of 100,000 characters: 20 MB if it ran to the end. The layer's string cap is
    // raised so that the budget, not the cap, fires.
    InHouseExpression layer = new InHouseExpression(new Limits(512, 256, 1_000_000));
    String source = "s" + " + s".repeat(200);
    Recording bindings = bind("s", text("x".repeat(100_000)));
    InHouseExpression.Compiled compiled = ExprTestSupport.compile(layer, source, bindings);
    CostMeter meter = new CostMeter(new CostBudget(10_000));
    assertThatThrownBy(() -> compiled.evaluate(bindings, meter))
        .isInstanceOf(BudgetExceededException.class);
    // The k-th concatenation (left to right) charges (k + 1) x 100,000 / 256 units. Five of them
    // cost 7,810 (the 200 + 1 descent and the reads add about 210); the sixth costs 2,734 more
    // and is refused before its 700,000-character string is built.
    assertThat(meter.used()).isBetween(7_810L, 10_000L);
    // the sixth concatenation would have taken the total past the budget
    assertThat(meter.used() + 2_734).isGreaterThan(10_000L);
  }

  @Test
  void aConcatenationLongerThanTheLimitIsRange() {
    Recording strings = bind("s", text("x".repeat(3_000)), "t", text("y".repeat(3_000)));
    InHouseExpression.Compiled compiled =
        ExprTestSupport.compile(new InHouseExpression(), "s + t", strings);
    CostMeter meter = new CostMeter(new CostBudget(ExprTestSupport.BIG));
    assertThatThrownBy(() -> compiled.evaluate(strings, meter))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.RANGE));
    // the charge was made first: 3 nodes plus 6,000 / 256 units
    assertThat(meter.used()).isEqualTo(3 + 6_000 / 256);

    List<Value> items = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      items.add(integer(i));
    }
    Recording lists = bind("a", new ListValue(items), "b", new ListValue(items));
    InHouseExpression.Compiled joined =
        ExprTestSupport.compile(new InHouseExpression(), "a + b", lists);
    CostMeter listMeter = new CostMeter(new CostBudget(ExprTestSupport.BIG));
    assertThatThrownBy(() -> joined.evaluate(lists, listMeter))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.RANGE));
    assertThat(listMeter.used()).isEqualTo(3 + 400);
  }

  @Test
  void aBudgetTooSmallForTheSurchargeStopsBeforeTheConcatenation() {
    Recording strings = bind("s", text("x".repeat(4_000)));
    InHouseExpression.Compiled compiled =
        ExprTestSupport.compile(new InHouseExpression(), "s + s", strings);
    CostMeter meter = new CostMeter(new CostBudget(3 + 8_000 / 256 - 1));
    assertThatThrownBy(() -> compiled.evaluate(strings, meter))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(meter.used()).isEqualTo(3);
  }
}
