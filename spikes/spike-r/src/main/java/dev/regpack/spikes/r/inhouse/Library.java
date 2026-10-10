package dev.regpack.spikes.r.inhouse;

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
import dev.regpack.spikes.r.inhouse.InHouseExpression.EvalException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * The function library and the shared value rules (cost model v1: units are charged through the
 * meter before the work is done). Functions: dec, round, string, int, timestamp, date, isoMicros,
 * dayOfWeek, upper, lower, size, contains; methods: startsWith, endsWith, contains.
 */
public final class Library {
  /** The library functions, by name and shape. */
  public enum Fn {
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

    private final String label;
    private final int arity;
    private final boolean global;
    private final boolean method;

    Fn(String label, int arity, boolean global, boolean method) {
      this.label = label;
      this.arity = arity;
      this.global = global;
      this.method = method;
    }

    /** The number of arguments, a method receiver included. */
    public int arity() {
      return arity;
    }

    public String label() {
      return label;
    }
  }

  /** Largest absolute scale and largest precision of a decimal (digits). */
  static final int MAX_DIGITS = 38;

  private static final long MIN_EPOCH_SECOND = -62_167_219_200L; // 0000-01-01T00:00:00Z
  private static final long MAX_EPOCH_SECOND = 253_402_300_799L; // 9999-12-31T23:59:59Z

  private Library() {}

  /** The global function of that name, if there is one. */
  public static Optional<Fn> global(String name) {
    for (Fn fn : Fn.values()) {
      if (fn.global && fn.label.equals(name)) {
        return Optional.of(fn);
      }
    }
    return Optional.empty();
  }

  /** The method of that name, if there is one; its receiver is the first argument. */
  public static Optional<Fn> method(String name) {
    for (Fn fn : Fn.values()) {
      if (fn.method && fn.label.equals(name)) {
        return Optional.of(fn);
      }
    }
    return Optional.empty();
  }

  /** Applies a function; the arity was checked at compile time. */
  static Value apply(Fn fn, Value[] a, CostMeter meter) {
    return switch (fn) {
      case DEC -> dec(a[0], meter);
      case ROUND -> round(a[0], a[1], a[2], meter);
      case STRING -> string(a[0], meter);
      case INT -> toInt(a[0], meter);
      case TIMESTAMP -> {
        String s = str(a[0]);
        meter.charge(1 + units(s));
        yield timestamp(s);
      }
      case DATE -> {
        String s = str(a[0]);
        meter.charge(1 + units(s));
        yield date(s);
      }
      case ISO_MICROS -> new StringValue(isoMicros(instant(a[0])));
      case DAY_OF_WEEK -> new StringValue(dateOf(a[0]).getDayOfWeek().name());
      case UPPER -> {
        String s = str(a[0]);
        meter.charge(units(s));
        yield new StringValue(s.toUpperCase(Locale.ROOT));
      }
      case LOWER -> {
        String s = str(a[0]);
        meter.charge(units(s));
        yield new StringValue(s.toLowerCase(Locale.ROOT));
      }
      case SIZE -> size(a[0], meter);
      case CONTAINS -> contains(a[0], a[1], meter);
      case STARTS_WITH -> {
        String s = str(a[0]);
        String p = str(a[1]);
        meter.charge(units(s) + units(p));
        yield new BoolValue(s.startsWith(p));
      }
      case ENDS_WITH -> {
        String s = str(a[0]);
        String p = str(a[1]);
        meter.charge(units(s) + units(p));
        yield new BoolValue(s.endsWith(p));
      }
    };
  }

  // ---- cost helpers -------------------------------------------------------------------------

  /** One unit per 256 code points. */
  static long units(String s) {
    return s.codePointCount(0, s.length()) / 256;
  }

  // ---- value access -------------------------------------------------------------------------

  static String str(Value v) {
    if (v instanceof StringValue(String s)) {
      return s;
    }
    throw error("TYPE", "expected a string");
  }

  private static Instant instant(Value v) {
    if (v instanceof TimestampValue(Instant t)) {
      return t;
    }
    throw error("TYPE", "expected a timestamp");
  }

