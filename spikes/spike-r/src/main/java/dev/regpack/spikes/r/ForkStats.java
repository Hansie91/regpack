package dev.regpack.spikes.r;

import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.proto.ForkResult;
import dev.regpack.bench.proto.ForkSeries;
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
