package dev.regpack.engine.exec;

import dev.regpack.model.decision.PathStep;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.Executor;
import dev.regpack.ports.exec.RuleEvaluationException;
import dev.regpack.ports.exec.RuleProgram;
import dev.regpack.ports.exec.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The reference executor: it counts deterministic cost units and never reads a clock, so the same
 * input hits the same budget on every run (spec 6.6, 14.1). It creates one meter and one trace per
 * call and keeps no state between calls, so it is safe to share between threads.
 */
public final class MeteredExecutor implements Executor {
  public static final String ID = "metered-reference-v0";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public <I, O> ExecutionResult<O> execute(RuleProgram<I, O> program, I input, CostBudget budget) {
    Objects.requireNonNull(program, "program");
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(budget, "budget");
    CostMeter meter = new CostMeter(budget);
    ListTrace trace = new ListTrace();
    try {
      O value = Objects.requireNonNull(program.run(input, meter, trace), "program result");
      return new ExecutionResult.Completed<>(value, meter.used(), trace.steps());
    } catch (BudgetExceededException e) {
      return new ExecutionResult.BudgetExhausted<>(
          e.ruleId(), meter.used(), budget.maxUnits(), trace.steps());
    } catch (RuleEvaluationException e) {
      Throwable cause = e.getCause();
      return new ExecutionResult.Failed<>(
          e.ruleId(), className(cause == null ? e : cause), meter.used(), trace.steps());
    } catch (RuntimeException e) {
      return new ExecutionResult.Failed<>(
          meter.currentRule(), className(e), meter.used(), trace.steps());
    }
  }

  private static String className(Throwable t) {
    String simple = t.getClass().getSimpleName();
    return simple.isEmpty() ? t.getClass().getName() : simple;
  }

  private static final class ListTrace implements Trace {
    private final List<PathStep> steps = new ArrayList<>();

    @Override
    public void step(String ruleId, boolean matched, long costUnits) {
      steps.add(new PathStep(ruleId, matched, costUnits));
    }

    @Override
    public List<PathStep> steps() {
      return List.copyOf(steps);
    }
  }
}
