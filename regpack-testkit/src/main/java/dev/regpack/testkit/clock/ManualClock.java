package dev.regpack.testkit.clock;

import dev.regpack.ports.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Settable, thread-safe {@link Clock} that works in whole microseconds. */
public final class ManualClock implements Clock {
  private Instant wall;
  private long monotonic;

  public ManualClock(Instant start) {
    this.wall = requireWholeMicros(start);
    this.monotonic = 0L;
  }

  @Override
  public synchronized Instant now() {
    return wall;
  }

  @Override
  public synchronized long monotonicNanos() {
    return monotonic;
  }

  /** Moves wall and monotonic time together; {@code d} must not be negative. */
  public synchronized void advance(Duration d) {
    long nanos = requireWholeMicros(d);
    if (nanos < 0) {
      throw new IllegalArgumentException("advance requires a non-negative duration: " + d);
    }
    long nextMonotonic = Math.addExact(monotonic, nanos);
    wall = wall.plus(d);
    monotonic = nextMonotonic;
  }

  /** Moves wall time only, in either direction; monotonic time is unchanged. */
  public synchronized void stepWall(Duration d) {
    requireWholeMicros(d);
    wall = wall.plus(d);
  }

  /** Sets wall time only. */
  public synchronized void set(Instant instant) {
    wall = requireWholeMicros(instant);
  }

  private static Instant requireWholeMicros(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    if (instant.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("instant is not whole microseconds: " + instant);
    }
    return instant;
  }

  private static long requireWholeMicros(Duration d) {
    Objects.requireNonNull(d, "d");
    long nanos;
    try {
      nanos = d.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("duration out of range: " + d, e);
    }
    if (nanos % 1000 != 0) {
      throw new IllegalArgumentException("duration is not whole microseconds: " + d);
    }
    return nanos;
  }
}
