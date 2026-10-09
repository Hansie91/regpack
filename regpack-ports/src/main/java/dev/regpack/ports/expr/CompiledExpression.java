package dev.regpack.ports.expr;

import dev.regpack.ports.exec.CostMeter;

/** A compiled expression. Evaluation charges the meter and reads no clock and no I/O. */
public interface CompiledExpression {
  String source();

  /**
   * Evaluates against the bindings.
   *
   * @throws ExpressionException if a path is read that is absent and not guarded by {@code has}
   */
  Value evaluate(Bindings bindings, CostMeter meter);
}
