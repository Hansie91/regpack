package dev.regpack.bench.stats;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A point estimate with a confidence interval, as exact decimals (scale 9) so that it can be
 * written to a result file, which holds no {@code double}.
 *
 * @param point the estimate
 * @param lower the lower bound of the interval
 * @param upper the upper bound of the interval
 * @param n the number of observations behind it
 * @param method a short label for how the interval was built
 */
public record Estimate(BigDecimal point, BigDecimal lower, BigDecimal upper, int n, String method) {
  public Estimate {
    Objects.requireNonNull(point, "point");
    Objects.requireNonNull(lower, "lower");
    Objects.requireNonNull(upper, "upper");
    Objects.requireNonNull(method, "method");
  }
}
