package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SystemClockTest {
  private final SystemClock clock = new SystemClock();

  @Test
  void nowIsWholeMicroseconds() {
    Instant first = clock.now();
    assertThat(Duration.between(first, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    assertThat(first.getNano() % 1000).isZero();
    for (int i = 0; i < 1000; i++) {
      assertThat(clock.now().getNano() % 1000).isZero();
    }
  }

  @Test
  void monotonicNanosNeverDecreases() {
    long previous = clock.monotonicNanos();
    for (int i = 0; i < 1000; i++) {
      long next = clock.monotonicNanos();
      assertThat(next).isGreaterThanOrEqualTo(previous);
      previous = next;
    }
  }
}
