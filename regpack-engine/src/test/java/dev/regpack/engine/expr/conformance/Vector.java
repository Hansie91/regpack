package dev.regpack.engine.expr.conformance;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * One conformance vector (companion contracts section 5.5). {@code vectorClass} is MUST (the quoted
 * spec text fixes the value), SHOULD (a sensible reading), DECIDE (the spec is silent; expected is
 * {@code @ruling} and the value lives in {@code rulings.tsv}) or SPIKE-DESIGN (a regression test of
 * the production design, never conformance). {@code bindings} is the raw cell, decoded by {@link
 * ConformanceHarness#decode}.
 */
record Vector(
    String id,
    String vectorClass,
    String expression,
    String bindings,
    String expected,
    OptionalLong budget,
    OptionalLong ceiling,
    String basis) {
  /** The expected value that names a ruling instead of a value. */
  static final String RULING = "@ruling";

  Vector {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(vectorClass, "vectorClass");
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(bindings, "bindings");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(ceiling, "ceiling");
    Objects.requireNonNull(basis, "basis");
  }
}
