package dev.regpack.spikes.r.inhouse;

import dev.regpack.spikes.r.inhouse.Ast.Node;

/**
 * An upper bound of the cost units one evaluation of a tree can charge (cost model v1), given the
 * largest list a path may bind and the longest string a path may bind: one unit per node plus the
 * size surcharges of every node, summed over every node whether or not a branch is taken. A list
 * literal is bounded by its own length.
 */
final class StaticCost {
  private final long listItems;
  private final long stringCodePoints;

  private StaticCost(long listItems, long stringCodePoints) {
    this.listItems = listItems;
    this.stringCodePoints = stringCodePoints;
  }

  static long of(Node root, int maxListItems, int maxStringCodePoints) {
    if (maxListItems < 0 || maxStringCodePoints < 0) {
      throw new IllegalArgumentException("the size limits must not be negative");
    }
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

  private long sum(java.util.List<Node> nodes) {
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

  /** The most items a value of this node can hold. */
  private long itemBound(Node node) {
    return node instanceof Ast.ListLit l ? l.items().size() : listItems;
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
      case CONTAINS -> Math.max(itemBound(c.args().get(0)), stringUnits(2));
      case STARTS_WITH, ENDS_WITH -> stringUnits(2);
    };
  }
}
