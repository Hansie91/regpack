package dev.regpack.spikes.r;

import dev.regpack.bench.proto.Protocol;
import dev.regpack.spikes.r.SpRRunner.Plan;
import dev.regpack.spikes.r.SpRRunner.Role;
import dev.regpack.spikes.r.SpRRunner.Step;

/**
 * The projected duration of the full run, from what a smoke run measured. The smoke protocol is
 * tiny (windows of 50 ms), so the first fork's duration is mostly start-up; the projection keeps
 * that fixed cost and adds what the full protocol spends per fork: six canary probes, the warm-up
 * and the measured windows. The warm-up is not known in advance: the typical figure assumes it ends
 * after five windows, the worst case lets it run to its limit.
 */
final class Projection {
  private static final int TYPICAL_WARMUP_WINDOWS = 5;

  /** The in-process steps of the full run are larger than the smoke ones by these factors. */
  private static final double DEFAULTS_FACTOR = 50.0;

  private static final double DETERMINISM_FACTOR = 100.0;
  private static final double DIFFERENTIAL_FACTOR = 50.0;
  private static final double HOT_SWAP_FACTOR = 200.0;

  private Projection() {}

  /**
   * The seconds of one fork: its fixed cost, two rounds of three canary probes, the warm-up and the
   * measured windows.
   */
  static double forkSeconds(Protocol p, double fixedSeconds, boolean worstWarmUp) {
    double window = p.window().toNanos() / 1e9;
    double warmUp = worstWarmUp ? p.warmupMax().toNanos() / 1e9 : TYPICAL_WARMUP_WINDOWS * window;
    return fixedSeconds + 6 * (p.canary().toNanos() / 1e9) + warmUp + p.measureWindows() * window;
  }

  /** The seconds of an explain fork: a canary and four batches of renders. */
  private static double explainForkSeconds(Protocol p, double fixedSeconds) {
    return fixedSeconds + 6 * (p.canary().toNanos() / 1e9) + 1.0;
  }

  /** The seconds the in-process steps of the full run take, scaled from the smoke ones. */
  static double inProcessSeconds(
      double defaults, double determinism, double differential, double hotSwap) {
    return defaults * DEFAULTS_FACTOR
        + determinism * DETERMINISM_FACTOR
        + differential * DIFFERENTIAL_FACTOR
        + hotSwap * HOT_SWAP_FACTOR;
  }

  /** The minutes of a whole plan: the forks of every forked step plus the in-process seconds. */
  static double totalMinutes(
      Plan plan, double fixedSeconds, double inProcessSeconds, boolean worstWarmUp) {
    double seconds = inProcessSeconds;
    for (Step step : plan.steps()) {
      if (!step.forked()) {
        continue;
      }
      Protocol p = SpRWorker.protocol(step.protocolName());
      double perFork =
          step.role() == Role.EXPLAIN
              ? explainForkSeconds(p, fixedSeconds)
              : forkSeconds(p, fixedSeconds, worstWarmUp);
      seconds += step.forks() * perFork;
    }
    return seconds / 60.0;
  }
}
