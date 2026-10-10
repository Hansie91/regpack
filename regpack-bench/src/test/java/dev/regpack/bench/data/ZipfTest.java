package dev.regpack.bench.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ZipfTest {
  @Test
  void cdfIsMonotoneAndEndsAtExactlyOne() {
    for (double s : new double[] {0.0, 0.9, 1.0, 2.0}) {
      Zipf zipf = new Zipf(1000, s);
      double previous = 0.0;
      for (int i = 0; i < 1000; i++) {
        double value = zipf.cdfAt(i);
        assertThat(value).isGreaterThan(previous);
        previous = value;
      }
      assertThat(zipf.cdfAt(999)).isEqualTo(1.0);
    }
    assertThat(new Zipf(1, 1.0).cdfAt(0)).isEqualTo(1.0);
  }

  @Test
  void samplesStayInRange() {
    Zipf zipf = new Zipf(7, 1.0);
    SplitMix64 rng = new SplitMix64(2);
    for (int i = 0; i < 10_000; i++) {
      assertThat(zipf.sample(rng)).isBetween(0, 6);
    }
  }

  @Test
  void frequenciesFollowRankOrder() {
    Zipf zipf = new Zipf(100, 1.0);
    SplitMix64 rng = new SplitMix64(5);
    int[] counts = new int[100];
    for (int i = 0; i < 200_000; i++) {
      counts[zipf.sample(rng)]++;
    }
    assertThat(counts[0]).isGreaterThan(counts[1]);
    assertThat(counts[1]).isGreaterThan(counts[9]);
    assertThat(counts[9]).isGreaterThan(counts[99]);
  }
}
