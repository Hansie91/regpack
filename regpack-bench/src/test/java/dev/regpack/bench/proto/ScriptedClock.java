package dev.regpack.bench.proto;

/**
 * A virtual clock for the protocol tests. {@code now} only moves when a test workload advances it
 * or, when {@code tick} is above zero, by {@code tick} nanoseconds on every reading.
 */
final class ScriptedClock implements NanoClock {
  long now;
  private final long tick;

  ScriptedClock(long tick) {
    this.tick = tick;
  }

  static ScriptedClock manual() {
    return new ScriptedClock(0);
  }

  @Override
  public long nanoTime() {
    long reading = now;
    now += tick;
    return reading;
  }
}
