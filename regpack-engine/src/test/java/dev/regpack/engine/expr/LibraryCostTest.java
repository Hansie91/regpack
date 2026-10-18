package dev.regpack.engine.expr;

import static dev.regpack.engine.expr.ExprTestSupport.BIG;
import static dev.regpack.engine.expr.ExprTestSupport.bind;
import static dev.regpack.engine.expr.ExprTestSupport.integer;
import static dev.regpack.engine.expr.ExprTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.ExprTestSupport.Recording;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.DateValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LibraryCostTest {
  /** The string lengths of the cost-model rows: below, at and above the 256 code point block. */
  private static final int[] LENGTHS = {0, 255, 256, 512};

  /**
   * The units one evaluation charges. An evaluation error does not matter here: every charge comes
   * before the work, so the units are the same whether the work then succeeds or fails.
   */
  private static long charged(String source, Recording bindings) {
    InHouseExpression.Compiled compiled =
        ExprTestSupport.compile(new InHouseExpression(), source, bindings);
    CostMeter meter = new CostMeter(new CostBudget(BIG));
    try {
      compiled.evaluate(bindings, meter);
    } catch (ExprException _) {
      // the charge was made before the failing work
    }
    return meter.used();
  }

  private static String zeros(int n) {
    return "0".repeat(n);
  }

  @Test
  void everyFunctionChargesItsDocumentedUnits() {
    for (int n : LENGTHS) {
      Recording s = bind("s", text(zeros(n)));
      long blocks = n / 256;
      // one unit per 256 code points of the string argument, on top of the two nodes
      assertThat(charged("upper(s)", s)).as("upper %d", n).isEqualTo(2 + blocks);
      assertThat(charged("lower(s)", s)).as("lower %d", n).isEqualTo(2 + blocks);
      assertThat(charged("size(s)", s)).as("size %d", n).isEqualTo(2 + blocks);
      assertThat(charged("string(s)", s)).as("string %d", n).isEqualTo(2 + blocks);
      assertThat(charged("int(s)", s)).as("int %d", n).isEqualTo(2 + blocks);
      // dec(text), timestamp and date: 1 + code points / 256
      assertThat(charged("dec(s)", s)).as("dec %d", n).isEqualTo(2 + 1 + blocks);
      assertThat(charged("timestamp(s)", s)).as("timestamp %d", n).isEqualTo(2 + 1 + blocks);
      assertThat(charged("date(s)", s)).as("date %d", n).isEqualTo(2 + 1 + blocks);
      for (int m : LENGTHS) {
        Recording st = bind("s", text(zeros(n)), "t", text(zeros(m)));
        long other = m / 256;
        assertThat(charged("s.startsWith(t)", st))
            .as("startsWith %d %d", n, m)
            .isEqualTo(3 + blocks + other);
        assertThat(charged("s.endsWith(t)", st))
            .as("endsWith %d %d", n, m)
            .isEqualTo(3 + blocks + other);
        // string contains: (cp(x) / 256 + 1) x (cp(y) / 256 + 1) - 1
        long product = (blocks + 1) * (other + 1) - 1;
        assertThat(charged("contains(s, t)", st)).as("contains %d %d", n, m).isEqualTo(3 + product);
        assertThat(charged("s.contains(t)", st))
            .as("contains method %d %d", n, m)
            .isEqualTo(3 + product);
      }
    }
  }

  @Test
  void functionsWithoutASizeSurchargeChargeTheirNodesAndTheirFixedUnits() {
    Recording b =
        bind(
            "d", new DecimalValue(new BigDecimal("1.5")),
            "dt", new DateValue(LocalDate.of(2026, 3, 1)),
            "ts", new TimestampValue(Instant.parse("2026-03-01T10:00:00Z")),
            "l", new ListValue(List.of(integer(1), integer(2), integer(3))));
    // dec of a non-text value: 1
    assertThat(charged("dec(7)", b)).isEqualTo(2 + 1);
    assertThat(charged("dec(d)", b)).isEqualTo(2 + 1);
    // round: 1
    assertThat(charged("round(d, 2, 'HALF_UP')", b)).isEqualTo(4 + 1);
    // decimal division: 1
    assertThat(charged("d / 2.0", b)).isEqualTo(3 + 1);
    // no surcharge for a non-string argument or for these two
    assertThat(charged("string(d)", b)).isEqualTo(2);
    assertThat(charged("string(7)", b)).isEqualTo(2);
    assertThat(charged("int(7)", b)).isEqualTo(2);
    assertThat(charged("int(d)", b)).isEqualTo(2);
    assertThat(charged("isoMicros(ts)", b)).isEqualTo(2);
    assertThat(charged("dayOfWeek(dt)", b)).isEqualTo(2);
    assertThat(charged("size(l)", b)).isEqualTo(2);
    // a list receiver of contains: the list length, found or not
    assertThat(charged("contains(l, 1)", b)).isEqualTo(3 + 3);
    assertThat(charged("contains(l, 9)", b)).isEqualTo(3 + 3);
    assertThat(charged("l.contains(1)", b)).isEqualTo(3 + 3);
  }

  @Test
  void stringContainsOfTwoStringsAtTheLimitChargesTwoHundredEightyEight() {
    Recording b = bind("s", text(zeros(4_096)), "t", text(zeros(4_096)));
    // 17 x 17 - 1 = 288
    assertThat(charged("contains(s, t)", b)).isEqualTo(3 + 288);
    assertThat(charged("s.contains(t)", b)).isEqualTo(3 + 288);
    InHouseExpression.Compiled compiled =
        ExprTestSupport.compile(new InHouseExpression(), "contains(s, t)", b);
    assertThat(compiled.staticWorstCaseUnits()).isGreaterThanOrEqualTo(3 + 288);
  }

  @Test
  void theChargeComesBeforeTheWork() {
    Recording s = bind("s", text(zeros(512)));
    // upper(s) costs 2 nodes + 2 blocks = 4; one unit less must fail before the work is done
    for (String source : List.of("upper(s)", "lower(s)", "size(s)", "string(s)", "int(s)")) {
      InHouseExpression.Compiled compiled =
          ExprTestSupport.compile(new InHouseExpression(), source, s);
      assertThat(compiled.evaluate(s, new CostMeter(new CostBudget(4)))).isNotNull();
      assertThatThrownBy(() -> compiled.evaluate(s, new CostMeter(new CostBudget(3))))
          .as(source)
          .isInstanceOf(BudgetExceededException.class);
    }
    InHouseExpression.Compiled dec = ExprTestSupport.compile(new InHouseExpression(), "dec(s)", s);
    assertThatThrownBy(() -> dec.evaluate(s, new CostMeter(new CostBudget(4))))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(dec.evaluate(s, new CostMeter(new CostBudget(5)))).isNotNull();
  }

  @Test
  void aFunctionOverAHugeStringIsStoppedByTheBudgetNotByTheWork() {
    // the layer allows a very long string, so only the budget stops the call
    InHouseExpression layer =
        new InHouseExpression(new InHouseExpression.Limits(512, 256, 100_000_000));
    Recording s = bind("s", text(zeros(2_000_000)));
    for (String source : List.of("upper(s)", "dec(s)", "s.startsWith(s)", "contains(s, s)")) {
      InHouseExpression.Compiled compiled = ExprTestSupport.compile(layer, source, s);
      assertThatThrownBy(() -> compiled.evaluate(s, new CostMeter(new CostBudget(1_000))))
          .as(source)
          .isInstanceOf(BudgetExceededException.class);
    }
  }

  @Test
  void libraryCallsNeverExceedTheStaticWorstCase() {
    SplittableRandom random = new SplittableRandom(13);
    List<String> templates =
        List.of(
            "upper(s)",
            "lower(s)",
            "size(s)",
            "string(s)",
            "int(s)",
            "dec(s)",
            "timestamp(s)",
            "date(s)",
            "s.startsWith(t)",
            "s.endsWith(t)",
            "contains(s, t)",
            "s.contains(t)",
            "upper(s) + lower(t)",
            "contains(l, 1)",
            "size(l)",
            "l.contains(1) && size(s) > 0",
            "string(7) + t",
            "int(7)",
            "dec(7)",
            "round(dec('1.5'), 2, 'HALF_UP')",
            "isoMicros(ts)",
            "dayOfWeek(dt)",
            "[1] + l",
            "s in [t, upper(s), lower(t)]");
    int[] interesting = {0, 1, 255, 256, 257, 511, 512, 4_095, 4_096};
    for (int i = 0; i < 600; i++) {
      Map<String, Value> values = new HashMap<>();
      values.put("s", text(zeros(length(random, interesting, 4_096))));
      values.put("t", text(zeros(length(random, interesting, 4_096))));
      List<Value> items = new ArrayList<>();
      int listSize = length(random, new int[] {0, 1, 255, 256}, 256);
      for (int k = 0; k < listSize; k++) {
        items.add(integer(k % 3));
      }
      values.put("l", new ListValue(items));
      values.put("ts", new TimestampValue(Instant.parse("2026-03-01T10:00:00Z")));
      values.put("dt", new DateValue(LocalDate.of(2026, 3, 1)));
      Recording b = new Recording(values);
      String source = templates.get(random.nextInt(templates.size()));
      InHouseExpression.Compiled compiled =
          ExprTestSupport.compile(new InHouseExpression(), source, b);
      CostMeter meter = new CostMeter(new CostBudget(BIG));
      try {
        compiled.evaluate(b, meter);
      } catch (ExprException _) {
        // a RANGE or PARSE error after the charge: the charge must still be within the bound
      }
      assertThat(meter.used())
          .as("%s with a list of %d, run %d", source, listSize, i)
          .isLessThanOrEqualTo(compiled.staticWorstCaseUnits());
    }
  }

  private static int length(SplittableRandom random, int[] interesting, int max) {
    if (random.nextBoolean()) {
      return Math.min(max, interesting[random.nextInt(interesting.length)]);
    }
    return random.nextInt(max + 1);
  }
}
