package dev.regpack.spikes.r.defaults;

import java.util.Arrays;

/**
 * The poison-circuit defaults (spec 7.3): a rule whose evaluation fails or exhausts its budget N
 * times within M evaluations opens its circuit. The model is MODELLED only: independent noise,
 * tumbling windows of M evaluations of one rule, so
 *
 * <ul>
 *   <li>the detection probability of a rule that errors on a fraction {@code d} of the records
 *       reaching it is {@code P(Bin(M, d) >= N)} per window;
 *   <li>the false opens per day and rule at a background rate {@code b} are {@code (recordsPerDay /
 *       M) x P(Bin(M, b) >= N)}.
 * </ul>
 *
 * {@link #choose} takes the smallest M and then the smallest N of the grids that detect with at
 * least the given probability and stay at or under the false-open limit. The clustered scenario
 * (one burst of consecutive defects) is a separate, informational figure. Arithmetic is {@link
 * StrictMath}, so the numbers are the same on every platform.
 */
public final class PoisonDefaults {
  /** The chosen pair with the two figures that justify it. */
  public record Choice(int n, int m, double falseOpenPerDay, double detectionProbability) {}

  private PoisonDefaults() {}

  /**
   * The smallest M, then the smallest N, that satisfy both constraints.
   *
   * @throws IllegalStateException if no pair of the grids qualifies
   */
  public static Choice choose(
      long recordsPerDay,
      double backgroundRate,
      double defectiveRate,
      double minDetection,
      double maxFalseOpenPerDay,
      int[] nGrid,
      int[] mGrid) {
    int[] ns = nGrid.clone();
    int[] ms = mGrid.clone();
    Arrays.sort(ns);
    Arrays.sort(ms);
    for (int m : ms) {
      for (int n : ns) {
        if (n > m) {
          continue;
        }
        double detection = detectionProbability(m, n, defectiveRate);
        double falseOpen = falseOpenPerDay(recordsPerDay, m, n, backgroundRate);
        if (detection >= minDetection && falseOpen <= maxFalseOpenPerDay) {
          return new Choice(n, m, falseOpen, detection);
        }
      }
    }
    throw new IllegalStateException("no (N, M) of the grids detects and stays under the limit");
  }

  /** The probability that a rule that errors on {@code defectiveRate} of its records opens. */
  public static double detectionProbability(int m, int n, double defectiveRate) {
    return windowOpenProbability(m, defectiveRate, n);
  }

  /** The expected false opens per day of one rule under independent background noise. */
  public static double falseOpenPerDay(long recordsPerDay, int m, int n, double backgroundRate) {
    return recordsPerDay / (double) m * windowOpenProbability(m, backgroundRate, n);
  }

  /**
   * The expected opens per day when one burst of {@code burstRecords} consecutive records errors at
   * {@code burstRate} and every other record at the background rate. Informational: it is not a
   * constraint of {@link #choose}, and a burst of real defects should open the circuit.
   */
  public static double clusteredFalseOpenPerDay(
      long recordsPerDay,
      double backgroundRate,
      long burstRecords,
      double burstRate,
      int n,
      int m) {
    if (burstRecords < 0 || burstRecords > recordsPerDay) {
      throw new IllegalArgumentException("burstRecords must be within the day: " + burstRecords);
    }
    return (recordsPerDay - burstRecords) / (double) m * windowOpenProbability(m, backgroundRate, n)
        + burstRecords / (double) m * windowOpenProbability(m, burstRate, n);
  }

  /** {@code P(Bin(m, p) >= n)}, computed in the tail that does not cancel. */
  public static double windowOpenProbability(int m, double p, int n) {
    if (m < 1) {
      throw new IllegalArgumentException("m must be at least 1: " + m);
    }
    if (!(p >= 0.0 && p <= 1.0)) {
      throw new IllegalArgumentException("p must be within [0, 1]: " + p);
    }
    if (n < 0) {
      throw new IllegalArgumentException("n must not be negative: " + n);
    }
    if (n == 0) {
      return 1.0;
    }
    if (n > m || p == 0.0) {
      return 0.0;
    }
    if (p == 1.0) {
      return 1.0;
    }
    double q = 1.0 - p;
    double ratio = p / q;
    if (n > m * p) {
      // the mean is below n: the upper tail is small, sum it directly from its largest term
      double term = StrictMath.pow(q, m - n);
      for (int i = 0; i < n; i++) {
        term *= (double) (m - i) / (i + 1) * p;
      }
      double sum = term;
      for (int k = n; k < m; k++) {
        term *= (double) (m - k) / (k + 1) * ratio;
        sum += term;
        if (term < sum * 1e-17) {
          break;
        }
      }
      return Math.min(1.0, sum);
    }
    // the mean is at or above n: the upper tail is large, take one minus the short lower tail
    double term = StrictMath.pow(q, m);
    double lower = term;
    for (int k = 0; k < n - 1; k++) {
      term *= (double) (m - k) / (k + 1) * ratio;
      lower += term;
    }
    return Math.max(0.0, 1.0 - lower);
  }
}
