package dev.regpack.engine.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ParserTest {
  private static final Map<String, ValueType> VARS = variables();

  private static Map<String, ValueType> variables() {
    Map<String, ValueType> vars = new HashMap<>();
    for (String name :
        List.of("a", "b", "c", "x", "y", "s", "l", "f", "instrument.isin", "a.b", "client.lei")) {
      vars.put(name, ValueType.STRING);
    }
    return Map.copyOf(vars);
  }

  private static final InHouseExpression LAYER = new InHouseExpression();

  private static InHouseExpression.Compiled compile(String source) {
    return LAYER.compile(source, VARS);
  }

  private static InHouseExpression.Compiled compile(String source, Limits limits) {
    return new InHouseExpression(limits).compile(source, VARS);
  }

  /** The tree as a prefix expression, so that grouping can be asserted. */
  private static String show(Ast.Node node) {
    return switch (node) {
      case Ast.Lit lit -> showValue(lit.value());
      case Ast.ListLit list -> "[" + showAll(list.items()) + "]";
      case Ast.Path path -> path.path();
      case Ast.Has has -> "has(" + has.path() + ")";
      case Ast.Unary u -> "(" + u.op() + " " + show(u.operand()) + ")";
      case Ast.Binary b -> "(" + b.op() + " " + show(b.left()) + " " + show(b.right()) + ")";
      case Ast.Ternary t ->
          "(? " + show(t.cond()) + " " + show(t.whenTrue()) + " " + show(t.whenFalse()) + ")";
      case Ast.Call c -> "(" + c.fn().label() + " " + showAll(c.args()) + ")";
    };
  }

  private static String showAll(List<Ast.Node> nodes) {
    List<String> parts = new ArrayList<>();
    for (Ast.Node node : nodes) {
      parts.add(show(node));
    }
    return String.join(" ", parts);
  }

  private static String showValue(Value value) {
    return switch (value) {
      case IntValue i -> Long.toString(i.value());
      case DecimalValue d -> d.value().toPlainString();
      case StringValue s -> "'" + s.value() + "'";
      case BoolValue b -> Boolean.toString(b.value());
      default -> throw new AssertionError(value);
    };
  }

  private static String tree(String source) {
    return show(Parser.parse(source, Limits.defaults(), VARS.keySet()).root());
  }

  private static void assertParseError(String source) {
    assertThatThrownBy(() -> compile(source))
        .as(source)
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
  }

  @Test
  void precedenceAndAssociativity() {
    assertThat(tree("1 + 2 * 3")).isEqualTo("(ADD 1 (MUL 2 3))");
    assertThat(tree("(1 + 2) * 3")).isEqualTo("(MUL (ADD 1 2) 3)");
    assertThat(tree("a - b - c")).isEqualTo("(SUB (SUB a b) c)");
    assertThat(tree("a / b * c")).isEqualTo("(MUL (DIV a b) c)");
    assertThat(tree("!a && b")).isEqualTo("(AND (NOT a) b)");
    assertThat(tree("a || b && c")).isEqualTo("(OR a (AND b c))");
    assertThat(tree("a && b || c")).isEqualTo("(OR (AND a b) c)");
    assertThat(tree("a == b + 1")).isEqualTo("(EQ a (ADD b 1))");
    assertThat(tree("a < b == c")).isEqualTo("(EQ (LT a b) c)");
    assertThat(tree("a in l && b")).isEqualTo("(AND (IN a l) b)");
    assertThat(tree("-a * b")).isEqualTo("(MUL (NEG a) b)");
    assertThat(tree("-a.b")).isEqualTo("(NEG a.b)");
  }

  @Test
  void ternaryIsRightAssociativeAndLowest() {
    assertThat(tree("a ? b : c ? x : y")).isEqualTo("(? a b (? c x y))");
    assertThat(tree("a || b ? x : y")).isEqualTo("(? (OR a b) x y)");
    assertThat(tree("a ? b ? x : y : c")).isEqualTo("(? a (? b x y) c)");
  }

  @Test
  void literalsAndLists() {
    assertThat(tree("[1, 2.50, 'a', \"b\", true, false]")).isEqualTo("[1 2.50 'a' 'b' true false]");
    assertThat(tree("[]")).isEqualTo("[]");
    assertThat(tree("x in ['A','B']")).isEqualTo("(IN x ['A' 'B'])");
    assertParseError("[1, 2,]");
    assertParseError("[1 2]");
    assertParseError("[1, 2");
  }

  @Test
  void nullLiteralIsRefused() {
    assertParseError("null");
    assertParseError("a == null");
    assertParseError("[null]");
  }

  @Test
  void loopMacrosAreRefused() {
    for (String macro : List.of("all", "exists", "exists_one", "map", "filter")) {
      assertThatThrownBy(() -> compile("l." + macro + "(x, x == 1)"))
          .isInstanceOfSatisfying(
              ExprException.class,
              e -> {
                assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
                assertThat(e.getMessage()).contains("loop macro " + macro);
              });
      assertParseError(macro + "(l, x == 1)");
    }
  }

  @Test
  void unknownFunctionAndMethodAreRefused() {
    assertParseError("nosuch(1)");
    assertParseError("a.nosuch('x')");
    assertParseError("'abc'.nosuch()");
    assertParseError("startsWith(s, 'a')");
    assertParseError("s.upper()");
    assertParseError("now()");
  }

  @Test
  void arityIsChecked() {
    assertParseError("dec()");
    assertParseError("dec(1, 2)");
    assertParseError("round(dec('1.5'), 0)");
    assertParseError("contains(l)");
    assertParseError("s.startsWith()");
    assertParseError("s.startsWith('a', 'b')");
    assertParseError("s.contains()");
    assertThat(tree("round(dec('1.5'), 0, 'HALF_UP')"))
        .isEqualTo("(round (dec '1.5') 0 'HALF_UP')");
    assertThat(tree("contains(l, x)")).isEqualTo("(contains l x)");
    assertThat(tree("l.contains(x)")).isEqualTo("(contains l x)");
    assertThat(tree("s.startsWith('a')")).isEqualTo("(startsWith s 'a')");
  }

  @Test
  void anUndeclaredPathIsACompileError() {
    assertParseError("z");
    assertParseError("a.z");
    assertParseError("a == z");
    assertParseError("has(z)");
    assertParseError("has(a.z)");
    assertParseError("a.b.c");
    assertThat(compile("a.b").paths()).containsExactly("a.b");
  }

  @Test
  void fieldAccessOnANonPathIsRefused() {
    assertParseError("'abc'.length");
    assertParseError("(a).b");
    assertParseError("dec(a).scale");
    assertParseError("[1].size");
    assertParseError("a.1");
    assertParseError("a.");
    assertParseError("has(1)");
    assertParseError("has(a.b())");
    assertParseError("has(a + b)");
    assertParseError("has(a, b)");
    assertParseError("has()");
  }

  @Test
  void methodCallOnAnyExpressionIsAccepted() {
    assertThat(tree("'abc'.startsWith('a')")).isEqualTo("(startsWith 'abc' 'a')");
    assertThat(tree("(a + b).endsWith('z')")).isEqualTo("(endsWith (ADD a b) 'z')");
    assertThat(tree("s.startsWith('a').contains(x)")).isEqualTo("(contains (startsWith s 'a') x)");
    assertThat(tree("[1, 2].contains(1)")).isEqualTo("(contains [1 2] 1)");
  }

  @Test
  void methodCallOnADottedDeclaredPath() {
    assertThat(tree("instrument.isin.startsWith('ZZ')"))
        .isEqualTo("(startsWith instrument.isin 'ZZ')");
    assertThat(compile("instrument.isin.startsWith('ZZ')").paths())
        .containsExactly("instrument.isin");
    assertThatThrownBy(() -> compile("instrument.isin.nosuch('x')"))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
    // the undeclared prefix is reported, not accepted as a method receiver
    assertParseError("instrument.nosuch.startsWith('x')");
  }

  @Test
  void nodeCeilingIsEnforcedWhileParsing() {
    // a well-formed list of 3,000 items is refused at the ceiling
    StringBuilder source = new StringBuilder("[");
    for (int i = 0; i < 3_000; i++) {
      source.append(i == 0 ? "1" : ", 1");
    }
    source.append(']');
    Limits limits = new Limits(1_000, 256, 4_096);
    assertThatThrownBy(() -> compile(source.toString(), limits))
        .isInstanceOfSatisfying(
            ExprException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
              assertThat(e.getMessage()).contains("1000");
            });
    // the ceiling is checked while parsing: the same list, over the ceiling and never closed, is
    // refused for its size, not for the missing bracket that a post-parse check would report
    String unclosed = "[" + "1,".repeat(3_000);
    assertThatThrownBy(() -> compile(unclosed, limits))
        .isInstanceOfSatisfying(
            ExprException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
              assertThat(e.getMessage())
                  .contains("ceiling")
                  .contains("1000")
                  .doesNotContain("expected");
            });
    // exactly at the ceiling is accepted, one node more is not
    Limits five = new Limits(5, 256, 4_096);
    assertThat(compile("1 + 2 + 3", five).nodeCount()).isEqualTo(5);
    assertThatThrownBy(() -> compile("1 + 2 + 3 + 4", five))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
  }

  @Test
  void everyLimitMustBeAtLeastOne() {
    assertThatThrownBy(() -> new Limits(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits(1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits(1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> new Limits(1, 1, 1)).doesNotThrowAnyException();
  }

  @Test
  void nestingAndDepthLimits() {
    Limits roomy = new Limits(100_000, 256, 4_096);
    // syntactic nesting: parentheses
    assertThatCode(() -> compile("(".repeat(100) + "1" + ")".repeat(100), roomy))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> compile("(".repeat(101) + "1" + ")".repeat(101), roomy))
        .isInstanceOfSatisfying(
            ExprException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
              assertThat(e.getMessage()).contains("100");
            });
    // syntactic nesting: unary chains, list brackets and right-nested ternaries
    assertThatCode(() -> compile("!".repeat(100) + "true", roomy)).doesNotThrowAnyException();
    assertThatThrownBy(() -> compile("!".repeat(101) + "true", roomy))
        .isInstanceOf(ExprException.class);
    assertThatCode(() -> compile("[".repeat(100) + "]".repeat(100), roomy))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> compile("[".repeat(101) + "]".repeat(101), roomy))
        .isInstanceOf(ExprException.class);
    assertThatCode(() -> compile("true ? 1 : ".repeat(100) + "1", roomy))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> compile("true ? 1 : ".repeat(101) + "1", roomy))
        .isInstanceOf(ExprException.class);
    // syntactic nesting: call-argument lists
    assertThatCode(() -> compile("dec(".repeat(100) + "1" + ")".repeat(100), roomy))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> compile("dec(".repeat(101) + "1" + ")".repeat(101), roomy))
        .isInstanceOfSatisfying(
            ExprException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
              assertThat(e.getMessage()).contains("100");
            });
    // tree depth: a left chain of 256 terms is the deepest allowed tree
    assertThatCode(() -> compile("1" + " + 1".repeat(255), roomy)).doesNotThrowAnyException();
    assertThatThrownBy(() -> compile("1" + " + 1".repeat(256), roomy))
        .isInstanceOfSatisfying(
            ExprException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
              assertThat(e.getMessage()).contains("256");
            });
  }

  @Test
  void aLongLeftChainFailsCleanlyNotWithStackOverflow() throws InterruptedException {
    String source = "1" + " + 1".repeat(49_999);
    Limits roomy = new Limits(100_000, 256, 4_096);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    // a small stack proves that no recursion is proportional to the chain length
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                compile(source, roomy);
              } catch (Throwable t) {
                thrown.set(t);
              }
            },
            "chain",
            256 * 1024);
    thread.start();
    thread.join();
    assertThat(thrown.get())
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
  }

  @Test
  void aLongOrChainWithinTheDepthLimitIsAccepted() {
    // 255 terms joined by || stay left-associative and within the depth limit
    String source = "a" + " || a".repeat(254);
    assertThat(compile(source, new Limits(1_000, 256, 4_096)).nodeCount()).isEqualTo(509);
  }

  @Test
  void emptySourceAndTrailingTokensAreRefused() {
    for (String source : List.of("", "   ", "\n", "1 2", "1 )", "a b", "(1", "1 +", "a ? b", "?")) {
      assertParseError(source);
    }
  }

  @Test
  void stringLiteralLongerThanTheLimitIsRefused() {
    Limits small = new Limits(512, 256, 8);
    assertThat(compile("'12345678'", small).nodeCount()).isEqualTo(1);
    assertThatThrownBy(() -> compile("'123456789'", small))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
    // counted in code points, not in UTF-16 units
    String emoji = new String(Character.toChars(0x1F600));
    assertThat(compile("'" + emoji.repeat(8) + "'", small).nodeCount()).isEqualTo(1);
  }

  @Test
  void errorMessagesDoNotEchoLongOrHostileText() {
    String longName = "a".repeat(5_000);
    assertThatThrownBy(() -> compile(longName))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.getMessage().length()).isLessThan(200));
    assertThatThrownBy(() -> compile("'" + "x".repeat(300) + "' 'y'"))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.getMessage().length()).isLessThan(200));
  }

  @Test
  void everyCompileErrorIsKindParse() {
    String noBreakSpace = new String(Character.toChars(0xA0));
    List<String> bad =
        List.of(
            "null",
            "1e3",
            "0x1",
            "a = 1",
            "a & b",
            "a | b",
            "all(l, true)",
            "l.map(x)",
            "z",
            "nosuch()",
            "dec()",
            "'abc'.length",
            "1 2",
            "",
            "((",
            "'unterminated",
            "9223372036854775808",
            "has(z)",
            "a ?",
            "[1,",
            "a.nosuch('x')",
            noBreakSpace,
            "1.5.5");
    for (String source : bad) {
      assertParseError(source);
    }
    assertThatThrownBy(() -> compile("[1, 1, 1, 1]", new Limits(3, 256, 4_096)))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
  }

  @Test
  void compiledReportsItsSource() {
    assertThat(compile("a + 1").source()).isEqualTo("a + 1");
    assertThat(InHouseExpression.ID).isEqualTo("inhouse-cel-subset-1");
    assertThat(Limits.defaults()).isEqualTo(new Limits(512, 256, 4_096));
  }
}
