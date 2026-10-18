package dev.regpack.spikes.r;

import dev.regpack.bench.eval.Verdict;
import dev.regpack.spikes.r.defaults.BudgetDefaults;
import dev.regpack.spikes.r.defaults.PoisonDefaults;
import dev.regpack.spikes.r.vectors.VectorResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The decisions SP-R derives from what it measured: the expression layer, the executor gate with
 * its three readings, the per-record budget and poison-circuit defaults, the explain render time,
 * the hot-swap activation times and the cost of a record. Pure: the same inputs give the same
 * decisions, and a figure that was not measured leaves its key out instead of inventing a value.
 *
 * <p>The expression layer is chosen on the criteria of a layer group (R-1, R-2, R-6): a layer whose
 * three criteria pass is chosen first, the bought {@code cel-java} before the in-house layer (the
 * spec says "otherwise a minimal in-house subset"). When no layer passes all three, only because
 * the executor gate (R-2) failed, the semantic criteria (R-1, R-6) still choose a layer, so that
 * the executor gate has a layer to speak of; the gate is then reported as failed on its own. When
 * no layer passes the semantic criteria the choice is NONE.
 */
public final class SpRDecisions {
  private static final String INHOUSE = "inhouse";
  private static final String CEL = "cel";
  private static final int SCALE = 3;

  /**
   * What the run measured.
   *
   * @param verdicts criterion id to verdict, as the evaluator judged the measurements so far; an id
   *     that is absent was not measured
   * @param inhouseVectors the vector results of the in-house layer
   * @param celVectors the vector results of the cel-java layer
   * @param t4PerThread layer to the median rate per thread with {@code threads} threads busy
   * @param t1 layer to the median rate of one thread
   * @param threads the busy threads of the multi-thread reading
   * @param physicalCores the physical cores of the machine (the logical count where unknown)
   * @param budget layer to the derived budget defaults
   * @param poison the chosen poison-circuit pair
   * @param explainP99Ms the median over forks of the Tier-1 render p99, in milliseconds
   * @param hotSwapP99Ms {@code <layer>.<rules>} to the activation time p99, in milliseconds
   * @param usPerRecord {@code <layer>.<rules>} to the microseconds per record, one thread
   */
  public record Inputs(
      Map<String, Verdict> verdicts,
      List<VectorResult> inhouseVectors,
      List<VectorResult> celVectors,
      Map<String, BigDecimal> t4PerThread,
      Map<String, BigDecimal> t1,
      int threads,
      int physicalCores,
      Map<String, BudgetDefaults.Result> budget,
      Optional<PoisonDefaults.Choice> poison,
      Optional<BigDecimal> explainP99Ms,
      Map<String, BigDecimal> hotSwapP99Ms,
      Map<String, BigDecimal> usPerRecord) {
    public Inputs {
      verdicts = Map.copyOf(verdicts);
      inhouseVectors = List.copyOf(inhouseVectors);
      celVectors = List.copyOf(celVectors);
      t4PerThread = Map.copyOf(t4PerThread);
      t1 = Map.copyOf(t1);
      budget = Map.copyOf(budget);
      Objects.requireNonNull(poison, "poison");
      Objects.requireNonNull(explainP99Ms, "explainP99Ms");
      hotSwapP99Ms = Map.copyOf(hotSwapP99Ms);
      usPerRecord = Map.copyOf(usPerRecord);
      if (threads < 1 || physicalCores < 1) {
        throw new IllegalArgumentException("threads and physicalCores must be at least 1");
      }
    }
  }

  /** The decisions (keys of the SP-R report) and the open items they leave. */
  public record Derived(Map<String, Object> decisions, List<String> open) {
    public Derived {
      decisions = Map.copyOf(decisions);
      open = List.copyOf(open);
    }
  }

  private SpRDecisions() {}

