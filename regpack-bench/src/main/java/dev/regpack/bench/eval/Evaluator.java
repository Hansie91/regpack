package dev.regpack.bench.eval;

import dev.regpack.bench.stats.Estimate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The three-valued decision rules. A marginal pass is not a pass and a result that a slow phase
 * could explain is not a fail: both are inconclusive. Arithmetic is {@link BigDecimal} of scale 9,
 * half-even; there is no clock, randomness or locale here.
 */
public final class Evaluator {
  /** A noise margin above this makes every noisy criterion inconclusive. */
  public static final BigDecimal MAX_MARGIN = new BigDecimal("0.50");

  private static final int SCALE = 9;

  private Evaluator() {}

  /**
   * Judges a fork-series estimate against the threshold of a compared criterion. For a noisy
   * criterion the margin is the larger of the model's margin and the floor of {@link NoiseModel}; a
   * criterion that is not noisy is judged exactly.
   *
   * <p>At least: PASS if {@code lower >= T x (1 + m)}, FAIL if {@code upper < T / (1 + m)}. At
   * most: PASS if {@code upper <= T / (1 + m)}, FAIL if {@code lower > T x (1 + m)}. Otherwise, and
   * when there are fewer than {@code minForks} forks, when a declared canary resource is missing or
   * when the margin is above {@link #MAX_MARGIN}: INCONCLUSIVE.
   */
  public static CriterionResult evaluate(Criterion c, Estimate e, NoiseModel noise, int minForks) {
    if (c.direction() == Direction.MEASURED_ONLY) {
      throw new IllegalArgumentException(c.id() + " is measured only; use measuredOnly");
    }
    if (e.lower().compareTo(e.upper()) > 0) {
      throw new IllegalArgumentException(c.id() + ": the lower bound is above the upper bound");
    }
    BigDecimal threshold = c.threshold().orElseThrow();
    BigDecimal margin =
        c.noisy()
            ? noise.margin().max(NoiseModel.FLOOR).setScale(SCALE, RoundingMode.HALF_EVEN)
            : BigDecimal.ZERO.setScale(SCALE);
    Optional<Estimate> estimate = Optional.of(e);

    if (e.n() < minForks) {
      return result(
          c,
          Verdict.INCONCLUSIVE,
          estimate,
          margin,
          "only " + e.n() + " forks, " + minForks + " needed for a verdict");
    }
    if (c.noisy()) {
      Set<Resource> lacking = EnumSet.noneOf(Resource.class);
      lacking.addAll(c.resources());
      lacking.retainAll(noise.missing());
      if (!lacking.isEmpty()) {
        return result(c, Verdict.INCONCLUSIVE, estimate, margin, "no usable canary for " + lacking);
      }
      if (margin.compareTo(MAX_MARGIN) > 0) {
        return result(
            c,
            Verdict.INCONCLUSIVE,
            estimate,
            margin,
            "noise margin "
                + margin.toPlainString()
                + " is above the maximum "
                + MAX_MARGIN.toPlainString());
      }
    }

    BigDecimal factor = BigDecimal.ONE.add(margin);
    BigDecimal high = threshold.multiply(factor).setScale(SCALE, RoundingMode.HALF_EVEN);
    BigDecimal low = threshold.divide(factor, SCALE, RoundingMode.HALF_EVEN);
    String band = "threshold " + threshold.toPlainString() + ", margin " + margin.toPlainString();
    if (c.direction() == Direction.AT_LEAST) {
      if (e.lower().compareTo(high) >= 0) {
        return result(
            c,
            Verdict.PASS,
            estimate,
            margin,
            "lower bound "
                + e.lower().toPlainString()
                + " is at least "
                + high.toPlainString()
                + " ("
                + band
                + ")");
      }
      if (e.upper().compareTo(low) < 0) {
        return result(
            c,
            Verdict.FAIL,
            estimate,
            margin,
            "upper bound "
                + e.upper().toPlainString()
                + " is below "
                + low.toPlainString()
                + " ("
                + band
                + ")");
      }
    } else {
      if (e.upper().compareTo(low) <= 0) {
        return result(
            c,
            Verdict.PASS,
            estimate,
            margin,
            "upper bound "
                + e.upper().toPlainString()
                + " is at most "
                + low.toPlainString()
                + " ("
                + band
                + ")");
      }
      if (e.lower().compareTo(high) > 0) {
        return result(
            c,
            Verdict.FAIL,
            estimate,
            margin,
            "lower bound "
                + e.lower().toPlainString()
                + " is above "
                + high.toPlainString()
                + " ("
                + band
                + ")");
      }
    }
    return result(
        c,
        Verdict.INCONCLUSIVE,
        estimate,
        margin,
        "the interval "
            + e.lower().toPlainString()
            + " to "
            + e.upper().toPlainString()
            + " is not clear of the margin ("
            + band
            + ")");
  }

