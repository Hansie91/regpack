package dev.regpack.bench.proto;

import dev.regpack.bench.stats.Stats;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outcome of one fork.
 *
 * @param index position in the series, from 0
 * @param unstable the warm-up never settled (P-2); the fork is still kept
 * @param discarded the fork is excluded from the statistics, with a reason (P-5)
 * @param discardReason empty unless discarded; starts with {@code HARNESS_ERROR} or {@code STEAL}
 * @param windowRates the rate of each measured window, in units per second
 * @param canaryCpu CPU canary readings (before warm-up, after the last window), MB per second
 * @param canaryMemory memory canary readings, MB per second
 * @param canaryDisk disk canary readings, synchronous 4 KiB writes per second
 * @param stealFraction CPU steal over the fork's windows, 0 where unavailable
 * @param checksum the workload's checksum after the last window
 */
public record ForkResult(
    int index,
    boolean unstable,
    boolean discarded,
    String discardReason,
    List<Double> windowRates,
    List<Double> canaryCpu,
    List<Double> canaryMemory,
    List<Double> canaryDisk,
    double stealFraction,
    long checksum) {

  /** The marker that starts the one result line a child JVM prints. */
  public static final String LINE_PREFIX = "FORK-RESULT ";

  private static final int MAX_REASON = 400;

  public ForkResult {
    Objects.requireNonNull(discardReason, "discardReason");
    windowRates = List.copyOf(windowRates);
    canaryCpu = List.copyOf(canaryCpu);
    canaryMemory = List.copyOf(canaryMemory);
    canaryDisk = List.copyOf(canaryDisk);
    if (index < 0) {
      throw new IllegalArgumentException("index must not be negative: " + index);
    }
    if (discarded == discardReason.isEmpty()) {
      throw new IllegalArgumentException("a fork has a discard reason if and only if discarded");
    }
    if (!discarded && windowRates.isEmpty()) {
      throw new IllegalArgumentException("a kept fork has at least one window rate");
    }
  }

  /** The fork statistic: the median of the window rates. */
  public double statistic() {
    if (windowRates.isEmpty()) {
      throw new IllegalStateException("fork " + index + " has no window: " + discardReason);
    }
    return Stats.median(windowRates.stream().mapToDouble(Double::doubleValue).toArray());
  }

  /** The same result at another position of the series. */
  public ForkResult withIndex(int newIndex) {
    return new ForkResult(
        newIndex,
        unstable,
        discarded,
        discardReason,
        windowRates,
        canaryCpu,
        canaryMemory,
        canaryDisk,
        stealFraction,
        checksum);
  }

  /** The harness-error discard of a fork that produced no usable result. */
  static ForkResult harnessError(int index, String detail) {
    return new ForkResult(
        index,
        false,
        true,
        "HARNESS_ERROR: " + abbreviate(detail, MAX_REASON),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.0,
        0);
  }

  /**
   * The line a child JVM prints: {@code FORK-RESULT} and the canonical JSON. Rates are written as
   * decimals of scale 9, because canonical result JSON holds no {@code double}.
   */
  public String toLine() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("index", (long) index);
    map.put("unstable", unstable);
    map.put("discarded", discarded);
    map.put("discardReason", discardReason);
    map.put("windowRates", decimals(windowRates));
    map.put("canaryCpu", decimals(canaryCpu));
    map.put("canaryMemory", decimals(canaryMemory));
    map.put("canaryDisk", decimals(canaryDisk));
    map.put("stealFraction", Stats.decimal(stealFraction));
    map.put("checksum", checksum);
    return LINE_PREFIX + Json.canonical(map);
  }

  /** Parses {@link #toLine()}; anything else is an {@link IllegalArgumentException}. */
  public static ForkResult parseLine(String line) {
    if (!line.startsWith(LINE_PREFIX)) {
      throw new IllegalArgumentException("not a result line: " + abbreviate(line, 80));
    }
    try {
      Object parsed = Json.parse(line.substring(LINE_PREFIX.length()));
      if (!(parsed instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("the result is not a JSON object");
      }
      return new ForkResult(
          (int) longValue(map, "index"),
          boolValue(map, "unstable"),
          boolValue(map, "discarded"),
          stringValue(map, "discardReason"),
          doubles(map, "windowRates"),
          doubles(map, "canaryCpu"),
          doubles(map, "canaryMemory"),
          doubles(map, "canaryDisk"),
          number(map, "stealFraction").doubleValue(),
          longValue(map, "checksum"));
    } catch (JsonException | ClassCastException e) {
      throw new IllegalArgumentException("malformed result line: " + e.getMessage(), e);
    }
  }

  private static List<BigDecimal> decimals(List<Double> values) {
    List<BigDecimal> out = new ArrayList<>(values.size());
    for (double v : values) {
      out.add(Stats.decimal(v));
    }
    return out;
  }

  private static Object required(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException("missing field " + key);
    }
    return value;
  }

  private static Number number(Map<?, ?> map, String key) {
    return (Number) required(map, key);
  }

  private static long longValue(Map<?, ?> map, String key) {
    Object value = required(map, key);
    if (!(value instanceof Long l)) {
      throw new IllegalArgumentException("field " + key + " is not an integer");
    }
    return l;
  }

  private static boolean boolValue(Map<?, ?> map, String key) {
    return (Boolean) required(map, key);
  }

  private static String stringValue(Map<?, ?> map, String key) {
    return (String) required(map, key);
  }

  private static List<Double> doubles(Map<?, ?> map, String key) {
    List<?> raw = (List<?>) required(map, key);
    List<Double> out = new ArrayList<>(raw.size());
    for (Object o : raw) {
      out.add(((Number) o).doubleValue());
    }
    return out;
  }

  private static String abbreviate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }
}