  /**
   * Derives the decisions.
   *
   * @param gateThreshold the throughput threshold of R-2, records per second per thread
   */
  public static Derived derive(Inputs in, BigDecimal gateThreshold) {
    Map<String, Object> d = new LinkedHashMap<>();
    List<String> open = new ArrayList<>();

    Choice choice = chooseLayer(in);
    d.put("expressionLayer", choice.name);
    d.put("expressionLayerReason", choice.reason);
    String layer = choice.layer;

    String gate = gate(in, layer);
    d.put("executorGate", gate);
    Map<String, Object> readings = readings(in, layer);
    if (!readings.isEmpty()) {
      d.put("executorGateReadings", readings);
    }
    if (!gate.equals("PASS")) {
      open.add(gateLine(gate, readings, gateThreshold));
    }
    if (gate.equals("FAIL")) {
      boolean dependent = readingDependent(readings, gateThreshold);
      d.put("executorGateReadingDependent", dependent);
    }

    String budgetLayer = layer.isEmpty() ? INHOUSE : layer;
    BudgetDefaults.Result budget = in.budget().get(budgetLayer);
    if (budget != null) {
      d.put("budget.perRecordUnits", budget.perRecordBudgetUnits());
      d.put("budget.perRuleCeilingUnits", budget.perRuleCeilingUnits());
    }
    in.poison()
        .ifPresent(
            p -> {
              d.put("poison.n", (long) p.n());
              d.put("poison.m", (long) p.m());
            });
    in.explainP99Ms().ifPresent(v -> d.put("explain.tier1P99Ms", v));
    for (String size : List.of("200", "5000")) {
      BigDecimal p99 = in.hotSwapP99Ms().get(budgetLayer + "." + size);
      if (p99 != null) {
        d.put("hotswap.p99Ms." + size, p99);
      }
    }
    new java.util.TreeMap<>(in.usPerRecord())
        .forEach((key, value) -> d.put("decide.usPerRecord." + key, value));
    BigDecimal one = in.t1().get(budgetLayer);
    if (one != null && one.signum() > 0) {
      d.put(
          "decide.usPerRecordWorst",
          BigDecimal.valueOf(1_000_000L).divide(one, SCALE, RoundingMode.HALF_EVEN));
    }

    open.addAll(decideLines(in));
    return new Derived(d, open);
  }

  // ---------------------------------------------------------------- layer

  private record Choice(String name, String layer, String reason) {}

  private static Choice chooseLayer(Inputs in) {
    boolean celFull = passes(in, "R-1b", "R-2b", "R-6b");
    boolean inhouseFull = passes(in, "R-1a", "R-2a", "R-6a");
    boolean celFunctional = functionalMust(in.celVectors());
    if (celFull) {
      return celFunctional
          ? new Choice(
              "CEL_JAVA_FUNCTIONAL",
              CEL,
              "cel-java passes R-1b, R-2b and R-6b, but some MUST vectors pass only through"
                  + " function syntax")
          : new Choice("CEL_JAVA", CEL, "cel-java passes R-1b, R-2b and R-6b");
    }
    if (inhouseFull) {
      return new Choice(
          "INHOUSE", INHOUSE, "the in-house layer passes R-1a, R-2a and R-6a; cel-java does not");
    }
    boolean celSemantic = passes(in, "R-1b", "R-6b");
    boolean inhouseSemantic = passes(in, "R-1a", "R-6a");
    if (celSemantic) {
      return new Choice(
          celFunctional ? "CEL_JAVA_FUNCTIONAL" : "CEL_JAVA",
          CEL,
          "no layer passes the executor gate R-2; chosen on the semantic criteria (R-1b, R-6b)"
              + (celFunctional ? " with FUNCTIONAL MUST vectors" : ""));
    }
    if (inhouseSemantic) {
      return new Choice(
          "INHOUSE",
          INHOUSE,
          "no layer passes the executor gate R-2; chosen on the semantic criteria (R-1a, R-6a)");
    }
    return new Choice(
        "NONE",
        "",
        "no layer passes the semantic criteria: cel-java "
            + describe(in, "R-1b", "R-6b")
            + "; in-house "
            + describe(in, "R-1a", "R-6a"));
  }

  private static boolean passes(Inputs in, String... ids) {
    for (String id : ids) {
      if (in.verdicts().get(id) != Verdict.PASS) {
        return false;
      }
    }
    return true;
  }