  /**
   * Judges an exact count: no margin. Fewer than {@code minRuns} valid runs is INCONCLUSIVE
   * whatever was observed.
   */
  public static CriterionResult evaluateCount(Criterion c, long observed, int validRuns) {
    if (c.direction() == Direction.MEASURED_ONLY) {
      throw new IllegalArgumentException(c.id() + " is measured only; use measuredOnly");
    }
    if (observed < 0 || validRuns < 0) {
      throw new IllegalArgumentException(c.id() + ": a count and a run count are not negative");
    }
    BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
    if (validRuns < c.minRuns()) {
      return result(
          c,
          Verdict.INCONCLUSIVE,
          Optional.empty(),
          zero,
          validRuns + " valid runs, " + c.minRuns() + " needed");
    }
    BigDecimal threshold = c.threshold().orElseThrow();
    int order = BigDecimal.valueOf(observed).compareTo(threshold);
    boolean pass = c.direction() == Direction.AT_LEAST ? order >= 0 : order <= 0;
    String rule = c.direction() == Direction.AT_LEAST ? "at least " : "at most ";
    return result(
        c,
        pass ? Verdict.PASS : Verdict.FAIL,
        Optional.empty(),
        zero,
        "observed " + observed + ", " + rule + threshold.toPlainString() + " required");
  }

  /**
   * A criterion that is recorded and compared with nothing: FAIL when an invariant of the
   * measurement is broken, INCONCLUSIVE with fewer than {@code minN} observations, else PASS.
   */
  public static CriterionResult measuredOnly(Criterion c, boolean invariantsHold, int n, int minN) {
    if (c.direction() != Direction.MEASURED_ONLY) {
      throw new IllegalArgumentException(c.id() + " has a threshold; use evaluate");
    }
    BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
    if (!invariantsHold) {
      return result(
          c, Verdict.FAIL, Optional.empty(), zero, "an invariant of the measurement fails");
    }
    if (n < minN) {
      return result(
          c,
          Verdict.INCONCLUSIVE,
          Optional.empty(),
          zero,
          n + " observations, " + minN + " needed");
    }
    return result(c, Verdict.PASS, Optional.empty(), zero, "recorded, " + n + " observations");
  }

  /**
   * The verdict of a spike from its results, over the required criteria only. A group is passed
   * when all its required criteria pass and failed when one of them fails; the spike passes when
   * one group passes and every ungrouped required criterion passes; it fails when an ungrouped
   * required criterion fails or when every group fails; otherwise it is inconclusive. A required
   * criterion without a result counts as not run.
   */
  public static Verdict rollup(List<CriterionResult> results, List<Criterion> criteria) {
    Map<String, Verdict> byId = new HashMap<>();
    for (CriterionResult r : results) {
      if (byId.put(r.id(), r.verdict()) != null) {
        throw new IllegalArgumentException("two results for " + r.id());
      }
    }
    List<Verdict> ungrouped = new ArrayList<>();
    Map<String, List<Verdict>> groups = new LinkedHashMap<>();
    for (Criterion c : criteria) {
      if (!c.required()) {
        continue;
      }
      Verdict v = byId.getOrDefault(c.id(), Verdict.NOT_RUN);
      if (c.group().isPresent()) {
        groups.computeIfAbsent(c.group().get(), k -> new ArrayList<>()).add(v);
      } else {
        ungrouped.add(v);
      }
    }
    if (ungrouped.isEmpty() && groups.isEmpty()) {
      return Verdict.INCONCLUSIVE;
    }
    if (ungrouped.contains(Verdict.FAIL)) {
      return Verdict.FAIL;
    }
    boolean anyGroupPassed = false;
    boolean everyGroupFailed = !groups.isEmpty();
    for (List<Verdict> members : groups.values()) {
      Verdict group = groupVerdict(members);
      anyGroupPassed |= group == Verdict.PASS;
      everyGroupFailed &= group == Verdict.FAIL;
    }
    if (everyGroupFailed) {
      return Verdict.FAIL;
    }
    boolean ungroupedPass = ungrouped.stream().allMatch(v -> v == Verdict.PASS);
    boolean groupsPass = groups.isEmpty() || anyGroupPassed;
    return ungroupedPass && groupsPass ? Verdict.PASS : Verdict.INCONCLUSIVE;
  }

  private static Verdict groupVerdict(List<Verdict> members) {
    if (members.contains(Verdict.FAIL)) {
      return Verdict.FAIL;
    }
    return members.stream().allMatch(v -> v == Verdict.PASS) ? Verdict.PASS : Verdict.INCONCLUSIVE;
  }

  /**
   * A spike that exceeded its time-box cannot pass while a required criterion was not run: PASS
   * becomes INCONCLUSIVE, every other verdict stays.
   */
  public static Verdict capByTimeBox(Verdict v, boolean overBox, boolean anyRequiredNotRun) {
    return v == Verdict.PASS && overBox && anyRequiredNotRun ? Verdict.INCONCLUSIVE : v;
  }

  private static CriterionResult result(
      Criterion c, Verdict verdict, Optional<Estimate> estimate, BigDecimal margin, String reason) {
    return new CriterionResult(c.id(), verdict, c.basis(), estimate, c.threshold(), margin, reason);
  }
}
