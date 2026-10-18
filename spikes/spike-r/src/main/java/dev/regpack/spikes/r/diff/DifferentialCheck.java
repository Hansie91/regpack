package dev.regpack.spikes.r.diff;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.expr.Expression;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.table.RuleTableProgram;
import dev.regpack.spikes.r.table.TableDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs one pack through two expression layers and reports every record on which their decisions
 * differ. A decision is read as {@code OUTCOME/ruleId}; a failed evaluation reads {@code
 * EXEC_ERROR/ruleId} and an exhausted budget {@code EXEC_BUDGET/ruleId}, naming the rule that was
 * running, so two layers that fail in different rules disagree. The cost budget is unlimited: the
 * check compares semantics, never cost.
 */
public final class DifferentialCheck {
  private static final CostBudget UNLIMITED = new CostBudget(Long.MAX_VALUE);

  /** One record on which the layers decide differently; {@code seq} is the record's sequence. */
  public record Mismatch(long seq, String left, String right) {}

  private DifferentialCheck() {}

  /**
   * Compares layer {@code a} (left) with layer {@code b} (right) over the records.
   *
   * @param limit the most mismatches to collect; the run stops when it is reached
   */
  public static List<Mismatch> run(
      Expression a, Expression b, GeneratedPack pack, Iterable<TradeRecord> records, int limit) {
    Objects.requireNonNull(records, "records");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1: " + limit);
    }
    RuleTableProgram left = new RuleTableProgram(CompiledPack.compile(pack, a));
    RuleTableProgram right = new RuleTableProgram(CompiledPack.compile(pack, b));
    MeteredExecutor executor = new MeteredExecutor();
    List<Mismatch> out = new ArrayList<>();
    for (TradeRecord record : records) {
      String l = view(executor.execute(left, record, UNLIMITED));
      String r = view(executor.execute(right, record, UNLIMITED));
      if (!l.equals(r)) {
        out.add(new Mismatch(record.seq(), l, r));
        if (out.size() >= limit) {
          break;
        }
      }
    }
    return List.copyOf(out);
  }

  /** The decision of one record under one layer, in the form the check compares. */
  public static String decisionOf(Expression layer, GeneratedPack pack, TradeRecord record) {
    RuleTableProgram program = new RuleTableProgram(CompiledPack.compile(pack, layer));
    return view(new MeteredExecutor().execute(program, record, UNLIMITED));
  }

  private static String view(ExecutionResult<TableDecision> result) {
    return switch (result) {
      case ExecutionResult.Completed<TableDecision> done ->
          done.value().outcome() + "/" + done.value().ruleId();
      case ExecutionResult.Failed<TableDecision> failed ->
          TableDecision.EXEC_ERROR + "/" + failed.ruleId();
      case ExecutionResult.BudgetExhausted<TableDecision> out ->
          TableDecision.EXEC_BUDGET + "/" + out.ruleId();
    };
  }
}
