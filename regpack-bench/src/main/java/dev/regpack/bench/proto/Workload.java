package dev.regpack.bench.proto;

/**
 * Work that a fork measures. The work is consumed into a checksum so that the JIT cannot discard
 * it.
 */
public interface Workload {
  /**
   * Runs at most {@code maxUnits} units and returns how many it ran. When {@link #cycleUnits()} is
   * above zero the runner calls this with exactly {@code cycleUnits()}, and the call must run the
   * whole cycle.
   */
  long runUnits(long maxUnits);

  /** A value that depends on all the work done so far. */
  long checksum();

  /**
   * Above zero for a workload with a periodic non-unit phase (a file write, a SHA-256, a whole-file
   * gate): the number of units in one whole cycle. The default, zero, means a steady unit stream.
   */
  default long cycleUnits() {
    return 0;
  }
}
