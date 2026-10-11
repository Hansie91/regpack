package dev.regpack.spikes.x;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.proto.ForkResult;
import dev.regpack.bench.proto.ForkSeries;
import dev.regpack.bench.proto.Protocol;
import dev.regpack.spikes.x.SpXRunner.Profile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * How the runner feeds the model criteria (X-2, X-3) and the figures of X-4: the per-thread scale,
 * which series feed which component, lower bounds against points. Synthetic forks of known rates
 * stand in for the measured ones; the expected numbers come from the plan's formula written out
 * again here, not from the code under test.
 */
class SpXModelWiringTest {
  private static final double FILE_RECORDS = 100_000;

  /** A series whose forks each have one window, so the statistic of a fork is its rate. */
  private static ForkSeries series(double... rates) {
    List<ForkResult> forks = new ArrayList<>();
    for (int i = 0; i < rates.length; i++) {
      forks.add(
          new ForkResult(
              i, false, false, "", List.of(rates[i]), List.of(), List.of(), List.of(), 0.0, 0L));
    }
    return new ForkSeries(forks, Protocol.informative());
  }

  private static double[] repeat(double value, int times) {
    double[] out = new double[times];
    java.util.Arrays.fill(out, value);
    return out;
  }

  /** Seven forks: the median per thread is 100,000 (writer), 20,000 (xsd), 40,000 (gate). */
  private static Map<String, ForkSeries> components() {
    Map<String, ForkSeries> m = new LinkedHashMap<>();
    // four-thread aggregates; per thread they are a quarter, so the median is 100,000 and the
    // lower bound (the smallest of seven) 90,000
    m.put("writer-t4", series(360e3, 380e3, 390e3, 400e3, 410e3, 420e3, 440e3));
    m.put("xsd-t4", series(70e3, 76e3, 78e3, 80e3, 82e3, 84e3, 90e3));
    m.put("gate-t4", series(140e3, 150e3, 155e3, 160e3, 165e3, 170e3, 180e3));
    // bisects per second, one thread: median 0.5 (2 s per defect), lower bound 0.25 (4 s)
    m.put("bisect-t1", series(0.25, 0.4, 0.45, 0.5, 0.55, 0.7, 1.0));
    return m;
  }

  /** The plan's formula, written out again. */
  private static double capacity(
      double writer,
      double xsd,
      double gate,
      double bisectSeconds,
      double fraction,
      double defectRate) {
    double surviving = defectRate * (1 - fraction);
    double pFail = 1 - Math.pow(1 - surviving, FILE_RECORDS);
    double perRecord =
        1 / writer
            + fraction / xsd
            + 1 / gate
            + surviving * bisectSeconds
            + pFail * (1 / writer + 1 / gate);
    return 4 / perRecord;
  }

  private static double lower(double fraction, double defectRate) {
    return capacity(90_000, 17_500, 35_000, 1 / 0.25, fraction, defectRate);
  }

  private static double point(double fraction, double defectRate) {
    return capacity(100_000, 20_000, 40_000, 1 / 0.5, fraction, defectRate);
  }

  private static Optional<CountValue> count(List<Series> series, String id) {
    return series.stream()
        .filter(s -> s.criterionId().equals(id))
        .map(s -> (CountValue) s)
        .findFirst();
  }

  private static BigDecimal figure(Map<String, Object> figures, String key) {
    assertThat(figures).containsKey(key);
    return (BigDecimal) figures.get(key);
  }

  @Test
  void x2TakesTheLowerBoundsOfTheQuarterRatesAndTheMinimumOverTheDefectRates() {
    List<Series> out = new ArrayList<>();
    Map<String, Object> figures = new TreeMap<>();
    List<String> why = new ArrayList<>();
    Map<String, ForkSeries> measured = components();
    measured.put("assemble-ALL", series(repeat(50_000, 7)));

    SpXRunner.model(measured, out, figures, why);

    // a four-thread aggregate of 400,000 is 100,000 per thread: with the aggregate used as the
    // per-thread rate the capacities would be four times as high
    double minAll = Math.min(lower(1.0, 0.0), Math.min(lower(1.0, 0.001), lower(1.0, 0.01)));
    double minSampled = Math.min(lower(0.1, 0.0), Math.min(lower(0.1, 0.001), lower(0.1, 0.01)));
    assertThat(lower(0.1, 0.01))
        .as("the 1% case is the minimum of the sampled policy")
        .isEqualTo(minSampled);
    assertThat(count(out, "X-2a")).isPresent();
    assertThat(count(out, "X-2a").orElseThrow().observed()).isEqualTo((long) Math.floor(minAll));
    assertThat(count(out, "X-2b").orElseThrow().observed())
        .isEqualTo((long) Math.floor(minSampled));
    assertThat(minAll).as("the lower bounds are what X-2 uses").isLessThan(point(1.0, 0.0));
    assertThat(why).isEmpty();

    assertThat(figures.get("model.minLowerBound.ALL")).isEqualTo((long) Math.floor(minAll));
    assertThat(figures.get("model.minLowerBound.SAMPLED_10"))
        .isEqualTo((long) Math.floor(minSampled));
    assertThat(figure(figures, "model.ALL.0.lowerBound").doubleValue())
        .isCloseTo(lower(1.0, 0.0), within(0.001));
    assertThat(figure(figures, "model.ALL.0.point").doubleValue())
        .isCloseTo(point(1.0, 0.0), within(0.001));
    assertThat(figure(figures, "model.SAMPLED_10.0.01.lowerBound").doubleValue())
        .isCloseTo(lower(0.1, 0.01), within(0.001));
    assertThat(figure(figures, "model.SAMPLED_10.0.01.point").doubleValue())
        .isCloseTo(point(0.1, 0.01), within(0.001));
    assertThat(figures).containsKeys("model.SAMPLED_10.0.001.point", "model.SAMPLED_10.0.point");
  }

