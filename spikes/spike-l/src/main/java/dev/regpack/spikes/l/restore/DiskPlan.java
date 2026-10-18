package dev.regpack.spikes.l.restore;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Which restore sizes fit the disk of the runner. A restore point holds, at the same time, the live
 * copies of the fixture (primary, plus the standby and the replica while they live), the base
 * backup and the restored cluster; the free space is read at run time, so a size that does not fit
 * is NOT_RUN instead of filling the disk.
 */
public final class DiskPlan {
  /** The sizes of the plan, in keys. */
  public static final long[] CANDIDATE_KEYS = {
    500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L
  };

  private static final long TWO_GIB = 2L << 30;

  private DiskPlan() {}

  /**
   * The candidate sizes that fit, ascending. A size {@code K} qualifies when {@code (liveCopies +
   * 2) x K x bytesPerKey x 1.1 + walReserve + 2 GiB <= free}: the live copies, the backup and the
   * restored cluster, 10% for what the key estimate misses, the archived WAL and a fixed reserve.
   *
   * @param freeBytes the free bytes of the volume
   * @param bytesPerKey the bytes one key takes in the cluster with its indexes (measured)
   * @param liveCopies the servers that hold a copy while the restore point runs (1 when the standby
   *     and the replica are dropped)
   * @param walReserveBytes the archived and live WAL that the point may leave on the disk
   * @param candidateKeys the sizes to choose from, in any order
   * @throws IllegalArgumentException for a negative free space or reserve, a non-positive size per
   *     key or a negative number of copies
   */
  public static long[] choose(
      long freeBytes,
      long bytesPerKey,
      int liveCopies,
      long walReserveBytes,
      long[] candidateKeys) {
    if (freeBytes < 0 || walReserveBytes < 0) {
      throw new IllegalArgumentException("free space and WAL reserve must not be negative");
    }
    if (bytesPerKey <= 0) {
      throw new IllegalArgumentException("bytesPerKey must be positive: " + bytesPerKey);
    }
    if (liveCopies < 0) {
      throw new IllegalArgumentException("liveCopies must not be negative: " + liveCopies);
    }
    long[] sorted = candidateKeys.clone();
    Arrays.sort(sorted);
    return Arrays.stream(sorted)
        .filter(keys -> fits(freeBytes, keys, bytesPerKey, liveCopies, walReserveBytes))
        .toArray();
  }

  private static boolean fits(
      long freeBytes, long keys, long bytesPerKey, int liveCopies, long walReserveBytes) {
    BigInteger copies = BigInteger.valueOf(liveCopies + 2L);
    BigInteger data =
        copies
            .multiply(BigInteger.valueOf(keys))
            .multiply(BigInteger.valueOf(bytesPerKey))
            .multiply(BigInteger.valueOf(11));
    // x 1.1, rounded up so that a size is never admitted on a rounding error
    BigInteger scaled = data.add(BigInteger.valueOf(9)).divide(BigInteger.TEN);
    BigInteger need =
        scaled.add(BigInteger.valueOf(walReserveBytes)).add(BigInteger.valueOf(TWO_GIB));
    return need.compareTo(BigInteger.valueOf(freeBytes)) <= 0;
  }
}
