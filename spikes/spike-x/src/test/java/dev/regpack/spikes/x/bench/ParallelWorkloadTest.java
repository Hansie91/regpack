package dev.regpack.spikes.x.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.proto.Workload;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ParallelWorkloadTest {
  /** Counts the units it runs; works in cycles of {@code cycle} units when that is above zero. */
  private static final class Counting implements Workload {
    final AtomicLong units = new AtomicLong();
    final AtomicLong calls = new AtomicLong();
    final long cycle;

    Counting(long cycle) {
      this.cycle = cycle;
    }

    @Override
    public long runUnits(long maxUnits) {
      calls.incrementAndGet();
      units.addAndGet(maxUnits);
      return maxUnits;
    }

    @Override
    public long checksum() {
      return units.get();
    }

    @Override
    public long cycleUnits() {
      return cycle;
    }
  }

  @Test
  void aSteadyStreamIsSharedOverTheThreadsAndTheUnitsAreSummed() {
    Counting a = new Counting(0);
    Counting b = new Counting(0);
    Counting c = new Counting(0);
    try (ParallelWorkload parallel = new ParallelWorkload(List.of(a, b, c))) {
      assertThat(parallel.cycleUnits()).isZero();
      assertThat(parallel.runUnits(10)).isEqualTo(10);
      assertThat(List.of(a.units.get(), b.units.get(), c.units.get()))
          .as("ten units over three threads")
          .containsExactlyInAnyOrder(4L, 3L, 3L);
      assertThat(parallel.checksum()).isEqualTo(10);
    }
  }

  @Test
  void aCycleRunsWholeOnEveryThreadAndTheCycleIsTheSum() {
    Counting a = new Counting(100);
    Counting b = new Counting(100);
    try (ParallelWorkload parallel = new ParallelWorkload(List.of(a, b))) {
      assertThat(parallel.cycleUnits()).as("two threads of 100 units").isEqualTo(200);
      assertThat(parallel.runUnits(parallel.cycleUnits())).isEqualTo(200);
      assertThat(a.units.get()).isEqualTo(100);
      assertThat(b.units.get()).isEqualTo(100);
      assertThat(parallel.runUnits(400)).as("two whole cycles").isEqualTo(400);
      assertThat(a.calls.get()).isEqualTo(3);
      assertThat(parallel.runUnits(199)).as("no whole cycle fits").isZero();
    }
  }

  @Test
  void mixingCyclesAndStreamsIsRefused() {
    assertThatThrownBy(() -> new ParallelWorkload(List.of(new Counting(10), new Counting(0))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ParallelWorkload(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFailureOnAThreadReachesTheCaller() {
    Workload failing =
        new Workload() {
          @Override
          public long runUnits(long maxUnits) {
            throw new IllegalStateException("boom");
          }

          @Override
          public long checksum() {
            return 0;
          }
        };
    try (ParallelWorkload parallel = new ParallelWorkload(List.of(new Counting(0), failing))) {
      assertThatThrownBy(() -> parallel.runUnits(10))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("boom");
    }
  }
}