  @Test
  void x3ComparesThePointEstimateAtNoDefectsWithTheIntegratedMedianAroundFifteenPercent() {
    double model = point(1.0, 0.0);
    // (model - integrated) / integrated; the model is 14% and 16% above the run, 14% and 20% below
    assertThat(x3(model / 1.14)).as("model 14% high").isZero();
    assertThat(x3(model / 1.16)).as("model 16% high").isEqualTo(1);
    assertThat(x3(model / 0.86)).as("model 14% low").isZero();
    assertThat(x3(model / 0.80)).as("model 20% low").isEqualTo(1);
    assertThat(x3(model)).as("model equals the run").isZero();

    List<Series> out = new ArrayList<>();
    Map<String, Object> figures = new TreeMap<>();
    Map<String, ForkSeries> measured = components();
    measured.put("assemble-ALL", series(repeat(model / 1.16, 7)));
    SpXRunner.model(measured, out, figures, new ArrayList<>());
    assertThat(figure(figures, "model.deviation.ALL.0").doubleValue())
        .isCloseTo(0.16, within(1e-6));
  }

  /** The value of X-3 for an integrated ALL run of {@code rate} records per second. */
  private static long x3(double rate) {
    List<Series> out = new ArrayList<>();
    Map<String, ForkSeries> measured = components();
    measured.put("assemble-ALL", series(repeat(rate, 7)));
    SpXRunner.model(measured, out, new TreeMap<>(), new ArrayList<>());
    return count(out, "X-3").orElseThrow().observed();
  }

  @Test
  void x3UsesTheMedianOfTheIntegratedForksNotAnExtreme() {
    double model = point(1.0, 0.0);
    // the median is the model rate; the two extreme forks are far off and must not count
    Map<String, ForkSeries> measured = components();
    measured.put(
        "assemble-ALL",
        series(model * 0.2, model * 0.5, model, model, model, model * 3, model * 4));
    List<Series> out = new ArrayList<>();
    SpXRunner.model(measured, out, new TreeMap<>(), new ArrayList<>());
    assertThat(count(out, "X-3").orElseThrow().observed()).isZero();
  }

  @Test
  void aSeriesWithFewerThanSevenKeptForksLeavesTheCriteriaOutAndSaysWhy() {
    Map<String, ForkSeries> measured = components();
    measured.put("writer-t4", series(360e3, 380e3, 390e3, 400e3, 410e3, 420e3));
    measured.put("assemble-ALL", series(repeat(50_000, 7)));
    List<Series> out = new ArrayList<>();
    List<String> why = new ArrayList<>();
    SpXRunner.model(measured, out, new TreeMap<>(), why);
    assertThat(out).isEmpty();
    assertThat(String.join("\n", why)).contains("X-2a", "X-2b", "X-3", "fewer than 7 kept forks");
  }

  @Test
  void withoutTheBisectOnlyTheAllPolicyIsModelled() {
    Map<String, ForkSeries> measured = components();
    measured.remove("bisect-t1");
    measured.put("assemble-ALL", series(repeat(50_000, 7)));
    List<Series> out = new ArrayList<>();
    List<String> why = new ArrayList<>();
    SpXRunner.model(measured, out, new TreeMap<>(), why);
    assertThat(count(out, "X-2a")).isPresent();
    assertThat(count(out, "X-2b")).isEmpty();
    assertThat(count(out, "X-3")).isPresent();
    assertThat(String.join("\n", why)).contains("X-2b").doesNotContain("X-3");
  }

