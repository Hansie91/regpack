package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.postgres.DecisionWaves.Chain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure planning of a decision batch (T16, Convention 11): which decisions can be written by one
 * set-based statement, and in which order their rows are locked. No server is needed.
 */
class DecisionWavesTest {
  private static Chain chain(int regimeId, String event) {
    return new Chain((short) regimeId, event);
  }

  @Test
  void chainsThatAreAllDifferentShareOneWave() {
    List<Chain> chains = List.of(chain(1, "A"), chain(1, "B"), chain(2, "A"));
    assertThat(DecisionWaves.split(chains)).containsExactly(List.of(0, 1, 2));
  }

  @Test
  void aChainRepeatedInABatchGetsOneWavePerOccurrenceInInputOrder() {
    // A B A A B  ->  the first occurrences, then the second ones, then the third
    List<Chain> chains =
        List.of(chain(1, "A"), chain(1, "B"), chain(1, "A"), chain(1, "A"), chain(1, "B"));
    assertThat(DecisionWaves.split(chains))
        .containsExactly(List.of(0, 1), List.of(2, 4), List.of(3));
  }

  @Test
  void theSameEventUnderTwoRegimesIsTwoChains() {
    List<Chain> chains = List.of(chain(1, "A"), chain(2, "A"));
    assertThat(DecisionWaves.split(chains)).containsExactly(List.of(0, 1));
  }

  @Test
  void anEmptyBatchHasNoWave() {
    assertThat(DecisionWaves.split(List.of())).isEmpty();
  }

  @Test
  void everyIndexAppearsInExactlyOneWave() {
    List<Chain> chains = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      chains.add(chain(1 + i % 3, "E" + (i % 7)));
    }
    List<Integer> seen = new ArrayList<>();
    for (List<Integer> wave : DecisionWaves.split(chains)) {
      // within a wave no chain occurs twice, so the wave can be written by one statement
      assertThat(wave.stream().map(chains::get).distinct().count()).isEqualTo(wave.size());
      seen.addAll(wave);
    }
    Collections.sort(seen);
    List<Integer> all = new ArrayList<>();
    for (int i = 0; i < chains.size(); i++) {
      all.add(i);
    }
    assertThat(seen).isEqualTo(all);
  }

  @Test
  void canonicalLockOrderIsRegimeIdThenEventIdentity() {
    List<Chain> shuffled =
        new ArrayList<>(
            List.of(chain(2, "A"), chain(1, "B"), chain(1, "A"), chain(10, "A"), chain(2, "B")));
    Collections.sort(shuffled, DecisionWaves.CANONICAL);
    assertThat(shuffled)
        .containsExactly(
            chain(1, "A"), chain(1, "B"), chain(2, "A"), chain(2, "B"), chain(10, "A"));
  }

  @Test
  void eventIdentitiesAreOrderedByCodeUnitNotByLocale() {
    // a locale-aware collation would put "a" before "B"; the lock order must not depend on any
    // locale or database collation, so every writer sorts the same way
    List<Chain> chains = new ArrayList<>(List.of(chain(1, "a"), chain(1, "B"), chain(1, "Z")));
    Collections.sort(chains, DecisionWaves.CANONICAL);
    assertThat(chains).containsExactly(chain(1, "B"), chain(1, "Z"), chain(1, "a"));
  }
}