  private static String describe(Inputs in, String... ids) {
    List<String> parts = new ArrayList<>();
    for (String id : ids) {
      Verdict v = in.verdicts().get(id);
      parts.add(id + " " + (v == null ? "NOT_RUN" : v.name()));
    }
    return String.join(", ", parts);
  }

  private static boolean functionalMust(List<VectorResult> results) {
    return results.stream()
        .anyMatch(r -> r.vectorClass().equals("MUST") && r.result().equals("FUNCTIONAL"));
  }

  // ---------------------------------------------------------------- gate

  private static String gate(Inputs in, String layer) {
    if (layer.equals(INHOUSE)) {
      return verdictName(in.verdicts().get("R-2a"));
    }
    if (layer.equals(CEL)) {
      return verdictName(in.verdicts().get("R-2b"));
    }
    Verdict a = in.verdicts().get("R-2a");
    Verdict b = in.verdicts().get("R-2b");
    if (a == Verdict.PASS || b == Verdict.PASS) {
      return "PASS";
    }
    if (a == Verdict.FAIL && b == Verdict.FAIL) {
      return "FAIL";
    }
    return "INCONCLUSIVE";
  }

  private static String verdictName(Verdict v) {
    return v == Verdict.PASS ? "PASS" : v == Verdict.FAIL ? "FAIL" : "INCONCLUSIVE";
  }

  private static Map<String, Object> readings(Inputs in, String layer) {
    String measured = layer.isEmpty() ? INHOUSE : layer;
    Map<String, Object> out = new LinkedHashMap<>();
    BigDecimal four = in.t4PerThread().get(measured);
    BigDecimal one = in.t1().get(measured);
    if (four != null) {
      out.put("t4S2", four);
      out.put(
          "physCoreS2",
          four.multiply(BigDecimal.valueOf(in.threads()))
              .divide(BigDecimal.valueOf(in.physicalCores()), SCALE, RoundingMode.HALF_EVEN));
    }
    if (one != null) {
      out.put("t1S2", one);
    }
    return out;
  }

  private static boolean readingDependent(Map<String, Object> readings, BigDecimal threshold) {
    for (String key : List.of("t1S2", "physCoreS2")) {
      if (readings.get(key) instanceof BigDecimal value && value.compareTo(threshold) >= 0) {
        return true;
      }
    }
    return false;
  }

  private static String gateLine(String gate, Map<String, Object> readings, BigDecimal threshold) {
    StringBuilder line =
        new StringBuilder("executor gate ").append(gate).append(" at the gated reading; readings");
    for (String key : List.of("t4S2", "t1S2", "physCoreS2")) {
      line.append(' ').append(key).append('=');
      Object value = readings.get(key);
      line.append(value instanceof BigDecimal v ? v.stripTrailingZeros().toPlainString() : "n/a");
    }
    line.append(" records/s (threshold ").append(threshold.toPlainString()).append(')');
    if (gate.equals("FAIL") && readingDependent(readings, threshold)) {
      line.append(
          "; the verdict depends on the reading (Q3): another reading reaches the threshold");
    }
    return line.toString();
  }

  // ---------------------------------------------------------------- DECIDE vectors

  private static List<String> decideLines(Inputs in) {
    Map<String, VectorResult> cel = new LinkedHashMap<>();
    in.celVectors().stream()
        .filter(r -> r.vectorClass().equals("DECIDE"))
        .forEach(r -> cel.put(r.id(), r));
    List<String> lines = new ArrayList<>();
    for (VectorResult mine : in.inhouseVectors()) {
      if (!mine.vectorClass().equals("DECIDE")) {
        continue;
      }
      VectorResult theirs = cel.remove(mine.id());
      lines.add(
          "DECIDE "
              + mine.id()
              + ": in-house "
              + mine.result()
              + " ("
              + mine.actual()
              + "), cel-java "
              + (theirs == null ? "NOT_RUN" : theirs.result() + " (" + theirs.actual() + ")"));
    }
    cel.values()
        .forEach(
            r ->
                lines.add(
                    "DECIDE "
                        + r.id()
                        + ": in-house NOT_RUN, cel-java "
                        + r.result()
                        + " ("
                        + r.actual()
                        + ")"));
    return lines;
  }
}
