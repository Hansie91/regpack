package dev.regpack.spikes.x;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import dev.regpack.bench.stats.Estimate;
import dev.regpack.spikes.x.CapacityModel.Capacity;
import dev.regpack.spikes.x.CapacityModel.Rates;
import dev.regpack.spikes.x.validate.PolicyModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CapacityModelTest {
  private static Estimate estimate(double point, double lower, double upper) {
    return new Estimate(
        BigDecimal.valueOf(point), BigDecimal.valueOf(lower), BigDecimal.valueOf(upper), 7, "test");
  }

  private static Estimate exact(double v) {
    return estimate(v, v, v);
  }

  @Test
  void ampleComponentsGiveTheHarmonicCombination() {
    Rates rates = new Rates(exact(100_000), exact(100_000), exact(100_000), Optional.empty());
    List<Capacity> all = CapacityModel.capacities(rates, 4, PolicyModel.ALL).orElseThrow();
    // writer + per-record XSD + gate: 3e-5 seconds per record, four threads
    assertThat(all).hasSize(3);
    assertThat(all.get(0).lowerBound())
        .isCloseTo(4.0 / 3e-5, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(all.get(0).point()).isEqualTo(all.get(0).lowerBound());
    assertThat(CapacityModel.minimumLowerBound(all)).isEqualTo(133_333L);
  }

  @Test
  void lowerBoundsUseTheLowerRatesAndTheSlowerBisect() {
    Rates rates =
        new Rates(
            estimate(100_000, 80_000, 120_000),
            estimate(20_000, 15_000, 25_000),
            estimate(40_000, 30_000, 50_000),
            Optional.of(estimate(0.5, 0.25, 1.0)));
    List<Capacity> sampled =
        CapacityModel.capacities(rates, 4, PolicyModel.SAMPLED_10).orElseThrow();
    Capacity atOnePercent = sampled.get(2);
    assertThat(atOnePercent.defectRate()).isEqualTo(0.01);
    assertThat(atOnePercent.lowerBound())
        .as("a pessimistic component set is slower than the medians")
        .isLessThan(atOnePercent.point());
    // the lower-bound model at defect rate 0 has no bisect or rebuild term at all
    double perRecord = 1.0 / 80_000 + 0.1 / 15_000 + 1.0 / 30_000;
    assertThat(sampled.get(0).lowerBound())
        .isCloseTo(4.0 / perRecord, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(CapacityModel.minimumLowerBound(sampled))
        .isEqualTo((long) Math.floor(atOnePercent.lowerBound()));
  }

  @Test
  void onlyTheBisectEstimateDiffersAndTheLowerBoundUsesItsUpperBoundSeconds() {
    // writer, xsd and gate are exact; the bisect rate is 0.5 per second (2 s per defect), lower
    // bound 0.25 per second (4 s per defect)
    Rates rates =
        new Rates(
            exact(100_000), exact(20_000), exact(40_000), Optional.of(estimate(0.5, 0.25, 1.0)));
    Capacity atOnePercent =
        CapacityModel.capacities(rates, 4, PolicyModel.SAMPLED_10).orElseThrow().get(2);
    assertThat(atOnePercent.defectRate()).isEqualTo(0.01);
    assertThat(atOnePercent.lowerBound()).isLessThan(atOnePercent.point());
    assertThat(atOnePercent.lowerBound())
        .as("4 s per defect")
        .isCloseTo(handFormula(100_000, 20_000, 40_000, 1 / 0.25), withinPercentage(1e-9));
    assertThat(atOnePercent.point())
        .as("2 s per defect")
        .isCloseTo(handFormula(100_000, 20_000, 40_000, 1 / 0.5), withinPercentage(1e-9));
  }

  /** SAMPLED_10 at 1%: s = 0.1, 0.9% of the records survive, the file fails with pFail. */
  private static double handFormula(double writer, double xsd, double gate, double bisectSeconds) {
    double surviving = 0.01 * 0.9;
    double pFail = 1 - Math.pow(1 - surviving, 100_000);
    double perRecord =
        1 / writer
            + 0.1 / xsd
            + 1 / gate
            + surviving * bisectSeconds
            + pFail * (1 / writer + 1 / gate);
    return 4 / perRecord;
  }

  @Test
  void aSamplingPolicyNeedsTheBisectCostButAllDoesNot() {
    Rates withoutBisect = new Rates(exact(1e5), exact(1e5), exact(1e5), Optional.empty());
    assertThat(CapacityModel.capacities(withoutBisect, 4, PolicyModel.SAMPLED_10)).isEmpty();
    assertThat(CapacityModel.capacities(withoutBisect, 4, PolicyModel.ALL)).isPresent();
  }

  @Test
  void x3IsOneBeyondFifteenPercentOfTheIntegratedRun() {
    Rates rates = new Rates(exact(100_000), exact(100_000), exact(100_000), Optional.empty());
    List<Capacity> all = CapacityModel.capacities(rates, 4, PolicyModel.ALL).orElseThrow();
    double model = all.get(0).point();
    assertThat(CapacityModel.deviationExceeds(all, model)).isZero();
    assertThat(CapacityModel.deviationExceeds(all, model / 1.14)).as("model 14% high").isZero();
    assertThat(CapacityModel.deviationExceeds(all, model / 1.16)).as("model 16% high").isEqualTo(1);
    assertThat(CapacityModel.deviationExceeds(all, model / 0.86)).as("model 14% low").isZero();
    assertThat(CapacityModel.deviationExceeds(all, model / 0.80)).as("model 20% low").isEqualTo(1);
    assertThat(CapacityModel.deviation(all, model * 2)).isEqualTo(-0.5);
  }
}
