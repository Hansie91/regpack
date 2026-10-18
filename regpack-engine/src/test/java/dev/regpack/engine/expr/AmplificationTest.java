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
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Allocation tests. Charging a surcharge before the allocation it pays for (PF9) is observable only
 * where the budget stops the allocation; the meter cannot tell "charged, then allocated" from
 * "allocated, then charged" when the work succeeds. So {@link
 * #concatenationOverTheBudgetFailsFastWithBudgetExceeded} uses strings whose full chain would be 20
 * MB, and {@link #anAmplificationIsChargedBeforeItIsAllocated} measures the bytes the evaluating
 * thread allocates (a count, not a duration) around a join that the budget refuses.
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

  /**
   * Bytes the current thread allocated while {@code work} ran (the JVM's exact per-thread count).
   */
  private static long allocatedBy(Runnable work) {
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long id = Thread.currentThread().threadId();
    long before = threads.getThreadAllocatedBytes(id);
    work.run();
    return threads.getThreadAllocatedBytes(id) - before;
  }

  @Test
  void anAmplificationIsChargedBeforeItIsAllocated() {
    // Operands of 100,000 characters (100 KB as Latin-1) joined into 200,000 characters, with a
    // budget that refuses the charge. Charge first: nothing of the result is ever built, so the
    // evaluation allocates far less than one operand. Allocate first: at least 200 KB.
    InHouseExpression layer = new InHouseExpression(new Limits(512, 8_192, 1_000_000));
    Recording strings = bind("s", text("x".repeat(100_000)));
    InHouseExpression.Compiled joinedText = ExprTestSupport.compile(layer, "s + s", strings);
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < 4_000; i++) {
      items.add(integer(i));
    }
    Recording lists = bind("a", new ListValue(items));
    InHouseExpression.Compiled joinedList = ExprTestSupport.compile(layer, "a + a", lists);

    Runnable text = () -> refused(joinedText, strings);
    Runnable list = () -> refused(joinedList, lists);
    // warm up once so that class loading and first-use costs are not measured
    text.run();
    list.run();
    assertThat(allocatedBy(text)).as("string join").isLessThan(50_000L);
    assertThat(allocatedBy(list)).as("list join").isLessThan(16_000L);
  }

  private static void refused(InHouseExpression.Compiled compiled, Recording bindings) {
    CostMeter meter = new CostMeter(new CostBudget(100));
    assertThatThrownBy(() -> compiled.evaluate(bindings, meter))
        .isInstanceOf(BudgetExceededException.class);
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
