package dev.regpack.app;

import dev.regpack.ports.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The only wall-clock adapter: UTC wall time in whole microseconds and the JVM monotonic clock. */
public final class SystemClock implements Clock {
  @Override
  public Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  @Override
  public long monotonicNanos() {
    return System.nanoTime();
  }
}
