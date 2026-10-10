package dev.regpack.bench.eval;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The criteria registry: {@code criteria/criteria.json} is the criteria section of the plan as
 * data, {@code {"schema":"regpack.bench.criteria/1","params":{..},"boxes":{..},"criteria":[..]}}.
 * Every number is a string. The digest is the SHA-256 of the canonical JSON, so a changed threshold
 * changes it.
 */
public final class Criteria {
  /** The schema label of the registry file. */
  public static final String SCHEMA = "regpack.bench.criteria/1";

  private static final String RESOURCE = "/criteria/criteria.json";

  private static final Set<String> TOP_KEYS = Set.of("schema", "params", "boxes", "criteria");
  private static final Set<String> ROW_REQUIRED =
      Set.of(
          "id",
          "spike",
          "metric",
          "unit",
          "direction",
          "source",
          "required",
          "noisy",
          "resources",
          "minRuns",
          "basis");
  private static final Set<String> ROW_OPTIONAL =
      Set.of("threshold", "group", "consequenceIfNotRun");

  private final List<Criterion> criteria;
  private final Map<String, BigDecimal> params;
  private final Map<String, Duration> boxes;
  private final Sha256 digest;

  private Criteria(
      List<Criterion> criteria,
      Map<String, BigDecimal> params,
      Map<String, Duration> boxes,
      Sha256 digest) {
    this.criteria = List.copyOf(criteria);
    this.params = Map.copyOf(params);
    this.boxes = Map.copyOf(boxes);
    this.digest = digest;
  }

  /** Loads the registry from the class path resource. */
  public static Criteria load() {
    try (InputStream in = Criteria.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + RESOURCE);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Parses a registry; every deviation from the schema is an {@link IllegalArgumentException}. */
  public static Criteria parse(String json) {
    Object root = Json.parse(json);
    Map<String, Object> top = object(root, "root");
    requireKeys(top, TOP_KEYS, Set.of(), "root");
    if (!SCHEMA.equals(top.get("schema"))) {
      throw new IllegalArgumentException("schema must be " + SCHEMA);
    }

    Map<String, BigDecimal> params = new TreeMap<>();
    for (Map.Entry<String, Object> entry : object(top.get("params"), "params").entrySet()) {
      params.put(entry.getKey(), number(entry.getValue(), "params." + entry.getKey()));
    }

    Map<String, Duration> boxes = new TreeMap<>();
    for (Map.Entry<String, Object> entry : object(top.get("boxes"), "boxes").entrySet()) {
      BigDecimal minutes = number(entry.getValue(), "boxes." + entry.getKey());
      if (minutes.signum() <= 0 || minutes.stripTrailingZeros().scale() > 0) {
        throw new IllegalArgumentException("box " + entry.getKey() + " needs whole minutes");
      }
      boxes.put(entry.getKey(), Duration.ofMinutes(minutes.longValueExact()));
    }

    List<Criterion> rows = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Object list = top.get("criteria");
    if (!(list instanceof List<?> items)) {
      throw new IllegalArgumentException("criteria must be an array");
    }
    for (Object item : items) {
      Criterion row = row(object(item, "criteria[]"));
      if (!ids.add(row.id())) {
        throw new IllegalArgumentException("duplicate criterion " + row.id());
      }
      if (!boxes.containsKey(row.spike())) {
        throw new IllegalArgumentException(row.id() + ": no time-box for " + row.spike());
      }
      rows.add(row);
    }
    return new Criteria(rows, params, boxes, Sha256.ofUtf8(Json.canonical(root)));
  }

  private static Criterion row(Map<String, Object> m) {
    requireKeys(m, ROW_REQUIRED, ROW_OPTIONAL, "criterion");
    String id = text(m.get("id"), "id");
    String where = "criterion " + id;
    Set<Resource> resources = EnumSet.noneOf(Resource.class);
    if (!(m.get("resources") instanceof List<?> names)) {
      throw new IllegalArgumentException(where + ": resources must be an array");
    }
    for (Object name : names) {
      resources.add(enumValue(Resource.class, text(name, "resource"), where));
    }
    BigDecimal minRuns = number(m.get("minRuns"), where + ".minRuns");
    return new Criterion(
        id,
        text(m.get("spike"), "spike"),
        text(m.get("metric"), "metric"),
        text(m.get("unit"), "unit"),
        enumValue(Direction.class, text(m.get("direction"), "direction"), where),
        m.containsKey("threshold")
            ? Optional.of(number(m.get("threshold"), where + ".threshold"))
            : Optional.empty(),
        text(m.get("source"), "source"),
        bool(m.get("required"), where + ".required"),
        bool(m.get("noisy"), where + ".noisy"),
        resources,
        minRuns.intValueExact(),
        enumValue(Basis.class, text(m.get("basis"), "basis"), where),
        m.containsKey("group") ? Optional.of(text(m.get("group"), "group")) : Optional.empty(),
        m.containsKey("consequenceIfNotRun")
            ? Optional.of(text(m.get("consequenceIfNotRun"), "consequenceIfNotRun"))
            : Optional.empty());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String where) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new IllegalArgumentException(where + " must be an object");
  }

