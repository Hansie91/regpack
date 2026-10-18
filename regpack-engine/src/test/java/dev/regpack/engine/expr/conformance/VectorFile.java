package dev.regpack.engine.expr.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Reads {@code vectors.tsv}: UTF-8, LF, one vector per line, eight tab-separated columns {@code id
 * class expression bindings expected budget ceiling basis}. Budget and ceiling are empty or a
 * number; a bindings cell may be empty. A blank line, a comment or a wrong cell count is refused.
 */
final class VectorFile {
  static final String RESOURCE = "/conformance/vectors.tsv";
  static final Set<String> CLASSES = Set.of("MUST", "SHOULD", "DECIDE", "SPIKE-DESIGN");
  private static final int COLUMNS = 8;

  private VectorFile() {}

  /** The vectors of the committed file. */
  static List<Vector> load() {
    return parse(resourceText(RESOURCE));
  }

  /** The text of a test or main resource, UTF-8; a missing resource is an error. */
  static String resourceText(String resource) {
    try (InputStream in = VectorFile.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Parses the file text; any malformed row is an {@link IllegalArgumentException}. */
  static List<Vector> parse(String text) {
    List<Vector> vectors = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    String[] lines = text.replace("\r\n", "\n").split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      boolean lastEmpty = i == lines.length - 1 && line.isEmpty();
      if (lastEmpty) {
        continue;
      }
      int lineNumber = i + 1;
      String[] cols = line.split("\t", -1);
      if (cols.length != COLUMNS) {
        throw new IllegalArgumentException(
            "line " + lineNumber + ": need eight tab-separated columns, found " + cols.length);
      }
      if (!CLASSES.contains(cols[1])) {
        throw new IllegalArgumentException("line " + lineNumber + ": unknown class " + cols[1]);
      }
      if (cols[0].isEmpty() || !ids.add(cols[0])) {
        throw new IllegalArgumentException("line " + lineNumber + ": empty or duplicate id");
      }
      if (cols[2].isEmpty() || cols[4].isEmpty() || cols[7].isEmpty()) {
        throw new IllegalArgumentException(
            "line " + lineNumber + ": expression, expected and basis must not be empty");
      }
      vectors.add(
          new Vector(
              cols[0],
              cols[1],
              cols[2],
              cols[3],
              cols[4],
              units(lineNumber, cols[5]),
              units(lineNumber, cols[6]),
              cols[7]));
    }
    return List.copyOf(vectors);
  }

  private static OptionalLong units(int lineNumber, String cell) {
    if (cell.isEmpty()) {
      return OptionalLong.empty();
    }
    for (int i = 0; i < cell.length(); i++) {
      char c = cell.charAt(i);
      if (c < '0' || c > '9' || cell.length() > 9) {
        throw new IllegalArgumentException("line " + lineNumber + ": not a number: " + cell);
      }
    }
    return OptionalLong.of(Long.parseLong(cell));
  }
}
