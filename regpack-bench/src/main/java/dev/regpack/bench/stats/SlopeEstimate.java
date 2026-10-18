package dev.regpack.bench.stats;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A robust slope with a confidence interval, as exact decimals (scale 9).
 *
 * @param slope the median pairwise slope
 * @param lower the lower bound of the interval
 * @param upper the upper bound of the interval
 * @param n the number of points behind it
 */
public record SlopeEstimate(BigDecimal slope, BigDecimal lower, BigDecimal upper, int n) {
  public SlopeEstimate {
    Objects.requireNonNull(slope, "slope");
    Objects.requireNonNull(lower, "lower");
    Objects.requireNonNull(upper, "upper");
  }
}
