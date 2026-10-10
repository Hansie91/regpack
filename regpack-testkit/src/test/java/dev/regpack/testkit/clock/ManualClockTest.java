package dev.regpack.testkit.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManualClockTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void startsAtGivenInstantAndMonotonicAtZero() {
    ManualClock clock = new ManualClock(START);
    assertEquals(START, clock.now());
    assertEquals(0L, clock.monotonicNanos());
  }

  @Test
  void advanceMovesWallAndMonotonicTogether() {
    ManualClock clock = new ManualClock(START);
    clock.advance(Duration.ofSeconds(5));
    assertEquals(START.plusSeconds(5), clock.now());
    assertEquals(5_000_000_000L, clock.monotonicNanos());
  }

  @Test
  void stepWallCanGoBackwardsWithoutMovingMonotonic() {
    ManualClock clock = new ManualClock(START);
    clock.advance(Duration.ofSeconds(2));
    long before = clock.monotonicNanos();
    clock.stepWall(Duration.ofHours(-1));
    assertEquals(START.plusSeconds(2).minus(Duration.ofHours(1)), clock.now());
    assertEquals(before, clock.monotonicNanos());

    // set() is wall-only too, in either direction, and honours its argument.
    Instant earlier = START.minusSeconds(10);
    clock.set(earlier);
    assertEquals(earlier, clock.now());
    assertEquals(before, clock.monotonicNanos());
    Instant later = START.plusSeconds(3600);
    clock.set(later);
    assertEquals(later, clock.now());
    assertEquals(before, clock.monotonicNanos());
  }

  @Test
  void advanceRejectsNegative() {
    ManualClock clock = new ManualClock(START);
    assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofMillis(-1)));
    assertEquals(START, clock.now());
    assertEquals(0L, clock.monotonicNanos());
  }

  @Test
  void rejectsSubMicrosecondInstants() {
    Instant fine = START.plusNanos(1);
    assertThrows(IllegalArgumentException.class, () -> new ManualClock(fine));
    ManualClock clock = new ManualClock(START);
    assertThrows(IllegalArgumentException.class, () -> clock.set(fine));
    assertEquals(START, clock.now());
  }

  @Test
  void rejectsSubMicrosecondDurations() {
    ManualClock clock = new ManualClock(START);
    assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofNanos(1)));
    assertThrows(IllegalArgumentException.class, () -> clock.stepWall(Duration.ofNanos(1)));
    assertEquals(START, clock.now());
    assertEquals(0L, clock.monotonicNanos());
  }

  @Test
  void rejectsNullAndOutOfRangeArgumentsWithoutChangingState() {
    ManualClock clock = new ManualClock(START);
    assertThrows(NullPointerException.class, () -> new ManualClock(null));
    assertThrows(NullPointerException.class, () -> clock.set(null));
    assertThrows(NullPointerException.class, () -> clock.advance(null));
    assertThrows(NullPointerException.class, () -> clock.stepWall(null));
    // Durations whose nanosecond count does not fit a long.
    Duration huge = Duration.ofSeconds(Long.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> clock.advance(huge));
    assertThrows(IllegalArgumentException.class, () -> clock.stepWall(huge));
    assertThrows(IllegalArgumentException.class, () -> clock.stepWall(huge.negated()));
    assertEquals(START, clock.now());
    assertEquals(0L, clock.monotonicNanos());
    // A monotonic overflow fails before wall time moves (the advance is atomic).
    clock.advance(Duration.ofSeconds(9_000_000_000L));
    Instant wallBefore = clock.now();
    long monotonicBefore = clock.monotonicNanos();
    assertThrows(
        ArithmeticException.class, () -> clock.advance(Duration.ofSeconds(1_000_000_000L)));
    assertEquals(wallBefore, clock.now());
    assertEquals(monotonicBefore, clock.monotonicNanos());
  }

  @Test
  void isSafeFromManyThreads() throws Exception {
    ManualClock clock = new ManualClock(START);
    int threads = 8;
    int iterations = 20_000;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  for (int i = 0; i < iterations; i++) {
                    clock.advance(Duration.ofMillis(1));
                    // Readers must always see a consistent, non-decreasing monotonic value.
                    clock.monotonicNanos();
                    clock.now();
                  }
                  return null;
                }));
      }
      assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not start");
      go.countDown();
      for (Future<?> future : futures) {
        // get() rethrows any exception thrown inside a worker as an ExecutionException.
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    long totalMillis = (long) threads * iterations;
    assertEquals(START.plusMillis(totalMillis), clock.now());
    assertEquals(totalMillis * 1_000_000L, clock.monotonicNanos());
  }
}
