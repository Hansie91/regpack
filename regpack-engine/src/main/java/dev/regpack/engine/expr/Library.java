package dev.regpack.engine.expr;

import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import java.util.List;
import java.util.Optional;

/**
 * The function table of the expression layer: name, arity and whether the function is a global
 * call, a method (the receiver is the first argument) or both. The names are SPIKE-DESIGN and
 * unfrozen (companion contract section 3). Behaviour: {@code size} and {@code contains} are
 * implemented with the evaluator (task T12); the other functions arrive with the library (task T13)
 * and until then fail with an {@link UnsupportedOperationException}. That is an unchecked failure
 * outside {@link ExprException}, so nothing may be released or pushed between T12 and T13.
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

  /** The number of code points of a string. */
  static int codePoints(String s) {
    return s.codePointCount(0, s.length());
  }

  /**
   * Applies the function to evaluated arguments (the receiver first for a method call). Every
   * charge is made through the meter before the work it pays for.
   */
  Value apply(Value[] args, CostMeter meter) {
    return switch (this) {
      case SIZE -> size(args[0], meter);
      case CONTAINS -> contains(args[0], args[1], meter);
      default ->
          throw new UnsupportedOperationException(
              "library function " + label + " arrives with task T13");
    };
  }

  private static Value size(Value arg, CostMeter meter) {
    if (arg instanceof StringValue(String s)) {
      int cp = codePoints(s);
      meter.charge(cp / 256);
      return new IntValue(cp);
    }
    if (arg instanceof ListValue(List<Value> items)) {
      return new IntValue(items.size());
    }
    throw Evaluator.error(ErrorKind.TYPE, "size needs a string or a list");
  }

  /** A list receiver scans its items with {@code ==}; a string receiver looks for a substring. */
  private static Value contains(Value receiver, Value needle, CostMeter meter) {
    if (receiver instanceof ListValue(List<Value> items)) {
      meter.charge(items.size());
      for (Value item : items) {
        if (Evaluator.equal(needle, item)) {
          return new BoolValue(true);
        }
      }
      return new BoolValue(false);
    }
    if (receiver instanceof StringValue(String s) && needle instanceof StringValue(String t)) {
      long blocks = (long) (codePoints(s) / 256 + 1) * (codePoints(t) / 256 + 1);
      meter.charge(blocks - 1);
      return new BoolValue(s.contains(t));
    }
    throw Evaluator.error(ErrorKind.TYPE, "contains needs a list or two strings");
  }
}
