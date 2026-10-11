package dev.regpack.spikes.l.load;

import java.util.List;

/**
 * What one burst run measured. The per-batch arrays are in batch order and hold the completed
 * batches only.
 *
 * @param valid the run counts: it covered the load it was meant to, sustained it, and the standby
 *     precondition held throughout
 * @param records records of the completed batches
 * @param commitNanosSync per batch: the time of the two synchronous transactions (ingest and
 *     report), statements and commit, as reported by the writer
 * @param openLoopLatencyNanos per batch: completion minus scheduled arrival, so queueing behind a
 *     stall counts; empty for a closed-loop run
 * @param backlogSlope Theil-Sen slope of the backlog in records per second over the scheduled load
 *     (0 for a closed-loop run)
 * @param peakBacklogSeconds the largest backlog in records divided by 3,470 records/s (0 for a
 *     closed-loop run)
 * @param windowRates records per second of every full 60 s window of the covered load
 * @param stoppedBecause why the run ended
 * @param scheduledRecords records the schedule asked for (the completed ones for a closed loop)
 * @param coveredNanos the scheduled load that was covered: the time of the last scheduled arrival,
 *     or the elapsed time when the run stopped early or ran closed-loop
 * @param queuedAtEnd batches that had arrived and were not completed one interval after the last
 *     arrival
 * @param batchEndOffsetNanos per batch: completion time in nanoseconds from the start
 * @param reasons why the run is not valid, empty when it is
 */
public record RunResult(
    boolean valid,
    long records,
    long[] commitNanosSync,
    long[] openLoopLatencyNanos,
    double backlogSlope,
    double peakBacklogSeconds,
    double[] windowRates,
    BurstRunner.Stop stoppedBecause,
    long scheduledRecords,
    long coveredNanos,
    int queuedAtEnd,
    long[] batchEndOffsetNanos,
    List<String> reasons) {

  /** A run that stopped on a record cap covers at least this much load to be comparable. */
  public static final long MIN_COVERED_NANOS = 480_000_000_000L;

  /** How the reason that a record cap stopped a short run begins. */
  public static final String CAP_REASON_PREFIX = "the record cap ended the run after ";

  public RunResult {
    reasons = List.copyOf(reasons);
  }

  /**
   * The run ended on its record cap and nothing else is wrong with it: the cap is the only reason
   * that it is not {@link #valid()}, or it is valid. A matrix cell that is cut short by its cap on
   * purpose (to bound the disk it writes) is still a usable measurement; a sustain run is not.
   */
  public boolean onlyCappedByRecords() {
    return stoppedBecause == BurstRunner.Stop.MAX_RECORDS
        && reasons.stream().allMatch(r -> r.startsWith(CAP_REASON_PREFIX));
  }

  /**
   * A fault of the environment, neither a failed nor a valid run: the disk ran low, or the record
   * cap stopped the run before it covered {@link #MIN_COVERED_NANOS}.
   */
  public boolean environmentFault() {
    return stoppedBecause == BurstRunner.Stop.DISK
        || (stoppedBecause == BurstRunner.Stop.MAX_RECORDS && coveredNanos < MIN_COVERED_NANOS);
  }

  /** A run that counts against the design: not valid and not an environment fault. */
  public boolean failed() {
    return !valid && !environmentFault();
  }
}
