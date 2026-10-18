package dev.regpack.bench.eval;

import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The verdict of one spike with everything behind it. The status is the roll-up of the required
 * criteria after the time-box cap; a report with summary evidence shows it as computed only.
 *
 * @param spike the spike id
 * @param status PASS, FAIL or INCONCLUSIVE
 * @param scope FULL, PARTIAL or DOCUMENT
 * @param evidenceLevel RAW (recomputed from the raw measurements) or SUMMARY (taken from a pasted
 *     summary)
 * @param criteria one result per criterion of the spike
 * @param decisions the decisions the spike derives
 * @param open what is left open
 * @param source what was measured
 * @param env the environment of the run
 * @param timeBox {@code usedMinutes}, {@code boxMinutes} and, once evaluated, {@code hit}
 * @param criteriaDigest the digest of the criteria these verdicts were computed against
 * @param vmCount the distinct virtual machines behind the pooled forks
 * @param mixedHardware whether the pooled runs used more than one CPU model
 * @param runs the provenance of every pooled run
 */
public record SpikeResult(
    String spike,
    Verdict status,
    String scope,
    String evidenceLevel,
    List<CriterionResult> criteria,
    Map<String, Object> decisions,
    List<String> open,
    Map<String, Object> source,
    Map<String, Object> env,
    Map<String, Object> timeBox,
    String criteriaDigest,
    int vmCount,
    boolean mixedHardware,
    List<MeasurementSet.RunInfo> runs) {
  private static final Pattern SPIKE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> SCOPES = Set.of("FULL", "PARTIAL", "DOCUMENT");
  private static final Set<String> EVIDENCE = Set.of("RAW", "SUMMARY");

  public SpikeResult {
    Objects.requireNonNull(status, "status");
    if (spike == null || !SPIKE.matcher(spike).matches()) {
      throw new IllegalArgumentException("spike is malformed: " + spike);
    }
    if (status == Verdict.NOT_RUN) {
      throw new IllegalArgumentException("a spike is PASS, FAIL or INCONCLUSIVE, not NOT_RUN");
    }
    if (!SCOPES.contains(scope)) {
      throw new IllegalArgumentException("scope must be FULL, PARTIAL or DOCUMENT: " + scope);
    }
    if (!EVIDENCE.contains(evidenceLevel)) {
      throw new IllegalArgumentException("evidenceLevel must be RAW or SUMMARY: " + evidenceLevel);
    }
    if (criteriaDigest == null || !HEX64.matcher(criteriaDigest).matches()) {
      throw new IllegalArgumentException("criteriaDigest must be 64 lowercase hex characters");
    }
    if (vmCount < 0) {
      throw new IllegalArgumentException("vmCount must not be negative");
    }
    criteria = List.copyOf(criteria);
    decisions = unmodifiable(decisions);
    open = List.copyOf(open);
    source = unmodifiable(source);
    env = unmodifiable(env);
    timeBox = unmodifiable(timeBox);
    runs = List.copyOf(runs);
    minutes(timeBox, "usedMinutes");
    minutes(timeBox, "boxMinutes");
  }

  private static Map<String, Object> unmodifiable(Map<String, Object> map) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }

  private static BigDecimal minutes(Map<String, Object> timeBox, String key) {
    Object value = timeBox.get(key);
    BigDecimal minutes;
    if (value instanceof Long l) {
      minutes = BigDecimal.valueOf(l);
    } else if (value instanceof Integer i) {
      minutes = BigDecimal.valueOf(i);
    } else if (value instanceof BigDecimal d) {
      minutes = d;
    } else {
      throw new IllegalArgumentException("timeBox." + key + " must be a number of minutes");
    }
    if (minutes.signum() < 0) {
      throw new IllegalArgumentException("timeBox." + key + " must not be negative");
    }
    return minutes;
  }

  /** Minutes of agent work used on the spike. */
  public BigDecimal usedMinutes() {
    return minutes(timeBox, "usedMinutes");
  }

  /** The time-box in minutes. */
  public BigDecimal boxMinutes() {
    return minutes(timeBox, "boxMinutes");
  }

  /** The box is hit when the minutes used reach the box. */
  public static boolean boxHit(Map<String, Object> timeBox) {
    return minutes(timeBox, "usedMinutes").compareTo(minutes(timeBox, "boxMinutes")) >= 0;
  }

  /** The canonical JSON of the result, the content of {@code summary.json} (without final LF). */
  public String toJson() {
    Map<String, Object> top = new LinkedHashMap<>();
    top.put("schema", "regpack.bench.summary/1");
    top.put("spike", spike);
    top.put("status", status.name());
    top.put("scope", scope);
    top.put("evidenceLevel", evidenceLevel);
    top.put("criteriaDigest", criteriaDigest);
    top.put("vmCount", (long) vmCount);
    top.put("mixedHardware", mixedHardware);
    List<Object> rows = new ArrayList<>();
    for (CriterionResult c : criteria) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", c.id());
      m.put("verdict", c.verdict().name());
      m.put("basis", c.basis().name());
      m.put("margin", c.margin());
      m.put("reason", c.reason());
      c.threshold().ifPresent(t -> m.put("threshold", t));
      c.estimate()
          .ifPresent(
              e -> {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("point", e.point());
                em.put("lower", e.lower());
                em.put("upper", e.upper());
                em.put("n", (long) e.n());
                em.put("method", e.method());
                m.put("estimate", em);
              });
      rows.add(m);
    }
    top.put("criteria", rows);
    top.put("decisions", decisions);
    top.put("open", new ArrayList<>(open));
    top.put("source", source);
    top.put("env", env);
    top.put("timeBox", timeBox);
    List<Object> runRows = new ArrayList<>();
    for (MeasurementSet.RunInfo run : runs) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("runId", run.runId());
      m.put("attempt", (long) run.attempt());
      m.put("part", run.part());
      m.put("gitSha", run.gitSha());
      m.put("cpuModel", run.cpuModel());
      m.put("imageVersion", run.imageVersion());
      runRows.add(m);
    }
    top.put("runs", runRows);
    return Json.canonical(top);
  }
}
