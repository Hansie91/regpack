package dev.regpack.engine.expr;

import dev.regpack.engine.expr.Ast.Node;
import java.util.List;

/**
 * An upper bound of the cost units one evaluation of a tree can charge (cost model v1, companion
 * contract section 3): one unit per node plus the size surcharge of every node, summed over every
 * node whether or not a branch is taken. Every list is bounded by the list limit (a list literal by
 * its own length, also when it is a branch of a ternary) and every string by the string limit. The
 * bound adds every surcharge an operator can possibly charge, whatever the types of its operands
 * turn out to be.
 */
final class StaticCost {
  private final long listItems;
  private final long stringCodePoints;

  private StaticCost(long listItems, long stringCodePoints) {
    this.listItems = listItems;
    this.stringCodePoints = stringCodePoints;
  }

  /** The limits are the validated {@code Limits} values (at least 1). */
  static long of(Node root, int maxListItems, int maxStringCodePoints) {
    return new StaticCost(maxListItems, maxStringCodePoints).cost(root);
  }

  private long cost(Node node) {
    return switch (node) {
      case Ast.Lit _ -> 1;
      case Ast.Path _ -> 1;
      case Ast.Has _ -> 1;
      case Ast.ListLit l -> 1 + sum(l.items());
      case Ast.Unary u -> 1 + cost(u.operand());
      case Ast.Ternary t -> 1 + cost(t.cond()) + cost(t.whenTrue()) + cost(t.whenFalse());
      case Ast.Binary b -> 1 + cost(b.left()) + cost(b.right()) + surcharge(b);
      case Ast.Call c -> 1 + sum(c.args()) + surcharge(c);
    };
  }

  private long sum(List<Node> nodes) {
    long total = 0;
    for (Node node : nodes) {
      total += cost(node);
    }
    return total;
  }

  /** The units of {@code strings} strings of the longest length: one per 256 code points. */
  private long stringUnits(int strings) {
    return strings * stringCodePoints / 256;
  }

  /**
   * The most items a value of this node can hold. A list literal holds its own length, which may
   * exceed the list limit (only a string literal is refused at compile); a ternary yields one of
   * its branches, so it holds the larger bound of the two; every other node yields an input or a
   * capped result, so it holds at most the list limit.
   */
  private long itemBound(Node node) {
    return switch (node) {
      case Ast.ListLit l -> l.items().size();
      case Ast.Ternary t -> Math.max(itemBound(t.whenTrue()), itemBound(t.whenFalse()));
      default -> listItems;
    };
  }

  /** The units of {@code contains} on two strings of the longest length. */
  private long stringContainsUnits() {
    long blocks = stringCodePoints / 256 + 1;
    return blocks * blocks - 1;
  }

  private long surcharge(Ast.Binary b) {
    return switch (b.op()) {
      case ADD -> stringUnits(2) + itemBound(b.left()) + itemBound(b.right());
      case DIV -> 1;
      case IN -> itemBound(b.right());
      default -> 0;
    };
  }

  private long surcharge(Ast.Call c) {
    return switch (c.fn()) {
      case DEC, TIMESTAMP, DATE -> 1 + stringUnits(1);
      case ROUND -> 1;
      case STRING, INT, UPPER, LOWER, SIZE -> stringUnits(1);
      case ISO_MICROS, DAY_OF_WEEK -> 0;
      case CONTAINS -> containsSurcharge(c.args().get(0));
      case STARTS_WITH, ENDS_WITH -> stringUnits(2);
    };
  }

  /**
   * A list literal receiver is a list scan; any other receiver may be a list (bounded as by {@link
   * #itemBound}) or a string.
   */
  private long containsSurcharge(Node receiver) {
    if (receiver instanceof Ast.ListLit l) {
      return l.items().size();
    }
    return Math.max(itemBound(receiver), stringContainsUnits());
  }
}
