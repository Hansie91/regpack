package dev.regpack.spikes.r.inhouse;

import dev.regpack.ports.expr.Value;
import java.util.List;

/**
 * The syntax tree of the in-house subset. Every node knows its depth, so a deep tree is refused at
 * compile.
 */
public final class Ast {
  /** Binary operators. */
  public enum BinOp {
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
  public enum UnOp {
    NOT,
    NEG
  }

  /** A tree node. */
  public sealed interface Node permits Lit, ListLit, Path, Has, Unary, Binary, Ternary, Call {
    int depth();
  }

  /** A literal; the value is built once at compile time. */
  public record Lit(Value value) implements Node {
    @Override
    public int depth() {
      return 1;
    }
  }

  public record ListLit(List<Node> items, int depth) implements Node {}

  /** A dotted path into the bindings; absence is an error unless guarded by {@link Has}. */
  public record Path(String path) implements Node {
    @Override
    public int depth() {
      return 1;
    }
  }

  /** The macro {@code has(path)}: true when the path is bound. */
  public record Has(String path) implements Node {
    @Override
    public int depth() {
      return 1;
    }
  }

  public record Unary(UnOp op, Node operand, int depth) implements Node {}

  public record Binary(BinOp op, Node left, Node right, int depth) implements Node {}

  public record Ternary(Node cond, Node whenTrue, Node whenFalse, int depth) implements Node {}

  /** A call of a library function; a method call carries its receiver as the first argument. */
  public record Call(Library.Fn fn, List<Node> args, int depth) implements Node {}

  private Ast() {}

  static ListLit list(List<Node> items) {
    int d = 0;
    for (Node n : items) {
      d = Math.max(d, n.depth());
    }
    return new ListLit(List.copyOf(items), d + 1);
  }

  static Unary unary(UnOp op, Node operand) {
    return new Unary(op, operand, operand.depth() + 1);
  }

  static Binary binary(BinOp op, Node left, Node right) {
    return new Binary(op, left, right, Math.max(left.depth(), right.depth()) + 1);
  }

  static Ternary ternary(Node cond, Node a, Node b) {
    return new Ternary(cond, a, b, Math.max(cond.depth(), Math.max(a.depth(), b.depth())) + 1);
  }

  static Call call(Library.Fn fn, List<Node> args) {
    int d = 0;
    for (Node n : args) {
      d = Math.max(d, n.depth());
    }
    return new Call(fn, List.copyOf(args), d + 1);
  }
}
