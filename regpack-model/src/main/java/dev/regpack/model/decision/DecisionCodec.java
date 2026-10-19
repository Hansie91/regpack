package dev.regpack.model.decision;

import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Converts decision types to the map form of canonical JSON. */
public final class DecisionCodec {
  private DecisionCodec() {}

  public static Map<String, Object> contextToMap(DecisionContext context) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("packDigest", context.packDigest().hex());
    context.overlayDigest().ifPresent(d -> map.put("overlayDigest", d.hex()));
    map.put("interpretationPins", context.interpretationPins());
    map.put("referenceSnapshotIds", context.referenceSnapshotIds());
    map.put("enrichmentSnapshotIds", context.enrichmentSnapshotIds());
    map.put("firmProfileVersion", context.firmProfileVersion());
    map.put("mappingVersion", context.mappingVersion());
    map.put("engineBuild", context.engineBuild());
    map.put("executor", context.executor());
    if (context.circuitEpoch() > 0) {
      map.put("circuitEpoch", context.circuitEpoch());
    }
    return map;
  }

  /** The flag omits evaluatedAt, and nothing else, when false. */
  public static Map<String, Object> bodyToMap(DecisionBody body, boolean includeEvaluatedAt) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("eventIdentity", body.eventIdentity().value());
    map.put("regime", body.regime().id());
    map.put("decisionContextId", body.decisionContextId().hex());
    map.put("asAt", body.asAt());
    if (includeEvaluatedAt) {
      map.put("evaluatedAt", body.evaluatedAt());
    }
    map.put("outcome", body.outcome().name());
    map.put("flags", body.flags());
    map.put("reasonCode", body.reasonCode().value());
    map.put("node", body.node());
    map.put("ruleIdsHit", body.ruleIdsHit());
    List<Object> path = new ArrayList<>();
    for (PathStep step : body.path()) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("ruleId", step.ruleId());
      s.put("matched", step.matched());
      s.put("costUnits", step.costUnits());
      path.add(s);
    }
    map.put("path", path);
    List<Object> citations = new ArrayList<>();
    for (Citation citation : body.citations()) {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("sourceId", citation.sourceId());
      c.put("provisionPath", citation.provisionPath());
      c.put("role", citation.role().name());
      citation.quoteSha256().ifPresent(q -> c.put("quoteSha256", q.hex()));
      citations.add(c);
    }
    map.put("citations", citations);
    List<Object> legs = new ArrayList<>();
    for (Leg leg : body.legs()) {
      legs.add(leg.name());
    }
    map.put("legs", legs);
    map.put("inputDigest", body.inputDigest().hex());
    if (!body.attributes().isEmpty()) {
      map.put("attributes", new TreeMap<>(body.attributes()));
    }
    return map;
  }

  public static Map<String, Object> recordToMap(DecisionRecord record) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("decisionId", record.decisionId().value());
    map.put("decisionSeq", record.decisionSeq());
    record.supersedesDecisionId().ifPresent(id -> map.put("supersedesDecisionId", id.value()));
    map.put("body", bodyToMap(record.body(), true));
    return map;
  }

  /**
   * The inverse of {@code bodyToMap(body, true)}: reads the map that {@code Json.parse} yields for
   * the canonical JSON of a stored body (instants and enum names as strings, integers as {@code
   * Long}); the map that {@code bodyToMap} itself returns is accepted too. Strict: a missing key
   * (only {@code attributes} may be absent), an unknown key, a value of the wrong type or an
   * unknown name is an {@link IllegalArgumentException}, so a row that this codec did not write is
   * never half-read.
   */
  public static DecisionBody bodyFromMap(Map<String, Object> map) {
    Fields f = new Fields("decision body", map);
    DecisionBody body =
        new DecisionBody(
            new EventIdentity(f.string("eventIdentity")),
            new Regime(f.string("regime")),
            new Sha256(f.string("decisionContextId")),
            f.instant("asAt"),
            f.instant("evaluatedAt"),
            f.enumValue("outcome", Outcome.class),
            f.strings("flags"),
            new ReasonCode(f.string("reasonCode")),
            f.string("node"),
            f.strings("ruleIdsHit"),
            pathFromList(f.list("path")),
            citationsFromList(f.list("citations")),
            legsFromList(f.list("legs")),
            new Sha256(f.string("inputDigest")),
            f.optionalStringMap("attributes"));
    f.finish();
    return body;
  }

  /**
   * The inverse of {@link #contextToMap}: {@code overlayDigest} and {@code circuitEpoch} are
   * optional (absent means none and 0), everything else is required; strict like {@link
   * #bodyFromMap}.
   */
  public static DecisionContext contextFromMap(Map<String, Object> map) {
    Fields f = new Fields("decision context", map);
    DecisionContext context =
        new DecisionContext(
            new Sha256(f.string("packDigest")),
            f.optionalString("overlayDigest").map(Sha256::new),
            f.strings("interpretationPins"),
            f.strings("referenceSnapshotIds"),
            f.strings("enrichmentSnapshotIds"),
            f.string("firmProfileVersion"),
            f.string("mappingVersion"),
            f.string("engineBuild"),
            f.string("executor"),
            f.optionalLong("circuitEpoch").orElse(0L));
    f.finish();
    return context;
  }

  private static List<PathStep> pathFromList(List<Object> items) {
    List<PathStep> path = new ArrayList<>(items.size());
    for (Object item : items) {
      Fields s = new Fields("path step", Fields.asObject(item, "path step"));
      path.add(new PathStep(s.string("ruleId"), s.bool("matched"), s.number("costUnits")));
      s.finish();
    }
    return path;
  }

  private static List<Citation> citationsFromList(List<Object> items) {
    List<Citation> citations = new ArrayList<>(items.size());
    for (Object item : items) {
      Fields c = new Fields("citation", Fields.asObject(item, "citation"));
      citations.add(
          new Citation(
              c.string("sourceId"),
              c.string("provisionPath"),
              c.enumValue("role", CitationRole.class),
              c.optionalString("quoteSha256").map(Sha256::new)));
      c.finish();
    }
    return citations;
  }

  private static List<Leg> legsFromList(List<Object> items) {
    List<Leg> legs = new ArrayList<>(items.size());
    for (Object item : items) {
      if (!(item instanceof String name)) {
        throw new IllegalArgumentException("a leg must be a string");
      }
      legs.add(enumNamed(Leg.class, "leg", name));
    }
    return legs;
  }

  private static <E extends Enum<E>> E enumNamed(Class<E> type, String what, String name) {
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown " + what + ": " + name, e);
    }
  }

  /** Typed, strict reads of one JSON object; {@link #finish} rejects the keys nobody read. */
  private static final class Fields {
    private final String what;
    private final Map<String, Object> map;
    private final Set<String> used = new HashSet<>();

    Fields(String what, Map<String, Object> map) {
      this.what = what;
      this.map = Objects.requireNonNull(map, what);
    }

    static Map<String, Object> asObject(Object value, String what) {
      if (!(value instanceof Map<?, ?> raw)) {
        throw new IllegalArgumentException(what + " must be an object");
      }
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : raw.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException(what + " has a key that is not a string");
        }
        out.put(key, entry.getValue());
      }
      return out;
    }

    private Object required(String key) {
      Object value = map.get(key);
      if (value == null) {
        throw new IllegalArgumentException(what + " is missing " + key);
      }
      used.add(key);
      return value;
    }

    private Optional<Object> optional(String key) {
      Object value = map.get(key);
      if (value == null) {
        if (map.containsKey(key)) {
          throw new IllegalArgumentException(what + " has a null " + key);
        }
        return Optional.empty();
      }
      used.add(key);
      return Optional.of(value);
    }

    String string(String key) {
      return asString(required(key), key);
    }

    Optional<String> optionalString(String key) {
      return optional(key).map(v -> asString(v, key));
    }

    private String asString(Object value, String key) {
      if (!(value instanceof String s)) {
        throw new IllegalArgumentException(what + ": " + key + " must be a string");
      }
      return s;
    }

    boolean bool(String key) {
      if (!(required(key) instanceof Boolean b)) {
        throw new IllegalArgumentException(what + ": " + key + " must be a boolean");
      }
      return b;
    }

    long number(String key) {
      return asLong(required(key), key);
    }

    Optional<Long> optionalLong(String key) {
      return optional(key).map(v -> asLong(v, key));
    }

    private long asLong(Object value, String key) {
      if (value instanceof Long || value instanceof Integer) {
        return ((Number) value).longValue();
      }
      throw new IllegalArgumentException(what + ": " + key + " must be an integer");
    }

    Instant instant(String key) {
      Object value = required(key);
      if (value instanceof Instant t) {
        return t;
      }
      try {
        return Instant.parse(asString(value, key));
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException(what + ": " + key + " is not an instant", e);
      }
    }

    <E extends Enum<E>> E enumValue(String key, Class<E> type) {
      Object value = required(key);
      String name = value instanceof Enum<?> e ? e.name() : asString(value, key);
      return enumNamed(type, what + " " + key, name);
    }

    List<Object> list(String key) {
      if (!(required(key) instanceof List<?> raw)) {
        throw new IllegalArgumentException(what + ": " + key + " must be an array");
      }
      return new ArrayList<>(raw);
    }

    List<String> strings(String key) {
      List<String> out = new ArrayList<>();
      for (Object item : list(key)) {
        out.add(asString(item, key));
      }
      return out;
    }

    Map<String, String> optionalStringMap(String key) {
      Map<String, String> out = new LinkedHashMap<>();
      Optional<Object> value = optional(key);
      if (value.isPresent()) {
        Fields.asObject(value.get(), key)
            .forEach((k, v) -> out.put(k, asString(Objects.requireNonNull(v, key), key)));
      }
      return out;
    }

    void finish() {
      for (String key : map.keySet()) {
        if (!used.contains(key)) {
          throw new IllegalArgumentException(what + " has an unknown key: " + key);
        }
      }
    }
  }
}
