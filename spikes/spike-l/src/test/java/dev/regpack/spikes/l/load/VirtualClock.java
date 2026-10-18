package dev.regpack.spikes.l.load;

import dev.regpack.bench.proto.NanoClock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock that only moves when a test sink or sleeper advances it. With one writer the whole run is
 * deterministic: nothing but the writer thread moves time.
 */
final class VirtualClock implements NanoClock, BurstRunner.Sleeper {
  static final long SECOND = 1_000_000_000L;
  static final long MILLI = 1_000_000L;

  private final AtomicLong now = new AtomicLong(5_000_000_000L);

  @Override
  public long nanoTime() {
    return now.get();
  }

  void advance(long nanos) {
    now.addAndGet(nanos);
  }

  @Override
  public void sleepNanos(long nanos) {
    advance(nanos);
  }
}
