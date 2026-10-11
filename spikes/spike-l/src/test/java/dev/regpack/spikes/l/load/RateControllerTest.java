package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RateControllerTest {
  private static final long START = 1_000L;

  @Test
  void openLoopSchedulesAtTheTargetRate() {
    RateController rate = new RateController(3_470.0, 5_000, () -> START);
    assertThat(rate.startNanos()).isEqualTo(START);
    long previous = START;
    for (int k = 1; k <= 1_000; k++) {
      long arrival = rate.nextArrivalNanos();
      // 5,000 records at 3,470 records/s: one batch every 1.4409 s
      assertThat(arrival - previous).isCloseTo(1_440_922_190L, within(2L));
      assertThat(arrival).isEqualTo(rate.arrivalNanos(k));
      previous = arrival;
    }
    // no drift: the 1,000th arrival is at exactly 1,000 intervals, rounded once
    assertThat(previous - START).isEqualTo(Math.round(1_000 * 5_000 * 1e9 / 3_470.0));
  }

  @Test
  void arrivalsDoNotDependOnTheClockAfterTheStart() {
    long[] now = {START};
    RateController rate = new RateController(100.0, 100, () -> now[0]);
    now[0] =
        START + 50_000_000_000L; // the writers were busy for 50 s: arrivals stay where they are
    assertThat(rate.nextArrivalNanos()).isEqualTo(START + 1_000_000_000L);
    assertThat(rate.nextArrivalNanos()).isEqualTo(START + 2_000_000_000L);
  }

  @Test
  void backlogGrowsWhenServiceIsSlower() {
    // 100 records/s in batches of 100: one batch a second. A service that finishes one batch per
    // two seconds falls behind by one batch every two seconds.
    RateController rate = new RateController(100.0, 100, () -> START);
    long second = 1_000_000_000L;
    long previous = 0;
    for (int t = 10; t <= 60; t += 10) {
      long now = START + t * second;
      long arrived = rate.arrivedRecords(now);
      assertThat(arrived).isEqualTo(t * 100L);
      long completed = (t / 2) * 100L;
      long backlog = rate.backlogRecords(now, completed);
      assertThat(backlog).isEqualTo(arrived - completed);
      assertThat(backlog).isGreaterThan(previous);
      previous = backlog;
    }
  }

  @Test
  void backlogIsNeverNegativeAndAnArrivalCountsAtItsInstant() {
    RateController rate = new RateController(100.0, 100, () -> START);
    assertThat(rate.arrivedRecords(START)).isZero();
    assertThat(rate.arrivedRecords(START + 999_999_999L)).isZero();
    assertThat(rate.arrivedRecords(START + 1_000_000_000L)).isEqualTo(100);
    assertThat(rate.backlogRecords(START, 500)).isZero();
  }

  @Test
  void badParametersAreRejected() {
    assertThatThrownBy(() -> new RateController(0.0, 100, () -> 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RateController(100.0, 0, () -> 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
