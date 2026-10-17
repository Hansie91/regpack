package dev.regpack.engine.expr;

import static dev.regpack.engine.expr.ExprTestSupport.bind;
import static dev.regpack.engine.expr.ExprTestSupport.bool;
import static dev.regpack.engine.expr.ExprTestSupport.decimal;
import static dev.regpack.engine.expr.ExprTestSupport.eval;
import static dev.regpack.engine.expr.ExprTestSupport.integer;
import static dev.regpack.engine.expr.ExprTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.ExprTestSupport.Recording;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.ListValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EvaluatorTest {
  private static final Value TRUE = bool(true);
  private static final Value FALSE = bool(false);

  private static void assertError(ErrorKind kind, String source) {
    assertError(kind, source, bind());
  }

  private static void assertError(
      ErrorKind kind, String source, Recording bindings, String... declared) {
    assertThatThrownBy(() -> eval(source, bindings, declared))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).as(source).isEqualTo(kind));
  }

  @Test
  void decimalArithmeticIsExact() {
    assertThat(eval("0.1 + 0.2 == 0.3")).isEqualTo(TRUE);
    assertThat(eval("0.1 * 3 == 0.3")).isEqualTo(TRUE); // int with decimal: allowed (D13)
    assertThat(eval("0.1 * 3.0 == 0.3")).isEqualTo(TRUE);
    assertThat(eval("1234.5 / 100 == 12.345")).isEqualTo(TRUE);
    assertThat(eval("0.30 < 0.4")).isEqualTo(TRUE);
    assertThat(eval("0.5 - 0.25")).isEqualTo(decimal("0.25"));
    assertThat(eval("-0.5")).isEqualTo(decimal("-0.5"));
  }

  @Test
  void decimalEqualityIgnoresScale() {
    assertThat(eval("1.10 == 1.1")).isEqualTo(TRUE);
    assertThat(eval("1 == 1.0")).isEqualTo(TRUE);
    assertThat(eval("1.10 != 1.1")).isEqualTo(FALSE);
    assertThat(eval("1.10 <= 1.1 && 1.10 >= 1.1")).isEqualTo(TRUE);
  }

  @Test
  void integerOverflowIsAnError() {
    Recording max = bind("x", integer(Long.MAX_VALUE));
    Recording min = bind("x", integer(Long.MIN_VALUE));
    assertError(ErrorKind.OVERFLOW, "x + 1", max);
    assertError(ErrorKind.OVERFLOW, "x - -1", max);
    assertError(ErrorKind.OVERFLOW, "x * 2", max);
    assertError(ErrorKind.OVERFLOW, "x - 1", min);
    assertError(ErrorKind.OVERFLOW, "x + -1", min);
    assertError(ErrorKind.OVERFLOW, "x * -1", min);
    assertError(ErrorKind.OVERFLOW, "-x", min);
    assertError(ErrorKind.OVERFLOW, "x / -1", min);
    assertThat(eval("x + 0", max)).isEqualTo(integer(Long.MAX_VALUE));
    assertThat(eval("-x", max)).isEqualTo(integer(-Long.MAX_VALUE));
  }

  @Test
  void divisionRules() {
    assertThat(eval("7 / 2")).isEqualTo(integer(3));
    assertThat(eval("-7 / 2")).isEqualTo(integer(-3));
    assertThat(eval("1 / 3")).isEqualTo(integer(0)); // int / int truncates (companion section 3)
    assertError(ErrorKind.NON_TERMINATING, "1.0 / 3.0");
    assertThat(eval("1.0 / 4.0 == 0.25")).isEqualTo(TRUE);
    assertError(ErrorKind.DIV_ZERO, "1 / 0");
    assertError(ErrorKind.DIV_ZERO, "0.5 / 0.0");
    assertError(ErrorKind.DIV_ZERO, "0.5 / 0");
  }

  @Test
  void decimalsAreLimitedTo38Digits() {
    Recording big = bind("x", decimal("99999999999999999999999999999999999999")); // 38 digits
    assertThat(eval("x + 0", big)).isEqualTo(decimal("99999999999999999999999999999999999999"));
    assertError(ErrorKind.SCALE, "x * 10.0", big);
    assertError(ErrorKind.SCALE, "x + 1", big);
  }

  @Test
  void absenceIsAnErrorUnlessGuarded() {
    assertError(ErrorKind.ABSENT, "x == 1", bind(), "x");
    assertThat(eval("has(x)", bind(), "x")).isEqualTo(FALSE);
    assertThat(eval("has(x) && x == 1", bind(), "x")).isEqualTo(FALSE);
    assertThat(eval("has(x) ? x : 5", bind(), "x")).isEqualTo(integer(5));
    Recording bound = bind("x", integer(1));
    assertThat(eval("has(x) && x == 1", bound)).isEqualTo(TRUE);
    assertThat(eval("has(x) ? x : 5", bound)).isEqualTo(integer(1));
  }

  @Test
  void andOrShortCircuitLeftToRightAndErrorsPropagate() {
    assertThat(eval("true || (1 / 0 == 1)")).isEqualTo(TRUE);
    assertError(ErrorKind.DIV_ZERO, "(1 / 0 == 1) || true");
    assertThat(eval("false && (1 / 0 == 1)")).isEqualTo(FALSE);
    assertError(ErrorKind.DIV_ZERO, "(1 / 0 == 1) && false");
    assertError(ErrorKind.DIV_ZERO, "true && (1 / 0 == 1)");
    assertError(ErrorKind.DIV_ZERO, "false || (1 / 0 == 1)");
    // false && x never reads x
    Recording bindings = bind("y", integer(1));
    assertThat(eval("false && x == 1", bindings, "x")).isEqualTo(FALSE);
    assertThat(bindings.reads()).doesNotContain("x");
    Recording other = bind("y", integer(1));
    assertThat(eval("true || x == 1", other, "x")).isEqualTo(TRUE);
    assertThat(other.reads()).doesNotContain("x");
    // a non-bool operand is a type error
    assertError(ErrorKind.TYPE, "1 && true");
    assertError(ErrorKind.TYPE, "true && 1");
    assertError(ErrorKind.TYPE, "false || 1");
    assertError(ErrorKind.TYPE, "1 || true");
  }

  @Test
  void ternaryNeedsABool() {
    assertError(ErrorKind.TYPE, "1 ? 2 : 3");
    assertThat(eval("true ? 2 : 3")).isEqualTo(integer(2));
    assertThat(eval("false ? 2 : true ? 4 : 5")).isEqualTo(integer(4));
    // the branch not taken is not evaluated, so its error does not occur
    assertThat(eval("true ? 1 : 1 / 0")).isEqualTo(integer(1));
  }

  @Test
  void inAndContains() {
    assertThat(eval("'DEAL' in ['DEAL', 'MTCH', 'AOTC']")).isEqualTo(TRUE);
    assertThat(eval("'XXXX' in ['DEAL', 'MTCH', 'AOTC']")).isEqualTo(FALSE);
    assertThat(eval("1 in [1.0, 2]")).isEqualTo(TRUE);
    assertThat(eval("3 in []")).isEqualTo(FALSE);
    assertError(ErrorKind.TYPE, "1 in 'abc'");
    assertError(ErrorKind.TYPE, "1 in ['a']");
    assertThat(eval("contains([1, 2, 3], 2)")).isEqualTo(TRUE);
    assertThat(eval("contains([1, 2, 3], 4)")).isEqualTo(FALSE);
    assertThat(eval("contains('abc', 'bc')")).isEqualTo(TRUE);
    assertThat(eval("'abc'.contains('x')")).isEqualTo(FALSE);
    assertError(ErrorKind.TYPE, "contains('abc', 1)");
    assertError(ErrorKind.TYPE, "contains(1, 1)");
  }

  @Test
  void typeClashesAreTypeErrors() {
    assertError(ErrorKind.TYPE, "1 < 'a'");
    assertError(ErrorKind.TYPE, "1 == 'a'");
    assertError(ErrorKind.TYPE, "!1");
    assertError(ErrorKind.TYPE, "true + 1");
    assertError(ErrorKind.TYPE, "-'a'");
    assertError(ErrorKind.TYPE, "'a' - 'b'");
    assertError(ErrorKind.TYPE, "true < false");
    assertError(ErrorKind.TYPE, "[1] == 1");
  }

  @Test
  void enumsCompareByNameWithStrings() {
    Recording kinds =
        bind(
            "e",
            new EnumValue("LifecycleKind", "NEW"),
            "f",
            new EnumValue("LifecycleKind", "NEW"),
            "g",
            new EnumValue("Other", "NEW"),
            "h",
            new EnumValue("LifecycleKind", "CANCEL"));
    assertThat(eval("e == 'NEW'", kinds)).isEqualTo(TRUE);
    assertThat(eval("'NEW' == e", kinds)).isEqualTo(TRUE);
    assertThat(eval("e == 'CANCEL'", kinds)).isEqualTo(FALSE);
    assertThat(eval("e != 'NEW'", kinds)).isEqualTo(FALSE);
    assertThat(eval("e == f", kinds)).isEqualTo(TRUE);
    assertThat(eval("e == h", kinds)).isEqualTo(FALSE);
    assertThat(eval("e == g", kinds)).isEqualTo(FALSE); // same name, another enum type
    assertThat(eval("e in ['NEW', 'AMEND']", kinds)).isEqualTo(TRUE);
    assertError(ErrorKind.TYPE, "e < h", kinds);
    assertError(ErrorKind.TYPE, "e == 1", kinds);
  }

  @Test
  void listsAndStringsConcatenate() {
    assertThat(eval("[1, 2] + [3] == [1, 2, 3]")).isEqualTo(TRUE);
    assertThat(eval("[1, 2] + [3]"))
        .isEqualTo(new ListValue(List.of(integer(1), integer(2), integer(3))));
    assertThat(eval("'A' + 'B' == 'AB'")).isEqualTo(TRUE);
    assertThat(eval("[1, 2] == [1, 2.0]")).isEqualTo(TRUE);
    assertThat(eval("[1, 2] == [1]")).isEqualTo(FALSE);
    assertThat(eval("'a' < 'b' && 'B' < 'a'")).isEqualTo(TRUE);
    assertError(ErrorKind.TYPE, "'a' + 1");
    assertError(ErrorKind.TYPE, "[1] + 'a'");
  }

  @Test
  void sizeCountsCodePointsNotUtf16() {
    String grin = new String(Character.toChars(0x1F600));
    assertThat(eval("size('" + grin + "') == 1")).isEqualTo(TRUE);
    assertThat(eval("size('é') == 1")).isEqualTo(TRUE);
    assertThat(eval("size('" + grin + grin + "a')")).isEqualTo(integer(3));
    assertThat(eval("size([1, 2, 3]) == 3")).isEqualTo(TRUE);
    assertError(ErrorKind.TYPE, "size(1)");
  }

  @Test
  void resultsOverTheLimitAreRangeErrors() {
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      items.add(integer(i));
    }
    ListValue full = new ListValue(items);
    Recording lists = bind("a", full, "b", full, "c", full);
    assertThat(eval("size(a + [])", lists)).isEqualTo(integer(256));
    CostMeter meter = new CostMeter(new CostBudget(ExprTestSupport.BIG));
    InHouseExpression.Compiled compiled =
        ExprTestSupport.compile(new InHouseExpression(), "a + b + c", lists);
    assertThatThrownBy(() -> compiled.evaluate(lists, meter))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.RANGE));
    // RANGE at the first +: the descent and the two reads were charged, then both list sizes
    assertThat(meter.used()).isEqualTo(4 + 512);
    // an input list within the limit is fine; one over it never reaches a result
    Recording strings = bind("s", text("x".repeat(4_096)));
    assertThat(eval("size(s + '')", strings)).isEqualTo(integer(4_096));
    assertError(ErrorKind.RANGE, "s + 'x'", strings);
  }

  /** Evaluates on a thread with a small stack and returns what it produced or threw. */
  private static Object onSmallStack(String source) {
    InHouseExpression.Compiled compiled = new InHouseExpression().compile(source, Map.of());
    AtomicReference<Object> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                outcome.set(
                    compiled.evaluate(bind(), new CostMeter(new CostBudget(ExprTestSupport.BIG))));
              } catch (Throwable t) {
                outcome.set(t);
              }
            },
            "deep",
            256 * 1024);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return outcome.get();
  }

  @Test
  void deepestAllowedTreeEvaluatesOnASmallStack() {
    // a left chain of the maximal tree depth (256): 255 additions, 511 nodes
    String chain = "1" + " + 1".repeat(255);
    assertThat(new InHouseExpression().compile(chain, Map.of()).nodeCount()).isEqualTo(511);
    assertThat(onSmallStack(chain)).isEqualTo(integer(256));
    // '&&' and '||' use three frames per level (eval, binary, and/or): the worst frame count
    assertThat(onSmallStack("true" + " && true".repeat(255))).isEqualTo(bool(true));
    assertThat(onSmallStack("false" + " || false".repeat(255))).isEqualTo(bool(false));
    // a right-nested ternary of the maximal syntactic nesting (100)
    String ternary = "false ? 0 : ".repeat(100) + "7";
    assertThat(onSmallStack(ternary)).isEqualTo(integer(7));
    // nested negations and parentheses at the nesting limit
    assertThat(onSmallStack("-".repeat(99) + "1")).isEqualTo(integer(-1));
    // never a StackOverflowError: a result or an ExprException
    assertThat(onSmallStack("(1 + 1) / 0")).isInstanceOf(ExprException.class);
  }

  // ---- inputs are held to the limits on entry (FX1) --------------------------------------------

  private static Value wideDecimal(int digits) {
    return new Value.DecimalValue(new java.math.BigDecimal(java.math.BigInteger.TEN.pow(digits)));
  }

  private static void assertErrorWithShortMessage(
      ErrorKind kind, String source, Recording bindings, String... declared) {
    assertThatThrownBy(() -> eval(source, bindings, declared))
        .isInstanceOfSatisfying(
            ExprException.class,
            e -> {
              assertThat(e.kind()).as(source).isEqualTo(kind);
              assertThat(e.getMessage()).hasSizeLessThan(200);
            });
  }

  @Test
  void aDecimalInputWiderThan38DigitsIsRefusedBeforeAnyUse() {
    Recording wide = bind("q", wideDecimal(200_000), "p", wideDecimal(200_000));
    for (String source :
        List.of(
            "q / p > 1.0",
            "q > 1.0",
            "q == p",
            "q + 1.0 > 1.0",
            "-q > 1.0",
            "string(q) == 'x'",
            "round(q, 2, 'HALF_UP') > 1.0",
            "int(q) > 1",
            "has(q)",
            "has(q) && q > 1.0")) {
      assertErrorWithShortMessage(ErrorKind.SCALE, source, wide);
    }
    // a scale beyond 38 in either direction, with few digits
    assertError(
        ErrorKind.SCALE,
        "q > 1.0",
        bind("q", decimal("0.1234567890123456789012345678901234567890")));
    assertError(ErrorKind.SCALE, "q > 1.0", bind("q", decimal("1E+39")));
    assertError(ErrorKind.SCALE, "has(q)", bind("q", decimal("1E+39")));
    // a list element is held to the same limit
    assertError(
        ErrorKind.SCALE, "size(l) > 0", bind("l", new ListValue(List.of(wideDecimal(200_000)))));
  }

  @Test
  void aDecimalInputOf38DigitsStillEvaluates() {
    Recording at = bind("q", decimal("99999999999999999999999999999999999999"));
    assertThat(eval("q > 1.0", at)).isEqualTo(TRUE);
    assertThat(eval("has(q)", at)).isEqualTo(TRUE);
    Recording scaled = bind("q", decimal("0.99999999999999999999999999999999999999")); // scale 38
    assertThat(eval("q < 1.0", scaled)).isEqualTo(TRUE);
    assertThat(eval("q > 1.0", bind("q", decimal("1E+38")))).isEqualTo(TRUE);
  }

  @Test
  void aStringInputLongerThanTheLimitIsRefusedBeforeAnyUse() {
    Recording huge = bind("s", text("a".repeat(1_000_000)), "t", text("b"));
    for (String source :
        List.of(
            "size(s) > 0",
            "dec(s) > 1.5",
            "s == t",
            "s + t == t",
            "contains(s, t)",
            "s.contains(t)",
            "t.contains(s)",
            "upper(s) == t",
            "s.startsWith(t)",
            "s < t",
            "has(s)",
            "has(s) && size(s) > 0")) {
      assertErrorWithShortMessage(ErrorKind.RANGE, source, huge);
    }
    // 4097 code points are over the limit, 4096 are not (code points, not chars)
    String grin = new String(Character.toChars(0x1F600));
    assertError(ErrorKind.RANGE, "size(s) > 0", bind("s", text("a".repeat(4097))));
    assertThat(eval("size(s) == 4096", bind("s", text("a".repeat(4096))))).isEqualTo(TRUE);
    assertThat(eval("size(s) == 4096", bind("s", text(grin.repeat(4096))))).isEqualTo(TRUE);
    assertError(ErrorKind.RANGE, "size(s) > 0", bind("s", text(grin.repeat(4097))));
    assertThat(eval("has(s)", bind("s", text("a".repeat(4096))))).isEqualTo(TRUE);
    // a string inside a list is held to the limit as well
    assertError(
        ErrorKind.RANGE, "size(l) > 0", bind("l", new ListValue(List.of(text("a".repeat(4097))))));
  }

  @Test
  void aListInputLongerThanTheLimitIsRefusedBeforeAnyUse() {
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < 257; i++) {
      items.add(integer(i));
    }
    Recording longList = bind("l", new ListValue(items), "x", integer(1));
    for (String source :
        List.of("size(l) > 0", "x in l", "contains(l, x)", "l == l", "has(l)", "l + l == l")) {
      assertErrorWithShortMessage(ErrorKind.RANGE, source, longList);
    }
    assertThat(eval("size(l) == 256", bind("l", new ListValue(items.subList(0, 256)))))
        .isEqualTo(TRUE);
  }

  @Test
  void anOversizedInputIsChargedItsSizeBeforeItIsRefused() {
    List<Value> items = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      items.add(integer(i));
    }
    Recording longList = bind("l", new ListValue(items));
    Recording longText = bind("s", text("a".repeat(100_000)));
    var listCall = ExprTestSupport.compile(new InHouseExpression(), "size(l)", longList);
    var textCall = ExprTestSupport.compile(new InHouseExpression(), "size(s)", longText);
    assertThatThrownBy(() -> listCall.evaluate(longList, new CostMeter(new CostBudget(100))))
        .isInstanceOf(BudgetExceededException.class);
    assertThatThrownBy(() -> textCall.evaluate(longText, new CostMeter(new CostBudget(100))))
        .isInstanceOf(BudgetExceededException.class);
  }
}
