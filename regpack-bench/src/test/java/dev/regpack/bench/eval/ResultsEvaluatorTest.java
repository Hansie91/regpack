package dev.regpack.bench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.MeasuredOnly;
import dev.regpack.bench.eval.MeasurementSet.Projection;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultsEvaluatorTest {
  private static final String DIGEST = "c".repeat(64);
  private static final Criteria CRITERIA = Criteria.load();
  private static final Map<String, Object> BOX = Map.of("usedMinutes", 100L, "boxMinutes", 480L);

  /**
   * A quiet canary: ten samples about 1000 with a spread of 0.4%, so the margin is the 0.10 floor.
   */
  private static Map<Resource, List<BigDecimal>> quietCanary() {
    Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
    for (Resource r : Resource.values()) {
      List<BigDecimal> samples = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        samples.add(new BigDecimal(1000 + (i % 3)));
      }
      canary.put(r, samples);
    }
    return canary;
  }

  private static Map<Resource, List<BigDecimal>> noisyCanary() {
    Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
    for (Resource r : Resource.values()) {
      List<BigDecimal> samples = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        samples.add(new BigDecimal(i % 2 == 0 ? 1000 : 1400));
      }
      canary.put(r, samples);
    }
    return canary;
  }

  private static MeasurementSet set(
      String spike, Map<Resource, List<BigDecimal>> canary, List<RunInfo> runs, Series... series) {
    return new MeasurementSet(
        spike,
        DIGEST,
        "FULL",
        List.of(series),
        canary,
        runs,
        Map.of("gitSha", "abc1234"),
        Map.of(),
        Map.of("executorGate", "PASS"),
        List.of());
  }

  private static List<RunInfo> twoRuns() {
    return List.of(
        new RunInfo("1", 1, "a", "abc1234", "cpu", "img"),
        new RunInfo("2", 1, "a", "abc1234", "cpu", "img"));
  }

  private static String unit(String criterionId) {
    return CRITERIA.byId(criterionId).orElseThrow().unit();
  }

  private static ForkValues forkSeries(String id, String scale, double... statistics) {
    List<ForkStat> list = new ArrayList<>();
    for (int i = 0; i < statistics.length; i++) {
      list.add(new ForkStat(i, BigDecimal.valueOf(statistics[i]), false, "", false));
    }
    return new ForkValues(id, unit(id), new BigDecimal(scale), list);
  }

  private static double[] around(double centre, int n) {
    double[] v = new double[n];
    for (int i = 0; i < n; i++) {
      v[i] = centre - 50 + i * (100.0 / (n - 1));
    }
    return v;
  }

  private static BigDecimal nineDecimals(double v) {
    return dev.regpack.bench.stats.Stats.decimal(v);
  }

  private static CriterionResult find(SpikeResult r, String id) {
    return r.criteria().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
  }

  private static SpikeResult evaluate(MeasurementSet m) {
    return ResultsEvaluator.evaluate(m, CRITERIA, "RAW", BOX);
  }

  @Test
  void forkSeriesProducesEstimateAndVerdict() {
    double[] stats = around(12_000, 15);
    MeasurementSet m = set("SP-R", quietCanary(), twoRuns(), forkSeries("R-2a", "1", stats));
    SpikeResult r = evaluate(m);
    CriterionResult c = find(r, "R-2a");
    assertThat(c.verdict()).isEqualTo(Verdict.PASS);
    assertThat(c.estimate()).isPresent();
    // 4th smallest and 4th largest of 15
    assertThat(c.estimate().orElseThrow().lower()).isEqualByComparingTo(nineDecimals(stats[3]));
    assertThat(c.estimate().orElseThrow().upper()).isEqualByComparingTo(nineDecimals(stats[11]));
    assertThat(c.estimate().orElseThrow().n()).isEqualTo(15);
    assertThat(c.margin()).isEqualByComparingTo("0.10");
    assertThat(r.criteriaDigest()).isEqualTo(CRITERIA.digest().hex());
    assertThat(r.vmCount()).isEqualTo(2);
    assertThat(r.evidenceLevel()).isEqualTo("RAW");
  }

  @Test
  void noisyCanaryTurnsALowerBoundIntoInconclusive() {
    // the same series that passes on a quiet runner is INCONCLUSIVE on a runner with a 40% spread
    MeasurementSet m =
        set("SP-R", noisyCanary(), twoRuns(), forkSeries("R-2a", "1", around(12_000, 15)));
    CriterionResult c = find(evaluate(m), "R-2a");
    assertThat(c.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(c.margin()).isEqualByComparingTo("0.4");
  }

  @Test
  void aNoisyCriterionWithoutCanaryIsInconclusive() {
    MeasurementSet m =
        set(
            "SP-R",
            new EnumMap<>(Resource.class),
            twoRuns(),
            forkSeries("R-2a", "1", around(12_000, 15)));
    CriterionResult c = find(evaluate(m), "R-2a");
    assertThat(c.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(c.reason()).containsIgnoringCase("canary");
  }

  @Test
  void scaleTurnsAnAggregateIntoPerThread() {
    // four busy threads: the aggregate of 48,000 is 12,000 per thread
    double[] aggregate = around(48_000, 15);
    MeasurementSet m = set("SP-R", quietCanary(), twoRuns(), forkSeries("R-2a", "0.25", aggregate));
    CriterionResult c = find(evaluate(m), "R-2a");
    assertThat(c.verdict()).isEqualTo(Verdict.PASS);
    assertThat(c.estimate().orElseThrow().point()).isEqualByComparingTo("12000");
    assertThat(c.estimate().orElseThrow().lower())
        .isEqualByComparingTo(nineDecimals(aggregate[3] / 4));
  }

  @Test
  void discardedForksAreExcludedAndMoreThanThreeAreInconclusive() {
    double[] stats = around(12_000, 15);
    List<ForkStat> three = new ArrayList<>();
    for (int i = 0; i < stats.length; i++) {
      boolean discard = i < 3;
      // a discarded fork carries a wild value that must not enter the estimate
      three.add(
          new ForkStat(
              i,
              discard ? BigDecimal.ONE : BigDecimal.valueOf(stats[i]),
              discard,
              discard ? "STEAL 0.07" : "",
              false));
    }
    CriterionResult ok =
        find(
            evaluate(
                set(
                    "SP-R",
                    quietCanary(),
                    twoRuns(),
                    new ForkValues("R-2a", unit("R-2a"), BigDecimal.ONE, three))),
            "R-2a");
    assertThat(ok.verdict()).isEqualTo(Verdict.PASS);
    assertThat(ok.estimate().orElseThrow().n()).isEqualTo(12);
    assertThat(ok.reason()).contains("STEAL 0.07");

    List<ForkStat> four = new ArrayList<>(three);
    four.set(3, new ForkStat(3, BigDecimal.ONE, true, "HARNESS_ERROR: boom", false));
    CriterionResult bad =
        find(
            evaluate(
                set(
                    "SP-R",
                    quietCanary(),
                    twoRuns(),
                    new ForkValues("R-2a", unit("R-2a"), BigDecimal.ONE, four))),
            "R-2a");
    assertThat(bad.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(bad.reason()).containsIgnoringCase("discard");
  }

  @Test
  void fewerThanFiveKeptForksIsInconclusiveAndNeverThrows() {
    CriterionResult c =
        find(
            evaluate(
                set(
                    "SP-R",
                    quietCanary(),
                    twoRuns(),
                    forkSeries("R-2a", "1", 12_000, 12_100, 12_200, 12_300))),
            "R-2a");
    assertThat(c.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(c.estimate()).isEmpty();
  }

  @Test
  void aMajorityOfUnstableForksIsInconclusive() {
    double[] stats = around(12_000, 15);
    List<ForkStat> list = new ArrayList<>();
    for (int i = 0; i < stats.length; i++) {
      list.add(new ForkStat(i, BigDecimal.valueOf(stats[i]), false, "", i < 4));
    }
    CriterionResult c =
        find(
            evaluate(
                set(
                    "SP-R",
                    quietCanary(),
                    twoRuns(),
                    new ForkValues("R-2a", unit("R-2a"), BigDecimal.ONE, list))),
            "R-2a");
    assertThat(c.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(c.reason()).containsIgnoringCase("unstable");
  }

  @Test
  void countSeriesIsExact() {
    SpikeResult zero =
        evaluate(set("SP-R", quietCanary(), twoRuns(), new CountValue("R-1a", 0, 1)));
    assertThat(find(zero, "R-1a").verdict()).isEqualTo(Verdict.PASS);
    assertThat(find(zero, "R-1a").margin()).isEqualByComparingTo("0");
    SpikeResult one = evaluate(set("SP-R", quietCanary(), twoRuns(), new CountValue("R-1a", 1, 1)));
    assertThat(find(one, "R-1a").verdict()).isEqualTo(Verdict.FAIL);
    // L-1 needs two valid runs
    SpikeResult single =
        evaluate(set("SP-L", quietCanary(), twoRuns(), new CountValue("L-1", 0, 1)));
    assertThat(find(single, "L-1").verdict()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void measuredOnlySeriesRecordsItsValue() {
    SpikeResult r =
        evaluate(
            set(
                "SP-R",
                quietCanary(),
                twoRuns(),
                new MeasuredOnly("R-3", true, 15, new BigDecimal("48000"), "records/s")));
    CriterionResult c = find(r, "R-3");
    assertThat(c.verdict()).isEqualTo(Verdict.PASS);
    assertThat(c.reason()).contains("48000").contains("records/s");
    SpikeResult broken =
        evaluate(
            set(
                "SP-R",
                quietCanary(),
                twoRuns(),
                new MeasuredOnly("R-3", false, 15, BigDecimal.ONE, "x")));
    assertThat(find(broken, "R-3").verdict()).isEqualTo(Verdict.FAIL);
  }

  private static Projection projection(String scale, String floor, int points) {
    List<BigDecimal> xs = new ArrayList<>();
    List<BigDecimal> ys = new ArrayList<>();
    for (int i = 1; i <= points; i++) {
      xs.add(BigDecimal.valueOf(i * 1_000_000L));
      ys.add(BigDecimal.valueOf(i * 60L));
    }
    return new Projection(
        "L-6",
        "hours",
        new BigDecimal(scale),
        xs,
        ys,
        new BigDecimal("10000000"),
        new BigDecimal(floor));
  }

  @Test
  void projectionNeedsThreePointsAndIsRaisedToTheFloor() {
    CriterionResult two =
        find(evaluate(set("SP-L", quietCanary(), twoRuns(), projection("1", "0", 2))), "L-6");
    assertThat(two.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(two.estimate()).isEmpty();

    // y = 60 per million keys, so 10M keys is 600 exactly
    CriterionResult exact =
        find(evaluate(set("SP-L", quietCanary(), twoRuns(), projection("1", "0", 3))), "L-6");
    assertThat(exact.estimate().orElseThrow().point()).isEqualByComparingTo("600");
    assertThat(exact.estimate().orElseThrow().lower()).isEqualByComparingTo("600");
    assertThat(exact.estimate().orElseThrow().upper()).isEqualByComparingTo("600");
    assertThat(exact.estimate().orElseThrow().n()).isEqualTo(3);

    // the physical floor of 5 (hours) is above the projection of 600 x 1/3600 = 0.1667
    CriterionResult floored =
        find(
            evaluate(set("SP-L", quietCanary(), twoRuns(), projection("0.000277777778", "5", 3))),
            "L-6");
    assertThat(floored.estimate().orElseThrow().point()).isEqualByComparingTo("5");
    assertThat(floored.estimate().orElseThrow().lower()).isEqualByComparingTo("5");
    assertThat(floored.estimate().orElseThrow().upper()).isEqualByComparingTo("5");
    // 5 hours is above the 4 hour limit even with the margin
    assertThat(floored.verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  void threePointProjectionClearlyBelowTheLimitCanPass() {
    CriterionResult c =
        find(
            evaluate(set("SP-L", quietCanary(), twoRuns(), projection("0.000277777778", "0", 3))),
            "L-6");
    assertThat(c.verdict()).isEqualTo(Verdict.PASS);
    assertThat(c.basis()).isEqualTo(Basis.EXTRAPOLATED);
    assertThat(c.estimate().orElseThrow().point().doubleValue()).isBetween(0.1666, 0.1667);
  }

  /**
   * Three scattered points (sizes in millions of keys): 60, 300 and 100 at 1, 2 and 3. The pairwise
   * slopes are -200, 20 and 240, so the Theil-Sen slope is 20 with the bounds -200 and 240. The
   * median residual of each slope is 40 (slope 20), 700 (slope -200) and -180 (slope 240).
   */
  private static Projection scattered(String scale, String target, String floor) {
    return new Projection(
        "L-6",
        "hours",
        new BigDecimal(scale),
        List.of(BigDecimal.ONE, BigDecimal.TWO, new BigDecimal("3")),
        List.of(new BigDecimal("60"), new BigDecimal("300"), new BigDecimal("100")),
        new BigDecimal(target),
        new BigDecimal(floor));
  }

  @Test
  void projectionBoundsWidenWithScatter() {
    // at 10 million keys: slope 20 gives 40 + 200 = 240, slope 240 gives -180 + 2400 = 2220 and
    // slope -200 gives 700 - 2000 = -1300; scale 0.01 turns that into 2.4, 22.2 and -13
    CriterionResult c =
        find(evaluate(set("SP-L", quietCanary(), twoRuns(), scattered("0.01", "10", "0"))), "L-6");
    var e = c.estimate().orElseThrow();
    assertThat(e.lower()).isLessThan(e.point());
    assertThat(e.point()).isLessThan(e.upper());
    assertThat(e.point().doubleValue()).isCloseTo(2.4, within(1e-9));
    assertThat(e.upper().doubleValue()).isCloseTo(22.2, within(1e-9));
    // the lower bound is the slope -200 line, -13, raised to the floor of 0
    assertThat(e.lower()).isEqualByComparingTo("0");
    // the point alone (2.4) is under the limit of 4 hours with its margin, the upper bound is not:
    // a projection that took its bounds from the point slope would PASS here
    assertThat(c.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(c.basis()).isEqualTo(Basis.EXTRAPOLATED);
    // the same points, without scatter in the bounds, do pass
    CriterionResult tight =
        find(evaluate(set("SP-L", quietCanary(), twoRuns(), projection("0.001", "0", 3))), "L-6");
    assertThat(tight.verdict()).isEqualTo(Verdict.PASS);
  }

  @Test
  void projectionLowerBoundCanComeFromTheUpperSlope() {
    // at 0.5 million keys, below the centre of the sizes, the steepest slope gives the lowest
    // value:
    // slope 240 gives -180 + 120 = -60, slope 20 gives 40 + 10 = 50, slope -200 gives 700 - 100 =
    // 600
    var e =
        find(evaluate(set("SP-L", quietCanary(), twoRuns(), scattered("1", "0.5", "-1000"))), "L-6")
            .estimate()
            .orElseThrow();
    assertThat(e.lower()).isEqualByComparingTo("-60");
    assertThat(e.point()).isEqualByComparingTo("50");
    assertThat(e.upper()).isEqualByComparingTo("600");
  }

  @Test
  void theFloorRaisesTheLowerBoundWithoutMovingTheOthers() {
    var e =
        find(evaluate(set("SP-L", quietCanary(), twoRuns(), scattered("1", "10", "100"))), "L-6")
            .estimate()
            .orElseThrow();
    assertThat(e.lower()).isEqualByComparingTo("100");
    assertThat(e.point()).isEqualByComparingTo("240");
    assertThat(e.upper()).isEqualByComparingTo("2220");
    // an unclipped lower bound stays what the slope gives
    var unclipped =
        find(evaluate(set("SP-L", quietCanary(), twoRuns(), scattered("1", "10", "-5000"))), "L-6")
            .estimate()
            .orElseThrow();
    assertThat(unclipped.lower()).isEqualByComparingTo("-1300");
  }

  @Test
  void projectionWithEqualSizesHasNoSlope() {
    Projection flat =
        new Projection(
            "L-6",
            "hours",
            BigDecimal.ONE,
            List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
            List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN),
            BigDecimal.TEN,
            BigDecimal.ZERO);
    CriterionResult c = find(evaluate(set("SP-L", quietCanary(), twoRuns(), flat)), "L-6");
    assertThat(c.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void unknownCriterionIdIsRejected() {
    assertThatThrownBy(
            () -> evaluate(set("SP-R", quietCanary(), twoRuns(), new CountValue("ZZ-9", 0, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ZZ-9");
    // a criterion of another spike is just as unknown
    assertThatThrownBy(
            () -> evaluate(set("SP-R", quietCanary(), twoRuns(), new CountValue("ST-1", 0, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ST-1");
    assertThatThrownBy(
            () -> evaluate(set("SP-NOPE", quietCanary(), twoRuns(), new CountValue("R-1a", 0, 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSeriesOfTheWrongKindIsRejected() {
    // R-3 is measured only, a count has nothing to compare with
    assertThatThrownBy(
            () -> evaluate(set("SP-R", quietCanary(), twoRuns(), new CountValue("R-3", 0, 1))))
        .isInstanceOf(IllegalArgumentException.class);
    // R-1a has a threshold, a measured-only series cannot judge it
    assertThatThrownBy(
            () ->
                evaluate(
                    set(
                        "SP-R",
                        quietCanary(),
                        twoRuns(),
                        new MeasuredOnly("R-1a", true, 1, BigDecimal.ONE, "x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCountCannotStandInForANoisyCriterion() {
    // 10,000 records/s is the threshold of R-2a: a count of 10,000 would skip the forks, the
    // noise margin and the interval and pass with margin 0
    assertThatThrownBy(
            () ->
                evaluate(set("SP-R", quietCanary(), twoRuns(), new CountValue("R-2a", 10_000, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("R-2a");
  }

  @Test
  void aForkSeriesOrProjectionCannotStandInForAnExactCountCriterion() {
    // L-1 is an exact count that needs two valid runs
    assertThatThrownBy(
            () ->
                evaluate(
                    set(
                        "SP-L",
                        quietCanary(),
                        twoRuns(),
                        new ForkValues("L-1", unit("L-1"), BigDecimal.ONE, List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("L-1");
    Projection asProjection =
        new Projection(
            "L-1",
            unit("L-1"),
            BigDecimal.ONE,
            List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN),
            List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN),
            BigDecimal.TEN,
            BigDecimal.ZERO);
    assertThatThrownBy(() -> evaluate(set("SP-L", quietCanary(), twoRuns(), asProjection)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("L-1");
  }

  @Test
  void aSeriesInAnotherUnitThanItsCriterionIsRejected() {
    // seconds where the criterion is in hours (a scale slip of a factor 3600)
    Projection seconds =
        new Projection(
            "L-6",
            "seconds",
            BigDecimal.ONE,
            List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN),
            List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN),
            BigDecimal.TEN,
            BigDecimal.ZERO);
    assertThatThrownBy(() -> evaluate(set("SP-L", quietCanary(), twoRuns(), seconds)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seconds")
        .hasMessageContaining("hours");
    List<ForkStat> forks = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      forks.add(new ForkStat(i, BigDecimal.valueOf(12_000 + i), false, "", false));
    }
    assertThatThrownBy(
            () ->
                evaluate(
                    set(
                        "SP-R",
                        quietCanary(),
                        twoRuns(),
                        new ForkValues("R-2a", "records/s", BigDecimal.ONE, forks))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("R-2a");
  }

  @Test
  void missingRequiredSeriesIsNotRun() {
    SpikeResult r =
        evaluate(set("HARNESS", quietCanary(), twoRuns(), new CountValue("ST-1", 0, 1)));
    assertThat(r.criteria()).hasSize(2);
    assertThat(find(r, "ST-1").verdict()).isEqualTo(Verdict.PASS);
    assertThat(find(r, "ST-2").verdict()).isEqualTo(Verdict.NOT_RUN);
    assertThat(r.status()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void oneResultPerCriterionOfTheSpikeInRegistryOrder() {
    SpikeResult r = evaluate(set("SP-R", quietCanary(), twoRuns()));
    assertThat(r.criteria())
        .extracting(CriterionResult::id)
        .containsExactlyElementsOf(CRITERIA.forSpike("SP-R").stream().map(Criterion::id).toList());
    assertThat(r.criteria()).allMatch(c -> c.verdict() == Verdict.NOT_RUN);
    assertThat(r.status()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  private static MeasurementSet inhouseLayerOnly() {
    return set(
        "SP-R",
        quietCanary(),
        twoRuns(),
        new CountValue("R-1a", 0, 1),
        forkSeries("R-2a", "1", around(12_000, 15)),
        new CountValue("R-6a", 0, 1),
        forkSeries("R-4", "1", around(50, 15)),
        new CountValue("R-5", 0, 1),
        new CountValue("R-7", 0, 1));
  }

  @Test
  void aSpikeCanPassThroughOneLayerAndTheOtherLayerIsNotRun() {
    SpikeResult r = evaluate(inhouseLayerOnly());
    assertThat(find(r, "R-4").verdict()).isEqualTo(Verdict.PASS);
    assertThat(r.status()).isEqualTo(Verdict.PASS);
  }

  @Test
  void timeBoxOverrunCapsAPassWhileARequiredCriterionIsNotRun() {
    SpikeResult over =
        ResultsEvaluator.evaluate(
            inhouseLayerOnly(), CRITERIA, "RAW", Map.of("usedMinutes", 500L, "boxMinutes", 480L));
    assertThat(over.status()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(over.timeBox().get("hit")).isEqualTo(Boolean.TRUE);
    SpikeResult within = ResultsEvaluator.evaluate(inhouseLayerOnly(), CRITERIA, "RAW", BOX);
    assertThat(within.status()).isEqualTo(Verdict.PASS);
    assertThat(within.timeBox().get("hit")).isEqualTo(Boolean.FALSE);
  }

  @Test
  void aTimeBoxMapWithoutMinutesIsRejected() {
    assertThatThrownBy(
            () -> ResultsEvaluator.evaluate(inhouseLayerOnly(), CRITERIA, "RAW", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ResultsEvaluator.evaluate(inhouseLayerOnly(), CRITERIA, "MAYBE", BOX))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultCarriesProvenanceAndDecisions() {
    MeasurementSet m =
        new MeasurementSet(
            "SP-R",
            DIGEST,
            "PARTIAL",
            List.of(new CountValue("R-1a", 0, 1)),
            quietCanary(),
            List.of(
                new RunInfo("1", 1, "a", "sha1", "AMD", "img"),
                new RunInfo("2", 1, "a", "sha2", "Intel", "img")),
            Map.of("gitSha", "sha1"),
            Map.of("cpu.count", 4L),
            Map.of("expressionLayer", "INHOUSE"),
            List.of("S1 shape not run"));
    SpikeResult r = evaluate(m);
    assertThat(r.scope()).isEqualTo("PARTIAL");
    assertThat(r.mixedHardware()).isTrue();
    assertThat(r.decisions()).containsEntry("expressionLayer", "INHOUSE");
    assertThat(r.open()).containsExactly("S1 shape not run");
    assertThat(r.runs()).hasSize(2);
    assertThat(r.toJson()).doesNotContain("E+").contains("\"criteriaDigest\"");
  }
}
