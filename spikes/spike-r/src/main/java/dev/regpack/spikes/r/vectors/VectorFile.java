package dev.regpack.spikes.r.vectors;

import dev.regpack.model.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Reads {@code semantic-vectors.tsv}: UTF-8, LF, one vector per line, seven tab-separated columns
 * {@code id class expression bindings expected budget ceiling}. The bindings column is a canonical
 * JSON object from dotted path to a type-prefixed text ({@code s:} string, {@code i:} int, {@code
 * d:} decimal, {@code b:} bool, {@code t:} timestamp, {@code D:} date, {@code e:Type.NAME} enum,
 * {@code l:[...]} list of prefixed texts); budget and ceiling are empty or a number of units.
 */
public final class VectorFile {
  public static final String RESOURCE = "/semantic-vectors.tsv";
  private static final Set<String> CLASSES = Set.of("MUST", "SHOULD", "DECIDE");

  private VectorFile() {}

  /** Loads the vectors that ship with the spike. */
  public static List<Vector> load() {
    try (InputStream in = VectorFile.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + RESOURCE);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Parses the file text; any malformed row is an {@link IllegalArgumentException}. */
  public static List<Vector> parse(String text) {
    List<Vector> vectors = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    int lineNumber = 0;
    for (String line : text.split("\n", -1)) {
      lineNumber++;
      if (line.isEmpty()) {
        continue;
      }
      String[] cols = line.split("\t", -1);
      if (cols.length != 7) {
        throw new IllegalArgumentException(
            "line " + lineNumber + ": need seven tab-separated columns, found " + cols.length);
      }
      if (!CLASSES.contains(cols[1])) {
        throw new IllegalArgumentException("line " + lineNumber + ": unknown class " + cols[1]);
      }
      if (!ids.add(cols[0])) {
        throw new IllegalArgumentException("line " + lineNumber + ": duplicate id " + cols[0]);
      }
      vectors.add(
          new Vector(
              cols[0],
              cols[1],
              cols[2],
              bindings(lineNumber, cols[3]),
              cols[4],
              units(lineNumber, cols[5]),
              units(lineNumber, cols[6])));
    }
    return List.copyOf(vectors);
  }

  private static OptionalLong units(int lineNumber, String cell) {
    if (cell.isEmpty()) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(cell));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("line " + lineNumber + ": not a number: " + cell);
    }
  }

  private static Map<String, String> bindings(int lineNumber, String cell) {
    Object parsed = Json.parse(cell);
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("line " + lineNumber + ": bindings must be an object");
    }
    Map<String, String> result = new java.util.TreeMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      if (!(e.getKey() instanceof String key) || !(e.getValue() instanceof String value)) {
        throw new IllegalArgumentException("line " + lineNumber + ": bindings must be strings");
      }
      result.put(key, value);
    }
    return result;
  }
}
