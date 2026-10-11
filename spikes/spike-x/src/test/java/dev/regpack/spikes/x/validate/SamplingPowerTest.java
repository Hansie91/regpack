package dev.regpack.spikes.x.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.spikes.x.validate.PolicyModel.Policy;
import org.junit.jupiter.api.Test;

class SamplingPowerTest {
  /** A file of 1,000 records holding {@code d} defects is caught when one of them is sampled. */
  private static double detectionFrequency(Policy policy, int defects, int trials) {
    SplitMix64 rng = new SplitMix64(5L);
    int caught = 0;
    for (int trial = 0; trial < trials; trial++) {
      boolean detected = false;
      for (int d = 0; d < defects; d++) {
        // every defective record is one independent sampling decision, in file order
        if (policy.sample(rng)) {
          detected = true;
        }
      }
      if (detected) {
        caught++;
      }
    }
    return caught / (double) trials;
  }

  @Test
  void detectionProbabilityMatchesBinomial() {
    for (int defects : new int[] {1, 5, 20}) {
      double expected = 1 - Math.pow(1 - 0.1, defects);
      double observed = detectionFrequency(PolicyModel.SAMPLED_10, defects, 20_000);
      assertThat(observed * 100)
          .as("detection frequency in percent with %d defects", defects)
          .isBetween(expected * 100 - 1.5, expected * 100 + 1.5);
    }
  }

  @Test
  void theFullPolicyDetectsEverythingAndTheEmptyOneNothing() {
    assertThat(detectionFrequency(PolicyModel.ALL, 1, 2_000)).isEqualTo(1.0);
    assertThat(detectionFrequency(new Policy("NONE", 0.0), 20, 2_000)).isZero();
  }

  @Test
  void theSampledFractionOfRecordsMatchesTheFraction() {
    SplitMix64 rng = new SplitMix64(9L);
    int sampled = 0;
    int records = 100_000;
    for (int i = 0; i < records; i++) {
      if (PolicyModel.SAMPLED_10.sample(rng)) {
        sampled++;
      }
    }
    assertThat(sampled / (double) records).isBetween(0.095, 0.105);
  }

  @Test
  void aFractionOutsideZeroToOneIsRejected() {
    assertThatThrownBy(() -> new Policy("x", 1.01)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Policy("x", -0.01)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Policy("x", Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
