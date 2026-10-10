package dev.regpack.spikes.r.table;

import dev.regpack.ports.exec.ExecutionResult;
import java.util.Objects;

/**
 * The decision of one record under a rule pack. {@code outcome} is the name of a model {@code
 * Outcome}; {@code ruleId} is the deciding row (the last catch-all when every table ended in its
 * catch-all); {@code tablesVisited} and {@code rowsEvaluated} count the work done.
 */
public record TableDecision(String outcome, String ruleId, int tablesVisited, int rowsEvaluated) {
  /** The reason code recorded for an evaluation error (spec 7.3). */
  public static final String EXEC_ERROR = "EXEC_ERROR";

  /** The reason code recorded for an exhausted budget (spec 7.3). */
  public static final String EXEC_BUDGET = "EXEC_BUDGET";

  public TableDecision {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(ruleId, "ruleId");
  }

  /**
   * The decision as the caller records it: a completed run is its value; a failed run is
   * UNDETERMINED with reason {@link #EXEC_ERROR} and an exhausted budget is UNDETERMINED with
   * reason {@link #EXEC_BUDGET}. The work counters of an interrupted run are not known and read 0.
   */
  public static TableDecision from(ExecutionResult<TableDecision> result) {
    return switch (result) {
      case ExecutionResult.Completed<TableDecision> done -> done.value();
      case ExecutionResult.Failed<TableDecision> failed ->
          new TableDecision("UNDETERMINED", EXEC_ERROR, 0, 0);
      case ExecutionResult.BudgetExhausted<TableDecision> out ->
          new TableDecision("UNDETERMINED", EXEC_BUDGET, 0, 0);
    };
  }
}
