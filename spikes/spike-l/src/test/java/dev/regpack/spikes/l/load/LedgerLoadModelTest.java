package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.bench.data.Digests;
import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.spikes.l.encoding.PathCodec;
import dev.regpack.spikes.l.load.LedgerLoadModel.Candidate;
import dev.regpack.spikes.l.load.LedgerLoadModel.Params;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LedgerLoadModelTest {
  private static final long SEED = 7L;
  private static final String BATCHES_DIGEST =
      "d667dacb7901e31dc4ec707174ff04c5fc705360198d3bae4d7059bb2fab2174";

  private static List<String> existing(int count) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(LedgerLoadModel.identityOf(SEED, 1_000_000L + i));
    }
    return out;
  }

  @Test
  void batchCompositionFollowsParameters() {
    Params params = new Params(5_000, 0.5, 1.5, 0.02, SEED);
    TradeRecordGen gen = new TradeRecordGen(3);
    SplitMix64 rng = new SplitMix64(11);
    List<String> existing = existing(3_000);
    List<Candidate> all = new ArrayList<>();
    all.addAll(LedgerLoadModel.nextBatch(params, gen, rng, existing));
    all.addAll(LedgerLoadModel.nextBatch(params, gen, rng, existing));
    assertThat(all).hasSize(10_000);

    long reports = all.stream().filter(Candidate::report).count();
    long superseding = all.stream().filter(Candidate::superseding).count();
    double legs =
        all.stream().filter(Candidate::report).mapToInt(Candidate::legs).average().orElseThrow();
    assertThat(reports / 10_000.0).isCloseTo(0.5, within(0.02));
    assertThat(legs).isCloseTo(1.5, within(0.05));
    assertThat(superseding / 10_000.0).isCloseTo(0.02, within(0.005));

    for (Candidate c : all) {
      if (!c.report()) {
        assertThat(c.legs()).isZero();
        assertThat(c.superseding()).isFalse();
      } else {
        assertThat(c.legs()).isBetween(1, 2);
        assertThat(c.legs()).isEqualTo(LedgerLoadModel.legsOf(params, c.eventIdentity()));
      }
      if (c.superseding()) {
        assertThat(c.report()).isTrue();
      }
    }
  }

  @Test
  void supersedingReusesExistingEventIdentities() {
    Params params = new Params(5_000, 0.5, 1.5, 0.02, SEED);
    List<String> existing = existing(2_000);
    Set<String> before = new HashSet<>(existing);
    List<Candidate> batch =
        LedgerLoadModel.nextBatch(params, new TradeRecordGen(5), new SplitMix64(9), existing);

    List<Candidate> superseding = batch.stream().filter(Candidate::superseding).toList();
    assertThat(superseding).isNotEmpty();
    Set<String> used = new HashSet<>();
    for (Candidate c : superseding) {
      assertThat(before).contains(c.eventIdentity());
      assertThat(used.add(c.eventIdentity())).as("each identity is superseded once").isTrue();
    }
    for (Candidate c : batch) {
      if (!c.superseding()) {
        assertThat(c.eventIdentity()).isEqualTo(LedgerLoadModel.identityOf(SEED, c.record().seq()));
      }
    }
    // a superseded identity is consumed, so two writers never supersede it twice
    assertThat(existing).hasSize(2_000 - superseding.size());
    assertThat(existing).doesNotContainAnyElementsOf(used);
  }

  @Test
  void withNothingToSupersedeNoCandidateSupersedes() {
    Params params = new Params(2_000, 0.5, 1.5, 0.5, SEED);
    List<Candidate> batch =
        LedgerLoadModel.nextBatch(
            params, new TradeRecordGen(5), new SplitMix64(9), new ArrayList<>());
    assertThat(batch).hasSize(2_000);
    assertThat(batch).noneMatch(Candidate::superseding);
  }

  @Test
  void sameSeedSameBatches() {
    assertThat(digestOfThreeBatches(1)).isEqualTo(digestOfThreeBatches(1));
    assertThat(digestOfThreeBatches(1)).isNotEqualTo(digestOfThreeBatches(2));
    assertThat(digestOfThreeBatches(1)).isEqualTo(BATCHES_DIGEST);
  }

  private static String digestOfThreeBatches(long seed) {
    Params params = new Params(1_000, 0.5, 1.5, 0.02, seed);
    TradeRecordGen gen = new TradeRecordGen(seed);
    SplitMix64 rng = new SplitMix64(seed + 100);
    List<String> existing = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      existing.add(LedgerLoadModel.identityOf(seed, 9_000_000L + i));
    }
    List<String> lines = new ArrayList<>();
    for (int b = 0; b < 3; b++) {
      for (Candidate c : LedgerLoadModel.nextBatch(params, gen, rng, existing)) {
        lines.add(
            c.eventIdentity()
                + ","
                + c.sourceRecordId()
                + ","
                + c.report()
                + ","
                + c.legs()
                + ","
                + c.superseding()
                + ","
                + c.record().seq());
      }
    }
    return Digests.ofLines(lines).hex();
  }

  @Test
  void identityInsertionOrderIsNotMonotone() {
    int n = 100_000;
    String[] ids = new String[n];
    Set<String> distinct = new HashSet<>();
    for (int i = 0; i < n; i++) {
      ids[i] = LedgerLoadModel.identityOf(SEED, i + 1L);
      assertThat(ids[i]).matches("[0-9a-f]{16}");
      distinct.add(ids[i]);
    }
    assertThat(distinct).hasSize(n);
    // Spearman correlation between the insertion rank (the index) and the key rank
    Integer[] byKey = new Integer[n];
    for (int i = 0; i < n; i++) {
      byKey[i] = i;
    }
    Arrays.sort(byKey, (a, b) -> ids[a].compareTo(ids[b]));
    double sumSquares = 0;
    for (int keyRank = 0; keyRank < n; keyRank++) {
      double d = byKey[keyRank] - keyRank;
      sumSquares += d * d;
    }
    double rho = 1.0 - 6.0 * sumSquares / ((double) n * ((double) n * n - 1.0));
    assertThat(Math.abs(rho)).isLessThan(0.05);
  }

  @Test
  void recordIdsAreDistinctFromIdentitiesAndUnique() {
    Set<String> all = new HashSet<>();
    for (long seq = 1; seq <= 20_000; seq++) {
      assertThat(all.add(LedgerLoadModel.identityOf(SEED, seq))).isTrue();
      assertThat(all.add(LedgerLoadModel.sourceRecordIdOf(SEED, seq))).isTrue();
    }
  }

  @Test
  void syntheticPathsAreDeterministicAndCompact() {
    for (long seq = 1; seq <= 2_000; seq++) {
      LedgerLoadModel.SyntheticPath a = LedgerLoadModel.syntheticPath(SEED, seq);
      LedgerLoadModel.SyntheticPath b = LedgerLoadModel.syntheticPath(SEED, seq);
      byte[] encoded = PathCodec.encode(a.ruleOrdinals(), a.matched(), a.costUnits());
      assertThat(encoded).isEqualTo(PathCodec.encode(b.ruleOrdinals(), b.matched(), b.costUnits()));
      assertThat(a.ruleOrdinals().length).isBetween(3, 40);
      assertThat(encoded.length).isLessThanOrEqualTo(150);
      assertThat(a.verboseJson()).isEqualTo(b.verboseJson());
      assertThat(a.verboseJson()).startsWith("[{\"table\":").endsWith("}]");
    }
  }

  @Test
  void badParametersAreRejected() {
    assertThatThrownBy(() -> new Params(0, 0.5, 1.5, 0.02, SEED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Params(10, 1.5, 1.5, 0.02, SEED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Params(10, 0.5, 0.5, 0.02, SEED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Params(10, 0.5, 1.5, 0.6, SEED))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
