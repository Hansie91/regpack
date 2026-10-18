package dev.regpack.spikes.r.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.proto.Workload;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ThroughputBenchTest {
  private static final long BUDGET = 10_000_000L;
  private static final TradeRecord[] SMALL_RING = ThroughputBench.ring(1L, 2_000);

  private static ThroughputBench bench(GeneratedPack pack, int thread) {
    return new ThroughputBench(new InHouseExpression(), pack, SMALL_RING, thread, BUDGET);
  }

  private static long checksumAfter(GeneratedPack pack, long records) {
    ThroughputBench bench = bench(pack, 0);
    assertThat(bench.runUnits(records)).isEqualTo(records);
    return bench.checksum();
  }

  /** The same pack with the outcome of one rule replaced. */
  private static GeneratedPack withOutcome(GeneratedPack pack, String ruleId, String outcome) {
    List<List<GeneratedRule>> tables = new ArrayList<>();
    for (List<GeneratedRule> table : pack.tables()) {
      List<GeneratedRule> rows = new ArrayList<>();
      for (GeneratedRule r : table) {
        rows.add(
            r.id().equals(ruleId)
                ? new GeneratedRule(r.id(), r.table(), r.priority(), r.when(), outcome, r.reason())
                : r);
      }
      tables.add(rows);
    }
    return new GeneratedPack(pack.id(), pack.seed(), pack.shape(), pack.ruleCount(), tables);
  }

  @Test
  void checksumDefeatsDeadCodeElimination() {
    GeneratedPack s1 = RulePackGen.generate(5L, 40, PackShape.S1_FIRST_HIT_REALISTIC);
    GeneratedPack s2 = RulePackGen.generate(5L, 40, PackShape.S2_FULL_SCAN_WORST_CASE);

    long first = checksumAfter(s1, 1_500);
    assertThat(checksumAfter(s1, 1_500)).as("same ring, same pack").isEqualTo(first);
    assertThat(first).as("the checksum is not the empty value").isNotZero();
    assertThat(checksumAfter(s1, 1_000))
        .as("a shorter run folds fewer decisions")
        .isNotEqualTo(first);
    assertThat(checksumAfter(s2, 1_500)).as("S1 and S2 packs differ").isNotEqualTo(first);

    // flip the outcome of a rule that some record of the ring really hits
    GeneratedRule hit =
        s1.tables().stream()
            .flatMap(List::stream)
            .filter(r -> !r.when().equals("true"))
            .filter(
                r -> {
                  GeneratedPack flipped = withOutcome(s1, r.id(), flip(r.outcome()));
                  return checksumAfter(flipped, 1_500) != first;
                })
            .findFirst()
            .orElseThrow(() -> new AssertionError("no rule of the pack changes the checksum"));
    assertThat(hit.id()).isNotBlank();
  }

  private static String flip(String outcome) {
    return outcome.equals("REPORT") ? "DO_NOT_REPORT" : "REPORT";
  }

  @Test
  void checksumDependsOnWhichRuleDecided() {
    GeneratedPack s1 = RulePackGen.generate(5L, 40, PackShape.S1_FIRST_HIT_REALISTIC);
    // renaming a rule keeps outcomes and changes only the rule id hash
    List<List<GeneratedRule>> tables = new ArrayList<>();
    for (List<GeneratedRule> table : s1.tables()) {
      List<GeneratedRule> rows = new ArrayList<>();
      for (GeneratedRule r : table) {
        rows.add(
            new GeneratedRule(
                "X" + r.id(), r.table(), r.priority(), r.when(), r.outcome(), r.reason()));
      }
      tables.add(rows);
    }
    GeneratedPack renamed =
        new GeneratedPack(s1.id(), s1.seed(), s1.shape(), s1.ruleCount(), tables);
    assertThat(checksumAfter(renamed, 1_500)).isNotEqualTo(checksumAfter(s1, 1_500));
  }

  @Test
  void ringHasAtLeast200000DistinctRecords() {
    TradeRecord[] ring = ThroughputBench.ring(1L, ThroughputBench.RING_SIZE);
    assertThat(ring.length).isGreaterThanOrEqualTo(200_000);
    Set<String> ids = new HashSet<>();
    for (TradeRecord r : ring) {
      ids.add(r.executionId());
    }
    assertThat(ids.size()).as("distinct executionId").isGreaterThanOrEqualTo(200_000);

    GeneratedPack pack = RulePackGen.generate(5L, 20, PackShape.S2_FULL_SCAN_WORST_CASE);
    Set<Integer> starts = new HashSet<>();
    for (int thread = 0; thread < 4; thread++) {
      ThroughputBench b =
          new ThroughputBench(new InHouseExpression(), pack, SMALL_RING, thread, BUDGET);
      starts.add(b.startPosition());
    }
    assertThat(starts).as("threads start at different ring positions").hasSize(4);
  }

  @Test
  void runUnitsRunsExactlyWhatWasAskedAndWrapsTheRing() {
    GeneratedPack pack = RulePackGen.generate(5L, 20, PackShape.S2_FULL_SCAN_WORST_CASE);
    ThroughputBench b = bench(pack, 1);
    assertThat(b.runUnits(0)).isZero();
    assertThat(b.runUnits(SMALL_RING.length + 17L)).isEqualTo(SMALL_RING.length + 17L);
    assertThatThrownBy(() -> b.runUnits(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parallelWorkloadSumsTheUnitsOfItsThreads() {
    GeneratedPack pack = RulePackGen.generate(5L, 20, PackShape.S2_FULL_SCAN_WORST_CASE);
    List<ThroughputBench> benches = new ArrayList<>();
    for (int t = 0; t < 4; t++) {
      benches.add(bench(pack, t));
    }
    try (ParallelWorkload parallel = new ParallelWorkload(benches)) {
      assertThat(parallel.runUnits(1)).isEqualTo(1);
      assertThat(parallel.runUnits(3)).isEqualTo(3);
      assertThat(parallel.runUnits(4_003)).isEqualTo(4_003);
      assertThat(parallel.runUnits(0)).isZero();
      long before = parallel.checksum();
      parallel.runUnits(400);
      assertThat(parallel.checksum()).isNotEqualTo(before);
    }
  }

  @Test
  void parallelWorkloadReportsAWorkerFailure() {
    GeneratedPack pack = RulePackGen.generate(5L, 20, PackShape.S2_FULL_SCAN_WORST_CASE);
    ThroughputBench good = bench(pack, 0);
    Workload bad =
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
    try (ParallelWorkload parallel = new ParallelWorkload(List.of(good, bad))) {
      assertThatThrownBy(() -> parallel.runUnits(10))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("boom");
    }
  }
}