  @Test
  void withoutTheIntegratedRunTheModelCriteriaExceptX3Remain() {
    Map<String, ForkSeries> measured = components();
    List<Series> out = new ArrayList<>();
    List<String> why = new ArrayList<>();
    SpXRunner.model(measured, out, new TreeMap<>(), why);
    assertThat(count(out, "X-2a")).isPresent();
    assertThat(count(out, "X-2b")).isPresent();
    assertThat(count(out, "X-3")).isEmpty();
    assertThat(String.join("\n", why)).contains("X-3").doesNotContain("X-2");
  }

  @Test
  void anIntegratedRunOfZeroRecordsPerSecondLeavesX3OutInsteadOfFailingTheWrite() {
    Map<String, ForkSeries> measured = components();
    measured.put("assemble-ALL", series(repeat(0.0, 7)));
    List<Series> out = new ArrayList<>();
    List<String> why = new ArrayList<>();
    SpXRunner.model(measured, out, new TreeMap<>(), why);
    assertThat(count(out, "X-2a")).isPresent();
    assertThat(count(out, "X-3")).isEmpty();
    assertThat(String.join("\n", why)).contains("X-3 not evaluated").contains("not positive");
  }

  @Test
  void figuresConvertTheRatesOfEachSeriesWithItsOwnScale() {
    Map<String, ForkSeries> measured = new LinkedHashMap<>();
    // four-thread series are aggregates (quarter per thread), one-thread series are as they are
    measured.put("writer-t4", series(repeat(40_000, 3)));
    measured.put("writer-t1", series(repeat(12_000, 3)));
    measured.put("xsd-t4", series(repeat(20_000, 3)));
    measured.put("xsd-t1", series(repeat(8_000, 3)));
    measured.put("gate-t4", series(repeat(100_000, 3)));
    measured.put("gate-t1", series(repeat(50_000, 3)));
    measured.put("bisect-t1", series(repeat(0.5, 3)));
    measured.put("assemble-ALL", series(repeat(30_000, 3)));
    measured.put("assemble-SAMPLED_10", series(repeat(35_000, 3)));

    Map<String, Object> f =
        SpXRunner.figures(measured, Profile.FULL.sizes(), new BigDecimal("1000.0"));

    assertThat(figure(f, "xml.avgRecordBytes")).isEqualByComparingTo("1000.0");
    assertThat(figure(f, "xml.write.recPerSec.perThread")).isEqualByComparingTo("10000");
    assertThat(figure(f, "xml.write.recPerSec.t1")).isEqualByComparingTo("12000");
    assertThat(figure(f, "xml.write.mbPerSec"))
        .as("10,000 rec/s x 1000 B")
        .isEqualByComparingTo("10.000");
    assertThat(figure(f, "xml.write.mbPerSec.t1")).isEqualByComparingTo("12.000");
    assertThat(figure(f, "xsd.perRecord.usPerRecord"))
        .as("5,000 rec/s per thread")
        .isEqualByComparingTo("200.000");
    assertThat(figure(f, "xsd.perRecord.usPerRecord.t1")).isEqualByComparingTo("125.000");
    assertThat(f.get("gate.fileRecords")).isEqualTo(100_000L);
    assertThat(figure(f, "gate.file100k.seconds.t1"))
        .as("100,000 / 50,000")
        .isEqualByComparingTo("2.000");
    assertThat(figure(f, "gate.file100k.seconds.t4"))
        .as("100,000 / 25,000")
        .isEqualByComparingTo("4.000");
    assertThat(figure(f, "bisect.secondsPerDefect")).isEqualByComparingTo("2.000");
    assertThat(figure(f, "integrated.recPerSec.ALL")).isEqualByComparingTo("30000");
    assertThat(figure(f, "integrated.recPerSec.SAMPLED_10")).isEqualByComparingTo("35000");
    assertThat(f.get("integrated.threads")).isEqualTo(4L);
  }

  @Test
  void figuresLeaveOutWhatWasNotMeasuredAndTheMegabytesWithoutARecordSize() {
    Map<String, ForkSeries> measured = new LinkedHashMap<>();
    measured.put("writer-t4", series(repeat(40_000, 3)));
    Map<String, Object> f = SpXRunner.figures(measured, Profile.SMOKE.sizes(), BigDecimal.ZERO);
    assertThat(f).containsKey("xml.write.recPerSec.perThread");
    assertThat(f.keySet())
        .doesNotContain("xml.avgRecordBytes", "xml.write.mbPerSec", "xml.write.mbPerSec.t1")
        .doesNotContain("xsd.perRecord.usPerRecord", "gate.file100k.seconds.t1");
    assertThat(f.get("gate.fileRecords")).isEqualTo(3_000L);
  }
}
