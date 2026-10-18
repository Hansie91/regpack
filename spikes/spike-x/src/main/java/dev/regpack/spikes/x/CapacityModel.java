package dev.regpack.spikes.x;

import dev.regpack.bench.stats.Estimate;
import dev.regpack.spikes.x.validate.PolicyModel;
import dev.regpack.spikes.x.validate.PolicyModel.Component;
import dev.regpack.spikes.x.validate.PolicyModel.Policy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Criteria X-2 and X-3: the policy model of {@link PolicyModel} applied to the measured components.
 * X-2 takes the lower bounds of the component estimates (the pessimistic reading: the lower bound
 * of every rate, the upper bound of the bisect seconds), X-3 the point estimates (the medians).
 * Everything here is arithmetic on the estimates; no clock, no randomness.
 */
final class CapacityModel {
  /** The defect rates of X-2: none, 0.1% and 1%. */
  static final double[] DEFECT_RATES = {0.0, 0.001, 0.01};

  /** X-3: the largest relative deviation of the model from the integrated ALL run. */
  static final double MAX_DEVIATION = 0.15;

  private CapacityModel() {}

  /**
   * The per-thread component estimates.
   *
   * @param writer records per second, one thread, the StAX writer
   * @param xsd records per second, one thread, the per-record XSD validation
   * @param gate records per second, one thread, the whole-file gate over a file of the plan's size
   * @param bisectPerSecond bisects per second, one thread, each finding one defect in a file of the
   *     plan's size (the reciprocal is the seconds per defect); empty when it was not measured
   */
  record Rates(Estimate writer, Estimate xsd, Estimate gate, Optional<Estimate> bisectPerSecond) {}

  /** The capacity of one policy at one defect rate. */
  record Capacity(String policy, double defectRate, double lowerBound, double point) {}

  /**
   * The capacities of the policy at the three defect rates; empty when the policy needs the bisect
   * cost (it samples) and the bisect was not measured.
   */
  static Optional<List<Capacity>> capacities(Rates rates, int threads, Policy policy) {
    Optional<Component> lower = component(rates, threads, policy, true);
    Optional<Component> point = component(rates, threads, policy, false);
    if (lower.isEmpty() || point.isEmpty()) {
      return Optional.empty();
    }
    List<Capacity> out = new ArrayList<>();
    for (double rate : DEFECT_RATES) {
      out.add(
          new Capacity(
              policy.name(),
              rate,
              PolicyModel.capacity(lower.get(), policy, rate),
              PolicyModel.capacity(point.get(), policy, rate)));
    }
    return Optional.of(List.copyOf(out));
  }

  private static Optional<Component> component(
      Rates rates, int threads, Policy policy, boolean lowerBounds) {
    double bisectSeconds;
    if (rates.bisectPerSecond().isPresent()) {
      Estimate e = rates.bisectPerSecond().get();
      double perSecond = (lowerBounds ? e.lower() : e.point()).doubleValue();
      bisectSeconds = 1.0 / perSecond;
    } else if (policy.fraction() >= 1.0) {
      bisectSeconds = 0.0; // every defect is caught per record: the term has the factor (1 - s) = 0
    } else {
      return Optional.empty();
    }
    return Optional.of(
        new Component(
            pick(rates.writer(), lowerBounds),
            pick(rates.xsd(), lowerBounds),
            pick(rates.gate(), lowerBounds),
            bisectSeconds,
            threads));
  }

  private static double pick(Estimate e, boolean lowerBounds) {
    return (lowerBounds ? e.lower() : e.point()).doubleValue();
  }

  /** The smallest lower-bound capacity over the defect rates, in whole records per second. */
  static long minimumLowerBound(List<Capacity> capacities) {
    double min = Double.POSITIVE_INFINITY;
    for (Capacity c : capacities) {
      min = Math.min(min, c.lowerBound());
    }
    return (long) Math.floor(min);
  }

  /**
   * X-3: 1 when the model on the point estimates at defect rate 0 deviates from the integrated run
   * by more than {@link #MAX_DEVIATION}, else 0.
   */
  static long deviationExceeds(List<Capacity> all, double integratedPoint) {
    return Math.abs(deviation(all, integratedPoint)) > MAX_DEVIATION ? 1 : 0;
  }

  /** The signed relative deviation of the model at defect rate 0 from the integrated run. */
  static double deviation(List<Capacity> all, double integratedPoint) {
    Capacity zero = all.stream().filter(c -> c.defectRate() == 0.0).findFirst().orElseThrow();
    return PolicyModel.relDeviation(zero.point(), integratedPoint);
  }
}
