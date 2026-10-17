package dev.regpack.engine.expr;

import java.util.Optional;

/**
 * The function table of the expression layer: name, arity and whether the function is a global
 * call, a method (the receiver is the first argument) or both. The behaviour arrives with the
 * evaluator; the names are SPIKE-DESIGN and unfrozen (companion contract section 3).
 */
public enum Library {
  DEC("dec", 1, true, false),
  ROUND("round", 3, true, false),
  STRING("string", 1, true, false),
  INT("int", 1, true, false),
  TIMESTAMP("timestamp", 1, true, false),
  DATE("date", 1, true, false),
  ISO_MICROS("isoMicros", 1, true, false),
  DAY_OF_WEEK("dayOfWeek", 1, true, false),
  UPPER("upper", 1, true, false),
  LOWER("lower", 1, true, false),
  SIZE("size", 1, true, false),
  CONTAINS("contains", 2, true, true),
  STARTS_WITH("startsWith", 2, false, true),
  ENDS_WITH("endsWith", 2, false, true);

  /** The largest number of digits of a decimal (precision and scale). */
  static final int MAX_DECIMAL_DIGITS = 38;

  private final String label;
  private final int arity;
  private final boolean global;
  private final boolean method;

  Library(String label, int arity, boolean global, boolean method) {
    this.label = label;
    this.arity = arity;
    this.global = global;
    this.method = method;
  }

  /** The name as written in an expression. */
  public String label() {
    return label;
  }

  /** The number of arguments, the receiver of a method call included. */
  public int arity() {
    return arity;
  }

  /** The function with this name that may be called as {@code name(args)}. */
  static Optional<Library> global(String name) {
    for (Library fn : values()) {
      if (fn.global && fn.label.equals(name)) {
        return Optional.of(fn);
      }
    }
    return Optional.empty();
  }

  /** The function with this name that may be called as {@code receiver.name(args)}. */
  static Optional<Library> method(String name) {
    for (Library fn : values()) {
      if (fn.method && fn.label.equals(name)) {
        return Optional.of(fn);
      }
    }
    return Optional.empty();
  }
}
