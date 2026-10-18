package dev.regpack.spikes.x;

import dev.regpack.bench.eval.Verdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The decisions SP-X derives from what it measured: the per-record XSD policy and the stage latency
 * per file size, together with the measured figures that go into the report. Pure: the same inputs
 * give the same decisions.
 *
 * <p>The policy follows the two criterion groups of the plan: {@code ALL} when every criterion of
 * {@code policy:ALL} (X-1a, X-2a) passes, else {@code SAMPLED_10} when every criterion of {@code
 * policy:S10} (X-1b, X-2b, X-3) passes, else {@code NONE}. A criterion that was not measured has no
 * verdict and is not passed.
 */
public final class SpXDecisions {
  static final String ALL = "ALL";
  static final String SAMPLED_10 = "SAMPLED_10";
  static final String NONE = "NONE";

  private static final List<String> GROUP_ALL = List.of("X-1a", "X-2a");
  private static final List<String> GROUP_S10 = List.of("X-1b", "X-2b", "X-3");

  /**
   * What the run measured.
   *
   * @param verdicts criterion id to verdict, as the evaluator judged the measurements so far; an id
   *     that is absent was not measured
   * @param latencyMs policy name to {@code file size -> stage latency in milliseconds} (the median
   *     over forks, from the first record written to the file sealed and passed by the gate)
   * @param figures measured figures that are copied into the decisions as they are
   */
  public record Inputs(
      Map<String, Verdict> verdicts,
      Map<String, Map<Integer, BigDecimal>> latencyMs,
      Map<String, Object> figures) {
    public Inputs {
      verdicts = Map.copyOf(verdicts);
      Map<String, Map<Integer, BigDecimal>> copy = new TreeMap<>();
      latencyMs.forEach((policy, sizes) -> copy.put(policy, new TreeMap<>(sizes)));
      latencyMs = Map.copyOf(copy);
      figures = Map.copyOf(figures);
    }
  }

  /** The decisions (keys of the SP-X report) and the open items they leave. */
  public record Derived(Map<String, Object> decisions, List<String> open) {
    public Derived {
      decisions = Map.copyOf(decisions);
      open = List.copyOf(open);
    }
  }

  private SpXDecisions() {}

  public static Derived derive(Inputs in) {
    Map<String, Object> d = new LinkedHashMap<>(new TreeMap<>(in.figures()));
    List<String> open = new ArrayList<>();

    boolean all = passes(in, GROUP_ALL);
    boolean sampled = passes(in, GROUP_S10);
    String policy = all ? ALL : sampled ? SAMPLED_10 : NONE;
    d.put("policy", policy);
    d.put(
        "policyReason",
        all
            ? "every criterion of policy:ALL passes (" + describe(in, GROUP_ALL) + ")"
            : sampled
                ? "policy:ALL does not pass ("
                    + describe(in, GROUP_ALL)
                    + "); every criterion of policy:S10 passes ("
                    + describe(in, GROUP_S10)
                    + ")"
                : "neither group passes: ALL ("
                    + describe(in, GROUP_ALL)
                    + "), SAMPLED_10 ("
                    + describe(in, GROUP_S10)
                    + ")");
    if (policy.equals(NONE)) {
      open.add(
          "no policy meets X-1 and the model criteria: ALL ("
              + describe(in, GROUP_ALL)
              + "); SAMPLED_10 ("
              + describe(in, GROUP_S10)
              + "). The owner chooses between more cores (spec 5.2), the sampled policy with bisect, or"
              + " reopening the spec 5.3 row on libxml2.");
    }

    String latencyPolicy = policy.equals(NONE) ? ALL : policy;
    Map<Integer, BigDecimal> chosen = in.latencyMs().get(latencyPolicy);
    if (chosen != null) {
      chosen.forEach((size, ms) -> d.put("assemble.latencyMs." + size, ms));
    }
    in.latencyMs()
        .forEach(
            (name, sizes) ->
                sizes.forEach((size, ms) -> d.put("assemble.latencyMs." + name + "." + size, ms)));
    return new Derived(d, open);
  }

  private static boolean passes(Inputs in, List<String> ids) {
    for (String id : ids) {
      if (in.verdicts().get(id) != Verdict.PASS) {
        return false;
      }
    }
    return true;
  }

  private static String describe(Inputs in, List<String> ids) {
    List<String> parts = new ArrayList<>();
    for (String id : ids) {
      Verdict v = in.verdicts().get(id);
      parts.add(id + " " + (v == null ? "NOT_RUN" : v.name()));
    }
    return String.join(", ", parts);
  }
}
