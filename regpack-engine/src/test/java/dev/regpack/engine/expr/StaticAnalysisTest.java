package dev.regpack.engine.expr;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.ports.expr.ValueType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaticAnalysisTest {
  private static final Map<String, ValueType> VARS = variables();

  private static Map<String, ValueType> variables() {
    Map<String, ValueType> vars = new HashMap<>();
    for (String name : List.of("a", "b", "x", "y", "s", "t", "c", "z.y", "a.b", "m.n")) {
      vars.put(name, ValueType.STRING);
    }
    return Map.copyOf(vars);
  }

  /** The limits of the plan: lists of 256 items and strings of 4,096 code points. */
  private static final InHouseExpression LAYER = new InHouseExpression(Limits.defaults());

  private static InHouseExpression.Compiled compile(String source) {
    return LAYER.compile(source, VARS);
  }

  @Test
  void nodeCountAndPathsAreReported() {
    InHouseExpression.Compiled compiled = compile("m.n == 'x' && has(a.b) && z.y > 1 || a");
    // m.n, 'x', ==, a.b (has), &&, z.y, 1, >, &&, a, ||
    assertThat(compiled.nodeCount()).isEqualTo(11);
    assertThat(compiled.paths()).containsExactly("a", "a.b", "m.n", "z.y");
    // has(a.b) is one node: the path argument is not a node, but the path is reported
    InHouseExpression.Compiled has = compile("has(a.b)");
    assertThat(has.nodeCount()).isEqualTo(1);
    assertThat(has.staticWorstCaseUnits()).isEqualTo(1);
    assertThat(has.paths()).containsExactly("a.b");
    assertThat(compile("1 + 2").paths()).isEmpty();
  }

  @Test
  void worstCaseOfALiteralIsOne() {
    assertThat(compile("1").staticWorstCaseUnits()).isEqualTo(1);
    assertThat(compile("'abc'").staticWorstCaseUnits()).isEqualTo(1);
    assertThat(compile("true").staticWorstCaseUnits()).isEqualTo(1);
    assertThat(compile("12.50").staticWorstCaseUnits()).isEqualTo(1);
    assertThat(compile("a").staticWorstCaseUnits()).isEqualTo(1);
    assertThat(compile("1 + 2").staticWorstCaseUnits()).isEqualTo(3 + 32 + 512);
  }

  @Test
  void worstCaseChargesListAndStringSurchargesAtTheLimits() {
    // x in y: 3 nodes, the list scan of at most 256 items
    assertThat(compile("x in y").staticWorstCaseUnits()).isEqualTo(3 + 256);
    // s + t: 3 nodes, two strings of 4,096 code points / 256 each, plus both list sizes
    assertThat(compile("s + t").staticWorstCaseUnits()).isEqualTo(3 + 2 * 4096 / 256 + 256 + 256);
    assertThat(compile("s + t").staticWorstCaseUnits()).isEqualTo(547);
    // decimal division and the other calls
    assertThat(compile("a / b").staticWorstCaseUnits()).isEqualTo(3 + 1);
    assertThat(compile("a - b").staticWorstCaseUnits()).isEqualTo(3);
    assertThat(compile("a * b == c").staticWorstCaseUnits()).isEqualTo(5);
    assertThat(compile("upper(s)").staticWorstCaseUnits()).isEqualTo(2 + 4096 / 256);
    assertThat(compile("dec(s)").staticWorstCaseUnits()).isEqualTo(2 + 1 + 4096 / 256);
    assertThat(compile("timestamp(s)").staticWorstCaseUnits()).isEqualTo(2 + 1 + 16);
    assertThat(compile("date(s)").staticWorstCaseUnits()).isEqualTo(2 + 1 + 16);
    assertThat(compile("round(a, 2, 'HALF_UP')").staticWorstCaseUnits()).isEqualTo(4 + 1);
    assertThat(compile("size(s)").staticWorstCaseUnits()).isEqualTo(2 + 16);
    assertThat(compile("int(s)").staticWorstCaseUnits()).isEqualTo(2 + 16);
    assertThat(compile("string(a)").staticWorstCaseUnits()).isEqualTo(2 + 16);
    assertThat(compile("isoMicros(a)").staticWorstCaseUnits()).isEqualTo(2);
    assertThat(compile("dayOfWeek(a)").staticWorstCaseUnits()).isEqualTo(2);
    assertThat(compile("s.startsWith(t)").staticWorstCaseUnits()).isEqualTo(3 + 2 * 4096 / 256);
    assertThat(compile("s.endsWith('a')").staticWorstCaseUnits()).isEqualTo(3 + 32);
    // contains: the larger of the list scan (256) and the string product (17 x 17 - 1 = 288)
    assertThat(compile("contains(s, t)").staticWorstCaseUnits()).isEqualTo(3 + 288);
    assertThat(compile("s.contains(t)").staticWorstCaseUnits()).isEqualTo(3 + 288);
    assertThat(compile("contains([1, 2], x)").staticWorstCaseUnits()).isEqualTo(5 + 2);
  }

  @Test
  void theBoundFollowsTheConfiguredLimits() {
    InHouseExpression small = new InHouseExpression(new Limits(512, 8, 512));
    assertThat(small.compile("x in y", VARS).staticWorstCaseUnits()).isEqualTo(3 + 8);
    assertThat(small.compile("s + t", VARS).staticWorstCaseUnits())
        .isEqualTo(3 + 2 * 512 / 256 + 8 + 8);
    assertThat(small.compile("contains(s, t)", VARS).staticWorstCaseUnits())
        .isEqualTo(3 + (3L * 3 - 1));
  }

  @Test
  void aListLiteralUsesItsOwnLength() {
    // x in [1, 2, 3]: x, 1, 2, 3, the list and `in` are six nodes, plus three scanned items
    assertThat(compile("x in [1, 2, 3]").staticWorstCaseUnits()).isEqualTo(6 + 3);
    assertThat(compile("[1, 2] + [3]").staticWorstCaseUnits())
        .isEqualTo(6 + 2 * 4096 / 256 + 2 + 1);
    assertThat(compile("x in []").staticWorstCaseUnits()).isEqualTo(3);
    // a list literal is an operand like any other: a non-literal operand is bounded by the limit
    assertThat(compile("x in [1] + y").staticWorstCaseUnits())
        .isEqualTo(6 + 2 * 4096 / 256 + 1 + 256 + 256);
  }

  @Test
  void bothBranchesOfATernaryAreCounted() {
    // the taken branch is not known statically: condition, both branches and the ternary itself
    assertThat(compile("a ? 1 : 2").staticWorstCaseUnits()).isEqualTo(4);
    assertThat(compile("a ? x in y : 2").staticWorstCaseUnits()).isEqualTo(1 + 1 + 259 + 1);
    assertThat(compile("a ? s + t : x in y").staticWorstCaseUnits())
        .isEqualTo(1 + 1 + 547 + 259 + 0);
    assertThat(compile("a ? 1 : 2").nodeCount()).isEqualTo(4);
  }

  @Test
  void aSumOverAllNodesNotJustTheTaken() {
    // a && (x in y): the right operand is counted although && may short-circuit
    assertThat(compile("a && x in y").staticWorstCaseUnits()).isEqualTo(1 + 259 + 1);
    assertThat(compile("a || x in y").staticWorstCaseUnits()).isEqualTo(1 + 259 + 1);
  }
}
