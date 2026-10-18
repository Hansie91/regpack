package dev.regpack.spikes.r.table;

import static dev.regpack.spikes.r.table.TestRecords.pack;
import static dev.regpack.spikes.r.table.TestRecords.record;
import static dev.regpack.spikes.r.table.TestRecords.rule;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.BudgetExhausted;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.exec.ExecutionResult.Failed;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleTableProgramTest {
  private static final MeteredExecutor EXECUTOR = new MeteredExecutor();

  private static RuleTableProgram program(GeneratedPack pack) {
    return new RuleTableProgram(CompiledPack.compile(pack, new InHouseExpression()));
  }

  private static TableDecision decide(RuleTableProgram program, TradeRecord record) {
    ExecutionResult<TableDecision> result =
        EXECUTOR.execute(program, record, new CostBudget(1_000_000L));
    return ((Completed<TableDecision>) result).value();
  }

  private static TradeRecord eq(long seq, String kind) {
    return record(seq, kind, "EQUITY", true, "LEGAL_ENTITY");
  }

  @Test
  void firstHitWinsByPriority() {
    GeneratedPack pack =
        pack(
            "first-hit",
            List.of(
                List.of(
                    rule("A", 1, 200, "event.kind == 'NEW'", "DO_NOT_REPORT"),
                    rule("B", 1, 100, "event.kind == 'NEW'", "REPORT"),
                    rule("C", 1, 900, "true", "UNDETERMINED"))));
    RuleTableProgram program = program(pack);

    TableDecision hit = decide(program, eq(1, "NEW"));
    assertThat(hit).isEqualTo(new TableDecision("REPORT", "B", 1, 1));

    TableDecision none = decide(program, eq(2, "AMEND"));
    assertThat(none).isEqualTo(new TableDecision("UNDETERMINED", "C", 1, 3));
  }

  @Test
  void catchAllFallsThroughAndAllCatchAllsGiveUndetermined() {
    GeneratedPack pack =
        pack(
            "fall-through",
            List.of(
                List.of(
                    rule("X", 1, 100, "event.kind == 'NOVATION'", "REPORT"),
                    rule("CATCH1", 1, 900, "true", "UNDETERMINED")),
                List.of(
                    rule("Y", 2, 100, "instrument.assetClass == 'EQUITY'", "DO_NOT_REPORT"),
                    rule("CATCH2", 2, 900, "true", "UNDETERMINED"))));
    RuleTableProgram program = program(pack);

    assertThat(decide(program, eq(1, "NOVATION")))
        .as("a hit in the first table stops the walk")
        .isEqualTo(new TableDecision("REPORT", "X", 1, 1));
    assertThat(decide(program, eq(2, "NEW")))
        .as("the first catch-all goes on to the second table")
        .isEqualTo(new TableDecision("DO_NOT_REPORT", "Y", 2, 3));
    assertThat(decide(program, record(3, "NEW", "DEBT", true, "LEGAL_ENTITY")))
        .as("every table ends in its catch-all: UNDETERMINED from the last one")
        .isEqualTo(new TableDecision("UNDETERMINED", "CATCH2", 2, 4));
  }

  @Test
  void errorYieldsExecErrorAndBudgetYieldsExecBudget() {
    GeneratedPack failing =
        pack(
            "failing",
            List.of(
                List.of(
                    rule("OK1", 1, 100, "event.kind == 'ZZ'", "REPORT"),
                    rule("BAD", 1, 200, "trade.price / dec('0') > dec('1')", "REPORT"),
                    rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    ExecutionResult<TableDecision> failed =
        EXECUTOR.execute(program(failing), eq(1, "NEW"), new CostBudget(1_000L));
    assertThat(failed).isInstanceOf(Failed.class);
    assertThat(((Failed<TableDecision>) failed).ruleId()).isEqualTo("BAD");
    assertThat(TableDecision.from(failed))
        .isEqualTo(new TableDecision("UNDETERMINED", "EXEC_ERROR", 0, 0));

    List<dev.regpack.bench.data.GeneratedRule> rows = new ArrayList<>();
    for (int r = 1; r <= 19; r++) {
      rows.add(rule("R" + r, 1, r * 10, "event.kind == 'ZZ'", "REPORT"));
    }
    rows.add(rule("R20", 1, 900, "true", "UNDETERMINED"));
    GeneratedPack twenty = pack("twenty", List.of(rows));
    ExecutionResult<TableDecision> exhausted =
        EXECUTOR.execute(program(twenty), eq(1, "NEW"), new CostBudget(5L));
    assertThat(exhausted).isInstanceOf(BudgetExhausted.class);
    BudgetExhausted<TableDecision> out = (BudgetExhausted<TableDecision>) exhausted;
    assertThat(out.ruleId())
        .as("rows cost 1 plus 3 nodes: the second row breaks 5")
        .isEqualTo("R2");
    assertThat(out.limit()).isEqualTo(5L);
    assertThat(TableDecision.from(exhausted))
        .isEqualTo(new TableDecision("UNDETERMINED", "EXEC_BUDGET", 0, 0));

    ExecutionResult<TableDecision> enough =
        EXECUTOR.execute(program(twenty), eq(1, "NEW"), new CostBudget(1_000L));
    assertThat(enough).isInstanceOf(Completed.class);
    assertThat(((Completed<TableDecision>) enough).costUnits())
        .as("19 failing rows at 4 units, the catch-all at 2")
        .isEqualTo(19 * 4 + 2);
  }

  @Test
  void aWhenThatIsNotABoolFailsClosed() {
    GeneratedPack pack =
        pack(
            "non-bool",
            List.of(
                List.of(
                    rule("NB", 1, 100, "trade.price", "REPORT"),
                    rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    ExecutionResult<TableDecision> result =
        EXECUTOR.execute(program(pack), eq(1, "NEW"), new CostBudget(1_000L));
    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed<TableDecision>) result).ruleId()).isEqualTo("NB");
  }

  @Test
  void s2PackEvaluatesEveryRowForEveryRecord() {
    GeneratedPack s2 = RulePackGen.generate(7L, 200, PackShape.S2_FULL_SCAN_WORST_CASE);
    RuleTableProgram program = program(s2);
    TradeRecordGen gen = new TradeRecordGen(1L);
    for (int i = 0; i < 1_000; i++) {
      TableDecision d = decide(program, gen.next());
      assertThat(d.rowsEvaluated()).as("record " + i).isEqualTo(200);
      assertThat(d.tablesVisited()).isEqualTo(10);
      assertThat(d.outcome()).isEqualTo("UNDETERMINED");
      assertThat(d.ruleId()).isEqualTo("SYN.T10.R020");
    }
  }

  @Test
  void s1PackStopsEarlyForSomeRecords() {
    GeneratedPack s1 = RulePackGen.generate(7L, 200, PackShape.S1_FIRST_HIT_REALISTIC);
    RuleTableProgram program = program(s1);
    TradeRecordGen gen = new TradeRecordGen(1L);
    long evaluated = 0;
    for (int i = 0; i < 1_000; i++) {
      evaluated += decide(program, gen.next()).rowsEvaluated();
    }
    assertThat(evaluated).as("a realistic pack hits early").isLessThan(1_000L * 200);
  }

  @Test
  void theTraceHasOneStepPerEvaluatedRowWhenOnAndNoneWhenOff() {
    GeneratedPack pack =
        pack(
            "trace",
            List.of(
                List.of(
                    rule("A", 1, 100, "event.kind == 'ZZ'", "REPORT"),
                    rule("B", 1, 200, "event.kind == 'NEW'", "REPORT"),
                    rule("C", 1, 900, "true", "UNDETERMINED"))));
    CompiledPack compiled = CompiledPack.compile(pack, new InHouseExpression());
    ExecutionResult<TableDecision> on =
        EXECUTOR.execute(new RuleTableProgram(compiled, true), eq(1, "NEW"), new CostBudget(100));
    assertThat(((Completed<TableDecision>) on).path()).hasSize(2);
    ExecutionResult<TableDecision> off =
        EXECUTOR.execute(new RuleTableProgram(compiled), eq(1, "NEW"), new CostBudget(100));
    assertThat(((Completed<TableDecision>) off).path()).isEmpty();
  }

  @Test
  void costIsTheSameOnEveryRepeat() {
    GeneratedPack s2 = RulePackGen.generate(7L, 200, PackShape.S2_FULL_SCAN_WORST_CASE);
    RuleTableProgram program = program(s2);
    TradeRecord record = new TradeRecordGen(1L).next();
    long first =
        ((Completed<TableDecision>) EXECUTOR.execute(program, record, new CostBudget(1_000_000L)))
            .costUnits();
    for (int i = 0; i < 1_000; i++) {
      long again =
          ((Completed<TableDecision>) EXECUTOR.execute(program, record, new CostBudget(1_000_000L)))
              .costUnits();
      assertThat(again).isEqualTo(first);
    }
  }
}
