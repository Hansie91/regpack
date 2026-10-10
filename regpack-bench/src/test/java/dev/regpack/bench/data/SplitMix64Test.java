package dev.regpack.bench.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SplitMix64Test {
  @Test
  void seedZeroMatchesReferenceVectors() {
    SplitMix64 rng = new SplitMix64(0);
    assertThat(rng.nextLong()).isEqualTo(0xE220A8397B1DCDAFL);
    assertThat(rng.nextLong()).isEqualTo(0x6E789E6AA1B965F4L);
    assertThat(rng.nextLong()).isEqualTo(0x06C45D188009454FL);
    assertThat(rng.nextLong()).isEqualTo(0xF88BB8A8724C81ECL);
    assertThat(rng.nextLong()).isEqualTo(0x1B39896A51A8749BL);
  }

  @Test
  void forkVectors() {
    SplitMix64 parent = new SplitMix64(0);
    assertThat(parent.fork(1).nextLong()).isEqualTo(0xA706DD2F4D197E6FL);
    assertThat(parent.fork(2).nextLong()).isEqualTo(0x46B73E79F0C37C00L);
    assertThat(parent.nextLong()).isEqualTo(0xE220A8397B1DCDAFL);
  }

  @Test
  void nextIntIsUnbiasedAtBoundaries() {
    SplitMix64 one = new SplitMix64(11);
    for (int i = 0; i < 1000; i++) {
      assertThat(one.nextInt(1)).isZero();
      assertThat(one.nextLong(1L)).isZero();
    }
    SplitMix64 rng = new SplitMix64(7);
    int[] counts = new int[6];
    for (int i = 0; i < 60_000; i++) {
      counts[rng.nextInt(6)]++;
    }
    for (int count : counts) {
      assertThat(count).isBetween(9_500, 10_500);
    }
  }

  @Test
  void boundsMustBePositive() {
    SplitMix64 rng = new SplitMix64(1);
    assertThatThrownBy(() -> rng.nextInt(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rng.nextInt(-5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rng.nextLong(0L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unitDoubleIsTheExactGrid() {
    SplitMix64 rng = new SplitMix64(3);
    SplitMix64 reference = new SplitMix64(3);
    double grid = 0x1.0p-53;
    for (int i = 0; i < 10_000; i++) {
      double u = rng.nextUnit();
      long bits = reference.nextLong() >>> 11;
      assertThat(u).isEqualTo(bits * grid);
      assertThat(u).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
      assertThat(u * 0x1.0p53).isEqualTo((double) bits);
    }
  }

  @Test
  void chanceIsExact() {
    SplitMix64 rng = new SplitMix64(9);
    for (int i = 0; i < 1000; i++) {
      assertThat(rng.chance(0, 10)).isFalse();
      assertThat(rng.chance(10, 10)).isTrue();
    }
    int hits = 0;
    for (int i = 0; i < 100_000; i++) {
      if (rng.chance(1, 4)) {
        hits++;
      }
    }
    assertThat(hits).isBetween(24_000, 26_000);
  }

  @Test
  void forkWithZeroStreamIsRejected() {
    SplitMix64 rng = new SplitMix64(5);
    assertThatThrownBy(() -> rng.fork(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
