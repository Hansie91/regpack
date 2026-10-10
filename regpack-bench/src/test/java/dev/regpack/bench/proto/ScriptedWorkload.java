package dev.regpack.bench.proto;

import java.util.function.LongUnaryOperator;

/**
 * A workload that costs virtual time on a {@link ScriptedClock}. The cost of one unit (nanoseconds)
 * is a function of the virtual time at which a call starts, so a test can script a warm-up curve
 * window by window. With a positive {@code cycleUnits} it models a periodic job: one call of {@code
 * runUnits(cycleUnits)} costs the unit time plus a fixed non-unit phase.
 */
final class ScriptedWorkload implements Workload {
  static final long WINDOW_NANOS = 2_000_000_000L;

  private final ScriptedClock clock;
  private final LongUnaryOperator nanosPerUnitAt;
  private final long cycleUnits;
  private final long nonUnitPhaseNanos;
  long units;
  int calls;
  long firstCallAt = -1;
  long lastCallEndedAt = -1;

  ScriptedWorkload(ScriptedClock clock, LongUnaryOperator nanosPerUnitAt) {
    this(clock, nanosPerUnitAt, 0, 0);
  }

  ScriptedWorkload(
      ScriptedClock clock,
      LongUnaryOperator nanosPerUnitAt,
      long cycleUnits,
      long nonUnitPhaseNanos) {
    this.clock = clock;
    this.nanosPerUnitAt = nanosPerUnitAt;
    this.cycleUnits = cycleUnits;
    this.nonUnitPhaseNanos = nonUnitPhaseNanos;
  }

  /** Index of the 2 s window that the virtual time {@code now} falls into. */
  static int windowIndex(long now) {
    return (int) (now / WINDOW_NANOS);
  }

  @Override
  public long runUnits(long maxUnits) {
    if (firstCallAt < 0) {
      firstCallAt = clock.now;
    }
    calls++;
    long nanosPerUnit = nanosPerUnitAt.applyAsLong(clock.now);
    clock.now += maxUnits * nanosPerUnit;
    if (cycleUnits > 0) {
      clock.now += nonUnitPhaseNanos;
    }
    units += maxUnits;
    lastCallEndedAt = clock.now;
    return maxUnits;
  }

  @Override
  public long checksum() {
    return units;
  }

  @Override
  public long cycleUnits() {
    return cycleUnits;
  }
}
