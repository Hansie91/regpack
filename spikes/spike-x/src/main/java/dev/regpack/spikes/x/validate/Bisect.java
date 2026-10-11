package dev.regpack.spikes.x.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds the records that make a file fail its gate by halving: the whole set is validated, and a
 * set that fails is split in two halves that are validated in turn; a half that passes is not
 * descended into, a failing single record is an offender. When the left half of a failing range is
 * clean, the right half is known to fail and is descended into without being validated. With {@code
 * k} offenders among {@code n} records the cost is about {@code k log2(n/k)} validations (at most
 * twice that).
 *
 * <p>The predicate says whether a subset is valid; it must be monotone (a subset without any
 * offender is valid, a subset with one is not).
 */
public final class Bisect {
  private Bisect() {}

  /**
   * @param offenders indices into the list, ascending
   * @param validations calls of the predicate
   * @param bytesValidated the bytes of all the subsets that were validated
   */
  public record Result(List<Integer> offenders, long validations, long bytesValidated) {
    public Result {
      offenders = List.copyOf(offenders);
    }
  }

  public static Result findOffenders(List<byte[]> records, Predicate<List<byte[]>> validSubset) {
    long[] prefix = new long[records.size() + 1];
    for (int i = 0; i < records.size(); i++) {
      prefix[i + 1] = prefix[i] + records.get(i).length;
    }
    Search search = new Search(records, validSubset, prefix);
    if (!records.isEmpty() && !search.valid(0, records.size())) {
      search.descend(0, records.size());
    }
    return new Result(search.offenders, search.validations, search.bytes);
  }

  private static final class Search {
    final List<byte[]> records;
    final Predicate<List<byte[]>> validSubset;
    final long[] prefix;
    final List<Integer> offenders = new ArrayList<>();
    long validations;
    long bytes;

    Search(List<byte[]> records, Predicate<List<byte[]>> validSubset, long[] prefix) {
      this.records = records;
      this.validSubset = validSubset;
      this.prefix = prefix;
    }

    boolean valid(int from, int to) {
      validations++;
      bytes += prefix[to] - prefix[from];
      return validSubset.test(records.subList(from, to));
    }

    /** The range is known to fail. */
    void descend(int from, int to) {
      if (to - from == 1) {
        offenders.add(from);
        return;
      }
      int middle = from + (to - from) / 2;
      if (valid(from, middle)) {
        // the range fails and its left half is clean, so by monotonicity the right half fails:
        // it is descended into without a validation of its own
        descend(middle, to);
        return;
      }
      descend(from, middle);
      if (!valid(middle, to)) {
        descend(middle, to);
      }
    }
  }
}
