package dev.regpack.spikes.r.bench;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.expr.Expression;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.table.RuleTableProgram;
import dev.regpack.spikes.r.table.TableDecision;
import java.util.List;
import java.util.Objects;

/**
 * R-6: the cost units of one input must be the same on every run (spec 6.6, 14.1). Each input is
 * run {@code repeats} times through the executor and every run whose cost units differ from the
 * first run of that input is counted.
 */
public final class CostRepeats {
  private static final CostBudget UNLIMITED = new CostBudget(Long.MAX_VALUE);

  private CostRepeats() {}

  /** The runs, over all inputs, whose cost units differ from the first run of their input. */
  public static long differingRuns(
      Expression layer, GeneratedPack pack, List<TradeRecord> inputs, int repeats) {
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(pack, "pack");
    if (repeats < 2) {
      throw new IllegalArgumentException("repeats must be at least 2: " + repeats);
    }
    RuleTableProgram program = new RuleTableProgram(CompiledPack.compile(pack, layer));
    MeteredExecutor executor = new MeteredExecutor();
    long differing = 0;
    for (TradeRecord input : inputs) {
      long first = units(executor.execute(program, input, UNLIMITED));
      for (int i = 1; i < repeats; i++) {
        if (units(executor.execute(program, input, UNLIMITED)) != first) {
          differing++;
        }
      }
    }
    return differing;
  }

  private static long units(ExecutionResult<TableDecision> result) {
    return switch (result) {
      case ExecutionResult.Completed<TableDecision> done -> done.costUnits();
      case ExecutionResult.Failed<TableDecision> failed -> failed.costUnits();
      case ExecutionResult.BudgetExhausted<TableDecision> out -> out.costUnits();
    };
  }
}
