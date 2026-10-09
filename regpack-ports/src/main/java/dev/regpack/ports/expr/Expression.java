package dev.regpack.ports.expr;

import java.util.Map;

/** The expression language: no loops, I/O, clock or user functions (spec 6.2). */
public interface Expression {
  /**
   * Compiles a source text against the declared variable types.
   *
   * @throws ExpressionException if the source does not parse or does not type-check
   */
  CompiledExpression compile(String source, Map<String, ValueType> variables);
}
