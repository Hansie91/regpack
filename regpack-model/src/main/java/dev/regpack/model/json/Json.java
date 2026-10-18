package dev.regpack.model.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small strict JSON parser and a canonical writer.
 *
 * <p>Parsing yields {@code Map<String,Object>} (a {@link LinkedHashMap}), {@code List<Object>},
 * {@code String}, {@code Boolean}, {@code Long} or {@code BigDecimal}. The literal {@code null} is
 * rejected: explicit absence means the key is omitted. The canonical form has sorted keys, no
 * whitespace and scaled decimals only, so it is byte-exact for hashing and for goldens.
 */
public final class Json {
  private static final int MAX_DEPTH = 512;

  /** Largest absolute decimal scale accepted; keeps the plain-string form of a number small. */
  private static final int MAX_SCALE = 1000;

  /**
   * Longest numeric literal in characters (1,000 digits of scale plus an integer part, a sign, a
   * dot and an exponent): a longer one is refused before a decimal is built, because building one
   * takes quadratic time in the digits.
   */
  private static final int MAX_NUMBER_CHARS = 1_100;

  /** Characters of a literal that an error message repeats. */
  private static final int MAX_ECHO = 40;

  private static final char[] HEX = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };
  private static final DateTimeFormatter INSTANT_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private Json() {}

  /** Parses one JSON value; anything after it other than whitespace is an error. */
  public static Object parse(String text) {
    Parser parser = new Parser(text);
    parser.skipWhitespace();
    Object value = parser.value(0);
    parser.skipWhitespace();
    if (!parser.atEnd()) {
      throw parser.error("unexpected trailing characters");
    }
    return value;
  }

  /** Parses one value per non-blank line; errors name the 1-based line. */
  public static List<Object> parseNdjson(String text) {
    List<Object> values = new ArrayList<>();
    int lineNumber = 0;
    int start = 0;
    int length = text.length();
    while (start < length) {
      int end = text.indexOf('\n', start);
      if (end < 0) {
        end = length;
      }
      lineNumber++;
      String line = text.substring(start, end);
      start = end + 1;
      if (isBlank(line)) {
        continue;
      }
      try {
        values.add(parse(line));
      } catch (JsonException e) {
        throw new JsonException("line " + lineNumber + ": " + e.getMessage());
      }
    }
    return List.copyOf(values);
  }

  /** Writes the canonical form: sorted keys, no whitespace, scaled decimals only. */
  public static String canonical(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value);
    return out.toString();
  }

  private static boolean isBlank(String line) {
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c != ' ' && c != '\t' && c != '\r') {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------- writer

  private static void write(StringBuilder out, Object value) {
    switch (value) {
      case null -> throw new IllegalArgumentException("null is not representable");
      case String s -> writeString(out, s);
      case Boolean b -> out.append(b.booleanValue() ? "true" : "false");
      case Long n -> out.append(n.longValue());
      case Integer n -> out.append(n.intValue());
      case Short n -> out.append(n.shortValue());
      case Byte n -> out.append(n.byteValue());
      case BigInteger n -> out.append(n.toString());
      case BigDecimal n ->
          out.append(n.signum() == 0 ? "0" : n.stripTrailingZeros().toPlainString());
      case Instant t -> writeInstant(out, t);
      case LocalDate d -> out.append('"').append(d.toString()).append('"');
      case Enum<?> e -> writeString(out, e.name());
      case Map<?, ?> m -> writeMap(out, m);
      case List<?> l -> writeList(out, l);
      default ->
          throw new IllegalArgumentException(
              "not representable in canonical JSON: " + value.getClass().getName());
    }
  }

  private static void writeInstant(StringBuilder out, Instant t) {
    if (t.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("instant has sub-microsecond precision: " + t);
    }
    int year = t.atOffset(ZoneOffset.UTC).getYear();
    if (year < 0 || year > 9999) {
      throw new IllegalArgumentException("instant year outside 0000-9999: " + t);
    }
    out.append('"').append(INSTANT_FORMAT.format(t)).append('"');
  }

  private static void writeMap(StringBuilder out, Map<?, ?> map) {
    List<String> keys = new ArrayList<>(map.size());
    for (Object key : map.keySet()) {
      if (!(key instanceof String s)) {
        throw new IllegalArgumentException("object keys must be strings");
      }
      keys.add(s);
    }
    keys.sort(String::compareTo);
    out.append('{');
    boolean first = true;
    for (String key : keys) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(out, key);
      out.append(':');
      write(out, map.get(key));
    }
    out.append('}');
  }

  private static void writeList(StringBuilder out, List<?> list) {
    out.append('[');
    boolean first = true;
    for (Object element : list) {
      if (!first) {
        out.append(',');
      }
      first = false;
      write(out, element);
    }
    out.append(']');
  }

  /** Returns the index of the first unpaired surrogate, or -1. */
  private static int unpairedSurrogate(CharSequence s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < s.length()
          && Character.isLowSurrogate(s.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return i;
      }
    }
    return -1;
  }

  private static void writeString(StringBuilder out, String s) {
    if (unpairedSurrogate(s) >= 0) {
      throw new IllegalArgumentException("string contains an unpaired surrogate");
    }
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  // ---------------------------------------------------------------- parser

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    boolean atEnd() {
      return pos >= text.length();
    }

    JsonException error(String message) {
      return new JsonException(message + " at offset " + pos);
    }

    void skipWhitespace() {
      while (pos < text.length()) {
        char c = text.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          return;
        }
      }
    }

    Object value(int depth) {
      if (atEnd()) {
        throw error("unexpected end of input");
      }
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> object(depth + 1);
        case '[' -> array(depth + 1);
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> {
          if (text.startsWith("null", pos)) {
            throw error("the null literal is not allowed; omit the key instead");
          }
          throw error("unexpected character 'n'");
        }
        default -> {
          if (c == '-' || isDigit(c)) {
            yield number();
          }
          throw error("unexpected character '" + c + "'");
        }
      };
    }

    private Object literal(String word, Boolean result) {
      if (!text.startsWith(word, pos)) {
        throw error("invalid literal");
      }
      pos += word.length();
      return result;
    }

    private Map<String, Object> object(int depth) {
      checkDepth(depth);
      pos++; // '{'
      Map<String, Object> map = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw error("object key must be a string");
        }
        String key = string();
        skipWhitespace();
        if (peek() != ':') {
          throw error("expected ':'");
        }
        pos++;
        skipWhitespace();
        Object member = value(depth);
        if (map.containsKey(key)) {
          throw error("duplicate key \"" + key + "\"");
        }
        map.put(key, member);
        skipWhitespace();
        char c = peek();
        if (c == '}') {
          pos++;
          return map;
        }
        if (c != ',') {
          throw error("expected ',' or '}'");
        }
        pos++;
      }
    }

    private List<Object> array(int depth) {
      checkDepth(depth);
      pos++; // '['
      List<Object> list = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        return list;
      }
      while (true) {
        skipWhitespace();
        list.add(value(depth));
        skipWhitespace();
        char c = peek();
        if (c == ']') {
          pos++;
          return list;
        }
        if (c != ',') {
          throw error("expected ',' or ']'");
        }
        pos++;
      }
    }

    private void checkDepth(int depth) {
      if (depth > MAX_DEPTH) {
        throw error("nesting deeper than " + MAX_DEPTH);
      }
    }

    /** Returns the current character, or NUL at the end of input (never valid JSON syntax). */
    private char peek() {
      return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private String string() {
      pos++; // opening quote
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (atEnd()) {
          throw error("unterminated string");
        }
        char c = text.charAt(pos);
        if (c == '"') {
          pos++;
          if (unpairedSurrogate(sb) >= 0) {
            pos--;
            throw error("unpaired surrogate in string");
          }
          return sb.toString();
        }
        if (c < 0x20) {
          throw error("unescaped control character in string");
        }
        if (c == '\\') {
          pos++;
          sb.append(escape());
        } else {
          sb.append(c);
          pos++;
        }
      }
    }

    private char escape() {
      if (atEnd()) {
        throw error("unterminated escape");
      }
      char c = text.charAt(pos++);
      return switch (c) {
        case '"' -> '"';
        case '\\' -> '\\';
        case '/' -> '/';
        case 'b' -> '\b';
        case 'f' -> '\f';
        case 'n' -> '\n';
        case 'r' -> '\r';
        case 't' -> '\t';
        case 'u' -> unicode();
        default -> {
          pos--;
          throw error("invalid escape");
        }
      };
    }

    private char unicode() {
      if (pos + 4 > text.length()) {
        throw error("truncated unicode escape");
      }
      int code = 0;
      for (int i = 0; i < 4; i++) {
        char h = text.charAt(pos + i);
        int digit = h < 128 ? Character.digit(h, 16) : -1;
        if (digit < 0) {
          throw error("invalid unicode escape");
        }
        code = (code << 4) | digit;
      }
      pos += 4;
      return (char) code;
    }

    private Object number() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      if (peek() == '0') {
        pos++;
        if (isDigit(peek())) {
          throw error("leading zeros are not allowed");
        }
      } else if (isDigit(peek())) {
        while (isDigit(peek())) {
          pos++;
        }
      } else {
        throw error("invalid number");
      }
      boolean integral = true;
      if (peek() == '.') {
        integral = false;
        pos++;
        if (!isDigit(peek())) {
          throw error("digits required after decimal point");
        }
        while (isDigit(peek())) {
          pos++;
        }
      }
      if (peek() == 'e' || peek() == 'E') {
        integral = false;
        pos++;
        if (peek() == '+' || peek() == '-') {
          pos++;
        }
        if (!isDigit(peek())) {
          throw error("digits required in exponent");
        }
        while (isDigit(peek())) {
          pos++;
        }
      }
      if (pos - start > MAX_NUMBER_CHARS) {
        // before any BigDecimal is built: its construction is quadratic in the digits
        pos = start;
        throw error("number too long");
      }
      String literal = text.substring(start, pos);
      try {
        if (integral) {
          return Long.valueOf(Long.parseLong(literal));
        }
        BigDecimal decimal = new BigDecimal(literal);
        if (Math.abs((long) decimal.scale()) > MAX_SCALE) {
          pos = start;
          throw error("number out of range: " + excerpt(literal));
        }
        return decimal;
      } catch (NumberFormatException e) {
        pos = start;
        throw error("number out of range: " + excerpt(literal));
      }
    }

    /** The first 40 characters of a literal, so that a message stays short. */
    private static String excerpt(String literal) {
      return literal.length() <= MAX_ECHO ? literal : literal.substring(0, MAX_ECHO) + "...";
    }

    private static boolean isDigit(char c) {
      return c >= '0' && c <= '9';
    }
  }
}
