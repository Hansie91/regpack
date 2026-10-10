package dev.regpack.testkit.golden;

import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.json.Json;
import dev.regpack.testkit.golden.GoldenCase.Expected;
import dev.regpack.testkit.golden.GoldenCase.Provenance;
import dev.regpack.testkit.golden.GoldenCase.ProvenanceKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Strict reader of golden case files. */
public final class GoldenCaseLoader {
  private static final Set<String> CASE_KEYS =
      Set.of("id", "node", "description", "author", "input", "expected", "provenance");
  private static final Set<String> EXPECTED_KEYS = Set.of("outcome", "reasonCode", "ruleIdsHit");
  private static final Set<String> PROVENANCE_KEYS =
      Set.of("kind", "source", "reviewer", "reviewedOn");

  private GoldenCaseLoader() {}

  /** Parses one case; a missing {@code provenance} object means CONSISTENCY_ONLY. */
  public static GoldenCase parse(String json) {
    Map<String, Object> root = object(Json.parse(json), "case");
    requireKnownKeys(root, CASE_KEYS, "case");
    String id = string(root, "id", "case");
    String node = string(root, "node", "case");
    String description = string(root, "description", "case");
    String author = string(root, "author", "case");
    Map<String, Object> input = object(require(root, "input", "case"), "input");
    Map<String, Object> expected = object(require(root, "expected", "case"), "expected");
    requireKnownKeys(expected, EXPECTED_KEYS, "expected");
    Expected parsedExpected =
        new Expected(
            enumValue(Outcome.class, string(expected, "outcome", "expected"), "outcome"),
            new ReasonCode(string(expected, "reasonCode", "expected")),
            stringList(require(expected, "ruleIdsHit", "expected"), "ruleIdsHit"));
    Provenance provenance =
        root.containsKey("provenance")
            ? provenance(object(root.get("provenance"), "provenance"))
            : Provenance.consistencyOnly();
    return new GoldenCase(id, node, description, input, parsedExpected, provenance, author);
  }

  /** Loads every {@code *.json} file of a directory, sorted by file name. */
  public static List<GoldenCase> loadDirectory(Path dir) {
    List<Path> files;
    try (Stream<Path> listing = Files.list(dir)) {
      files =
          listing
              .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
              .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
              .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list golden directory " + dir, e);
    }
    List<GoldenCase> cases = new ArrayList<>();
    for (Path file : files) {
      try {
        cases.add(parse(Files.readString(file, StandardCharsets.UTF_8)));
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read golden case " + file, e);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            "invalid golden case " + file + ": " + e.getMessage(), e);
      }
    }
    return List.copyOf(cases);
  }

  private static Provenance provenance(Map<String, Object> node) {
    requireKnownKeys(node, PROVENANCE_KEYS, "provenance");
    ProvenanceKind kind =
        enumValue(ProvenanceKind.class, string(node, "kind", "provenance"), "provenance kind");
    Optional<LocalDate> reviewedOn =
        node.containsKey("reviewedOn")
            ? Optional.of(date(string(node, "reviewedOn", "provenance")))
            : Optional.empty();
    return new Provenance(
        kind, optionalString(node, "source"), optionalString(node, "reviewer"), reviewedOn);
  }

  private static LocalDate date(String text) {
    try {
      return LocalDate.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("reviewedOn must be yyyy-mm-dd but was: " + text, e);
    }
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String what) {
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown " + what + ": " + name, e);
    }
  }

  private static void requireKnownKeys(Map<String, Object> node, Set<String> known, String where) {
    for (String key : node.keySet()) {
      if (!known.contains(key)) {
        throw new IllegalArgumentException("unknown key '" + key + "' in " + where);
      }
    }
  }

  private static Object require(Map<String, Object> node, String key, String where) {
    Object value = node.get(key);
    if (value == null) {
      throw new IllegalArgumentException("missing field '" + key + "' in " + where);
    }
    return value;
  }

  private static String string(Map<String, Object> node, String key, String where) {
    if (require(node, key, where) instanceof String text) {
      return text;
    }
    throw new IllegalArgumentException("field '" + key + "' in " + where + " must be a string");
  }

  private static Optional<String> optionalString(Map<String, Object> node, String key) {
    return node.containsKey(key) ? Optional.of(string(node, key, "provenance")) : Optional.empty();
  }

  private static Map<String, Object> object(Object value, String what) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        copy.put((String) entry.getKey(), entry.getValue());
      }
      return copy;
    }
    throw new IllegalArgumentException(what + " must be a JSON object");
  }

  private static List<String> stringList(Object value, String what) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(what + " must be a JSON array of strings");
    }
    List<String> result = new ArrayList<>();
    for (Object element : list) {
      if (!(element instanceof String text)) {
        throw new IllegalArgumentException(what + " must be a JSON array of strings");
      }
      result.add(text);
    }
    return result;
  }
}
