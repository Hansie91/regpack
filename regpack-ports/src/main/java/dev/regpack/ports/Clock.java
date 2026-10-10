package dev.regpack.ports;

import java.time.Instant;

/**
 * Time source for orchestration and adapters. Rules never receive a {@code Clock}. Every
 * implementation returns instants truncated to whole microseconds.
 */
public interface Clock {
  /**
   * UTC wall time, always whole microseconds ({@code getNano() % 1000 == 0}), the canonical and
   * PostgreSQL precision. May step forwards or backwards (clock drills).
   */
  Instant now();

  /** Durations only; never decreases; origin unspecified. */
  long monotonicNanos();
}
