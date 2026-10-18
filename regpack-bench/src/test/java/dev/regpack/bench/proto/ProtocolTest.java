package dev.regpack.bench.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.bench.stats.Stats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolTest {
  private static final long MS = 1_000_000L;
  private static final long SECOND = 1_000_000_000L;
  private static final StealSource NO_STEAL = () -> new long[] {0, 0};

  /** The full protocol without the canary probes, which need a real workload and a real disk. */
  private static Protocol fullWithoutCanary() {
    return new Protocol(
        15, Duration.ofSeconds(60), Duration.ofSeconds(2), 3, 0.03, 15, Duration.ZERO, 0.05, 3, 7);
  }

  private static double[] doubles(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  @Test
  void fullProtocolIsTheSpecifiedOne() {
    Protocol p = Protocol.full();
    assertThat(p.forks()).isEqualTo(15);
    assertThat(p.warmupMax()).isEqualTo(Duration.ofSeconds(60));
    assertThat(p.window()).isEqualTo(Duration.ofSeconds(2));
    assertThat(p.stableWindows()).isEqualTo(3);
    assertThat(p.stabilityTolerance()).isEqualTo(0.03);
    assertThat(p.measureWindows()).isEqualTo(15);
    assertThat(p.canary()).isEqualTo(Duration.ofSeconds(1));
    assertThat(p.maxStealFraction()).isEqualTo(0.05);
    assertThat(p.maxDiscards()).isEqualTo(3);
    assertThat(p.minForksForVerdict()).isEqualTo(7);
  }

  @Test
  void informativeProtocolIsFullWithSevenForks() {
    Protocol p = Protocol.informative();
    assertThat(p.forks()).isEqualTo(7);
    assertThat(p)
        .isEqualTo(
            new Protocol(
                7,
                Protocol.full().warmupMax(),
                Protocol.full().window(),
                3,
                0.03,
                15,
                Duration.ofSeconds(1),
                0.05,
                3,
                7));
  }

  @Test
  void smokeProtocolIsTwoForks() {
    Protocol p = Protocol.smoke();
    assertThat(p.forks()).isEqualTo(2);
    // fewer forks than a verdict needs: a smoke run can only ever be INCONCLUSIVE
    assertThat(p.forks()).isLessThan(p.minForksForVerdict());
    assertThat(p.window()).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void aProtocolWithNonsenseValuesIsRejected() {
    assertThatThrownBy(
            () ->
                new Protocol(
                    0,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(2),
                    3,
                    0.03,
                    15,
                    Duration.ZERO,
                    0.05,
                    3,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Protocol(
                    15,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    3,
                    0.03,
                    15,
                    Duration.ZERO,
                    0.05,
                    3,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Protocol(
                    15,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(2),
                    3,
                    0.03,
                    0,
                    Duration.ZERO,
                    0.05,
                    3,
                    7))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void warmupEndsAfterThreeStableWindows() {
    ScriptedClock clock = ScriptedClock.manual();
    // windows 0 and 1 are still warming up (667 and 833 units/s); from window 2 the rate is 1,000
    ScriptedWorkload w =
        new ScriptedWorkload(
            clock,
            now ->
                switch (ScriptedWorkload.windowIndex(now)) {
                  case 0 -> 1_500_000L;
                  case 1 -> 1_200_000L;
                  default -> 1_000_000L;
                });
    ForkResult r = ForkRunner.runInProcess(fullWithoutCanary(), w, 0, clock, NO_STEAL);
    assertThat(r.unstable()).isFalse();
    assertThat(r.discarded()).isFalse();
    assertThat(r.windowRates()).hasSize(15);
    // Windows 2, 3 and 4 are the first three consecutive stable ones, so warm-up is five windows
    // (10 s) and the 15 measured windows are 30 s. A rule that ended after two stable windows would
    // total 38 s.
    assertThat(clock.now).isBetween(40 * SECOND, 40 * SECOND + 100 * MS);
    assertThat(r.statistic()).isEqualTo(1_000.0, within(0.5));
    assertThat(r.checksum()).isEqualTo(w.units);
  }

  @Test
  void warmupStopsAtSixtySecondsAndFlagsTheForkUnstable() {
    ScriptedClock clock = ScriptedClock.manual();
    // the rate flips by 50% from window to window and never settles
    ScriptedWorkload w =
        new ScriptedWorkload(
            clock, now -> ScriptedWorkload.windowIndex(now) % 2 == 0 ? 1_000_000L : 1_500_000L);
    ForkResult r = ForkRunner.runInProcess(fullWithoutCanary(), w, 3, clock, NO_STEAL);
    assertThat(r.index()).isEqualTo(3);
    assertThat(r.unstable()).isTrue();
    assertThat(r.discarded()).as("an unstable fork is kept").isFalse();
    assertThat(r.windowRates()).hasSize(15);
    // 60 s of warm-up and 30 s of measurement
    assertThat(clock.now).isBetween(90 * SECOND, 90 * SECOND + 100 * MS);
  }

  @Test
  void statisticIsTheMedianOfTheWindowRates() {
    ScriptedClock clock = ScriptedClock.manual();
    long[] orderOfCosts = {0, 14, 3, 11, 5, 9, 1, 7, 13, 2, 12, 4, 10, 6, 8};
    // windows 0 to 2 are the stable warm-up; measured window j costs 1,000,000 + 10,000 * order[j]
    ScriptedWorkload w =
        new ScriptedWorkload(
            clock,
            now -> {
              int window = ScriptedWorkload.windowIndex(now);
              // each window ends up to one unit late, so the last call of the last window may start
              // just after the nominal 36 s: clamp to the last measured window
              return window < 3
                  ? 1_000_000L
                  : 1_000_000L + 10_000L * orderOfCosts[Math.min(window - 3, 14)];
            });
    ForkResult r = ForkRunner.runInProcess(fullWithoutCanary(), w, 0, clock, NO_STEAL);
    assertThat(r.windowRates()).hasSize(15);
    assertThat(r.statistic()).isEqualTo(Stats.median(doubles(r.windowRates())));
    // the median window is the one with cost order 7: 1,070,000 ns per unit
    assertThat(r.statistic()).isEqualTo(1e9 / 1_070_000.0, within(0.5));
    // neither the first nor the last window decides it
    assertThat(r.statistic()).isNotEqualTo(r.windowRates().get(0));
    assertThat(r.statistic()).isNotEqualTo(r.windowRates().get(14));
  }

  @Test
  void aFastOrSlowForkIsNeverDiscarded() {
    for (long nanosPerUnit : new long[] {100_000L, 10_000_000L}) {
      ScriptedClock clock = ScriptedClock.manual();
      ScriptedWorkload w = new ScriptedWorkload(clock, now -> nanosPerUnit);
      ForkResult r = ForkRunner.runInProcess(fullWithoutCanary(), w, 0, clock, NO_STEAL);
      assertThat(r.discarded()).as("cost %d", nanosPerUnit).isFalse();
      assertThat(r.discardReason()).isEmpty();
      assertThat(r.statistic()).isEqualTo(1e9 / nanosPerUnit, within(1e9 / nanosPerUnit * 0.01));
    }
  }

  @Test
  void cycleWindowIncludesTheNonUnitPhase() {
    ScriptedClock clock = ScriptedClock.manual();
    // one cycle: 1,000 units at 1 ms each (1 s) plus a 1 s non-unit phase (file write, gate)
    ScriptedWorkload w = new ScriptedWorkload(clock, now -> MS, 1_000, SECOND);
    ForkResult r = ForkRunner.runInProcess(fullWithoutCanary(), w, 0, clock, NO_STEAL);
    // one untimed warm-up cycle and three measured cycles, nothing else
    assertThat(w.calls).isEqualTo(4);
    assertThat(w.units).isEqualTo(4_000);
    assertThat(r.unstable()).isFalse();
    // 3,000 units in 3 x 2 s: 500 per second, not the 1,000 per second of the unit phase alone
    assertThat(r.statistic()).isEqualTo(500.0, within(1e-6));
    assertThat(r.windowRates()).hasSize(1);
    // the warm-up cycle is not part of the measured 6 s
    assertThat(w.firstCallAt).isZero();
    assertThat(clock.now).isEqualTo(8 * SECOND);
  }

  @Test
  void highStealDiscardsWithAReason() {
    ScriptedClock clock = ScriptedClock.manual();
    ScriptedWorkload w = new ScriptedWorkload(clock, now -> 1_000_000L);
    long[][] readings = {{1_000, 0}, {2_000, 60}};
    int[] next = {0};
    StealSource steal = () -> readings[next[0]++];
    ForkResult r = ForkRunner.runInProcess(fullWithoutCanary(), w, 0, clock, steal);
    assertThat(next[0]).as("steal is read once before and once after the windows").isEqualTo(2);
    assertThat(r.discarded()).isTrue();
    assertThat(r.discardReason()).startsWith("STEAL").contains("0.06");
    assertThat(r.stealFraction()).isEqualTo(0.06, within(1e-12));
    assertThat(r.windowRates()).as("the measurement is kept for the listing").hasSize(15);
  }

  @Test
  void stealAtTheLimitIsKept() {
    ScriptedClock clock = ScriptedClock.manual();
    ScriptedWorkload w = new ScriptedWorkload(clock, now -> 1_000_000L);
    long[][] readings = {{1_000, 10}, {2_000, 60}};
    int[] next = {0};
    ForkResult r =
        ForkRunner.runInProcess(fullWithoutCanary(), w, 0, clock, () -> readings[next[0]++]);
    assertThat(r.stealFraction()).isEqualTo(0.05, within(1e-12));
    assertThat(r.discarded()).isFalse();
  }

  @Test
  void stealIsZeroWhereUnavailable() {
    ScriptedClock clock = ScriptedClock.manual();
    ScriptedWorkload w = new ScriptedWorkload(clock, now -> 1_000_000L);
    ForkResult r = ForkRunner.runInProcess(fullWithoutCanary(), w, 0, clock, NO_STEAL);
    assertThat(r.stealFraction()).isZero();
    assertThat(r.discarded()).isFalse();
  }

  @Test
  void aWorkloadThatMakesNoProgressIsAHarnessError() {
    ScriptedClock clock = ScriptedClock.manual();
    Workload stuck =
        new Workload() {
          @Override
          public long runUnits(long maxUnits) {
            return 0;
          }

          @Override
          public long checksum() {
            return 0;
          }
        };
    assertThatThrownBy(
            () -> ForkRunner.runInProcess(fullWithoutCanary(), stuck, 0, clock, NO_STEAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("progress");
  }

  @Test
  void canaryProbesRunBeforeWarmupAndAfterTheLastWindow() {
    // every clock reading costs 1 ms, so a 5 ms probe is a handful of iterations
    ScriptedClock clock = new ScriptedClock(MS);
    ScriptedWorkload w = new ScriptedWorkload(clock, now -> 1_000L);
    Protocol p =
        new Protocol(
            1,
            Duration.ofMillis(60),
            Duration.ofMillis(10),
            3,
            0.03,
            2,
            Duration.ofMillis(5),
            0.05,
            3,
            7);
    ForkResult r = ForkRunner.runInProcess(p, w, 0, clock, NO_STEAL);
    for (List<Double> probe : List.of(r.canaryCpu(), r.canaryMemory(), r.canaryDisk())) {
      assertThat(probe).as("one reading before warm-up and one after the last window").hasSize(2);
      assertThat(probe).allSatisfy(rate -> assertThat(rate).isPositive());
    }
    // three probes of at least 5 ms of virtual time each precede the first unit of work ...
    assertThat(w.firstCallAt).isGreaterThanOrEqualTo(15 * MS);
    // ... and three more follow the last one
    assertThat(clock.now - w.lastCallEndedAt).isGreaterThanOrEqualTo(15 * MS);
  }

  @Test
  void moreThanThreeDiscardsMakeTheSeriesTooManyDiscards() {
    assertThat(series(15, 3, 0).tooManyDiscards()).isFalse();
    assertThat(series(15, 4, 0).tooManyDiscards()).isTrue();
    assertThat(series(15, 0, 0).tooManyDiscards()).isFalse();
  }

  @Test
  void discardedForksAreListedAndNotKept() {
    ForkSeries s = series(15, 4, 0);
    assertThat(s.kept()).hasSize(11);
    assertThat(s.discarded()).hasSize(4);
    assertThat(s.discarded()).allSatisfy(f -> assertThat(f.discardReason()).isNotEmpty());
    assertThat(s.kept()).allSatisfy(f -> assertThat(f.discarded()).isFalse());
  }

  @Test
  void unstableFractionIsMeasuredOverTheKeptForks() {
    assertThat(series(10, 0, 2).unstableFraction()).isEqualTo(0.2, within(1e-12));
    assertThat(series(10, 2, 2).unstableFraction()).isEqualTo(0.25, within(1e-12));
    assertThat(series(10, 0, 0).unstableFraction()).isZero();
    assertThat(series(2, 2, 0).unstableFraction()).as("no kept fork").isZero();
  }

  /**
   * {@code total} forks of which the first {@code discards} are discarded and the last unstable.
   */
  private static ForkSeries series(int total, int discards, int unstable) {
    List<ForkResult> forks = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      boolean discarded = i < discards;
      boolean isUnstable = !discarded && i >= total - unstable;
      forks.add(
          new ForkResult(
              i,
              isUnstable,
              discarded,
              discarded ? "HARNESS_ERROR: scripted" : "",
              List.of(1.0, 2.0, 3.0),
              List.of(),
              List.of(),
              List.of(),
              0.0,
              i));
    }
    return new ForkSeries(forks, Protocol.full());
  }
}
