package dev.regpack.ports.exec;

import dev.regpack.model.decision.PathStep;
import java.util.List;
import java.util.Objects;

/** The outcome of one {@link Executor#execute}: completed, failed, or out of budget. */
public sealed interface ExecutionResult<O> {
  record Completed<O>(O value, long costUnits, List<PathStep> path) implements ExecutionResult<O> {
    public Completed {
      Objects.requireNonNull(value, "value");
      path = List.copyOf(path);
    }
  }

  record Failed<O>(String ruleId, String errorClass, long costUnits, List<PathStep> path)
      implements ExecutionResult<O> {
    public Failed {
      Objects.requireNonNull(ruleId, "ruleId");
      Objects.requireNonNull(errorClass, "errorClass");
      path = List.copyOf(path);
    }
  }

  record BudgetExhausted<O>(String ruleId, long costUnits, long limit, List<PathStep> path)
      implements ExecutionResult<O> {
    public BudgetExhausted {
      Objects.requireNonNull(ruleId, "ruleId");
      path = List.copyOf(path);
    }
  }
}
