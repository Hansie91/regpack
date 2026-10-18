package dev.regpack.spikes.x;

import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.proto.ForkResult;
import dev.regpack.bench.proto.ForkSeries;
import dev.regpack.bench.proto.Protocol;
import dev.regpack.bench.stats.Estimate;
import dev.regpack.bench.stats.Stats;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Turns the forks of a series into what the measurement set holds. */
final class ForkStats {
  private ForkStats() {}

  /**
   * One statistic per fork that was started, discarded ones included. A fork that produced no
   * window (a harness error) has the statistic 0 and keeps its discard reason.
   */
  static List<ForkStat> of(ForkSeries series) {
    List<ForkStat> out = new ArrayList<>();
    for (ForkResult f : series.forks()) {
      BigDecimal statistic =
          f.windowRates().isEmpty() ? BigDecimal.ZERO.setScale(Stats.SCALE) : decimal(f);
      out.add(new ForkStat(f.index(), statistic, f.discarded(), f.discardReason(), f.unstable()));
    }
    return out;
  }

  private static BigDecimal decimal(ForkResult f) {
    return Stats.decimal(f.statistic());
  }

  /** The median of the statistics of the forks that count; empty when none counts. */
  static Optional<BigDecimal> median(ForkSeries series) {
    List<ForkResult> kept = series.kept();
    if (kept.isEmpty()) {
      return Optional.empty();
    }
    double[] values = kept.stream().mapToDouble(ForkResult::statistic).toArray();
    return Optional.of(Stats.decimal(Stats.median(values)));
  }

  /**
   * The fewest kept forks from which the model may use an estimate: the fork count under which a
   * series can never give a verdict (P-6), taken from the protocol of the component series.
   */
  static final int MIN_KEPT_FOR_ESTIMATE = Protocol.informative().minForksForVerdict();

  /**
   * The median of the kept forks times {@code scale}, with its exact order-statistic bounds; empty
   * when fewer than {@link #MIN_KEPT_FOR_ESTIMATE} forks were kept, because fewer can never give a
   * verdict.
   */
  static Optional<Estimate> estimate(ForkSeries series, BigDecimal scale) {
    List<ForkResult> kept = series.kept();
    if (kept.size() < MIN_KEPT_FOR_ESTIMATE) {
      return Optional.empty();
    }
    double factor = scale.doubleValue();
    double[] values = kept.stream().mapToDouble(f -> f.statistic() * factor).toArray();
    return Optional.of(Stats.medianWithBounds(values));
  }

  /** Appends the canary readings of every fork (a discarded fork still read its canary). */
  static void addCanary(ForkSeries series, Map<Resource, List<BigDecimal>> into) {
    for (ForkResult f : series.forks()) {
      append(into, Resource.CPU, f.canaryCpu());
      append(into, Resource.MEMORY, f.canaryMemory());
      append(into, Resource.DISK, f.canaryDisk());
    }
  }

  private static void append(
      Map<Resource, List<BigDecimal>> into, Resource resource, List<Double> readings) {
    List<BigDecimal> list = into.computeIfAbsent(resource, r -> new ArrayList<>());
    for (double reading : readings) {
      list.add(Stats.decimal(reading));
    }
  }
}
