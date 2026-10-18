package dev.regpack.engine.expr;

import static dev.regpack.engine.expr.ExprTestSupport.bind;
import static dev.regpack.engine.expr.ExprTestSupport.decimal;
import static dev.regpack.engine.expr.ExprTestSupport.integer;
import static dev.regpack.engine.expr.ExprTestSupport.text;
import static dev.regpack.engine.expr.ExprTestSupport.units;
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
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class CostTest {
  @Test
  void costIsOneUnitPerNodeAndDeterministic() {
    Recording none = bind();
    for (int i = 0; i < 1_000; i++) {
      assertThat(units("1 + 2", none)).isEqualTo(3);
    }
    assertThat(units("true", none)).isEqualTo(1);
    assertThat(units("has(a.b)", none, "a.b")).isEqualTo(1);
    assertThat(units("size('abc')", none)).isEqualTo(2);
    assertThat(units("-(1 + 2)", none)).isEqualTo(4);
    assertThat(units("1.0 / 4.0", none)).isEqualTo(4); // 3 nodes plus 1 for a decimal division
    assertThat(units("1 / 2", none)).isEqualTo(3); // int division: no surcharge
  }

  @Test
  void listScanChargesTheListLength() {
    Recording x = bind("x", integer(2));
    // in, x, list, three items = 6 nodes, plus the 3 items scanned (the match is at item 2)
    assertThat(units("x in [1, 2, 3]", x)).isEqualTo(9);
    assertThat(units("x in []", x)).isEqualTo(3);
    assertThat(units("contains([1, 2, 3], x)", x)).isEqualTo(1 + 4 + 1 + 3);
    // list plus list: both sizes
    assertThat(units("[1, 2] + [3]", x)).isEqualTo(1 + 3 + 2 + 3);
  }

  @Test
  void stringSurchargesFollowTheCostModel() {
    Recording s = bind("s", text("x".repeat(600)), "t", text("y".repeat(300)));
    assertThat(units("s + t", s)).isEqualTo(3 + 900 / 256);
    assertThat(units("size(s)", s)).isEqualTo(2 + 600 / 256);
    // contains on strings: (cp(x)/256 + 1) x (cp(y)/256 + 1) - 1 = 3 x 2 - 1
    assertThat(units("contains(s, t)", s)).isEqualTo(3 + 5);
    assertThat(units("s.contains(t)", s)).isEqualTo(3 + 5);
  }

  @Test
  void aTernaryChargesOnlyTheTakenBranch() {
    Recording none = bind();
    assertThat(units("true ? 1 : 2 + 3 * 4", none)).isEqualTo(3);
    assertThat(units("false ? 1 : 2 + 3", none)).isEqualTo(5);
    assertThat(units("false && 1 / 0 == 1", none)).isEqualTo(2);
    assertThat(units("true || 1 / 0 == 1", none)).isEqualTo(2);
  }

  @Test
  void chargeComesBeforeTheWork() {
    InHouseExpression.Compiled compiled =
        new InHouseExpression().compile("1 + 2", java.util.Map.of());
    CostMeter meter = new CostMeter(new CostBudget(2));
    assertThatThrownBy(() -> compiled.evaluate(bind(), meter))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(meter.used()).isEqualTo(2);
    // the surcharge is paid before the scan: a list of 3 with the budget exactly at the nodes
    Recording x = bind("x", integer(2));
    InHouseExpression.Compiled scan =
        ExprTestSupport.compile(new InHouseExpression(), "x in [1, 2, 3]", x);
    CostMeter tight = new CostMeter(new CostBudget(8));
    assertThatThrownBy(() -> scan.evaluate(x, tight)).isInstanceOf(BudgetExceededException.class);
    assertThat(tight.used()).isEqualTo(6);
    CostMeter exact = new CostMeter(new CostBudget(9));
    assertThat(scan.evaluate(x, exact)).isEqualTo(ExprTestSupport.bool(true));
    assertThat(exact.used()).isEqualTo(9);
  }

  /**
   * The node charge is made on entry, before the node's children and path reads: with the budget
   * spent by the root, no path is read. An evaluator that charged on exit would read x first. (A
   * surcharge paid after its scan or allocation is not observable through the meter, because the
   * scan has no side effect; that order is held by the structure of {@code Evaluator.add}, {@code
   * in} and {@code Library}, and by review, see {@code AmplificationTest}.)
   */
  @Test
  void aNodeIsChargedOnEntryBeforeItsChildrenAreRead() {
    Recording bindings = bind("x", integer(1), "y", integer(2));
    InHouseExpression.Compiled compiled =
        ExprTestSupport.compile(new InHouseExpression(), "x + y", bindings);
    CostMeter meter = new CostMeter(new CostBudget(1));
    assertThatThrownBy(() -> compiled.evaluate(bindings, meter))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(bindings.reads()).isEmpty();
    // with room for the root and the left leaf only, x is read and y is not
    Recording partial = bind("x", integer(1), "y", integer(2));
    InHouseExpression.Compiled two =
        ExprTestSupport.compile(new InHouseExpression(), "x + y", partial);
    assertThatThrownBy(() -> two.evaluate(partial, new CostMeter(new CostBudget(2))))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(partial.reads()).containsExactly("x");
  }

  @Test
  void budgetExceptionNamesTheEnteredRule() {
    InHouseExpression.Compiled compiled =
        new InHouseExpression().compile("1 + 2", java.util.Map.of());
    CostMeter meter = new CostMeter(new CostBudget(1));
    meter.enter("R-7");
    assertThatThrownBy(() -> compiled.evaluate(bind(), meter))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            e -> {
              assertThat(e.ruleId()).isEqualTo("R-7");
              assertThat(e.limit()).isEqualTo(1);
              assertThat(e.used()).isEqualTo(1);
            });
  }

  // ---- the property: runtime units never exceed the static worst case ------------------------

  private static final String[] INTS = {"i", "j", "1", "2", "-3"};
  private static final String[] DECS = {"d", "e", "0.5", "1.25"};
  private static final String[] STRS = {"s", "t", "'ab'", "''", "'x'"};
  private static final String[] LISTS = {"l", "m", "[1, 2]", "[]", "[1, 'a']"};
  private static final String[] BOOLS = {"b", "true", "false"};

  private static String pick(SplittableRandom rnd, String[] options) {
    return options[rnd.nextInt(options.length)];
  }

  /**
   * A random expression of bounded depth. Mostly well typed, so that many evaluations succeed; now
   * and then an operand of another type is slipped in, so that many fail too.
   */
  private static String generate(SplittableRandom rnd, int depth) {
    return switch (rnd.nextInt(5)) {
      case 0 -> ints(rnd, depth);
      case 1 -> decs(rnd, depth);
      case 2 -> strs(rnd, depth);
      case 3 -> lists(rnd, depth);
      default -> bools(rnd, depth);
    };
  }

  /** An operand of the wanted type, or in one case in eight of any type. */
  private static String operand(SplittableRandom rnd, int depth, int wanted) {
    if (rnd.nextInt(8) == 0) {
      return generate(rnd, depth);
    }
    return switch (wanted) {
      case 0 -> ints(rnd, depth);
      case 1 -> decs(rnd, depth);
      case 2 -> strs(rnd, depth);
      case 3 -> lists(rnd, depth);
      default -> bools(rnd, depth);
    };
  }

  private static String ints(SplittableRandom rnd, int depth) {
    if (depth == 0) {
      return pick(rnd, INTS);
    }
    int d = depth - 1;
    return switch (rnd.nextInt(8)) {
      case 0 -> "(" + operand(rnd, d, 0) + " + " + operand(rnd, d, 0) + ")";
      case 1 -> "(" + operand(rnd, d, 0) + " - " + operand(rnd, d, 0) + ")";
      case 2 -> "(" + operand(rnd, d, 0) + " * " + operand(rnd, d, 0) + ")";
      case 3 -> "(" + operand(rnd, d, 0) + " / " + operand(rnd, d, 0) + ")";
      case 4 -> "size(" + operand(rnd, d, 2) + ")";
      case 5 -> "size(" + operand(rnd, d, 3) + ")";
      case 6 -> "(-" + operand(rnd, d, 0) + ")";
      default ->
          "(" + operand(rnd, d, 4) + " ? " + operand(rnd, d, 0) + " : " + operand(rnd, d, 0) + ")";
    };
  }

  private static String decs(SplittableRandom rnd, int depth) {
    if (depth == 0) {
      return pick(rnd, DECS);
    }
    int d = depth - 1;
    return switch (rnd.nextInt(5)) {
      case 0 -> "(" + operand(rnd, d, 1) + " + " + operand(rnd, d, 1) + ")";
      case 1 -> "(" + operand(rnd, d, 1) + " - " + operand(rnd, d, 1) + ")";
      case 2 -> "(" + operand(rnd, d, 1) + " * " + operand(rnd, d, 1) + ")";
      case 3 -> "(" + operand(rnd, d, 1) + " / " + operand(rnd, d, 1) + ")";
      default -> "(-" + operand(rnd, d, 1) + ")";
    };
  }

  private static String strs(SplittableRandom rnd, int depth) {
    if (depth == 0) {
      return pick(rnd, STRS);
    }
    int d = depth - 1;
    if (rnd.nextInt(4) == 0) {
      return "("
          + operand(rnd, d, 4)
          + " ? "
          + operand(rnd, d, 2)
          + " : "
          + operand(rnd, d, 2)
          + ")";
    }
    return "(" + operand(rnd, d, 2) + " + " + operand(rnd, d, 2) + ")";
  }

  private static String lists(SplittableRandom rnd, int depth) {
    if (depth == 0) {
      return pick(rnd, LISTS);
    }
    int d = depth - 1;
    return switch (rnd.nextInt(3)) {
      case 0 -> "(" + operand(rnd, d, 3) + " + " + operand(rnd, d, 3) + ")";
      case 1 -> "[" + operand(rnd, d, 0) + ", " + operand(rnd, d, 0) + "]";
      default ->
          "(" + operand(rnd, d, 4) + " ? " + operand(rnd, d, 3) + " : " + operand(rnd, d, 3) + ")";
    };
  }

  private static String bools(SplittableRandom rnd, int depth) {
    if (depth == 0) {
      return rnd.nextInt(3) == 0
          ? "has(" + pick(rnd, new String[] {"i", "u", "l"}) + ")"
          : pick(rnd, BOOLS);
    }
    int d = depth - 1;
    return switch (rnd.nextInt(12)) {
      case 0 -> "(" + operand(rnd, d, 0) + " < " + operand(rnd, d, 0) + ")";
      case 1 -> "(" + operand(rnd, d, 0) + " == " + operand(rnd, d, 0) + ")";
      case 2 -> "(" + operand(rnd, d, 2) + " != " + operand(rnd, d, 2) + ")";
      case 3 -> "(" + operand(rnd, d, 1) + " >= " + operand(rnd, d, 1) + ")";
      case 4 -> "(" + operand(rnd, d, 0) + " in " + operand(rnd, d, 3) + ")";
      case 5 -> "contains(" + operand(rnd, d, 3) + ", " + operand(rnd, d, 0) + ")";
      case 6 -> "contains(" + operand(rnd, d, 2) + ", " + operand(rnd, d, 2) + ")";
      case 7 -> operand(rnd, d, 2) + ".contains(" + operand(rnd, d, 2) + ")";
      case 8 -> "(!" + operand(rnd, d, 4) + ")";
      case 9 -> "(" + operand(rnd, d, 4) + " && " + operand(rnd, d, 4) + ")";
      case 10 -> "(" + operand(rnd, d, 4) + " || " + operand(rnd, d, 4) + ")";
      default -> "(" + operand(rnd, d, 3) + " == " + operand(rnd, d, 3) + ")";
    };
  }

  private static Value bigString(SplittableRandom rnd) {
    int[] sizes = {0, 1, 255, 256, 257, 2_048, 4_095, 4_096};
    return text("a".repeat(sizes[rnd.nextInt(sizes.length)]));
  }

  private static Value bigList(SplittableRandom rnd) {
    int[] sizes = {0, 1, 3, 255, 256};
    int n = sizes[rnd.nextInt(sizes.length)];
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      items.add(integer(i % 7));
    }
    return new ListValue(items);
  }

  private static Recording randomBindings(SplittableRandom rnd) {
    return bind(
        "i", integer(rnd.nextInt(5) - 2),
        "j", integer(rnd.nextBoolean() ? Long.MAX_VALUE : 3),
        "d", decimal("2.50"),
        "e", decimal("0.1"),
        "s", bigString(rnd),
        "t", bigString(rnd),
        "l", bigList(rnd),
        "m", bigList(rnd),
        "b", ExprTestSupport.bool(rnd.nextBoolean()));
  }

  /** The declared paths: those bound above plus {@code u}, which is declared and never bound. */
  private static final String[] EXTRA = {"u"};

  @Test
  void runtimeCostNeverExceedsTheStaticWorstCase() {
    SplittableRandom rnd = new SplittableRandom(7);
    InHouseExpression layer = new InHouseExpression();
    int evaluated = 0;
    int failed = 0;
    for (int n = 0; n < 2_000; n++) {
      String source = generate(rnd, 1 + rnd.nextInt(4));
      Recording bindings = randomBindings(rnd);
      InHouseExpression.Compiled compiled = ExprTestSupport.compile(layer, source, bindings, EXTRA);
      CostMeter meter = new CostMeter(new CostBudget(ExprTestSupport.BIG));
      try {
        compiled.evaluate(bindings, meter);
        evaluated++;
      } catch (ExprException e) {
        failed++;
      }
      assertThat(meter.used()).as(source).isLessThanOrEqualTo(compiled.staticWorstCaseUnits());
    }
    // the generator must exercise both successful and failing evaluations
    assertThat(evaluated).isGreaterThan(200);
    assertThat(failed).isGreaterThan(200);
  }

  /**
   * A list literal longer than the list limit is accepted (only a string literal is refused at
   * compile), so the bound must see it through a ternary too: the literal is the value of the taken
   * branch and is scanned or joined at its own length.
   */
  @Test
  void aLongListLiteralUnderATernaryStaysWithinTheStaticWorstCase() {
    String literal = "[" + "1, ".repeat(299) + "1]"; // 300 items, above the default list limit 256
    Recording bindings = bind("c", ExprTestSupport.bool(true), "l", new ListValue(List.of()));
    String[] sources = {
      "5 in (c ? " + literal + " : l)",
      "5 in (!c ? l : " + literal + ")",
      "5 in (c ? (c ? " + literal + " : l) : l)",
      "contains((c ? " + literal + " : l), 5)",
      "size((c ? " + literal + " : l) + l)",
      "size(l + (c ? " + literal + " : l))",
      "5 in (c ? " + literal + " : [1, 2])",
    };
    InHouseExpression layer = new InHouseExpression(new Limits(512, 256, 4_096));
    for (String source : sources) {
      InHouseExpression.Compiled compiled = ExprTestSupport.compile(layer, source, bindings);
      CostMeter meter = new CostMeter(new CostBudget(ExprTestSupport.BIG));
      try {
        compiled.evaluate(bindings, meter);
      } catch (ExprException e) {
        // a join of 300 and 0 items is a RANGE error after the charge; that is within the bound
        assertThat(e.kind()).as(source).isEqualTo(ErrorKind.RANGE);
      }
      assertThat(meter.used())
          .as(source)
          .isGreaterThan(300L)
          .isLessThanOrEqualTo(compiled.staticWorstCaseUnits());
    }
  }

  @Test
  void concatenationChainsAtTheLimitStayWithinTheStaticWorstCase() {
    SplittableRandom rnd = new SplittableRandom(7);
    InHouseExpression layer = new InHouseExpression();
    Value fullString = text("a".repeat(4_096));
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      items.add(integer(i));
    }
    Value fullList = new ListValue(items);
    for (int n = 0; n < 200; n++) {
      boolean strings = rnd.nextBoolean();
      int operands = 2 + rnd.nextInt(5);
      StringBuilder source = new StringBuilder();
      for (int k = 0; k < operands; k++) {
        if (k > 0) {
          source.append(" + ");
        }
        source.append(strings ? (rnd.nextBoolean() ? "s" : "''") : rnd.nextBoolean() ? "l" : "[]");
      }
      Recording bindings = bind("s", fullString, "l", fullList);
      InHouseExpression.Compiled compiled =
          ExprTestSupport.compile(layer, source.toString(), bindings);
      CostMeter meter = new CostMeter(new CostBudget(ExprTestSupport.BIG));
      try {
        compiled.evaluate(bindings, meter);
      } catch (ExprException e) {
        assertThat(e.kind()).isEqualTo(ErrorKind.RANGE);
      }
      assertThat(meter.used())
          .as(source.toString())
          .isLessThanOrEqualTo(compiled.staticWorstCaseUnits());
    }
  }
}
