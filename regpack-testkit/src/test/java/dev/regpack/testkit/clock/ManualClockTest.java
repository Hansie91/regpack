package dev.regpack.testkit.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
  void isSafeFromManyThreads() throws InterruptedException {
    ManualClock clock = new ManualClock(START);
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 8; t++) {
      Thread thread =
          new Thread(
              () -> {
                for (int i = 0; i < 1000; i++) {
                  clock.advance(Duration.ofMillis(1));
                }
              });
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertEquals(START.plusSeconds(8), clock.now());
    assertEquals(8_000_000_000L, clock.monotonicNanos());
  }
}
