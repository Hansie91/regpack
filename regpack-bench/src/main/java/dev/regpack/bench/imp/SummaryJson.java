package dev.regpack.bench.imp;

import dev.regpack.bench.eval.Basis;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.bench.stats.Estimate;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code summary.json} (the text {@link SpikeResult#toJson()} writes) back into a {@link
 * SpikeResult}. The file comes from CI or from a pasted job summary, so the reading is strict: an
 * unknown key, a missing key, a value of the wrong type and every {@code double} is refused. The
 * result is data to compare with a recomputation, never a verdict that is trusted.
 */
public final class SummaryJson {
  public static final String SCHEMA = "regpack.bench.summary/1";

  private SummaryJson() {}

  /**
   * Parses the summary.
   *
   * @throws IllegalArgumentException for any deviation from the schema
   */
  public static SpikeResult parse(String json) {
    Object root;
    try {
      root = Json.parse(json);
    } catch (JsonException e) {
      throw new IllegalArgumentException("malformed summary: " + e.getMessage(), e);
    }
    Map<String, Object> top = object(root, "summary");
    keys(
        top,
        Set.of(
            "schema",
            "spike",
            "status",
            "scope",
            "evidenceLevel",
            "criteriaDigest",
            "vmCount",
            "mixedHardware",
            "criteria",
            "decisions",
            "open",
            "source",
            "env",
            "timeBox",
            "runs"),
        "summary");
    if (!SCHEMA.equals(top.get("schema"))) {
      throw new IllegalArgumentException("schema must be " + SCHEMA);
    }
    List<CriterionResult> criteria = new ArrayList<>();
    for (Object item : list(top.get("criteria"), "criteria")) {
      criteria.add(criterion(object(item, "criteria[]")));
    }
    List<String> open = new ArrayList<>();
    for (Object item : list(top.get("open"), "open")) {
      open.add(string(item, "open[]"));
    }
    List<RunInfo> runs = new ArrayList<>();
    for (Object item : list(top.get("runs"), "runs")) {
      Map<String, Object> m = object(item, "runs[]");
      keys(m, Set.of("runId", "attempt", "part", "gitSha", "cpuModel", "imageVersion"), "run");
      runs.add(
          new RunInfo(
              string(m.get("runId"), "runId"),
              integer(m.get("attempt"), "attempt"),
              string(m.get("part"), "part"),
              string(m.get("gitSha"), "gitSha"),
              string(m.get("cpuModel"), "cpuModel"),
              string(m.get("imageVersion"), "imageVersion")));
    }
    return new SpikeResult(
        string(top.get("spike"), "spike"),
        enumValue(Verdict.class, top.get("status"), "status"),
        string(top.get("scope"), "scope"),
        string(top.get("evidenceLevel"), "evidenceLevel"),
        criteria,
        object(top.get("decisions"), "decisions"),
        open,
        object(top.get("source"), "source"),
        object(top.get("env"), "env"),
        object(top.get("timeBox"), "timeBox"),
        string(top.get("criteriaDigest"), "criteriaDigest"),
        integer(top.get("vmCount"), "vmCount"),
        bool(top.get("mixedHardware"), "mixedHardware"),
        runs);
  }

  private static CriterionResult criterion(Map<String, Object> m) {
    keys(
        m,
        Set.of("id", "verdict", "basis", "margin", "reason"),
        Set.of("threshold", "estimate"),
        "criterion");
    Optional<Estimate> estimate = Optional.empty();
    if (m.containsKey("estimate")) {
      Map<String, Object> e = object(m.get("estimate"), "estimate");
      keys(e, Set.of("point", "lower", "upper", "n", "method"), "estimate");
      estimate =
          Optional.of(
              new Estimate(
                  decimal(e.get("point"), "point"),
                  decimal(e.get("lower"), "lower"),
                  decimal(e.get("upper"), "upper"),
                  integer(e.get("n"), "n"),
                  string(e.get("method"), "method")));
    }
    return new CriterionResult(
        string(m.get("id"), "id"),
        enumValue(Verdict.class, m.get("verdict"), "verdict"),
        enumValue(Basis.class, m.get("basis"), "basis"),
        estimate,
        m.containsKey("threshold")
            ? Optional.of(decimal(m.get("threshold"), "threshold"))
            : Optional.empty(),
        decimal(m.get("margin"), "margin"),
        string(m.get("reason"), "reason"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String where) {
    if (value instanceof Map<?, ?> map) {
      return new LinkedHashMap<>((Map<String, Object>) map);
    }
    throw new IllegalArgumentException(where + " must be an object");
  }

  private static List<?> list(Object value, String where) {
    if (value instanceof List<?> list) {
      return list;
    }
    throw new IllegalArgumentException(where + " must be an array");
  }

  private static void keys(Map<String, Object> m, Set<String> required, String where) {
    keys(m, required, Set.of(), where);
  }

  private static void keys(
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

  private static String string(Object value, String where) {
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException(where + " must be a string");
  }

  private static boolean bool(Object value, String where) {
    if (value instanceof Boolean b) {
      return b;
    }
    throw new IllegalArgumentException(where + " must be true or false");
  }

  private static BigDecimal decimal(Object value, String where) {
    if (value instanceof Long l) {
      return BigDecimal.valueOf(l);
    }
    if (value instanceof BigDecimal d) {
      return d;
    }
    throw new IllegalArgumentException(where + " must be a number");
  }

  private static int integer(Object value, String where) {
    if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
      return (int) (long) l;
    }
    throw new IllegalArgumentException(where + " must be an integer");
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String where) {
    String name = string(value, where);
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(where + ": unknown " + type.getSimpleName() + " " + name);
    }
  }
}
