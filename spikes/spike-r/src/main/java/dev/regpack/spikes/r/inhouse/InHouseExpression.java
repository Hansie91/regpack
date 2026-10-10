package dev.regpack.spikes.r.inhouse;

import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.spikes.r.vectors.Outcome;
import java.util.Map;
import java.util.Objects;

/**
 * The in-house CEL-shaped expression subset over {@code BigDecimal} behind the P0 {@link
 * Expression} port (the spec 5.3 fallback). It is dynamically typed: the declared variable types
 * are not used at compile time, a type clash is a TYPE error at evaluation. The per-rule ceiling is
 * the compiled node count and is checked at compile time.
 */
public final class InHouseExpression implements Expression {
  /** The node-count ceiling of {@code new InHouseExpression()}. */
  public static final long DEFAULT_CEILING = 100_000L;

  private final long ceiling;

  public InHouseExpression() {
    this(DEFAULT_CEILING);
  }

  private InHouseExpression(long ceiling) {
    if (ceiling < 1) {
      throw new IllegalArgumentException("ceiling must be >= 1");
    }
    this.ceiling = ceiling;
  }

  /**
   * An expression layer that refuses, at compile time, any source with more nodes than the ceiling.
   */
  public static InHouseExpression withCeiling(long perRuleCeilingUnits) {
    return new InHouseExpression(perRuleCeilingUnits);
  }

  @Override
  public Compiled compile(String source, Map<String, ValueType> variables) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(variables, "variables");
    Parser.Parsed parsed = Parser.parse(source, ceiling);
    return new Compiled(source, parsed);
  }

  /** A compiled source; evaluation is a pure function of the bindings and charges the meter. */
  public static final class Compiled implements CompiledExpression {
    private final String source;
    private final Parser.Parsed parsed;

    private Compiled(String source, Parser.Parsed parsed) {
      this.source = source;
      this.parsed = parsed;
    }

    @Override
    public String source() {
      return source;
    }

    /** The number of tree nodes, the static size that the per-rule ceiling bounds. */
    public long nodeCount() {
      return parsed.nodeCount();
    }

    @Override
    public Value evaluate(Bindings bindings, CostMeter meter) {
      return Evaluator.eval(parsed.root(), bindings, meter);
    }
  }

  /** An evaluation error with one of the fixed kinds of {@link Outcome#ERROR_KINDS}. */
  public static final class EvalException extends ExpressionException {
    private static final long serialVersionUID = 1L;

    private final String kind;

    public EvalException(String kind, String message) {
      super(message);
      if (!Outcome.ERROR_KINDS.contains(kind)) {
        throw new IllegalArgumentException("unknown error kind " + kind);
      }
      this.kind = kind;
    }

    public String kind() {
      return kind;
    }

    /** Evaluation errors are expected outcomes, so they carry no stack trace. */
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }
}