  private static LocalDate dateOf(Value v) {
    if (v instanceof DateValue(LocalDate d)) {
      return d;
    }
    throw error("TYPE", "expected a date");
  }

  static EvalException error(String kind, String message) {
    return new EvalException(kind, message);
  }

  // ---- decimals -----------------------------------------------------------------------------

  /** Normalises a negative scale to 0 and refuses a scale or precision above 38 digits. */
  static BigDecimal checked(BigDecimal d) {
    BigDecimal r = d;
    if (r.scale() < 0) {
      if (r.scale() < -MAX_DIGITS) {
        throw error("SCALE", "decimal exponent out of range");
      }
      r = r.setScale(0);
    }
    if (r.scale() > MAX_DIGITS || r.precision() > MAX_DIGITS) {
      throw error("SCALE", "decimal exceeds 38 digits");
    }
    return r;
  }

  static BigDecimal decimalOf(Value v) {
    if (v instanceof DecimalValue(BigDecimal d)) {
      return d;
    }
    if (v instanceof IntValue(long n)) {
      return BigDecimal.valueOf(n);
    }
    throw error("TYPE", "expected a number");
  }

  private static boolean asciiNumeric(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok =
          (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.' || c == 'e' || c == 'E';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  private static Value dec(Value arg, CostMeter meter) {
    if (arg instanceof StringValue(String s)) {
      meter.charge(1 + units(s));
      if (!asciiNumeric(s)) {
        throw error("PARSE", "not a decimal");
      }
      try {
        return new DecimalValue(checked(new BigDecimal(s)));
      } catch (NumberFormatException e) {
        throw error("PARSE", "not a decimal");
      }
    }
    meter.charge(1);
    return new DecimalValue(checked(decimalOf(arg)));
  }

  private static Value round(Value value, Value scale, Value mode, CostMeter meter) {
    meter.charge(1);
    BigDecimal d = decimalOf(value);
    if (!(scale instanceof IntValue(long places))) {
      throw error("TYPE", "round scale must be an int");
    }
    if (places < 0 || places > MAX_DIGITS) {
      throw error("RANGE", "round scale out of range");
    }
    RoundingMode rounding =
        switch (str(mode)) {
          case "HALF_UP" -> RoundingMode.HALF_UP;
          case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
          default -> throw error("OTHER", "unknown rounding mode");
        };
    return new DecimalValue(checked(d.setScale((int) places, rounding)));
  }

  // ---- conversions --------------------------------------------------------------------------

  private static Value string(Value v, CostMeter meter) {
    return switch (v) {
      case StringValue s -> {
        meter.charge(units(s.value()));
        yield s;
      }
      case BoolValue b -> new StringValue(b.value() ? "true" : "false");
      case IntValue i -> new StringValue(Long.toString(i.value()));
      case DecimalValue d -> new StringValue(d.value().stripTrailingZeros().toPlainString());
      case TimestampValue t -> new StringValue(isoMicros(t.value()));
      case DateValue d -> new StringValue(d.value().toString());
      case EnumValue e -> new StringValue(e.name());
      case ListValue _ -> throw error("TYPE", "a list has no string form");
    };
  }

  private static Value toInt(Value v, CostMeter meter) {
    return switch (v) {
      case IntValue i -> i;
      case DecimalValue d -> {
        try {
          BigInteger exact = d.value().toBigIntegerExact();
          yield new IntValue(exact.longValueExact());
        } catch (ArithmeticException e) {
          boolean fractional = d.value().stripTrailingZeros().scale() > 0;
          throw error(fractional ? "LOSSY" : "OVERFLOW", "decimal does not fit an int");
        }
      }
      case StringValue s -> {
        meter.charge(units(s.value()));
        yield new IntValue(parseInt(s.value()));
      }
      default -> throw error("TYPE", "cannot convert to int");
    };
  }

  private static long parseInt(String s) {
    int start = s.startsWith("-") ? 1 : 0;
    if (s.length() == start) {
      throw error("PARSE", "not an int");
    }
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        throw error("PARSE", "not an int");
      }
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      throw error("PARSE", "int out of range");
    }
  }

  // ---- time ---------------------------------------------------------------------------------

  private static Value timestamp(String s) {
    // RFC 3339 shape: seconds and an offset are mandatory (a zone-less text is a parse error)
    if (s.length() < 20 || s.charAt(10) != 'T' || s.charAt(13) != ':' || s.charAt(16) != ':') {
      throw error("PARSE", "not an RFC 3339 timestamp");
    }
    OffsetDateTime parsed;
    try {
      parsed = OffsetDateTime.parse(s);
    } catch (DateTimeException e) {
      throw error("PARSE", "not an RFC 3339 timestamp");
    }
    if (parsed.getNano() % 1000 != 0) {
      throw error("RANGE", "finer than microseconds");
    }
    return new TimestampValue(parsed.toInstant());
  }

  private static Value date(String s) {
    try {
      return new DateValue(LocalDate.parse(s));
    } catch (DateTimeException e) {
      throw error("PARSE", "not an ISO date");
    }
  }

  /** The fixed form yyyy-MM-ddTHH:mm:ss.ffffffZ, written by hand (no formatter, no locale). */
  static String isoMicros(Instant t) {
    if (t.getNano() % 1000 != 0) {
      throw error("RANGE", "finer than microseconds");
    }
    if (t.getEpochSecond() < MIN_EPOCH_SECOND || t.getEpochSecond() > MAX_EPOCH_SECOND) {
      throw error("RANGE", "year outside 0000 to 9999");
    }
    LocalDateTime ldt = LocalDateTime.ofEpochSecond(t.getEpochSecond(), 0, ZoneOffset.UTC);
    StringBuilder out = new StringBuilder(27);
    pad(out, ldt.getYear(), 4).append('-');
    pad(out, ldt.getMonthValue(), 2).append('-');
    pad(out, ldt.getDayOfMonth(), 2).append('T');
    pad(out, ldt.getHour(), 2).append(':');
    pad(out, ldt.getMinute(), 2).append(':');
    pad(out, ldt.getSecond(), 2).append('.');
    pad(out, t.getNano() / 1000, 6).append('Z');
    return out.toString();
  }

  private static StringBuilder pad(StringBuilder out, int value, int width) {
    String digits = Integer.toString(value);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits);
  }

  // ---- strings and lists --------------------------------------------------------------------

  private static Value size(Value v, CostMeter meter) {
    if (v instanceof StringValue(String s)) {
      meter.charge(units(s));
      return new IntValue(s.codePointCount(0, s.length()));
    }
    if (v instanceof ListValue(java.util.List<Value> items)) {
      return new IntValue(items.size());
    }
    throw error("TYPE", "size needs a string or a list");
  }

  private static Value contains(Value haystack, Value needle, CostMeter meter) {
    if (haystack instanceof ListValue(java.util.List<Value> items)) {
      meter.charge(items.size());
      for (Value item : items) {
        if (equal(item, needle)) {
          return new BoolValue(true);
        }
      }
      return new BoolValue(false);
    }
    String s = str(haystack);
    String p = str(needle);
    meter.charge(units(s) + units(p));
    return new BoolValue(s.contains(p));
  }

  // ---- equality and order -------------------------------------------------------------------

  private static boolean numeric(Value v) {
    return v instanceof IntValue || v instanceof DecimalValue;
  }

  /** Equality; two values of unrelated types are a TYPE error, never silently unequal. */
  static boolean equal(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      return a == b;
    }
    if (numeric(l) && numeric(r)) {
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
    if (l instanceof ListValue(java.util.List<Value> a)
        && r instanceof ListValue(java.util.List<Value> b)) {
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
    throw error("TYPE", "cannot compare values of these types");
  }

  /** Order of numbers, strings (by code point), timestamps and dates. */
  static int compare(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      return Long.compare(a, b);
    }
    if (numeric(l) && numeric(r)) {
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
    throw error("TYPE", "cannot order values of these types");
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
}
