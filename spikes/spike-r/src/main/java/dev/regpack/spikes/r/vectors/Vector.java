package dev.regpack.spikes.r.vectors;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One semantic vector (SP-R). {@code vectorClass} is MUST (the spec says it), SHOULD (a sensible
 * reading) or DECIDE (the spec is silent and the layers differ; the owner decides at the freeze).
 * {@code bindings} maps a dotted path to a type-prefixed value text; a path that is not in the map
 * is explicitly absent. {@code expected} uses the syntax documented on {@link
 * ConformanceHarness#matches}.
 */
public record Vector(
    String id,
    String vectorClass,
    String expression,
    Map<String, String> bindings,
    String expected,
    OptionalLong budget,
    OptionalLong ceiling) {
  public Vector {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(vectorClass, "vectorClass");
    Objects.requireNonNull(expression, "expression");
    bindings = Map.copyOf(bindings);
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(ceiling, "ceiling");
  }
}
