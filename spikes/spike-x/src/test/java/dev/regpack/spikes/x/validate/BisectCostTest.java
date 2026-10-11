package dev.regpack.spikes.x.validate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.SplitMix64;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BisectCostTest {
  private static final int N = 4_096;

  /** Record {@code i} is 8 + i % 5 bytes; byte 0 is 1 for an offender. */
  private static List<byte[]> records(TreeSet<Integer> offenders) {
    List<byte[]> records = new ArrayList<>(N);
    for (int i = 0; i < N; i++) {
      byte[] record = new byte[8 + i % 5];
      record[0] = (byte) (offenders.contains(i) ? 1 : 0);
      records.add(record);
    }
    return records;
  }

  private static TreeSet<Integer> offenders(long seed, int k) {
    SplitMix64 rng = new SplitMix64(seed);
    TreeSet<Integer> set = new TreeSet<>();
    while (set.size() < k) {
      set.add(rng.nextInt(N));
    }
    return set;
  }

  /** Counts its own calls and bytes, independently of what Bisect reports. */
  private static final class Gate implements java.util.function.Predicate<List<byte[]>> {
    final AtomicLong calls = new AtomicLong();
    final AtomicLong bytes = new AtomicLong();

    @Override
    public boolean test(List<byte[]> subset) {
      calls.incrementAndGet();
      boolean valid = true;
      for (byte[] record : subset) {
        bytes.addAndGet(record.length);
        if (record[0] != 0) {
          valid = false;
        }
      }
      return valid;
    }
  }

  @Test
  void bisectValidationsFollowTheLogModel() {
    for (int k : new int[] {1, 10, 100}) {
      TreeSet<Integer> expected = offenders(11L + k, k);
      Gate gate = new Gate();
      Bisect.Result result = Bisect.findOffenders(records(expected), gate);
      assertThat(result.offenders())
          .as("offenders for k=%d", k)
          .containsExactlyElementsOf(expected);
      double bound = 2.0 * k * (Math.log(N / (double) k) / Math.log(2) + 1) + 1;
      assertThat((double) result.validations())
          .as("validations for k=%d", k)
          .isLessThanOrEqualTo(bound)
          .isGreaterThanOrEqualTo(k);
      assertThat(result.validations()).isEqualTo(gate.calls.get());
      assertThat(result.bytesValidated()).isEqualTo(gate.bytes.get());
    }
  }

  @Test
  void aCleanSetCostsOneValidationOfTheWholeSet() {
    Gate gate = new Gate();
    List<byte[]> records = records(new TreeSet<>());
    Bisect.Result result = Bisect.findOffenders(records, gate);
    assertThat(result.offenders()).isEmpty();
    assertThat(result.validations()).isEqualTo(1);
    assertThat(result.bytesValidated()).isEqualTo(records.stream().mapToLong(r -> r.length).sum());
  }

  @Test
  void aCleanHalfIsNotDescendedInto() {
    // one offender in the first record: the right half is validated once and never descended into
    TreeSet<Integer> only = new TreeSet<>(List.of(0));
    List<byte[]> records = records(only);
    List<List<byte[]>> seen = new ArrayList<>();
    Bisect.Result result =
        Bisect.findOffenders(
            records,
            subset -> {
              seen.add(new ArrayList<>(subset));
              return subset.stream().allMatch(r -> r[0] == 0);
            });
    assertThat(result.offenders()).containsExactly(0);
    long wholeHalfOfTheRight =
        seen.stream().filter(s -> s.size() == N / 2 && s.get(0) == records.get(N / 2)).count();
    assertThat(wholeHalfOfTheRight).isEqualTo(1);
    assertThat(seen.stream().filter(s -> s.size() > 1 && s.get(0) == records.get(N / 2 + 1)))
        .as("nothing inside the clean half is validated")
        .isEmpty();
  }

  @Test
  void aCleanLeftHalfLeavesTheRightHalfUnvalidatedBecauseItMustFail() {
    // one offender in the last record: at every level the left half is clean, so the right half
    // is known to fail and costs no validation. The whole set plus one left half per level.
    int levels = 12; // log2(4096)
    TreeSet<Integer> last = new TreeSet<>(List.of(N - 1));
    Gate gate = new Gate();
    Bisect.Result result = Bisect.findOffenders(records(last), gate);
    assertThat(result.offenders()).containsExactly(N - 1);
    assertThat(result.validations()).isEqualTo(1 + levels);
    assertThat(gate.calls.get()).isEqualTo(1 + levels);
    // the other extreme: the offender in the first record costs the sibling of every level
    Gate first = new Gate();
    assertThat(Bisect.findOffenders(records(new TreeSet<>(List.of(0))), first).validations())
        .isEqualTo(1 + 2 * levels);
  }

  @Test
  void everyRecordBadFindsEveryRecord() {
    TreeSet<Integer> all = new TreeSet<>();
    for (int i = 0; i < 64; i++) {
      all.add(i);
    }
    List<byte[]> records = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      records.add(new byte[] {1, 2});
    }
    Bisect.Result result =
        Bisect.findOffenders(records, subset -> subset.stream().allMatch(r -> r[0] == 0));
    assertThat(result.offenders()).containsExactlyElementsOf(all);
  }

  @Test
  void aSingleRecordSetIsAnOffenderOrNot() {
    List<byte[]> bad = List.of(new byte[] {1});
    List<byte[]> good = List.of(new byte[] {0});
    java.util.function.Predicate<List<byte[]>> gate =
        subset -> subset.stream().allMatch(r -> r[0] == 0);
    assertThat(Bisect.findOffenders(bad, gate).offenders()).containsExactly(0);
    assertThat(Bisect.findOffenders(good, gate).offenders()).isEmpty();
    assertThat(Bisect.findOffenders(List.of(), gate).validations()).isZero();
  }
}
