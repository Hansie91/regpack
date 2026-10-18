package dev.regpack.engine.expr.conformance;

import dev.regpack.engine.expr.Rulings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The raw rows of {@code rulings.tsv} for the tests (the production reader is {@link
 * Rulings#parse}). {@code expected} is the outcome of the vector under the in-house value of the
 * switch (the first constant of its enum) and {@code expectedAlt} under {@code altValue}, so that a
 * ruling is the change of {@code value} alone.
 */
final class RulingsFile {
  /** Name of the resource next to {@link Rulings}. */
  static final String RESOURCE_NAME = "rulings.tsv";

  /** One data row. */
  record Row(
      String id,
      String status,
      String switchName,
      String value,
      String altValue,
      String expected,
      String expectedAlt,
      String ruledBy,
      String ruledOn,
      String note) {

    /** The in-house value of the switch of this row. */
    String inHouseValue() {
      Rulings in = Rulings.provisional();
      return switch (switchName) {
        case "intDecimalMix" -> in.intDecimalMix().name();
        case "andLeftError" -> in.andLeftError().name();
        case "orLeftError" -> in.orLeftError().name();
        case "hasBelowAbsent" -> in.hasBelowAbsent().name();
        case "enumStringEquality" -> "BY_NAME";
        default -> throw new IllegalStateException("unknown switch " + switchName);
      };
    }

    /** The expected outcome of the vector under the value that the row currently holds. */
    String expectation() {
      return value.equals(inHouseValue()) ? expected : expectedAlt;
    }

    /** The expected outcome under the alternative value. */
    String alternativeExpectation() {
      return expectedAlt;
    }
  }

  private RulingsFile() {}

  /** The text of the committed file. */
  static String text() {
    try (var in = Rulings.class.getResourceAsStream(RESOURCE_NAME)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + RESOURCE_NAME);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Row> load() {
    return rows(text());
  }

  /** The data rows (comments and the header line skipped). */
  static List<Row> rows(String text) {
    List<Row> rows = new ArrayList<>();
    boolean header = true;
    for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      if (header) {
        header = false;
        continue;
      }
      String[] c = line.split("\t", -1);
      rows.add(new Row(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]));
    }
    return List.copyOf(rows);
  }

  /** The expectation of every ruling row, keyed by vector id, under the value the file holds. */
  static Map<String, String> expectations() {
    Map<String, String> map = new LinkedHashMap<>();
    for (Row row : load()) {
      map.put(row.id(), row.expectation());
    }
    return Map.copyOf(map);
  }

  /** The file text with the {@code value} cell of one row replaced (a one-line ruling). */
  static String withValue(String text, String id, String newValue) {
    return replaceCell(text, id, 3, newValue);
  }

  /** The file text with one cell of the row with the given id replaced. */
  static String replaceCell(String text, String id, int column, String newValue) {
    StringBuilder out = new StringBuilder();
    boolean changed = false;
    for (String line : text.split("\n", -1)) {
      if (line.startsWith(id + "\t")) {
        String[] c = line.split("\t", -1);
        c[column] = newValue;
        line = String.join("\t", c);
        changed = true;
      }
      out.append(line).append('\n');
    }
    if (!changed) {
      throw new IllegalArgumentException("no row " + id);
    }
    // split("\n", -1) yields a trailing empty element: drop the extra newline it produced
    return out.substring(0, out.length() - 1);
  }
}
