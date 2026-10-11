package dev.regpack.spikes.l.load;

import dev.regpack.bench.proto.NanoClock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The schedule of an open-loop run: batch {@code k} (k = 1, 2, ...) of {@code batchSize} records
 * arrives at {@code start + k * batchSize / recordsPerSecond}, whatever the writers are doing. A
 * batch is the micro-batch that closes when its {@code batchSize}-th record has arrived, so the
 * first arrival is one interval after the start. Every arrival time is rounded once from the exact
 * product, so the schedule does not drift.
 */
public final class RateController {
  private static final double NANOS_PER_SECOND = 1e9;

  private final double recordsPerSecond;
  private final int batchSize;
  private final long start;
  private final AtomicLong issued = new AtomicLong();

  /**
   * A schedule that starts now.
   *
   * @throws IllegalArgumentException unless the rate and the batch size are positive
   */
  public RateController(double recordsPerSecond, int batchSize, NanoClock clock) {
    if (!(recordsPerSecond > 0.0) || Double.isInfinite(recordsPerSecond)) {
      throw new IllegalArgumentException("the rate must be positive: " + recordsPerSecond);
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("the batch size must be positive: " + batchSize);
    }
    this.recordsPerSecond = recordsPerSecond;
    this.batchSize = batchSize;
    this.start = clock.nanoTime();
  }

  /** The clock reading at which the schedule started. */
  public long startNanos() {
    return start;
  }

  /** The clock reading at which batch {@code k} (k at least 1) arrives. */
  public long arrivalNanos(long k) {
    return start + offsetNanos(k);
  }

  /** The nanoseconds from the start to the arrival of batch {@code k}. */
  public long offsetNanos(long k) {
    return Math.round((double) k * batchSize * NANOS_PER_SECOND / recordsPerSecond);
  }

  /** The arrival of the next batch not handed out before: batch 1, then batch 2, and so on. */
  public long nextArrivalNanos() {
    return arrivalNanos(issued.incrementAndGet());
  }

  /** Records that have arrived by {@code nowNanos}: complete batches whose arrival has passed. */
  public long arrivedRecords(long nowNanos) {
    return arrivedBatches(nowNanos) * batchSize;
  }

  /** Records that have arrived by {@code nowNanos} and are not completed, never negative. */
  public long backlogRecords(long nowNanos, long completedRecords) {
    return Math.max(0L, arrivedRecords(nowNanos) - completedRecords);
  }

  private long arrivedBatches(long nowNanos) {
    long elapsed = nowNanos - start;
    if (elapsed < 0) {
      return 0;
    }
    long k = (long) Math.floor(elapsed * recordsPerSecond / (NANOS_PER_SECOND * batchSize));
    // the estimate can be off by one in either direction because of the rounding of arrivals
    while (offsetNanos(k + 1) <= elapsed) {
      k++;
    }
    while (k > 0 && offsetNanos(k) > elapsed) {
      k--;
    }
    return k;
  }
}
