package dev.regpack.bench.stats;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * Order statistics, a robust slope and a binomial tail, all deterministic: no wall clock,
 * randomness or default-locale API, and {@link StrictMath} for every transcendental function.
 */
public final class Stats {
  /** Decimal places of every {@link BigDecimal} that leaves this class. */
  public static final int SCALE = 9;

  /** The one-sided normal quantile for 95%. */
  private static final double Z_95 = 1.645;

  /** Largest input of {@link #theilSen}: its pair array grows with the square of the count. */
  private static final int MAX_SLOPE_POINTS = 4_000;

  private Stats() {}

  /** A finite double as a decimal of scale 9, rounded half-even. */
  public static BigDecimal decimal(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("not a finite number: " + value);
    }
    return new BigDecimal(Double.toString(value)).setScale(SCALE, RoundingMode.HALF_EVEN);
  }

  private static double[] sortedCopy(double[] v) {
    if (v.length == 0) {
      throw new IllegalArgumentException("no values");
    }
    double[] copy = v.clone();
    for (double d : copy) {
      if (!Double.isFinite(d)) {
        throw new IllegalArgumentException("not a finite number: " + d);
      }
    }
    Arrays.sort(copy);
    return copy;
  }

  /**
   * The nearest-rank percentile: the value of rank {@code ceil(percent / 100 * n)} (at least 1).
   */
  public static double percentile(double[] v, int percent) {
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("percent must be in [0, 100]: " + percent);
    }
    double[] sorted = sortedCopy(v);
    int rank = (int) Math.max(1L, ((long) percent * sorted.length + 99) / 100);
    return sorted[rank - 1];
  }

  /** The median; for an even count the mean of the two middle values. */
  public static double median(double[] v) {
    return medianOfSorted(sortedCopy(v));
  }

  private static double medianOfSorted(double[] sorted) {
    int n = sorted.length;
    return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
  }

  /**
   * The largest {@code l >= 1} with {@code 20 * sum_{i<l} C(n,i) <= 2^n}, computed exactly in
   * integers; 0 when there is none (every {@code n < 5}). The {@code l}-th smallest and the {@code
   * (n+1-l)}-th smallest of {@code n} observations then bound the median with at least 95%
   * one-sided (90% two-sided) confidence, whatever the distribution.
   */
  public static int lowerBoundRank(int n) {
    if (n < 5) {
      return 0;
    }
    BigInteger limit = BigInteger.ONE.shiftLeft(n);
    BigInteger twenty = BigInteger.valueOf(20);
    BigInteger sum = BigInteger.ZERO;
    BigInteger choose = BigInteger.ONE; // C(n, i)
    int rank = 0;
    for (int i = 0; i < n; i++) {
      sum = sum.add(choose);
      if (sum.multiply(twenty).compareTo(limit) > 0) {
        break;
      }
      rank = i + 1;
      choose = choose.multiply(BigInteger.valueOf(n - i)).divide(BigInteger.valueOf(i + 1L));
    }
    return rank;
  }

  /** The median with the exact order-statistic bounds of {@link #lowerBoundRank}. */
  public static Estimate medianWithBounds(double[] v) {
    int n = v.length;
    if (n < 5) {
      throw new IllegalArgumentException("at least 5 values are needed for a median bound: " + n);
    }
    double[] sorted = sortedCopy(v);
    int l = lowerBoundRank(n);
    return new Estimate(
        decimal(medianOfSorted(sorted)),
        decimal(sorted[l - 1]),
        decimal(sorted[n - l]),
        n,
        "median-order-statistics-95-one-sided");
  }

  /**
   * The relative spread of a canary series: {@code p95 / p05 - 1} (nearest rank) from 20 samples
   * on, {@code max / min - 1} below that. The smallest sample must be positive.
   */
  public static double spread(double[] canary) {
    double[] sorted = sortedCopy(canary);
    double low;
    double high;
    if (sorted.length >= 20) {
      low = percentile(sorted, 5);
      high = percentile(sorted, 95);
    } else {
      low = sorted[0];
      high = sorted[sorted.length - 1];
    }
    if (low <= 0) {
      throw new IllegalArgumentException("canary rates must be positive: " + low);
    }
    return high / low - 1.0;
  }

  /**
   * The Theil-Sen slope: the median of all pairwise slopes (pairs with equal x are skipped), with
   * the interval of the Kendall tau rank test at one-sided 95%: {@code C = z * sqrt(n (n-1) (2n+5)
   * / 18)}, {@code N} pairs, and the pairwise slopes of rank {@code floor((N - C) / 2)} and {@code
   * floor((N + C) / 2) + 1}, both clamped to {@code [1, N]}. For 3 and 4 points the bounds are the
   * smallest and the largest pairwise slope, which is conservative.
   */
  public static SlopeEstimate theilSen(double[] x, double[] y) {
    int n = x.length;
    if (y.length != n) {
      throw new IllegalArgumentException("x and y differ in length: " + n + " and " + y.length);
    }
    if (n < 3) {
      throw new IllegalArgumentException("at least 3 points are needed for a slope: " + n);
    }
    if (n > MAX_SLOPE_POINTS) {
      throw new IllegalArgumentException("at most " + MAX_SLOPE_POINTS + " points: " + n);
    }
    for (int i = 0; i < n; i++) {
      if (!Double.isFinite(x[i]) || !Double.isFinite(y[i])) {
        throw new IllegalArgumentException("not a finite number at index " + i);
      }
    }
    double[] slopes = new double[n * (n - 1) / 2];
    int pairs = 0;
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        if (x[i] != x[j]) {
          slopes[pairs++] = (y[j] - y[i]) / (x[j] - x[i]);
        }
      }
    }
    if (pairs == 0) {
      throw new IllegalArgumentException("all x are equal: no slope");
    }
    double[] sorted = Arrays.copyOf(slopes, pairs);
    Arrays.sort(sorted);
    double c = Z_95 * StrictMath.sqrt((double) n * (n - 1) * (2.0 * n + 5.0) / 18.0);
    int lowRank = clamp((long) StrictMath.floor((pairs - c) / 2.0), pairs);
    int highRank = clamp((long) StrictMath.floor((pairs + c) / 2.0) + 1, pairs);
    return new SlopeEstimate(
        decimal(medianOfSorted(sorted)),
        decimal(sorted[lowRank - 1]),
        decimal(sorted[highRank - 1]),
        n);
  }

  private static int clamp(long rank, int pairs) {
    return (int) Math.min(Math.max(rank, 1L), pairs);
  }

  /** {@code P(X >= k)} for {@code X ~ Binomial(n, p)}, summed in log space with StrictMath. */
  public static double binomialTailAtLeast(int n, int k, double p) {
    if (n < 0) {
      throw new IllegalArgumentException("n must not be negative: " + n);
    }
    if (!(p >= 0.0 && p <= 1.0)) {
      throw new IllegalArgumentException("p must be in [0, 1]: " + p);
    }
    if (k <= 0) {
      return 1.0;
    }
    if (k > n) {
      return 0.0;
    }
    if (p == 0.0) {
      return 0.0;
    }
    if (p == 1.0) {
      return 1.0;
    }
    double[] logFactorial = new double[n + 1];
    for (int i = 2; i <= n; i++) {
      logFactorial[i] = logFactorial[i - 1] + StrictMath.log(i);
    }
    double logP = StrictMath.log(p);
    double logQ = StrictMath.log1p(-p);
    double sum = 0.0;
    for (int i = k; i <= n; i++) {
      double logTerm =
          logFactorial[n] - logFactorial[i] - logFactorial[n - i] + i * logP + (n - i) * logQ;
      sum += StrictMath.exp(logTerm);
    }
    return Math.min(1.0, sum);
  }
}
