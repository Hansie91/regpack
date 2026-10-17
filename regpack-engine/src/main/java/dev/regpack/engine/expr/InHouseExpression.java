package dev.regpack.engine.expr;

import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.ValueType;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;

/**
 * The production expression layer: a CEL-shaped, non-Turing-complete subset over {@code BigDecimal}
 * (spec 6.2, assumption A1). Typing is dynamic; the variable map only refuses undeclared paths.
 * Compile errors are {@link ExprException}s of kind PARSE.
 */
public final class InHouseExpression implements Expression {
  public static final String ID = "inhouse-cel-subset-1";

  /**
   * The size limits of a layer.
   *
   * @param maxNodes the parse-work cap, counted while parsing; the per-rule ceiling is checked by
   *     the caller against {@link Compiled#nodeCount()}
   * @param maxListItems the most items a list may hold
   * @param maxStringCodePoints the most code points a string may hold
   */
  public record Limits(int maxNodes, int maxListItems, int maxStringCodePoints) {
    public Limits {
      if (maxNodes < 1 || maxListItems < 1 || maxStringCodePoints < 1) {
        throw new IllegalArgumentException("every limit must be at least 1");
      }
    }

    public static Limits defaults() {
      return new Limits(512, 256, 4_096);
    }
  }

  private final Limits limits;

  public InHouseExpression() {
    this(Limits.defaults());
  }

  public InHouseExpression(Limits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public Compiled compile(String source, Map<String, ValueType> variables) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(variables, "variables");
    Parser.Parsed parsed = Parser.parse(source, limits, variables.keySet());
    long worstCase =
        StaticCost.of(parsed.root(), limits.maxListItems(), limits.maxStringCodePoints());
    return new Compiled(source, parsed, worstCase);
  }

  /** A compiled expression with its static facts. */
  public static final class Compiled implements CompiledExpression {
    private final String source;
    private final Ast.Node root;
    private final int nodeCount;
    private final SortedSet<String> paths;
    private final long staticWorstCaseUnits;

    private Compiled(String source, Parser.Parsed parsed, long staticWorstCaseUnits) {
      this.source = source;
      this.root = parsed.root();
      this.nodeCount = parsed.nodeCount();
      this.paths = parsed.paths();
      this.staticWorstCaseUnits = staticWorstCaseUnits;
    }

    @Override
    public String source() {
      return source;
    }

    /** Evaluation arrives with the evaluator (task T12). */
    @Override
    public Value evaluate(Bindings bindings, CostMeter meter) {
      throw new UnsupportedOperationException("evaluation is not implemented yet");
    }

    /** The syntax tree, for the evaluator. */
    Ast.Node root() {
      return root;
    }

    /** The number of nodes; the path argument of {@code has(...)} is not a node. */
    public int nodeCount() {
      return nodeCount;
    }

    /** The paths the expression reads, sorted, those named by {@code has} included. */
    public SortedSet<String> paths() {
      return paths;
    }

    /**
     * An upper bound of the cost units one evaluation can charge, for inputs within the limits
     * (cost model v1).
     */
    public long staticWorstCaseUnits() {
      return staticWorstCaseUnits;
    }
  }
}
