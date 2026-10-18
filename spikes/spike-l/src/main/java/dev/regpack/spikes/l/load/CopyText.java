package dev.regpack.spikes.l.load;

import java.time.LocalDate;
import java.util.List;

/**
 * The text of PostgreSQL's {@code COPY ... FROM STDIN} in the default text format: one line per
 * row, tab-separated columns, {@code \N} for NULL, and backslash escapes for backslash, tab, line
 * feed and carriage return. COPY removes one level of backslash, so a bytea in hex form ({@code
 * \x0aff}) is written with the backslash doubled.
 */
final class CopyText {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private CopyText() {}

  /** {@code COPY table (a, b) FROM STDIN}: the text format is the default. */
  static String copySql(String table, List<String> columns) {
    return "COPY " + table + " (" + String.join(", ", columns) + ") FROM STDIN";
  }

  /**
   * One row as a COPY line, with its line feed.
   *
   * @throws IllegalArgumentException for a value of a type that no column of the sketch has
   */
  static String line(Object[] row) {
    StringBuilder out = new StringBuilder(64);
    for (int i = 0; i < row.length; i++) {
      if (i > 0) {
        out.append('\t');
      }
      append(out, row[i]);
    }
    return out.append('\n').toString();
  }

  private static void append(StringBuilder out, Object value) {
    switch (value) {
      case null -> out.append("\\N");
      case Long v -> out.append(v.longValue());
      case Integer v -> out.append(v.intValue());
      case Short v -> out.append(v.shortValue());
      case Boolean v -> out.append(v.booleanValue() ? "true" : "false");
      case LocalDate v -> out.append(v.toString());
      case String v -> escape(out, v);
      case MicroBatchWriter.Jsonb v -> escape(out, v.text());
      case byte[] v -> {
        out.append("\\\\x");
        for (byte b : v) {
          out.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
      }
      default ->
          throw new IllegalArgumentException(
              "no COPY text for a value of type " + value.getClass().getName());
    }
  }

  private static void escape(StringBuilder out, String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        default -> out.append(c);
      }
    }
  }
}
