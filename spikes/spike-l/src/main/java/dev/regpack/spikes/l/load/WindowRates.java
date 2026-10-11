package dev.regpack.spikes.l.load;

/** Records per second in consecutive windows of a run, from the completion times of its batches. */
public final class WindowRates {
  private WindowRates() {}

  /** The number of complete windows in {@code coveredNanos} of run time. */
  public static int fullWindows(long coveredNanos, long windowNanos) {
    if (windowNanos <= 0) {
      throw new IllegalArgumentException("the window must be positive: " + windowNanos);
    }
    return (int) Math.max(0L, coveredNanos / windowNanos);
  }

  /**
   * The records completed in each of the first {@code windows} windows, per second of window. A
   * completion exactly on a window edge belongs to the window that starts there; completions after
   * the last window are not counted.
   *
   * @param completionOffsetsNanos completion time of each batch, nanoseconds from the start
   * @param records records of each batch
   */
  public static double[] recordsPerSecond(
      long[] completionOffsetsNanos, long[] records, long windowNanos, int windows) {
    if (completionOffsetsNanos.length != records.length) {
      throw new IllegalArgumentException("completions and records differ in length");
    }
    long[] sums = new long[windows];
    for (int i = 0; i < completionOffsetsNanos.length; i++) {
      long offset = completionOffsetsNanos[i];
      if (offset < 0) {
        continue;
      }
      long window = offset / windowNanos;
      if (window < windows) {
        sums[(int) window] += records[i];
      }
    }
    double seconds = windowNanos / 1e9;
    double[] rates = new double[windows];
    for (int i = 0; i < windows; i++) {
      rates[i] = sums[i] / seconds;
    }
    return rates;
  }
}
