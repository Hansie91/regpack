package dev.regpack.bench.proto;

import java.util.Objects;

/** The wall-time limit of a run: no fork starts once it has passed. */
public final class RunDeadline {
  private static final long NANOS_PER_MINUTE = 60_000_000_000L;
  private static final RunDeadline NONE = new RunDeadline(NanoClock.system(), 0, 0, false);

  private final NanoClock clock;
  private final long startNanos;
  private final long limitNanos;
  private final boolean limited;

  private RunDeadline(NanoClock clock, long startNanos, long limitNanos, boolean limited) {
    this.clock = clock;
    this.startNanos = startNanos;
    this.limitNanos = limitNanos;
    this.limited = limited;
  }

  /** A deadline {@code minutes} after now on {@code c}; the clock is read once here. */
  public static RunDeadline afterMinutes(NanoClock c, long minutes) {
    Objects.requireNonNull(c, "c");
    if (minutes < 0) {
      throw new IllegalArgumentException("minutes must not be negative: " + minutes);
    }
    return new RunDeadline(c, c.nanoTime(), Math.multiplyExact(minutes, NANOS_PER_MINUTE), true);
  }

  /** A deadline that never passes. */
  public static RunDeadline none() {
    return NONE;
  }

  /** Whether the limit has been reached; a limited deadline reads its clock once per call. */
  public boolean passed() {
    return limited && clock.nanoTime() - startNanos >= limitNanos;
  }
}
