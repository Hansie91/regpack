package dev.regpack.engine.expr;

import java.util.Objects;

/**
 * The four switches of the DECIDE vectors: points where the specification is silent and the layers
 * under test differ (assumption A2, open item OI-5). The provisional values are the in-house
 * outcomes. A layer reads its rulings once, at construction, into a final field; there is no static
 * state, so two layers with different rulings can coexist.
 *
 * @param intDecimalMix D13: {@code 1 + dec('0.5')}
 * @param andLeftError A04: {@code error && false}
 * @param orLeftError E06: {@code error || true}
 * @param hasBelowAbsent A07: {@code has(a.b.c)} when {@code a.b} is declared and unbound
 */
public record Rulings(
    IntDecimalMix intDecimalMix,
    AndLeftError andLeftError,
    OrLeftError orLeftError,
    HasBelowAbsent hasBelowAbsent) {

  /** D13: arithmetic that mixes an int with a decimal. */
  public enum IntDecimalMix {
    /** The int is widened to a decimal and the result is a decimal. */
    ALLOW,
    /** The mix is a TYPE error (comparison and equality stay numeric). */
    DENY
  }

  /** A04: an error raised by the left operand of {@code &&}. */
  public enum AndLeftError {
    /** The error propagates whatever the right operand is. */
    PROPAGATE,
    /** The right operand is evaluated; if it is false the result is false, else the error stays. */
    ABSORB
  }

  /** E06: an error raised by the left operand of {@code ||}. */
  public enum OrLeftError {
    /** The error propagates whatever the right operand is. */
    PROPAGATE,
    /** The right operand is evaluated; if it is true the result is true, else the error stays. */
    ABSORB
  }

  /** A07: {@code has(p)} when a declared strict ancestor path of {@code p} is unbound. */
  public enum HasBelowAbsent {
    /** The answer is simply whether {@code p} is bound. */
    FALSE,
    /** {@code has(p)} raises ABSENT when a declared ancestor path of {@code p} is unbound. */
    ERROR_IF_ANCESTOR_ABSENT
  }

  public Rulings {
    Objects.requireNonNull(intDecimalMix, "intDecimalMix");
    Objects.requireNonNull(andLeftError, "andLeftError");
    Objects.requireNonNull(orLeftError, "orLeftError");
    Objects.requireNonNull(hasBelowAbsent, "hasBelowAbsent");
  }

  /** The in-house outcomes: ALLOW, PROPAGATE, PROPAGATE, FALSE. */
  public static Rulings provisional() {
    return new Rulings(
        IntDecimalMix.ALLOW, AndLeftError.PROPAGATE, OrLeftError.PROPAGATE, HasBelowAbsent.FALSE);
  }
}
