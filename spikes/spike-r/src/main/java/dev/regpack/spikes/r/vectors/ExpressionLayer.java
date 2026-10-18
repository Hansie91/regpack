package dev.regpack.spikes.r.vectors;

import dev.regpack.ports.expr.Value;
import java.util.Map;
import java.util.Optional;

/** A candidate expression layer as the conformance harness sees it. */
public interface ExpressionLayer {
  String name();

  /** Compiles and evaluates once; never throws. A budget or ceiling breach is an outcome. */
  Outcome evaluate(String expression, Map<String, Value> bindings, long budget, long ceiling);

  /**
   * An equivalent expression in function syntax for a vector the layer cannot express as written; a
   * vector that passes only through it is classified FUNCTIONAL, never hidden.
   */
  default Optional<String> rewriteFor(String vectorId) {
    return Optional.empty();
  }
}