  private static void requireKeys(
      Map<String, Object> m, Set<String> required, Set<String> optional, String where) {
    for (String key : required) {
      if (!m.containsKey(key)) {
        throw new IllegalArgumentException(where + ": missing " + key);
      }
    }
    for (String key : m.keySet()) {
      if (!required.contains(key) && !optional.contains(key)) {
        throw new IllegalArgumentException(where + ": unknown key " + key);
      }
    }
  }

  private static String text(Object value, String where) {
    if (value instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException(where + " must be a non-empty string");
  }

  private static boolean bool(Object value, String where) {
    if (value instanceof Boolean b) {
      return b;
    }
    throw new IllegalArgumentException(where + " must be true or false");
  }

  private static BigDecimal number(Object value, String where) {
    if (value instanceof String s) {
      try {
        return new BigDecimal(s);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(where + " is not a number: " + s);
      }
    }
    throw new IllegalArgumentException(where + " must be a number written as a string");
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String where) {
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(where + ": unknown " + type.getSimpleName() + " " + name);
    }
  }

  /** The criteria of one spike, in registry order. */
  public List<Criterion> forSpike(String spike) {
    return criteria.stream().filter(c -> c.spike().equals(spike)).toList();
  }

  /** The criterion with the given id. */
  public Optional<Criterion> byId(String id) {
    return criteria.stream().filter(c -> c.id().equals(id)).findFirst();
  }

  /** The time-box of a spike (or of the harness). */
  public Duration box(String spike) {
    Duration box = boxes.get(spike);
    if (box == null) {
      throw new IllegalArgumentException("no time-box for " + spike);
    }
    return box;
  }

  /** A named parameter of the planning model. */
  public Optional<BigDecimal> param(String name) {
    return Optional.ofNullable(params.get(name));
  }

  /** The digest of the canonical JSON of the registry. */
  public Sha256 digest() {
    return digest;
  }

  /**
   * The registry as markdown for {@code docs/spikes/criteria.md}: one table, the placeholder rows
   * (source contains {@code PLAN}) first, each group in registry order. The owner confirms the
   * first group before the first full run.
   */
  public String toMarkdown() {
    StringBuilder out = new StringBuilder();
    out.append("# Criteria of the four spikes\n\n");
    out.append("Digest: ").append(digest.hex()).append("\n\n");
    out.append("Rows whose source contains PLAN are placeholders: the owner confirms them in ");
    out.append("`criteria.lock` before the first full run.\n\n");
    out.append("| Id | Spike | Metric | Rule | Unit | Source | Req | Noise | Resources ");
    out.append("| Min runs | Basis | Group |\n");
    out.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    List<Criterion> ordered = new ArrayList<>();
    criteria.stream().filter(c -> c.source().contains("PLAN")).forEach(ordered::add);
    criteria.stream().filter(c -> !c.source().contains("PLAN")).forEach(ordered::add);
    for (Criterion c : ordered) {
      out.append("| ").append(c.id());
      out.append(" | ").append(c.spike());
      out.append(" | ").append(cell(c.metric()));
      out.append(" | ").append(rule(c));
      out.append(" | ").append(cell(c.unit()));
      out.append(" | ").append(cell(c.source()));
      out.append(" | ").append(c.required() ? "yes" : "no");
      out.append(" | ").append(c.noisy() ? "yes" : "no");
      out.append(" | ")
          .append(String.join(", ", c.resources().stream().map(Enum::name).sorted().toList()));
      out.append(" | ").append(c.minRuns());
      out.append(" | ").append(c.basis());
      out.append(" | ").append(c.group().orElse(""));
      out.append(" |\n");
    }
    out.append("\n## Parameters\n\n");
    for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(params).entrySet()) {
      out.append("- ").append(entry.getKey()).append(": ");
      out.append(entry.getValue().toPlainString()).append('\n');
    }
    out.append("\n## Time-boxes (minutes)\n\n");
    Map<String, Long> minutes = new LinkedHashMap<>();
    new TreeMap<>(boxes).forEach((spike, box) -> minutes.put(spike, box.toMinutes()));
    minutes.forEach(
        (spike, m) -> out.append("- ").append(spike).append(": ").append(m).append('\n'));
    return out.toString();
  }

  private static String rule(Criterion c) {
    return switch (c.direction()) {
      case AT_LEAST -> "at least " + c.threshold().orElseThrow().toPlainString();
      case AT_MOST -> "at most " + c.threshold().orElseThrow().toPlainString();
      case MEASURED_ONLY -> "measured only";
    };
  }

  private static String cell(String text) {
    return text.replace("|", "\\|").replace("\n", " ");
  }
}
