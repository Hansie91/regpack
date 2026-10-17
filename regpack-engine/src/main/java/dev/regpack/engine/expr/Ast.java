package dev.regpack.engine.expr;

import dev.regpack.ports.expr.Value;
import java.util.List;

/**
 * The syntax tree of the expression subset. Every node knows its depth, so the parser can refuse a
 * deep tree while it builds it. A method call is a {@link Call} whose first argument is the
 * receiver.
 */
final class Ast {
  /** Binary operators. */
  enum BinOp {
    ADD,
    SUB,
    MUL,
    DIV,
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    IN,
    AND,
    OR
  }

  /** Unary operators. */
  enum UnOp {
    NOT,
    NEG
  }

  /** A tree node. */
  sealed interface Node permits Lit, ListLit, Path, Has, Unary, Binary, Ternary, Call {
    /** The height of the subtree: 1 for a leaf. */
    int depth();
  }

  /** A literal; the value is built once at compile time. */
  record Lit(Value value) implements Node {
    @Override
    public int depth() {
      return 1;
    }
  }

  record ListLit(List<Node> items, int depth) implements Node {}

  /** A dotted path into the bindings; absence is an error unless guarded by {@link Has}. */
  record Path(String path) implements Node {
    @Override
    public int depth() {
      return 1;
    }
  }

  /** {@code has(path)}: true when the path is bound. The path is not a node of its own. */
  record Has(String path) implements Node {
    @Override
    public int depth() {
      return 1;
    }
  }

  record Unary(UnOp op, Node operand, int depth) implements Node {}

  record Binary(BinOp op, Node left, Node right, int depth) implements Node {}

  record Ternary(Node cond, Node whenTrue, Node whenFalse, int depth) implements Node {}

  record Call(Library fn, List<Node> args, int depth) implements Node {}

  private Ast() {}

  static ListLit list(List<Node> items) {
    return new ListLit(List.copyOf(items), 1 + deepest(items));
  }

  static Unary unary(UnOp op, Node operand) {
    return new Unary(op, operand, operand.depth() + 1);
  }

  static Binary binary(BinOp op, Node left, Node right) {
    return new Binary(op, left, right, Math.max(left.depth(), right.depth()) + 1);
  }

  static Ternary ternary(Node cond, Node whenTrue, Node whenFalse) {
    int deepest = Math.max(cond.depth(), Math.max(whenTrue.depth(), whenFalse.depth()));
    return new Ternary(cond, whenTrue, whenFalse, deepest + 1);
  }

  static Call call(Library fn, List<Node> args) {
    return new Call(fn, List.copyOf(args), 1 + deepest(args));
  }

  private static int deepest(List<Node> nodes) {
    int deepest = 0;
    for (Node node : nodes) {
      deepest = Math.max(deepest, node.depth());
    }
    return deepest;
  }
}
