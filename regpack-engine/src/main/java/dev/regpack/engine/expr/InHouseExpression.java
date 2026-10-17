package dev.regpack.engine.expr;

import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.ValueType;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

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
  private final Rulings rulings;

  public InHouseExpression() {
    this(Limits.defaults());
  }

  public InHouseExpression(Limits limits) {
    this(limits, Rulings.load());
  }

  /** A layer with explicit rulings; they are read once, here, and never change. */
  public InHouseExpression(Limits limits, Rulings rulings) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.rulings = Objects.requireNonNull(rulings, "rulings");
  }

  @Override
  public Compiled compile(String source, Map<String, ValueType> variables) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(variables, "variables");
    Parser.Parsed parsed = Parser.parse(source, limits, variables.keySet());
    long worstCase =
        StaticCost.of(parsed.root(), limits.maxListItems(), limits.maxStringCodePoints());
    return new Compiled(
        source,
        parsed,
        worstCase,
        declaredAncestors(parsed.paths(), variables.keySet()),
        limits,
        rulings);
  }

  /**
   * The declared strict ancestors of the paths an expression reads: all that ruling A07 can ask
   * about. Keeping only these, not a copy of the whole variable map, keeps a compiled expression
   * small when a pack compiles hundreds of rules against one map.
   */
  private static Set<String> declaredAncestors(SortedSet<String> paths, Set<String> declared) {
    Set<String> ancestors = new TreeSet<>();
    for (String path : paths) {
      for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
        String ancestor = path.substring(0, dot);
        if (declared.contains(ancestor)) {
          ancestors.add(ancestor);
        }
      }
    }
    return Collections.unmodifiableSet(ancestors);
  }

  /** A compiled expression with its static facts. */
  public static final class Compiled implements CompiledExpression {
    private final String source;
    private final Ast.Node root;
    private final int nodeCount;
    private final SortedSet<String> paths;
    private final long staticWorstCaseUnits;
    private final Set<String> declaredAncestors;
    private final Limits limits;
    private final Rulings rulings;

    private Compiled(
        String source,
        Parser.Parsed parsed,
        long staticWorstCaseUnits,
        Set<String> declaredAncestors,
        Limits limits,
        Rulings rulings) {
      this.source = source;
      this.root = parsed.root();
      this.nodeCount = parsed.nodeCount();
      this.paths = parsed.paths();
      this.staticWorstCaseUnits = staticWorstCaseUnits;
      this.declaredAncestors = declaredAncestors;
      this.limits = limits;
      this.rulings = rulings;
    }

    @Override
    public String source() {
      return source;
    }

    /**
     * Evaluates the expression. Every unit is charged through the meter before the work it pays
     * for.
     *
     * @throws ExprException on an evaluation error (ABSENT, TYPE, OVERFLOW and the other kinds)
     * @throws dev.regpack.ports.exec.BudgetExceededException when the meter's budget is exhausted
     */
    @Override
    public Value evaluate(Bindings bindings, CostMeter meter) {
      Objects.requireNonNull(bindings, "bindings");
      Objects.requireNonNull(meter, "meter");
      return new Evaluator(bindings, meter, limits, rulings, declaredAncestors).eval(root);
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
