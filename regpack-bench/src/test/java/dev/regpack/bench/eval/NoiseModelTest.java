package dev.regpack.bench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoiseModelTest {
  private static double[] series(double low, double high) {
    // five samples, so the spread is max / min - 1
    return new double[] {low, (low + high) / 2, high, low, high};
  }

  @Test
  void marginIsTheLargestDeclaredResourceSpreadFlooredAtTenPercent() {
    Map<Resource, double[]> samples =
        Map.of(
            Resource.CPU, series(100, 105),
            Resource.MEMORY, series(100, 125),
            Resource.DISK, series(100, 160));
    NoiseModel declaredTwo =
        NoiseModel.fromCanary(samples, EnumSet.of(Resource.CPU, Resource.MEMORY));
    assertThat(declaredTwo.margin()).isEqualByComparingTo("0.25");
    assertThat(declaredTwo.missing()).isEmpty();

    NoiseModel quiet = NoiseModel.fromCanary(samples, EnumSet.of(Resource.CPU));
    assertThat(quiet.margin()).as("floor").isEqualByComparingTo("0.10");

    NoiseModel all = NoiseModel.fromCanary(samples, EnumSet.allOf(Resource.class));
    assertThat(all.margin()).isEqualByComparingTo("0.60");

    NoiseModel nothingDeclared = NoiseModel.fromCanary(samples, EnumSet.noneOf(Resource.class));
    assertThat(nothingDeclared.margin()).isEqualByComparingTo("0.10");
  }

  @Test
  void aDeclaredResourceWithFewerThanFiveSamplesIsMissing() {
    Map<Resource, double[]> samples =
        Map.of(Resource.CPU, series(100, 110), Resource.MEMORY, new double[] {100, 101, 102, 103});
    NoiseModel model = NoiseModel.fromCanary(samples, EnumSet.of(Resource.CPU, Resource.MEMORY));
    assertThat(model.missing()).containsExactly(Resource.MEMORY);
    NoiseModel absent = NoiseModel.fromCanary(samples, EnumSet.of(Resource.CPU, Resource.DISK));
    assertThat(absent.missing()).containsExactly(Resource.DISK);
    NoiseModel undeclaredIsIgnored = NoiseModel.fromCanary(samples, EnumSet.of(Resource.CPU));
    assertThat(undeclaredIsIgnored.missing()).isEmpty();
  }

  @Test
  void anUnreadableCanaryRateCountsAsMissing() {
    Map<Resource, double[]> samples = Map.of(Resource.CPU, new double[] {0, 100, 100, 100, 100});
    NoiseModel model = NoiseModel.fromCanary(samples, EnumSet.of(Resource.CPU));
    assertThat(model.missing()).containsExactly(Resource.CPU);
  }

  @Test
  void noneHasNoMissingResourcesAndTheFloorMargin() {
    NoiseModel none = NoiseModel.none();
    assertThat(none.missing()).isEmpty();
    assertThat(none.margin()).isEqualByComparingTo("0.10");
  }
}
