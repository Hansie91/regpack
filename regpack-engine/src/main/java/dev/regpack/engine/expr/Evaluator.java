package dev.regpack.engine.expr;

import dev.regpack.engine.expr.Ast.BinOp;
import dev.regpack.engine.expr.Ast.Node;
import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.engine.expr.Rulings.AndLeftError;
import dev.regpack.engine.expr.Rulings.HasBelowAbsent;
import dev.regpack.engine.expr.Rulings.IntDecimalMix;
import dev.regpack.engine.expr.Rulings.OrLeftError;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DateValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tree-walking evaluator (cost model v1, companion contract section 3). One instance serves one
 * evaluation on one thread. Every charge is made before the work it pays for: one unit for each
 * node on entry, then the surcharge of the operation before it allocates or scans. A budget overrun
 * is the port's {@code BudgetExceededException}; every other failure is an {@link ExprException}.
 * Inputs are held to the limits on entry: a value taken from the bindings (by a read or by {@code
 * has}) is refused before any use when a decimal has more than 38 digits of precision or a scale
 * outside -38 to 38 (SCALE), a string has more than {@code maxStringCodePoints} code points or a
 * list more than {@code maxListItems} items (RANGE), elements included. An oversized string or list
 * is first charged its size like any operand (so a budget overrun can come before the RANGE). The
 * static worst case holds only for inputs within the limits. The evaluator recurses per tree level
 * (at most 256, see {@link Parser#MAX_DEPTH}; {@code &&} and {@code ||} use three frames per level,
 * the worst case), so it fits a small worker stack; it does not catch a {@code StackOverflowError}.
 *
 * <p>Stack need, measured on a bare thread with a left chain of depth 256 ({@code +}, {@code &&},
 * {@code ||}, 255 operators each): a 128 KB stack overflows interpreted or cold, 160 KB passes with
 * and without {@code -Xint}. A thread that evaluates expressions must therefore have a stack of at
 * least 256 KB (the test thread of {@code EvaluatorTest} has exactly that, a margin of about 1.6);
 * the executor and program threads of tasks T15 to T17 must not be configured below it.
 */
final class Evaluator {
  private static final BoolValue TRUE = new BoolValue(true);
  private static final BoolValue FALSE = new BoolValue(false);

  private final Bindings bindings;
  private final CostMeter meter;
  private final Limits limits;
  private final Rulings rulings;
  private final Set<String> declaredAncestors;

  /**
   * @param declaredAncestors the declared strict ancestors of the paths the expression reads (all
   *     that ruling A07 looks up)
   */
  Evaluator(
      Bindings bindings,
      CostMeter meter,
      Limits limits,
      Rulings rulings,
      Set<String> declaredAncestors) {
    this.bindings = bindings;
    this.meter = meter;
    this.limits = limits;
    this.rulings = rulings;
    this.declaredAncestors = declaredAncestors;
  }

  static ExprException error(ErrorKind kind, String message) {
    return new ExprException(kind, message);
  }

  Value eval(Node node) {
    meter.charge(1);
    return switch (node) {
      case Ast.Lit lit -> lit.value();
      case Ast.Path p -> read(p.path());
      case Ast.Has h -> has(h.path());
      case Ast.ListLit l -> list(l);
      case Ast.Unary u -> unary(u);
      case Ast.Ternary t -> eval(bool(eval(t.cond())) ? t.whenTrue() : t.whenFalse());
      case Ast.Call c -> call(c);
      case Ast.Binary b -> binary(b);
    };
  }

  // ---- paths and absence ---------------------------------------------------------------------

  private Value read(String path) {
    return held(
        bindings.get(path).orElseThrow(() -> error(ErrorKind.ABSENT, "unbound path " + path)));
  }

  /**
   * Holds a bound value to the limits on entry, before anything uses it: a decimal of more than 38
   * digits of precision or a scale outside -38 to 38 is SCALE, a string of more than {@code
   * maxStringCodePoints} code points and a list of more than {@code maxListItems} items are RANGE
   * (the elements of a list are held to the same limits). The static worst case is only a bound for
   * inputs within the limits, so nothing may touch an input beyond them.
   */
  private Value held(Value value) {
    switch (value) {
      case DecimalValue(BigDecimal d) -> {
        // bitLength first: 38 digits fit 127 bits, and precision() of a huge value is not cheap
        if (Math.abs((long) d.scale()) > Library.MAX_DECIMAL_DIGITS
            || d.unscaledValue().bitLength() > 127
            || d.precision() > Library.MAX_DECIMAL_DIGITS) {
          throw error(
              ErrorKind.SCALE, "decimal input exceeds " + Library.MAX_DECIMAL_DIGITS + " digits");
        }
      }
      case StringValue(String s) -> {
        // a string of at most the limit in chars has at most the limit in code points
        if (s.length() > limits.maxStringCodePoints()) {
          int codePoints = Library.codePoints(s);
          if (codePoints > limits.maxStringCodePoints()) {
            meter.charge(codePoints / 256); // charged its size first, as every string operand is
            throw error(
                ErrorKind.RANGE, "string input longer than " + limits.maxStringCodePoints());
          }
        }
      }
      case ListValue(List<Value> items) -> {
        if (items.size() > limits.maxListItems()) {
          meter.charge(items.size()); // charged its length first, as every list operand is
          throw error(ErrorKind.RANGE, "list input longer than " + limits.maxListItems());
        }
        for (Value item : items) {
          held(item);
        }
      }
      default -> {}
    }
    return value;
  }

  private Value has(String path) {
    if (rulings.hasBelowAbsent() == HasBelowAbsent.ERROR_IF_ANCESTOR_ABSENT) {
      for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
        String ancestor = path.substring(0, dot);
        if (declaredAncestors.contains(ancestor) && bindings.get(ancestor).isEmpty()) {
          throw error(ErrorKind.ABSENT, "unbound ancestor " + ancestor + " of " + path);
        }
      }
    }
    return bindings.get(path).map(this::held).isPresent() ? TRUE : FALSE;
  }

  private Value list(Ast.ListLit literal) {
    List<Value> items = new ArrayList<>(literal.items().size());
    for (Node item : literal.items()) {
      items.add(eval(item));
    }
    return new ListValue(items);
  }

  // ---- unary ---------------------------------------------------------------------------------

  private static boolean bool(Value v) {
    if (v instanceof BoolValue(boolean b)) {
      return b;
    }
    throw error(ErrorKind.TYPE, "expected a bool");
  }

  private Value unary(Ast.Unary u) {
    Value operand = eval(u.operand());
    return switch (u.op()) {
      case NOT -> bool(operand) ? FALSE : TRUE;
      case NEG -> negate(operand);
    };
  }

  private static Value negate(Value operand) {
    if (operand instanceof IntValue(long n)) {
      try {
        return new IntValue(Math.negateExact(n));
      } catch (ArithmeticException _) {
        throw error(ErrorKind.OVERFLOW, "integer overflow");
      }
    }
    if (operand instanceof DecimalValue(BigDecimal d)) {
      return new DecimalValue(d.negate());
    }
    throw error(ErrorKind.TYPE, "cannot negate this type");
  }

  // ---- binary --------------------------------------------------------------------------------

  private Value binary(Ast.Binary b) {
    BinOp op = b.op();
    if (op == BinOp.AND) {
      return and(b);
    }
    if (op == BinOp.OR) {
      return or(b);
    }
    Value l = eval(b.left());
    Value r = eval(b.right());
    return switch (op) {
      case ADD -> add(l, r);
      case SUB -> sub(l, r);
      case MUL -> mul(l, r);
      case DIV -> div(l, r);
      case EQ -> equal(l, r) ? TRUE : FALSE;
      case NE -> equal(l, r) ? FALSE : TRUE;
      case LT -> compare(l, r) < 0 ? TRUE : FALSE;
      case LE -> compare(l, r) <= 0 ? TRUE : FALSE;
      case GT -> compare(l, r) > 0 ? TRUE : FALSE;
      case GE -> compare(l, r) >= 0 ? TRUE : FALSE;
      case IN -> in(l, r);
      case AND, OR -> throw new IllegalStateException("handled above");
    };
  }

  /**
   * {@code &&}, left to right, short-circuit. An error raised by evaluating the left operand
   * propagates (A04 PROPAGATE) or, under ABSORB, is replaced by false when the right operand
   * evaluates to false. A left operand that evaluates to a non-bool is a TYPE error, not an
   * absorbable evaluation error.
   */
  private Value and(Ast.Binary b) {
    Value left;
    try {
      left = eval(b.left());
    } catch (ExprException e) {
      if (rulings.andLeftError() == AndLeftError.PROPAGATE) {
        throw e;
      }
      return absorbed(e, b.right(), false);
    }
    if (!bool(left)) {
      return FALSE;
    }
    return bool(eval(b.right())) ? TRUE : FALSE;
  }

  /** {@code ||}, the mirror of {@link #and}: the deciding right operand is true. */
  private Value or(Ast.Binary b) {
    Value left;
    try {
      left = eval(b.left());
    } catch (ExprException e) {
      if (rulings.orLeftError() == OrLeftError.PROPAGATE) {
        throw e;
      }
      return absorbed(e, b.right(), true);
    }
    if (bool(left)) {
      return TRUE;
    }
    return bool(eval(b.right())) ? TRUE : FALSE;
  }

  /**
   * ABSORB: evaluates the right operand after a left error. If it evaluates to the deciding value
   * the result is that value; in every other case (another value, a non-bool, or an error of its
   * own) the left error stays and the right error is dropped. A budget overrun is not an {@link
   * ExprException} and propagates.
   */
  private Value absorbed(ExprException leftError, Node right, boolean deciding) {
    Value value;
    try {
      value = eval(right);
    } catch (ExprException _) {
      throw leftError;
    }
    if (value instanceof BoolValue(boolean b) && b == deciding) {
      return deciding ? TRUE : FALSE;
    }
    throw leftError;
  }

  // ---- arithmetic ----------------------------------------------------------------------------

  private static boolean isNumber(Value v) {
    return v instanceof IntValue || v instanceof DecimalValue;
  }

  /** True when an int meets a decimal and ruling D13 refuses the mix. */
  private boolean refusedMix(Value l, Value r) {
    return rulings.intDecimalMix() == IntDecimalMix.DENY
        && ((l instanceof IntValue && r instanceof DecimalValue)
            || (l instanceof DecimalValue && r instanceof IntValue));
  }

  private static BigDecimal decimalOf(Value v) {
    if (v instanceof DecimalValue(BigDecimal d)) {
      return d;
    }
    if (v instanceof IntValue(long n)) {
      return BigDecimal.valueOf(n);
    }
    throw error(ErrorKind.TYPE, "expected a number");
  }

  /** A negative scale becomes 0; more than 38 digits of scale or precision is SCALE. */
  static DecimalValue checked(BigDecimal d) {
    BigDecimal r = d;
    if (r.scale() < 0) {
      if (r.scale() < -Library.MAX_DECIMAL_DIGITS) {
        throw error(ErrorKind.SCALE, "decimal exponent out of range");
      }
      r = r.setScale(0);
    }
    if (r.scale() > Library.MAX_DECIMAL_DIGITS || r.precision() > Library.MAX_DECIMAL_DIGITS) {
      throw error(ErrorKind.SCALE, "decimal exceeds " + Library.MAX_DECIMAL_DIGITS + " digits");
    }
    return new DecimalValue(r);
  }

  private static ExprException overflow() {
    return error(ErrorKind.OVERFLOW, "integer overflow");
  }

  private Value add(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      try {
        return new IntValue(Math.addExact(a, b));
      } catch (ArithmeticException _) {
        throw overflow();
      }
    }
    if (isNumber(l) && isNumber(r)) {
      if (refusedMix(l, r)) {
        throw error(ErrorKind.TYPE, "an int and a decimal cannot be mixed");
      }
      return checked(decimalOf(l).add(decimalOf(r)));
    }
    if (l instanceof StringValue(String a) && r instanceof StringValue(String b)) {
      long total = (long) Library.codePoints(a) + Library.codePoints(b);
      meter.charge(total / 256);
      if (total > limits.maxStringCodePoints()) {
        throw error(ErrorKind.RANGE, "string longer than " + limits.maxStringCodePoints());
      }
      return new StringValue(a + b);
    }
    if (l instanceof ListValue(List<Value> a) && r instanceof ListValue(List<Value> b)) {
      long total = (long) a.size() + b.size();
      meter.charge(total);
      if (total > limits.maxListItems()) {
        throw error(ErrorKind.RANGE, "list longer than " + limits.maxListItems());
      }
      List<Value> joined = new ArrayList<>((int) total);
      joined.addAll(a);
      joined.addAll(b);
      return new ListValue(joined);
    }
    throw error(ErrorKind.TYPE, "cannot add these types");
  }

  private Value sub(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      try {
        return new IntValue(Math.subtractExact(a, b));
      } catch (ArithmeticException _) {
        throw overflow();
      }
    }
    if (isNumber(l) && isNumber(r)) {
      if (refusedMix(l, r)) {
        throw error(ErrorKind.TYPE, "an int and a decimal cannot be mixed");
      }
      return checked(decimalOf(l).subtract(decimalOf(r)));
    }
    throw error(ErrorKind.TYPE, "cannot subtract these types");
  }

  private Value mul(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      try {
        return new IntValue(Math.multiplyExact(a, b));
      } catch (ArithmeticException _) {
        throw overflow();
      }
    }
    if (isNumber(l) && isNumber(r)) {
      if (refusedMix(l, r)) {
        throw error(ErrorKind.TYPE, "an int and a decimal cannot be mixed");
      }
      return checked(decimalOf(l).multiply(decimalOf(r)));
    }
    throw error(ErrorKind.TYPE, "cannot multiply these types");
  }

  private Value div(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      if (b == 0) {
        throw error(ErrorKind.DIV_ZERO, "division by zero");
      }
      if (a == Long.MIN_VALUE && b == -1) {
        throw overflow();
      }
      return new IntValue(a / b);
    }
    if (isNumber(l) && isNumber(r)) {
      if (refusedMix(l, r)) {
        throw error(ErrorKind.TYPE, "an int and a decimal cannot be mixed");
      }
      meter.charge(1);
      BigDecimal divisor = decimalOf(r);
      if (divisor.signum() == 0) {
        throw error(ErrorKind.DIV_ZERO, "division by zero");
      }
      try {
        return checked(decimalOf(l).divide(divisor));
      } catch (ArithmeticException _) {
        throw error(ErrorKind.NON_TERMINATING, "the quotient has no finite decimal expansion");
      }
    }
    throw error(ErrorKind.TYPE, "cannot divide these types");
  }

  // ---- relations -----------------------------------------------------------------------------

  /**
   * Equality: same type; int with decimal numerically; enum with enum by type and name; enum with
   * string by name (S07); lists elementwise; anything else is a TYPE error.
   */
  static boolean equal(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      return a == b;
    }
    if (isNumber(l) && isNumber(r)) {
      return decimalOf(l).compareTo(decimalOf(r)) == 0;
    }
    if (l instanceof StringValue(String a) && r instanceof StringValue(String b)) {
      return a.equals(b);
    }
    if (l instanceof BoolValue(boolean a) && r instanceof BoolValue(boolean b)) {
      return a == b;
    }
    if (l instanceof TimestampValue(Instant a) && r instanceof TimestampValue(Instant b)) {
      return a.equals(b);
    }
    if (l instanceof DateValue(LocalDate a) && r instanceof DateValue(LocalDate b)) {
      return a.equals(b);
    }
    if (l instanceof EnumValue a && r instanceof EnumValue b) {
      return a.type().equals(b.type()) && a.name().equals(b.name());
    }
    if (l instanceof EnumValue(_, String name) && r instanceof StringValue(String b)) {
      return name.equals(b);
    }
    if (l instanceof StringValue(String a) && r instanceof EnumValue(_, String name)) {
      return name.equals(a);
    }
    if (l instanceof ListValue(List<Value> a) && r instanceof ListValue(List<Value> b)) {
      if (a.size() != b.size()) {
        return false;
      }
      for (int i = 0; i < a.size(); i++) {
        if (!equal(a.get(i), b.get(i))) {
          return false;
        }
      }
      return true;
    }
    throw error(ErrorKind.TYPE, "cannot compare values of these types");
  }

  /** Order of numbers, strings (by code point), timestamps and dates; else a TYPE error. */
  private static int compare(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      return Long.compare(a, b);
    }
    if (isNumber(l) && isNumber(r)) {
      return decimalOf(l).compareTo(decimalOf(r));
    }
    if (l instanceof StringValue(String a) && r instanceof StringValue(String b)) {
      return compareCodePoints(a, b);
    }
    if (l instanceof TimestampValue(Instant a) && r instanceof TimestampValue(Instant b)) {
      return a.compareTo(b);
    }
    if (l instanceof DateValue(LocalDate a) && r instanceof DateValue(LocalDate b)) {
      return a.compareTo(b);
    }
    throw error(ErrorKind.TYPE, "cannot order values of these types");
  }

  private static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i);
      int cb = b.codePointAt(j);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  /** {@code x in list}: the whole list length is charged before the scan, match or not. */
  private Value in(Value needle, Value haystack) {
    if (!(haystack instanceof ListValue(List<Value> items))) {
      throw error(ErrorKind.TYPE, "'in' needs a list on the right");
    }
    meter.charge(items.size());
    for (Value item : items) {
      if (equal(needle, item)) {
        return TRUE;
      }
    }
    return FALSE;
  }

  // ---- library calls -------------------------------------------------------------------------

  private Value call(Ast.Call c) {
    List<Node> argNodes = c.args();
    Value[] args = new Value[argNodes.size()];
    for (int i = 0; i < args.length; i++) {
      args[i] = eval(argNodes.get(i));
    }
    return c.fn().apply(args, meter, limits);
  }
}
