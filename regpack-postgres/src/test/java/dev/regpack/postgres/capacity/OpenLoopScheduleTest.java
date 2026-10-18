package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** The open-loop arrival schedule of the capacity probe (T21): pure arithmetic, no clock. */
class OpenLoopScheduleTest {
  private static final long SECOND = 1_000_000_000L;

  /**
   * A batch is due when its last record has arrived, so batch k is due at (k + 1) batch periods.
   * With a period of a whole number of nanoseconds the arrival times are exact multiples of it;
   * with one that is not (the SP-L shape), each arrival is the floor of the exact rational time, so
   * the rounding of one batch never leaks into the next (no drift).
   */
  @Test
  void arrivalTimesAreExactMultiplesOfTheBatchPeriod() {
    // 500 records per batch at 5,000 records/s: a period of exactly 100 ms
    OpenLoopSchedule exact = new OpenLoopSchedule(5_000, 500, 10);
    assertThat(exact.batchCount()).isEqualTo(100);
    long period = 100_000_000L;
    for (long k = 0; k < exact.batchCount(); k++) {
      assertThat(exact.arrivalNanos(k)).isEqualTo((k + 1) * period);
    }
    assertThat(exact.coveredNanos()).isEqualTo(10 * SECOND);
    assertThat(exact.scheduledRecords()).isEqualTo(50_000);

    // the SP-L slice: 3,470 records/s, batches of 5,000, 1,200 s: 832 batches, 4,160,000 records
    OpenLoopSchedule spl = new OpenLoopSchedule(3_470, 5_000, 1_200);
    assertThat(spl.batchCount()).isEqualTo(832);
    assertThat(spl.scheduledRecords()).isEqualTo(4_160_000);
    for (long k = 0; k < spl.batchCount(); k++) {
      BigInteger numerator =
          BigInteger.valueOf(k + 1).multiply(BigInteger.valueOf(5_000L * SECOND));
      BigInteger floor = numerator.divide(BigInteger.valueOf(3_470));
      assertThat(spl.arrivalNanos(k)).isEqualTo(floor.longValueExact());
    }
    // the slice SP-L reports as "1,198 s covered"
    assertThat(spl.coveredNanos()).isEqualTo(4_160_000L * SECOND / 3_470);
    assertThat(spl.coveredNanos() / SECOND).isEqualTo(1_198);
  }

  @Test
  void batchesOfTenThousandGiveHalfAsManyBatchesOverTheSameRecords() {
    OpenLoopSchedule schedule = new OpenLoopSchedule(3_470, 10_000, 1_200);
    assertThat(schedule.batchCount()).isEqualTo(416);
    assertThat(schedule.scheduledRecords()).isEqualTo(4_160_000);
  }

  @Test
  void arrivalsAreStrictlyIncreasingAndTheScheduleHasAtLeastOneBatch() {
    OpenLoopSchedule tiny = new OpenLoopSchedule(10, 1_000, 1);
    assertThat(tiny.batchCount()).isEqualTo(1);
    OpenLoopSchedule schedule = new OpenLoopSchedule(3_470, 5_000, 60);
    for (long k = 1; k < schedule.batchCount(); k++) {
      assertThat(schedule.arrivalNanos(k)).isGreaterThan(schedule.arrivalNanos(k - 1));
    }
  }

  @Test
  void anIndexOutsideTheScheduleAndAnUnusableShapeAreRefused() {
    OpenLoopSchedule schedule = new OpenLoopSchedule(5_000, 500, 1);
    assertThatThrownBy(() -> schedule.arrivalNanos(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> schedule.arrivalNanos(schedule.batchCount()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OpenLoopSchedule(0, 500, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OpenLoopSchedule(5_000, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OpenLoopSchedule(5_000, 500, 0))
        .isInstanceOf(IllegalArgumentException.class);
    // the arrival arithmetic must not overflow a long
    assertThatThrownBy(() -> new OpenLoopSchedule(1_000_000_000L, 500, 1_000_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
