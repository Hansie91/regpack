package dev.regpack.spikes.x;

import dev.regpack.bench.proto.Protocol;
import dev.regpack.spikes.x.SpXRunner.Plan;
import dev.regpack.spikes.x.SpXRunner.Role;
import dev.regpack.spikes.x.SpXRunner.Sizes;
import dev.regpack.spikes.x.SpXRunner.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The projected duration of the full run, from what a smoke run measured. The steps that run in
 * windows (the writer and the per-record validation) cost what the full protocol spends per fork:
 * the fixed cost of a JVM, six canary probes, the warm-up and the measured windows; the warm-up is
 * not known in advance, so the typical figure assumes it ends after five windows and the worst case
 * lets it run to its limit. The steps that run in whole cycles (the file gate, the integrated
 * stage, the bisect, the latency) cost their data preparation plus four cycles (one untimed, three
 * timed) at the rate the smoke run measured; a smoke JVM is colder than a full one, so these are on
 * the high side. A step whose smoke rate is missing is projected at an assumed rate of {@value
 * #ASSUMED_RATE} units per second and is named in the result.
 */
final class Projection {
  private static final int TYPICAL_WARMUP_WINDOWS = 5;
  private static final int CYCLES = 4;
  private static final int RING_EXTRA = 997;
  private static final int LATENCY_WARM_RECORDS = 2_000;
  static final double ASSUMED_RATE = 1_000.0;

  private Projection() {}

  /**
   * What the smoke run measured.
   *
   * @param fixedSeconds the fixed cost of one fork JVM
   * @param genSecondsPerRecord the seconds to generate and write one record
   * @param rates step id to the median smoke rate: units per second ({@code records/s}, or bisects
   *     per second for the bisect step)
   * @param latencySecondsPerRecord the seconds per record of the single-thread stage under ALL
   * @param factsSeconds the seconds the in-process facts step took in the smoke run
   */
  record Observed(
      double fixedSeconds,
      double genSecondsPerRecord,
      Map<String, Double> rates,
      double latencySecondsPerRecord,
      double factsSeconds) {
    Observed {
      rates = Map.copyOf(rates);
    }
  }

  /** The projection. */
  record Result(double typicalMinutes, double worstMinutes, List<String> assumed) {
    Result {
      assumed = List.copyOf(assumed);
    }
  }

  /** The seconds of one window fork: fixed cost, two rounds of canary probes, warm-up, windows. */
  static double windowForkSeconds(Protocol p, double fixedSeconds, boolean worstWarmUp) {
    double window = p.window().toNanos() / 1e9;
    double warmUp = worstWarmUp ? p.warmupMax().toNanos() / 1e9 : TYPICAL_WARMUP_WINDOWS * window;
    return fixedSeconds + 6 * (p.canary().toNanos() / 1e9) + warmUp + p.measureWindows() * window;
  }

  /** The projected minutes of a plan: the forks of every forked step plus the in-process steps. */
  static Result project(Plan plan, Sizes smoke, Observed o) {
    Sizes full = plan.profile().sizes();
    List<String> assumed = new ArrayList<>();
    double typical = 0;
    double worst = 0;
    for (Step step : plan.steps()) {
      if (step.role() == Role.FACTS) {
        double scaled = o.factsSeconds() * full.bisectRecords() / (double) smoke.bisectRecords();
        typical += scaled;
        worst += scaled;
        continue;
      }
      if (!step.forked()) {
        continue;
      }
      Protocol p = SpXWorker.protocol(step.protocolName());
      double data = dataSeconds(step, full, smoke, o, assumed);
      boolean windowed = step.role() == Role.COMPONENT && !step.bench().equals("gate");
      // a cycle fork pays the fixed cost and the canary probes; its cycles are in the data seconds
      double cycleFork = o.fixedSeconds() + 6 * (p.canary().toNanos() / 1e9);
      typical +=
          step.forks()
              * ((windowed ? windowForkSeconds(p, o.fixedSeconds(), false) : cycleFork) + data);
      worst +=
          step.forks()
              * ((windowed ? windowForkSeconds(p, o.fixedSeconds(), true) : cycleFork) + data);
    }
    return new Result(typical / 60.0, worst / 60.0, assumed);
  }

  /** The preparation and the measured cycles of a fork, at the full sizes. */
  private static double dataSeconds(
      Step step, Sizes full, Sizes smoke, Observed o, List<String> assumed) {
    double gen = o.genSecondsPerRecord();
    switch (step.role()) {
      case COMPONENT -> {
        if (step.bench().equals("writer")) {
          return gen * full.writerRing();
        }
        if (step.bench().equals("xsd")) {
          return gen * full.xsdRing();
        }
        double units = (double) step.threads() * full.gateRecords();
        return gen * full.gateRecords() + CYCLES * units / rate(step, o, assumed);
      }
      case INTEGRATED -> {
        double units = (double) step.threads() * full.recordsPerFile();
        double ring = (double) step.threads() * (full.recordsPerFile() + RING_EXTRA);
        return gen * ring + CYCLES * units / rate(step, o, assumed);
      }
      case BISECT -> {
        double scale = full.bisectRecords() / (double) smoke.bisectRecords();
        return gen * full.bisectRecords() + CYCLES * scale / rate(step, o, assumed);
      }
      case LATENCY -> {
        double records = LATENCY_WARM_RECORDS;
        double ring = 0;
        for (int size : full.latencySizes()) {
          records += size;
          ring += size + RING_EXTRA;
        }
        return gen * ring + records * o.latencySecondsPerRecord();
      }
      default -> {
        return 0.0;
      }
    }
  }

  private static double rate(Step step, Observed o, List<String> assumed) {
    Double measured = o.rates().get(step.id());
    if (measured == null || !(measured > 0.0)) {
      assumed.add(step.id());
      return ASSUMED_RATE;
    }
    return measured;
  }
}
