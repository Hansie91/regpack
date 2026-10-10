package dev.regpack.bench.eval;

import dev.regpack.bench.stats.Stats;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The noise of the runner as seen by the canary probes: one margin for the whole run, and the
 * declared resources for which no usable canary exists.
 *
 * @param margin the relative margin, at least zero
 * @param missing declared resources without a usable canary series
 */
public record NoiseModel(BigDecimal margin, Set<Resource> missing) {
  /** The smallest margin of any run. */
  public static final BigDecimal FLOOR = new BigDecimal("0.10");

  /** The fewest canary samples of a resource that can be judged. */
  public static final int MIN_SAMPLES = 5;

  public NoiseModel {
    Objects.requireNonNull(margin, "margin");
    if (margin.signum() < 0) {
      throw new IllegalArgumentException("margin must not be negative: " + margin);
    }
    missing = Set.copyOf(missing);
  }

  /** No canary information: the floor margin and nothing missing. */
  public static NoiseModel none() {
    return new NoiseModel(FLOOR, Set.of());
  }

  /**
   * The margin is the largest {@link Stats#spread} over the declared resources, at least {@link
   * #FLOOR}. A declared resource with fewer than {@link #MIN_SAMPLES} samples, none at all, or a
   * series that cannot be judged (a rate that is not positive) is listed in {@code missing}.
   */
  public static NoiseModel fromCanary(Map<Resource, double[]> samples, Set<Resource> declared) {
    double worst = 0;
    Set<Resource> missing = EnumSet.noneOf(Resource.class);
    for (Resource resource : declared) {
      double[] series = samples.get(resource);
      if (series == null || series.length < MIN_SAMPLES) {
        missing.add(resource);
        continue;
      }
      try {
        worst = Math.max(worst, Stats.spread(series));
      } catch (IllegalArgumentException e) {
        missing.add(resource);
      }
    }
    BigDecimal margin = Stats.decimal(worst).max(FLOOR);
    return new NoiseModel(margin, missing);
  }
}
