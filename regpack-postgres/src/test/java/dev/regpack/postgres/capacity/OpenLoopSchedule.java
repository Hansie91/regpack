package dev.regpack.postgres.capacity;

/**
 * The open-loop arrival schedule of the capacity probe (T21): records arrive at a fixed rate
 * whether or not the ledger keeps up, and a micro-batch is due when its last record has arrived, so
 * batch {@code k} (counting from 0) is due at {@code (k + 1) * batchSize / rate} seconds. Each
 * arrival is the floor of the exact rational time in nanoseconds, computed from the index and not
 * by adding a rounded period, so the rounding of one batch never leaks into the next.
 *
 * <p>The number of batches is the whole number of batches that the offered records fill in the
 * configured time (SP-L: 3,470 records/s for 1,200 s is 4,164,000 records, which is 832 batches of
 * 5,000: the 4,160,000-record slice that SP-L ran). Pure arithmetic: no clock, no I/O.
 */
final class OpenLoopSchedule {
  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final long recordsPerSecond;
  private final int batchSize;
  private final long batchCount;

  OpenLoopSchedule(long recordsPerSecond, int batchSize, long seconds) {
    if (recordsPerSecond <= 0 || batchSize <= 0 || seconds <= 0) {
      throw new IllegalArgumentException("rate, batch size and duration must be positive");
    }
    long offered;
    try {
      offered = Math.multiplyExact(recordsPerSecond, seconds);
      // every arrival is (index + 1) * batchSize * 1e9 with (index + 1) * batchSize <= offered
      Math.multiplyExact(offered, NANOS_PER_SECOND);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("rate times duration is too large for the schedule");
    }
    this.recordsPerSecond = recordsPerSecond;
    this.batchSize = batchSize;
    this.batchCount = Math.max(1, offered / batchSize);
    // a single batch larger than the offered records is still one batch; its arrival must fit
    try {
      Math.multiplyExact(Math.multiplyExact(batchCount, (long) batchSize), NANOS_PER_SECOND);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("batch size times count is too large for the schedule");
    }
  }

  long recordsPerSecond() {
    return recordsPerSecond;
  }

  int batchSize() {
    return batchSize;
  }

  /** The number of batches in the schedule, at least one. */
  long batchCount() {
    return batchCount;
  }

  /** The records that the schedule offers: the batch count times the batch size. */
  long scheduledRecords() {
    return batchCount * batchSize;
  }

  /** When batch {@code batchIndex} is due, in nanoseconds after the start of the run. */
  long arrivalNanos(long batchIndex) {
    if (batchIndex < 0 || batchIndex >= batchCount) {
      throw new IllegalArgumentException("batch index outside the schedule: " + batchIndex);
    }
    return (batchIndex + 1) * batchSize * NANOS_PER_SECOND / recordsPerSecond;
  }

  /** The time the slice covers: the arrival of its last batch. */
  long coveredNanos() {
    return arrivalNanos(batchCount - 1);
  }
}
