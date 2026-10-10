package dev.regpack.bench.proto;

/**
 * One timed window: the units done and the nanoseconds it took.
 *
 * @param units units of work done in the window
 * @param nanos the window's elapsed time on the clock of the run
 */
public record WindowResult(long units, long nanos) {
  public WindowResult {
    if (units < 0 || nanos <= 0) {
      throw new IllegalArgumentException("a window needs units >= 0 and nanos > 0");
    }
  }

  /** Units per second. */
  public double rate() {
    return units * 1_000_000_000.0 / nanos;
  }
}
