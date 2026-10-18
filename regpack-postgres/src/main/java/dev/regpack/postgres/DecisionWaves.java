package dev.regpack.postgres;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The pure planning of a decision batch (T16, Convention 11).
 *
 * <p>The decisions of one chain (a regime and an event identity) depend on one another: the second
 * expects the head that the first leaves. Decisions of different chains do not. A batch is
 * therefore cut into waves: wave k holds the k-th decision of every chain, in input order, so a
 * wave never holds one chain twice and can be written by a handful of set-based statements, while
 * the waves run one after the other and each sees what the earlier ones wrote in the same
 * transaction. The result equals running the decisions one at a time in input order, except for
 * which failure is reported first when a batch has several.
 *
 * <p>Inside a wave the rows are written and locked in one canonical order, {@link #CANONICAL}, so
 * that two units over the same chains in opposite input order take their locks in the same order
 * and cannot wait for each other (review focus LF20, {@code opposedBatchOrdersDoNotDeadlock}). The
 * order is by regime id and then by the UTF-16 code units of the event identity: Java's string
 * order, not the collation of any database, so that every writer sorts alike.
 */
final class DecisionWaves {
  /** One chain of decisions: a regime (by dictionary id) and an event identity. */
  record Chain(short regimeId, String eventIdentity) {
    Chain {
      Objects.requireNonNull(eventIdentity, "eventIdentity");
    }
  }

  /** The one order in which rows are locked and written inside a wave. */
  static final Comparator<Chain> CANONICAL =
      Comparator.comparingInt((Chain chain) -> chain.regimeId())
          .thenComparing(Chain::eventIdentity);

  private DecisionWaves() {}

  /**
   * Cuts the input positions into waves.
   *
   * @param chains the chain of each decision of the batch, in input order
   * @return the input positions of wave 0, wave 1 and so on, each wave in ascending position
   */
  static List<List<Integer>> split(List<Chain> chains) {
    Map<Chain, Integer> seen = new HashMap<>();
    List<List<Integer>> waves = new ArrayList<>();
    for (int position = 0; position < chains.size(); position++) {
      int wave = seen.merge(chains.get(position), 1, Integer::sum) - 1;
      if (wave == waves.size()) {
        waves.add(new ArrayList<>());
      }
      waves.get(wave).add(position);
    }
    List<List<Integer>> result = new ArrayList<>(waves.size());
    for (List<Integer> wave : waves) {
      result.add(List.copyOf(wave));
    }
    return List.copyOf(result);
  }
}
