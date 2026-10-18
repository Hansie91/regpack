package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Outcome;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards against a vacuous throughput benchmark (review focus PF20): the benchmark in {@link
 * PackThroughputCheck} is only information, so these checks make sure it measures what it claims.
 * They count rows, outcomes and a checksum; none of them reads a clock or asserts on a rate.
 */
class PackThroughputGuardTest {
  private static final int RECORDS = 1_000;
  private static final int RULES = 200;
  private static final int TABLES = 10;

  private static GeneratedPack worstCase(long seed) {
    return RulePackGen.generate(seed, RULES, PackShape.S2_FULL_SCAN_WORST_CASE);
  }

  private static List<TradeRecord> records(long seed, int count) {
    TradeRecordGen generator = new TradeRecordGen(seed);
    List<TradeRecord> records = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      records.add(generator.next());
    }
    return records;
  }

  @Test
  void everyRecordEvaluatesAllTwoHundredRows() {
    CompiledPack pack = GeneratedPackSource.compile(worstCase(1));
    TradeRecordBindings bindings = new TradeRecordBindings();
    MeteredExecutor executor = new MeteredExecutor();
    CostBudget budget = new CostBudget(pack.budget().perRecordUnits());
    String lastCatchAll = pack.lastTable().rows().getLast().id();

    for (TradeRecord record : records(1, RECORDS)) {
      bindings.reset(record);
      PackProgram program = new PackProgram(pack, true);
      ExecutionResult<PackProgram.Decision> result = executor.execute(program, bindings, budget);

      assertThat(result).isInstanceOf(Completed.class);
      PackProgram.Decision decision = ((Completed<PackProgram.Decision>) result).value();
      assertThat(program.rowTraces()).hasSize(RULES);
      assertThat(decision.row().outcome()).isEqualTo(Outcome.UNDETERMINED);
      assertThat(decision.row().id()).isEqualTo(lastCatchAll);
      assertThat(decision.tableId()).isEqualTo(pack.lastTable().id());
    }
    assertThat(pack.tables()).hasSize(TABLES);
  }

  @Test
  void noExecOutcomesOnTheCleanCorpus() {
    // A smoke datum on a synthetic corpus, 0 by construction: not the SP-R shadow-run measurement.
    CompiledPack pack = GeneratedPackSource.compile(worstCase(1));
    TradeRecordBindings bindings = new TradeRecordBindings();
    MeteredExecutor executor = new MeteredExecutor();
    CostBudget budget = new CostBudget(pack.budget().perRecordUnits());
    int failed = 0;
    int exhausted = 0;
    int completed = 0;

    for (TradeRecord record : records(1, RECORDS)) {
      bindings.reset(record);
      ExecutionResult<PackProgram.Decision> result =
          executor.execute(new PackProgram(pack, false), bindings, budget);
      switch (result) {
        case ExecutionResult.Completed<PackProgram.Decision> done -> completed++;
        case ExecutionResult.Failed<PackProgram.Decision> error -> failed++;
        case ExecutionResult.BudgetExhausted<PackProgram.Decision> out -> exhausted++;
      }
    }

    assertThat(failed).as("Failed results").isZero();
    assertThat(exhausted).as("BudgetExhausted results").isZero();
    assertThat(completed).isEqualTo(RECORDS);
  }

  @Test
  void checksumChangesWithThePack() {
    TradeRecord[] ring = records(1, RECORDS).toArray(new TradeRecord[0]);
    long seedOne = checksum(GeneratedPackSource.compile(worstCase(1)), ring);
    long seedOneAgain = checksum(GeneratedPackSource.compile(worstCase(1)), ring);
    long seedTwo = checksum(GeneratedPackSource.compile(worstCase(2)), ring);

    assertThat(seedOneAgain).as("the checksum of one pack is reproducible").isEqualTo(seedOne);
    assertThat(seedTwo).as("another pack gives another checksum").isNotEqualTo(seedOne);
  }

  @Test
  void theGeneratedPackPassesPackChecks() {
    LoadResult loaded = GeneratedPackSource.load(worstCase(1));

    List<String> problems = new ArrayList<>();
    for (PackProblem problem : loaded.problems()) {
      problems.add(problem.code() + " " + problem.path() + ": " + problem.message());
    }
    assertThat(problems).isEmpty();
    assertThat(loaded.pack()).isPresent();
    assertThat(loaded.pack().orElseThrow().scopeTables()).hasSize(TABLES);
    assertThat(loaded.pack().orElseThrow().reasons().entries()).hasSize(RULES);
    assertThat(
            CompiledPack.compile(
                    loaded.pack().orElseThrow(), GeneratedPackSource.REPORT_ID, Rulings.load())
                .problems())
        .isEmpty();
  }

  private static long checksum(CompiledPack pack, TradeRecord[] ring) {
    PackWorkload workload = new PackWorkload(pack, ring, 0);
    assertThat(workload.runUnits(RECORDS)).isEqualTo(RECORDS);
    assertThat(workload.execOutcomes()).isZero();
    return workload.checksum();
  }
}
