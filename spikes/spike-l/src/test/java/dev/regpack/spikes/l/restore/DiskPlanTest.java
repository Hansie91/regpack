package dev.regpack.spikes.l.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DiskPlanTest {
  private static final long GIB = 1L << 30;
  private static final long MIB = 1L << 20;

  @Test
  void chooseSizesThatFitTheLiveCopiesBackupAndRestore() {
    // (live copies + 2) x K x bytes per key x 1.1 + WAL reserve + 2 GiB must fit in the free space
    assertThat(DiskPlan.choose(14 * GIB, 1_000, 3, 256 * MIB, DiskPlan.CANDIDATE_KEYS))
        .containsExactly(500_000L, 1_000_000L, 2_000_000L);
    assertThat(DiskPlan.choose(20 * GIB, 1_000, 1, 256 * MIB, DiskPlan.CANDIDATE_KEYS))
        .containsExactly(500_000L, 1_000_000L, 2_000_000L, 5_000_000L);
  }

  @Test
  void theCandidatesAreTheFiveSizesOfThePlan() {
    assertThat(DiskPlan.CANDIDATE_KEYS)
        .containsExactly(500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L);
  }

  @Test
  void aSizeExactlyAtTheLimitFitsAndOneByteLessDoesNot() {
    // 2 copies in total (0 live + 2), 100 bytes per key, 1,000 keys: 2 x 1,000 x 100 x 1.1 =
    // 220,000
    long need = 220_000L + 100L + 2 * GIB;
    assertThat(DiskPlan.choose(need, 100, 0, 100, new long[] {1_000})).containsExactly(1_000L);
    assertThat(DiskPlan.choose(need - 1, 100, 0, 100, new long[] {1_000})).isEmpty();
  }

  @Test
  void nothingFitsWhenTheDiskIsSmallerThanTheFixedReserve() {
    assertThat(DiskPlan.choose(GIB, 1_000, 3, 256 * MIB, DiskPlan.CANDIDATE_KEYS)).isEmpty();
  }

  @Test
  void theResultIsAscendingWhateverTheOrderOfTheCandidates() {
    assertThat(
            DiskPlan.choose(100 * GIB, 1_000, 1, 0, new long[] {2_000_000L, 500_000L, 1_000_000L}))
        .containsExactly(500_000L, 1_000_000L, 2_000_000L);
  }

  @Test
  void invalidInputsAreRejected() {
    assertThatThrownBy(() -> DiskPlan.choose(-1, 1_000, 3, 0, new long[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DiskPlan.choose(GIB, 0, 3, 0, new long[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DiskPlan.choose(GIB, 1_000, -1, 0, new long[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
