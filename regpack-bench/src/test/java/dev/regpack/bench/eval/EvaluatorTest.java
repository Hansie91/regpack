package dev.regpack.bench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.stats.Estimate;
import dev.regpack.bench.stats.Stats;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvaluatorTest {
  private static final int FORKS = 7;

  private static Criterion criterion(
      String id, Direction direction, String threshold, boolean noisy, int minRuns, String group) {
    return new Criterion(
        id,
        "SP-R",
        "metric " + id,
        "unit",
        direction,
        threshold.isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(threshold)),
        "SPEC test",
        true,
        noisy,
        Set.of(Resource.CPU),
        minRuns,
        Basis.MEASURED,
        group.isEmpty() ? Optional.empty() : Optional.of(group),
        Optional.empty());
  }

  private static Criterion atLeast10k() {
    return criterion("R-2a", Direction.AT_LEAST, "10000", true, 0, "");
  }

  private static Criterion p99AtMost100() {
    return criterion("R-4", Direction.AT_MOST, "100", true, 0, "");
  }

  private static Estimate estimate(String lower, String upper, int n) {
    BigDecimal lo = new BigDecimal(lower);
    BigDecimal up = new BigDecimal(upper);
    return new Estimate(lo.add(up).divide(new BigDecimal(2)), lo, up, n, "test");
  }

  private static NoiseModel margin(String m) {
    return new NoiseModel(new BigDecimal(m), Set.of());
  }

  @Test
  void luckyFastForkDoesNotPass() {
    double[] statistics = new double[15];
    Arrays.fill(statistics, 9_500);
    statistics[14] = 12_000;
    Estimate e = Stats.medianWithBounds(statistics);
    assertThat(e.lower()).isEqualByComparingTo("9500");
    assertThat(e.upper()).isEqualByComparingTo("9500");
    CriterionResult r = Evaluator.evaluate(atLeast10k(), e, margin("0.10"), FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void marginalPassIsInconclusive() {
    CriterionResult r =
        Evaluator.evaluate(atLeast10k(), estimate("10500", "11500", 15), margin("0.10"), FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(r.margin()).isEqualByComparingTo("0.10");
    CriterionResult atTheEdge =
        Evaluator.evaluate(atLeast10k(), estimate("11000", "11500", 15), margin("0.10"), FORKS);
    assertThat(atTheEdge.verdict()).isEqualTo(Verdict.PASS);
  }

  @Test
  void noisyCanaryWidensTheBand() {
    double[] samples = new double[25];
    Arrays.fill(samples, 120);
    samples[0] = 100;
    samples[1] = 100;
    samples[23] = 140;
    samples[24] = 140;
    assertThat(Stats.spread(samples)).isBetween(0.399, 0.401);
    NoiseModel noisy =
        NoiseModel.fromCanary(Map.of(Resource.CPU, samples), EnumSet.of(Resource.CPU));
    assertThat(noisy.margin()).isEqualByComparingTo("0.40");
    Estimate e = estimate("13000", "15000", 15);
    assertThat(Evaluator.evaluate(atLeast10k(), e, noisy, FORKS).verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.evaluate(atLeast10k(), e, margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.PASS);
  }

  @Test
  void clearPassAboveTheMargin() {
    CriterionResult r =
        Evaluator.evaluate(atLeast10k(), estimate("12000", "13000", 15), margin("0.10"), FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.PASS);
    assertThat(r.basis()).isEqualTo(Basis.MEASURED);
    assertThat(r.threshold()).contains(new BigDecimal("10000"));
    assertThat(r.estimate()).isPresent();
  }

  @Test
  void clearFailBelowTheMargin() {
    CriterionResult r =
        Evaluator.evaluate(atLeast10k(), estimate("7000", "8000", 15), margin("0.10"), FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  void slowPhaseDoesNotFalselyFail() {
    // 9,500 is below the threshold, but a noise margin of 0.30 admits a slow phase:
    // 10,000 / 1.3 = 7,692
    CriterionResult r =
        Evaluator.evaluate(atLeast10k(), estimate("9000", "9500", 15), margin("0.30"), FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void veryNoisyRunnerIsInconclusiveBothWays() {
    CriterionResult high =
        Evaluator.evaluate(atLeast10k(), estimate("30000", "31000", 15), margin("0.80"), FORKS);
    CriterionResult low =
        Evaluator.evaluate(atLeast10k(), estimate("500", "1000", 15), margin("0.80"), FORKS);
    assertThat(high.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(low.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(high.reason()).containsIgnoringCase("margin").contains("0.8");
    assertThat(low.reason()).containsIgnoringCase("margin");
  }

  @Test
  void aMissingCanaryResourceIsInconclusive() {
    NoiseModel missing = new NoiseModel(new BigDecimal("0.10"), Set.of(Resource.CPU));
    CriterionResult r =
        Evaluator.evaluate(atLeast10k(), estimate("30000", "31000", 15), missing, FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(r.reason()).contains("CPU");
    NoiseModel unrelated = new NoiseModel(new BigDecimal("0.10"), Set.of(Resource.DISK));
    assertThat(
            Evaluator.evaluate(atLeast10k(), estimate("30000", "31000", 15), unrelated, FORKS)
                .verdict())
        .isEqualTo(Verdict.PASS);
  }

  @Test
  void noneNeverMeansNoMarginForANoisyCriterion() {
    // a run without canary information may not pass on a sliver above the threshold
    CriterionResult r =
        Evaluator.evaluate(atLeast10k(), estimate("10100", "10200", 15), NoiseModel.none(), FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(r.margin()).isGreaterThanOrEqualTo(new BigDecimal("0.10"));
    NoiseModel zero = new NoiseModel(BigDecimal.ZERO, Set.of());
    assertThat(
            Evaluator.evaluate(atLeast10k(), estimate("10100", "10200", 15), zero, FORKS).verdict())
        .as("a hand-built zero margin is raised to the floor")
        .isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void aCriterionThatIsNotNoisyUsesNoMargin() {
    Criterion exact = criterion("X-3", Direction.AT_MOST, "0.15", false, 0, "");
    CriterionResult pass =
        Evaluator.evaluate(exact, estimate("0.10", "0.15", 7), margin("0.30"), FORKS);
    assertThat(pass.verdict()).isEqualTo(Verdict.PASS);
    assertThat(pass.margin()).isEqualByComparingTo("0");
  }

  @Test
  void atMostMirrorsAtLeast() {
    Criterion c = p99AtMost100();
    assertThat(Evaluator.evaluate(c, estimate("70", "80", 15), margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.PASS);
    assertThat(Evaluator.evaluate(c, estimate("120", "130", 15), margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.FAIL);
    assertThat(Evaluator.evaluate(c, estimate("95", "105", 15), margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
    // 100 / 1.1 = 90.909..: an upper bound of 91 is not a pass, 90 is
    assertThat(Evaluator.evaluate(c, estimate("80", "91", 15), margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.evaluate(c, estimate("80", "90", 15), margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.PASS);
    // 110 is not above the band, 111 is
    assertThat(Evaluator.evaluate(c, estimate("110", "130", 15), margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.evaluate(c, estimate("111", "130", 15), margin("0.10"), FORKS).verdict())
        .isEqualTo(Verdict.FAIL);
  }

  @Test
  void fewerThanSevenForksIsInconclusive() {
    CriterionResult r =
        Evaluator.evaluate(atLeast10k(), estimate("30000", "31000", 5), margin("0.10"), FORKS);
    assertThat(r.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    CriterionResult failing =
        Evaluator.evaluate(atLeast10k(), estimate("100", "200", 5), margin("0.10"), FORKS);
    assertThat(failing.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void aMeasuredOnlyCriterionIsRejectedByTheForkEvaluator() {
    Criterion info = criterion("R-3", Direction.MEASURED_ONLY, "", false, 0, "");
    assertThatThrownBy(
            () -> Evaluator.evaluate(info, estimate("1", "2", 15), margin("0.10"), FORKS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void countIsExactWithNoMargin() {
    Criterion c = criterion("R-6a", Direction.AT_MOST, "0", false, 0, "");
    CriterionResult zero = Evaluator.evaluateCount(c, 0, 1);
    CriterionResult one = Evaluator.evaluateCount(c, 1, 1);
    assertThat(zero.verdict()).isEqualTo(Verdict.PASS);
    assertThat(one.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(zero.margin()).isEqualByComparingTo("0");
    Criterion atLeastSix = criterion("G-1", Direction.AT_LEAST, "6", false, 0, "");
    assertThat(Evaluator.evaluateCount(atLeastSix, 6, 1).verdict()).isEqualTo(Verdict.PASS);
    assertThat(Evaluator.evaluateCount(atLeastSix, 5, 1).verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  void singleRunCountCannotPass() {
    Criterion c = criterion("L-1", Direction.AT_MOST, "0", false, 2, "");
    assertThat(Evaluator.evaluateCount(c, 0, 1).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.evaluateCount(c, 0, 0).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.evaluateCount(c, 0, 2).verdict()).isEqualTo(Verdict.PASS);
    assertThat(Evaluator.evaluateCount(c, 1, 2).verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  void aCountRejectsNegativeInputAndMeasuredOnlyCriteria() {
    Criterion c = criterion("R-6a", Direction.AT_MOST, "0", false, 0, "");
    assertThatThrownBy(() -> Evaluator.evaluateCount(c, -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Evaluator.evaluateCount(c, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    Criterion info = criterion("R-3", Direction.MEASURED_ONLY, "", false, 0, "");
    assertThatThrownBy(() -> Evaluator.evaluateCount(info, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void measuredOnlyPassesWhenInvariantsHold() {
    Criterion info = criterion("R-3", Direction.MEASURED_ONLY, "", false, 0, "");
    assertThat(Evaluator.measuredOnly(info, true, 15, 7).verdict()).isEqualTo(Verdict.PASS);
    assertThat(Evaluator.measuredOnly(info, false, 15, 7).verdict()).isEqualTo(Verdict.FAIL);
    assertThat(Evaluator.measuredOnly(info, true, 6, 7).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    Criterion gate = atLeast10k();
    assertThatThrownBy(() -> Evaluator.measuredOnly(gate, true, 15, 7))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------- rollup

  private static List<CriterionResult> results(
      List<Criterion> criteria, Map<String, Verdict> verdicts) {
    List<CriterionResult> out = new ArrayList<>();
    for (Criterion c : criteria) {
      Verdict v = verdicts.get(c.id());
      if (v != null) {
        out.add(
            new CriterionResult(
                c.id(),
                v,
                c.basis(),
                Optional.empty(),
                c.threshold(),
                BigDecimal.ZERO,
                "scripted"));
      }
    }
    return out;
  }

  private static Criterion required(String id, String group) {
    return criterion(id, Direction.AT_MOST, "0", false, 0, group);
  }

  private static Criterion informational(String id) {
    Criterion base = criterion(id, Direction.MEASURED_ONLY, "", false, 0, "");
    return new Criterion(
        base.id(),
        base.spike(),
        base.metric(),
        base.unit(),
        base.direction(),
        base.threshold(),
        base.source(),
        false,
        base.noisy(),
        base.resources(),
        base.minRuns(),
        base.basis(),
        base.group(),
        base.consequenceIfNotRun());
  }

  @Test
  void rollupAnyRequiredFailIsFail() {
    List<Criterion> cs = List.of(required("A", ""), required("B", ""), required("C", ""));
    Map<String, Verdict> v =
        Map.of("A", Verdict.PASS, "B", Verdict.FAIL, "C", Verdict.INCONCLUSIVE);
    assertThat(Evaluator.rollup(results(cs, v), cs)).isEqualTo(Verdict.FAIL);
  }

  @Test
  void rollupAllRequiredPassIsPass() {
    List<Criterion> cs = List.of(required("A", ""), required("B", ""));
    Map<String, Verdict> v = Map.of("A", Verdict.PASS, "B", Verdict.PASS);
    assertThat(Evaluator.rollup(results(cs, v), cs)).isEqualTo(Verdict.PASS);
  }

  @Test
  void rollupInconclusiveOrNotRunIsInconclusive() {
    List<Criterion> cs = List.of(required("A", ""), required("B", ""));
    assertThat(
            Evaluator.rollup(results(cs, Map.of("A", Verdict.PASS, "B", Verdict.INCONCLUSIVE)), cs))
        .isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.rollup(results(cs, Map.of("A", Verdict.PASS, "B", Verdict.NOT_RUN)), cs))
        .isEqualTo(Verdict.INCONCLUSIVE);
    // a required criterion without any result is not run
    assertThat(Evaluator.rollup(results(cs, Map.of("A", Verdict.PASS)), cs))
        .isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.rollup(List.of(), cs)).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void informationalCriterionNeverAffectsTheRollup() {
    List<Criterion> cs = List.of(required("A", ""), informational("I"));
    assertThat(Evaluator.rollup(results(cs, Map.of("A", Verdict.PASS, "I", Verdict.FAIL)), cs))
        .isEqualTo(Verdict.PASS);
    assertThat(
            Evaluator.rollup(results(cs, Map.of("A", Verdict.PASS, "I", Verdict.INCONCLUSIVE)), cs))
        .isEqualTo(Verdict.PASS);
    assertThat(Evaluator.rollup(results(cs, Map.of("A", Verdict.PASS)), cs))
        .isEqualTo(Verdict.PASS);
  }

  @Test
  void layerGroupRollup() {
    List<Criterion> cs =
        List.of(
            required("R-1a", "layer:inhouse"),
            required("R-2a", "layer:inhouse"),
            required("R-6a", "layer:inhouse"),
            required("R-1b", "layer:cel"),
            required("R-2b", "layer:cel"),
            required("R-6b", "layer:cel"));
    Map<String, Verdict> v = new HashMap<>();
    for (Criterion c : cs) {
      v.put(c.id(), Verdict.PASS);
    }
    assertThat(Evaluator.rollup(results(cs, v), cs)).isEqualTo(Verdict.PASS);

    v.put("R-2b", Verdict.FAIL);
    assertThat(Evaluator.rollup(results(cs, v), cs)).as("one group passes").isEqualTo(Verdict.PASS);

    v.put("R-1a", Verdict.FAIL);
    assertThat(Evaluator.rollup(results(cs, v), cs)).as("both groups fail").isEqualTo(Verdict.FAIL);

    v.put("R-1a", Verdict.INCONCLUSIVE);
    assertThat(Evaluator.rollup(results(cs, v), cs))
        .as("one inconclusive, one failed")
        .isEqualTo(Verdict.INCONCLUSIVE);

    v.put("R-1a", Verdict.PASS);
    v.put("R-2b", Verdict.NOT_RUN);
    assertThat(Evaluator.rollup(results(cs, v), cs))
        .as("the group with a missing member does not pass, the other one does")
        .isEqualTo(Verdict.PASS);
  }

  @Test
  void aGroupWithAFailAndAnInconclusiveMemberIsFailed() {
    List<Criterion> cs = List.of(required("A1", "g1"), required("A2", "g1"), required("B1", "g2"));
    Map<String, Verdict> v =
        Map.of("A1", Verdict.FAIL, "A2", Verdict.INCONCLUSIVE, "B1", Verdict.FAIL);
    assertThat(Evaluator.rollup(results(cs, v), cs)).isEqualTo(Verdict.FAIL);
  }

  @Test
  void anUngroupedRequiredFailIsFailWhateverTheGroupsSay() {
    List<Criterion> cs =
        List.of(required("A", "layer:inhouse"), required("B", "layer:cel"), required("U", ""));
    Map<String, Verdict> v = Map.of("A", Verdict.PASS, "B", Verdict.PASS, "U", Verdict.FAIL);
    assertThat(Evaluator.rollup(results(cs, v), cs)).isEqualTo(Verdict.FAIL);
    Map<String, Verdict> inconclusive =
        Map.of("A", Verdict.PASS, "B", Verdict.PASS, "U", Verdict.INCONCLUSIVE);
    assertThat(Evaluator.rollup(results(cs, inconclusive), cs)).isEqualTo(Verdict.INCONCLUSIVE);
    Map<String, Verdict> groupsFailed =
        Map.of("A", Verdict.FAIL, "B", Verdict.FAIL, "U", Verdict.INCONCLUSIVE);
    assertThat(Evaluator.rollup(results(cs, groupsFailed), cs)).isEqualTo(Verdict.FAIL);
  }

  @Test
  void rollupRejectsDuplicateResults() {
    List<Criterion> cs = List.of(required("A", ""));
    List<CriterionResult> twice = new ArrayList<>(results(cs, Map.of("A", Verdict.PASS)));
    twice.addAll(results(cs, Map.of("A", Verdict.FAIL)));
    assertThatThrownBy(() -> Evaluator.rollup(twice, cs))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void timeBoxOverrunCapsAtInconclusive() {
    assertThat(Evaluator.capByTimeBox(Verdict.PASS, true, true)).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(Evaluator.capByTimeBox(Verdict.PASS, true, false)).isEqualTo(Verdict.PASS);
    assertThat(Evaluator.capByTimeBox(Verdict.FAIL, true, true)).isEqualTo(Verdict.FAIL);
    assertThat(Evaluator.capByTimeBox(Verdict.PASS, false, true)).isEqualTo(Verdict.PASS);
    assertThat(Evaluator.capByTimeBox(Verdict.INCONCLUSIVE, true, true))
        .isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void aGateCriterionNeedsAThresholdAndAMeasuredOnlyOneHasNone() {
    assertThatThrownBy(() -> criterion("G", Direction.AT_LEAST, "", false, 0, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> criterion("M", Direction.MEASURED_ONLY, "1", false, 0, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
