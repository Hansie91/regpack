package dev.regpack.engine.expr;

import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.ports.exec.CostMeter;
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
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The built-in function library of the expression layer, version {@link #VERSION}: name, arity and
 * whether the function is a global call, a method (the receiver is the first argument) or both, and
 * the behaviour of each function (companion contract section 3). The names are SPIKE-DESIGN and
 * unfrozen. Every charge is made through the meter before the work it pays for (cost model v1);
 * nothing here reads a clock, a locale or a time zone: text is written and read by hand, and case
 * mapping uses {@link Locale#ROOT}. The identifier functions (LEI, ISIN, CFI, MIC) are plan P5;
 * they will bump {@link #VERSION}.
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

  /**
   * The library version (published as {@code EngineInfo.LIBRARY_VERSION}). Any change to the
   * behaviour or the cost of a built-in function bumps it; it is part of the replay-compatibility
   * rule of an engine build (companion contract section 5.1).
   */
  public static final int VERSION = 1;

  /** The largest number of digits of a decimal (precision and scale). */
  static final int MAX_DECIMAL_DIGITS = 38;

  private static final long MIN_EPOCH_SECOND = -62_167_219_200L; // 0000-01-01T00:00:00Z
  private static final long MAX_EPOCH_SECOND = 253_402_300_799L; // 9999-12-31T23:59:59Z

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
   * Applies the function to evaluated arguments (the receiver first for a method call; the arity
   * was checked at compile time). Every charge is made through the meter before the work it pays
   * for.
   */
  Value apply(Value[] args, CostMeter meter, Limits limits) {
    return switch (this) {
      case DEC -> dec(args[0], meter);
      case ROUND -> round(args[0], args[1], args[2], meter);
      case STRING -> string(args[0], meter);
      case INT -> toInt(args[0], meter);
      case TIMESTAMP -> {
        String s = text(args[0], "timestamp needs a string");
        meter.charge(1 + units(s));
        yield timestamp(s);
      }
      case DATE -> {
        String s = text(args[0], "date needs a string");
        meter.charge(1 + units(s));
        yield date(s);
      }
      case ISO_MICROS -> new StringValue(isoMicros(instant(args[0])));
      case DAY_OF_WEEK -> new StringValue(dateOf(args[0]).getDayOfWeek().name());
      case UPPER -> {
        String s = text(args[0], "upper needs a string");
        meter.charge(units(s));
        yield capped(s.toUpperCase(Locale.ROOT), limits);
      }
      case LOWER -> {
        String s = text(args[0], "lower needs a string");
        meter.charge(units(s));
        yield capped(s.toLowerCase(Locale.ROOT), limits);
      }
      case SIZE -> size(args[0], meter);
      case CONTAINS -> contains(args[0], args[1], meter);
      case STARTS_WITH -> {
        String s = text(args[0], "startsWith needs strings");
        String p = text(args[1], "startsWith needs strings");
        meter.charge(units(s) + units(p));
        yield new BoolValue(s.startsWith(p));
      }
      case ENDS_WITH -> {
        String s = text(args[0], "endsWith needs strings");
        String p = text(args[1], "endsWith needs strings");
        meter.charge(units(s) + units(p));
        yield new BoolValue(s.endsWith(p));
      }
    };
  }

  // ---- cost and value access -----------------------------------------------------------------

  /** One unit per 256 code points of a string argument. */
  private static long units(String s) {
    return codePoints(s) / 256;
  }

  private static String text(Value v, String message) {
    if (v instanceof StringValue(String s)) {
      return s;
    }
    throw Evaluator.error(ErrorKind.TYPE, message);
  }

  /** The result of a case mapping, which can be longer than its operand, checked after the work. */
  private static Value capped(String result, Limits limits) {
    if (codePoints(result) > limits.maxStringCodePoints()) {
      throw Evaluator.error(ErrorKind.RANGE, "string longer than " + limits.maxStringCodePoints());
    }
    return new StringValue(result);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static BigDecimal decimalOf(Value v, String message) {
    if (v instanceof DecimalValue(BigDecimal d)) {
      return d;
    }
    if (v instanceof IntValue(long n)) {
      return BigDecimal.valueOf(n);
    }
    throw Evaluator.error(ErrorKind.TYPE, message);
  }

  // ---- decimals ------------------------------------------------------------------------------

  /** {@code dec(text)} reads {@code [+-]?digits[.digits]} and nothing else (ASCII digits). */
  private static Value dec(Value arg, CostMeter meter) {
    if (arg instanceof StringValue(String s)) {
      meter.charge(1 + units(s));
      return Evaluator.checked(parseDecimal(s));
    }
    meter.charge(1);
    return Evaluator.checked(decimalOf(arg, "dec needs a string, an int or a decimal"));
  }

  private static BigDecimal parseDecimal(String s) {
    int n = s.length();
    int i = 0;
    if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
      i++;
    }
    int integerStart = i;
    while (i < n && isDigit(s.charAt(i))) {
      i++;
    }
    boolean valid = i > integerStart;
    if (valid && i < n && s.charAt(i) == '.') {
      i++;
      int fractionStart = i;
      while (i < n && isDigit(s.charAt(i))) {
        i++;
      }
      valid = i > fractionStart;
    }
    if (!valid || i != n) {
      throw Evaluator.error(ErrorKind.PARSE, "not a decimal");
    }
    return new BigDecimal(s);
  }

  private static Value round(Value value, Value places, Value mode, CostMeter meter) {
    meter.charge(1);
    BigDecimal d = decimalOf(value, "round needs a number");
    if (!(places instanceof IntValue(long scale))) {
      throw Evaluator.error(ErrorKind.TYPE, "round places must be an int");
    }
    if (scale < 0 || scale > MAX_DECIMAL_DIGITS) {
      throw Evaluator.error(ErrorKind.RANGE, "round places outside 0 to " + MAX_DECIMAL_DIGITS);
    }
    RoundingMode rounding =
        switch (text(mode, "round mode must be a string")) {
          case "HALF_UP" -> RoundingMode.HALF_UP;
          case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
          default -> throw Evaluator.error(ErrorKind.OTHER, "unknown rounding mode");
        };
    return Evaluator.checked(d.setScale((int) scale, rounding));
  }

  // ---- conversions ---------------------------------------------------------------------------

  /** Decimals as plain text without trailing zeros, timestamps as isoMicros, enums by name. */
  private static Value string(Value v, CostMeter meter) {
    return switch (v) {
      case StringValue s -> {
        meter.charge(units(s.value()));
        yield s;
      }
      case BoolValue(boolean b) -> new StringValue(b ? "true" : "false");
      case IntValue(long n) -> new StringValue(Long.toString(n));
      case DecimalValue(BigDecimal d) -> new StringValue(d.stripTrailingZeros().toPlainString());
      case TimestampValue(Instant t) -> new StringValue(isoMicros(t));
      case DateValue(LocalDate d) -> new StringValue(isoDate(d));
      case EnumValue(_, String name) -> new StringValue(name);
      case ListValue _ -> throw Evaluator.error(ErrorKind.TYPE, "a list has no string form");
    };
  }

  private static Value toInt(Value v, CostMeter meter) {
    return switch (v) {
      case IntValue i -> i;
      case DecimalValue(BigDecimal d) -> new IntValue(exactLong(d));
      case StringValue(String s) -> {
        meter.charge(units(s));
        yield new IntValue(parseLong(s));
      }
      default -> throw Evaluator.error(ErrorKind.TYPE, "cannot convert this type to an int");
    };
  }

  private static long exactLong(BigDecimal d) {
    BigDecimal stripped = d.stripTrailingZeros();
    if (stripped.scale() > 0) {
      throw Evaluator.error(ErrorKind.LOSSY, "the decimal has a fractional part");
    }
    // at most 19 integer digits can fit; test before building the integer
    if ((long) stripped.precision() - stripped.scale() > 19) {
      throw Evaluator.error(ErrorKind.OVERFLOW, "the decimal does not fit an int");
    }
    try {
      return stripped.toBigIntegerExact().longValueExact();
    } catch (ArithmeticException _) {
      throw Evaluator.error(ErrorKind.OVERFLOW, "the decimal does not fit an int");
    }
  }

  /** {@code [+-]?digits}, ASCII digits only; a number above 64 bits is OVERFLOW. */
  private static long parseLong(String s) {
    int n = s.length();
    int start = n > 0 && (s.charAt(0) == '+' || s.charAt(0) == '-') ? 1 : 0;
    if (n == start) {
      throw Evaluator.error(ErrorKind.PARSE, "not an int");
    }
    for (int i = start; i < n; i++) {
      if (!isDigit(s.charAt(i))) {
        throw Evaluator.error(ErrorKind.PARSE, "not an int");
      }
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException _) {
      throw Evaluator.error(ErrorKind.OVERFLOW, "the int does not fit 64 bits");
    }
  }

  // ---- timestamps and dates ------------------------------------------------------------------

  /**
   * RFC 3339 read by hand: {@code yyyy-MM-ddTHH:mm:ss[.f+](Z|+HH:mm|-HH:mm)}, ASCII digits, the
   * seconds and the zone mandatory, a leap second refused. A fraction finer than a microsecond is
   * RANGE (zeros beyond the sixth digit are not finer).
   */
  private static Value timestamp(String s) {
    int n = s.length();
    if (n < 20
        || s.charAt(4) != '-'
        || s.charAt(7) != '-'
        || s.charAt(10) != 'T'
        || s.charAt(13) != ':'
        || s.charAt(16) != ':') {
      throw notATimestamp();
    }
    int year = digits(s, 0, 4);
    int month = digits(s, 5, 2);
    int day = digits(s, 8, 2);
    int hour = digits(s, 11, 2);
    int minute = digits(s, 14, 2);
    int second = digits(s, 17, 2);
    int i = 19;
    long micros = 0;
    boolean finer = false;
    if (s.charAt(i) == '.') {
      i++;
      int count = 0;
      while (i < n && isDigit(s.charAt(i))) {
        int d = s.charAt(i) - '0';
        if (count < 6) {
          micros = micros * 10 + d;
        } else if (d != 0) {
          finer = true;
        }
        count++;
        i++;
      }
      if (count == 0) {
        throw notATimestamp();
      }
      for (int c = count; c < 6; c++) {
        micros *= 10;
      }
    }
    ZoneOffset offset = zone(s, i);
    if (year < 0 || month < 0 || day < 0 || hour < 0 || minute < 0 || second < 0) {
      throw notATimestamp();
    }
    Instant instant;
    try {
      instant =
          LocalDateTime.of(year, month, day, hour, minute, second, (int) micros * 1_000)
              .toInstant(offset);
    } catch (DateTimeException _) {
      throw notATimestamp();
    }
    if (finer) {
      throw Evaluator.error(ErrorKind.RANGE, "finer than a microsecond");
    }
    return new TimestampValue(instant);
  }

  /**
   * The zone designator that starts at {@code i} and ends the text: {@code Z} or {@code +HH:mm}.
   */
  private static ZoneOffset zone(String s, int i) {
    int n = s.length();
    if (i >= n) {
      throw notATimestamp();
    }
    char c = s.charAt(i);
    if (c == 'Z' && i + 1 == n) {
      return ZoneOffset.UTC;
    }
    if ((c == '+' || c == '-') && n - i == 6 && s.charAt(i + 3) == ':') {
      int hours = digits(s, i + 1, 2);
      int minutes = digits(s, i + 4, 2);
      if (hours < 0 || minutes < 0 || hours > 23 || minutes > 59) {
        throw notATimestamp();
      }
      try {
        int sign = c == '-' ? -1 : 1;
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
      } catch (DateTimeException _) {
        throw notATimestamp();
      }
    }
    throw notATimestamp();
  }

  private static ExprException notATimestamp() {
    return Evaluator.error(ErrorKind.PARSE, "not an RFC 3339 timestamp");
  }

  /** The number written by {@code count} ASCII digits at {@code from}, or -1. */
  private static int digits(String s, int from, int count) {
    int value = 0;
    for (int i = from; i < from + count; i++) {
      char c = s.charAt(i);
      if (!isDigit(c)) {
        return -1;
      }
      value = value * 10 + (c - '0');
    }
    return value;
  }

  /** {@code yyyy-MM-dd}, ASCII digits, a real calendar date. */
  private static Value date(String s) {
    if (s.length() != 10 || s.charAt(4) != '-' || s.charAt(7) != '-') {
      throw Evaluator.error(ErrorKind.PARSE, "not an ISO date");
    }
    int year = digits(s, 0, 4);
    int month = digits(s, 5, 2);
    int day = digits(s, 8, 2);
    if (year < 0 || month < 0 || day < 0) {
      throw Evaluator.error(ErrorKind.PARSE, "not an ISO date");
    }
    try {
      return new DateValue(LocalDate.of(year, month, day));
    } catch (DateTimeException _) {
      throw Evaluator.error(ErrorKind.PARSE, "not an ISO date");
    }
  }

  private static Instant instant(Value v) {
    if (v instanceof TimestampValue(Instant t)) {
      return t;
    }
    throw Evaluator.error(ErrorKind.TYPE, "expected a timestamp");
  }

  private static LocalDate dateOf(Value v) {
    if (v instanceof DateValue(LocalDate d)) {
      return d;
    }
    throw Evaluator.error(ErrorKind.TYPE, "expected a date");
  }

  /** The fixed form {@code yyyy-MM-ddTHH:mm:ss.ffffffZ}, years 0000 to 9999, written by hand. */
  static String isoMicros(Instant t) {
    if (t.getNano() % 1_000 != 0) {
      throw Evaluator.error(ErrorKind.RANGE, "finer than a microsecond");
    }
    if (t.getEpochSecond() < MIN_EPOCH_SECOND || t.getEpochSecond() > MAX_EPOCH_SECOND) {
      throw Evaluator.error(ErrorKind.RANGE, "year outside 0000 to 9999");
    }
    LocalDateTime ldt = LocalDateTime.ofEpochSecond(t.getEpochSecond(), 0, ZoneOffset.UTC);
    StringBuilder out = new StringBuilder(27);
    pad(out, ldt.getYear(), 4).append('-');
    pad(out, ldt.getMonthValue(), 2).append('-');
    pad(out, ldt.getDayOfMonth(), 2).append('T');
    pad(out, ldt.getHour(), 2).append(':');
    pad(out, ldt.getMinute(), 2).append(':');
    pad(out, ldt.getSecond(), 2).append('.');
    pad(out, t.getNano() / 1_000, 6).append('Z');
    return out.toString();
  }

  private static String isoDate(LocalDate d) {
    if (d.getYear() < 0 || d.getYear() > 9_999) {
      throw Evaluator.error(ErrorKind.RANGE, "year outside 0000 to 9999");
    }
    StringBuilder out = new StringBuilder(10);
    pad(out, d.getYear(), 4).append('-');
    pad(out, d.getMonthValue(), 2).append('-');
    pad(out, d.getDayOfMonth(), 2);
    return out.toString();
  }

  private static StringBuilder pad(StringBuilder out, int value, int width) {
    String digits = Integer.toString(value);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits);
  }

  // ---- strings and lists ---------------------------------------------------------------------

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
