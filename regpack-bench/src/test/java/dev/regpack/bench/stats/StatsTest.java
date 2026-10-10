package dev.regpack.bench.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.bench.data.SplitMix64;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StatsTest {
  private static double[] range(int from, int toInclusive) {
    double[] v = new double[toInclusive - from + 1];
    for (int i = 0; i < v.length; i++) {
      v[i] = from + i;
    }
    return v;
  }

  @Test
  void percentileNearestRank() {
    double[] v = {50, 20, 15, 40, 35}; // unsorted on purpose: the method sorts a copy
    assertThat(Stats.percentile(v, 30)).isEqualTo(20);
    assertThat(Stats.percentile(v, 40)).isEqualTo(20);
    assertThat(Stats.percentile(v, 50)).isEqualTo(35);
    assertThat(Stats.percentile(v, 100)).isEqualTo(50);
    assertThat(v).containsExactly(50, 20, 15, 40, 35);
  }

  @Test
  void percentileRejectsBadInput() {
    assertThatThrownBy(() -> Stats.percentile(new double[0], 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stats.percentile(new double[] {1}, 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stats.percentile(new double[] {1, Double.NaN}, 50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void medianOfEvenAndOddCounts() {
    assertThat(Stats.median(new double[] {3, 1, 2})).isEqualTo(2);
    assertThat(Stats.median(new double[] {4, 1, 3, 2})).isEqualTo(2.5);
  }

  @Test
  void lowerBoundRankMatchesTheExactBinomial() {
    assertThat(Stats.lowerBoundRank(4)).isEqualTo(0);
    assertThat(Stats.lowerBoundRank(5)).isEqualTo(1);
    assertThat(Stats.lowerBoundRank(6)).isEqualTo(1);
    assertThat(Stats.lowerBoundRank(7)).isEqualTo(1);
    assertThat(Stats.lowerBoundRank(8)).isEqualTo(2);
    assertThat(Stats.lowerBoundRank(9)).isEqualTo(2);
    assertThat(Stats.lowerBoundRank(10)).isEqualTo(2);
    assertThat(Stats.lowerBoundRank(11)).isEqualTo(3);
    assertThat(Stats.lowerBoundRank(15)).isEqualTo(4);
    assertThat(Stats.lowerBoundRank(20)).isEqualTo(6);
    assertThat(Stats.lowerBoundRank(0)).isEqualTo(0);
  }

  @Test
  void medianBoundsN15() {
    Estimate e = Stats.medianWithBounds(range(1, 15));
    assertThat(e.point()).isEqualByComparingTo("8");
    assertThat(e.lower()).isEqualByComparingTo("4");
    assertThat(e.upper()).isEqualByComparingTo("12");
    assertThat(e.n()).isEqualTo(15);
  }

  @Test
  void medianBoundsN10() {
    Estimate e = Stats.medianWithBounds(range(1, 10));
    assertThat(e.point()).isEqualByComparingTo("5.5");
    assertThat(e.lower()).isEqualByComparingTo("2");
    assertThat(e.upper()).isEqualByComparingTo("9");
  }

  @Test
  void medianBoundsN7() {
    Estimate e = Stats.medianWithBounds(range(1, 7));
    assertThat(e.lower()).isEqualByComparingTo("1");
    assertThat(e.upper()).isEqualByComparingTo("7");
  }

  @Test
  void medianBoundsUseTheOrderStatisticsOfUnsortedInput() {
    double[] v = {9, 3, 15, 1, 7, 12, 5, 11, 2, 14, 6, 10, 4, 13, 8};
    Estimate e = Stats.medianWithBounds(v);
    assertThat(e.lower()).isEqualByComparingTo("4");
    assertThat(e.upper()).isEqualByComparingTo("12");
    assertThat(v[0]).isEqualTo(9);
  }

  @Test
  void medianBoundsRejectFewerThanFive() {
    assertThatThrownBy(() -> Stats.medianWithBounds(range(1, 4)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void estimatesAreRoundedToNineDecimals() {
    Estimate e = Stats.medianWithBounds(new double[] {0.1, 0.2, 0.3, 0.4, 0.5});
    assertThat(e.point()).isEqualTo(new BigDecimal("0.300000000"));
    assertThat(e.method()).isNotBlank();
  }

  @Test
  void spreadUsesPercentilesFromTwentySamples() {
    assertThat(Stats.spread(range(100, 109))).isEqualTo(0.09, within(1e-12));
    assertThat(Stats.spread(range(100, 124))).isEqualTo(123.0 / 101.0 - 1, within(1e-4));
    assertThat(Stats.spread(range(100, 124))).isEqualTo(0.2178, within(1e-4));
  }

  @Test
  void spreadRejectsEmptyAndNonPositiveSamples() {
    assertThatThrownBy(() -> Stats.spread(new double[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stats.spread(new double[] {0, 1}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theilSenRecoversAKnownSlope() {
    double[] x = range(0, 19);
    double[] y = new double[20];
    for (int i = 0; i < 20; i++) {
      y[i] = 3 * x[i] + 2;
    }
    SlopeEstimate s = Stats.theilSen(x, y);
    assertThat(s.slope()).isEqualByComparingTo("3");
    assertThat(s.lower()).isEqualByComparingTo("3");
    assertThat(s.upper()).isEqualByComparingTo("3");
    assertThat(s.n()).isEqualTo(20);
  }

  @Test
  void theilSenIntervalCoversANoisyLine() {
    SplitMix64 rng = new SplitMix64(11);
    double[] x = new double[100];
    double[] y = new double[100];
    for (int i = 0; i < 100; i++) {
      x[i] = i;
      y[i] = 0.5 * i + (rng.nextUnit() * 2 - 1);
    }
    SlopeEstimate s = Stats.theilSen(x, y);
    assertThat(s.lower()).isLessThanOrEqualTo(new BigDecimal("0.5"));
    assertThat(s.upper()).isGreaterThanOrEqualTo(new BigDecimal("0.5"));
    assertThat(s.upper().subtract(s.lower())).isLessThan(new BigDecimal("0.05"));
    assertThat(s.slope()).isBetween(s.lower(), s.upper());
  }

  @Test
  void theilSenWithThreePointsClampsToMinMaxSlope() {
    // pairwise slopes: (0,1) -> 1, (0,2) -> 2, (1,2) -> 3
    double[] x = {0, 1, 2};
    double[] y = {0, 1, 4};
    SlopeEstimate s = Stats.theilSen(x, y);
    assertThat(s.slope()).isEqualByComparingTo("2");
    assertThat(s.lower()).isEqualByComparingTo("1");
    assertThat(s.upper()).isEqualByComparingTo("3");
  }

  @Test
  void theilSenWithFourPointsAlsoUsesMinAndMax() {
    double[] x = {0, 1, 2, 3};
    double[] y = {0, 1, 4, 5};
    // slopes: 1, 2, 5/3, 3, 4/2, 1 -> min 1, max 3
    SlopeEstimate s = Stats.theilSen(x, y);
    assertThat(s.lower()).isEqualByComparingTo("1");
    assertThat(s.upper()).isEqualByComparingTo("3");
  }

  @Test
  void theilSenSkipsPairsWithEqualX() {
    double[] x = {1, 1, 2, 3};
    double[] y = {0, 100, 2, 3};
    SlopeEstimate s = Stats.theilSen(x, y);
    assertThat(s.lower()).isLessThanOrEqualTo(s.slope());
    assertThat(s.upper()).isGreaterThanOrEqualTo(s.slope());
  }

  @Test
  void theilSenRejectsBadInput() {
    assertThatThrownBy(() -> Stats.theilSen(new double[] {1, 2}, new double[] {1, 2}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stats.theilSen(new double[] {1, 2, 3}, new double[] {1, 2}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stats.theilSen(new double[] {1, 1, 1}, new double[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void binomialTailKnownValue() {
    assertThat(Stats.binomialTailAtLeast(10, 3, 0.1)).isEqualTo(0.0701908264, within(1e-9));
  }

  @Test
  void binomialTailEdges() {
    assertThat(Stats.binomialTailAtLeast(10, 0, 0.3)).isEqualTo(1.0);
    assertThat(Stats.binomialTailAtLeast(10, 11, 0.3)).isEqualTo(0.0);
    assertThat(Stats.binomialTailAtLeast(10, 10, 0.5)).isEqualTo(1.0 / 1024, within(1e-15));
    assertThat(Stats.binomialTailAtLeast(10, 1, 0.0)).isEqualTo(0.0);
    assertThat(Stats.binomialTailAtLeast(10, 10, 1.0)).isEqualTo(1.0);
  }

  @Test
  void binomialTailAgreesWithTheMedianBoundRank() {
    // lowerBoundRank(15) = 4: P(X <= 3) with p = 1/2 is at most 5%, and P(X <= 4) is above it
    double lowerTail = 1.0 - Stats.binomialTailAtLeast(15, 4, 0.5);
    assertThat(lowerTail).isLessThanOrEqualTo(0.05);
    double nextTail = 1.0 - Stats.binomialTailAtLeast(15, 5, 0.5);
    assertThat(nextTail).isGreaterThan(0.05);
  }
}
