package dev.regpack.engine.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.decision.PathStep;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.BudgetExhausted;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.exec.ExecutionResult.Failed;
import dev.regpack.ports.exec.RuleEvaluationException;
import dev.regpack.ports.exec.RuleProgram;
import dev.regpack.ports.exec.Trace;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Toy programs only. No clock is touched: budgets count cost units. */
class MeteredExecutorTest {
  private final MeteredExecutor executor = new MeteredExecutor();

  @FunctionalInterface
  private interface Body {
    String run(String input, CostMeter meter, Trace trace);
  }

  private record Toy(String id, Body body) implements RuleProgram<String, String> {
    @Override
    public String run(String input, CostMeter meter, Trace trace) {
      return body.run(input, meter, trace);
    }
  }

  private static Toy chargingOneUnit(int times) {
    return new Toy(
        "toy",
        (in, meter, trace) -> {
          meter.enter("R1");
          for (int i = 0; i < times; i++) {
            meter.charge(1);
          }
          return in;
        });
  }

  @Test
  void completedResultReportsUnitsAndPath() {
    Toy program =
        new Toy(
            "toy",
            (in, meter, trace) -> {
              meter.enter("R1");
              meter.charge(1);
              trace.step("R1", true, 1);
              meter.enter("R2");
              meter.charge(2);
              trace.step("R2", false, 2);
              return in + "!";
            });

    ExecutionResult<String> result = executor.execute(program, "x", new CostBudget(10));

    assertThat(result)
        .isEqualTo(
            new Completed<>(
                "x!", 3, List.of(new PathStep("R1", true, 1), new PathStep("R2", false, 2))));
  }

  @Test
  void budgetBoundaryIsInclusive() {
    assertThat(executor.execute(chargingOneUnit(10), "x", new CostBudget(10)))
        .isEqualTo(new Completed<>("x", 10, List.of()));

    assertThat(executor.execute(chargingOneUnit(11), "x", new CostBudget(10)))
        .isEqualTo(new BudgetExhausted<String>("R1", 10, 10, List.of()));
  }

  @Test
  void sameInputHitsSameBudgetOnEveryRun() {
    ExecutionResult<String> first = executor.execute(chargingOneUnit(11), "x", new CostBudget(10));
    for (int i = 0; i < 1000; i++) {
      assertThat(executor.execute(chargingOneUnit(11), "x", new CostBudget(10))).isEqualTo(first);
    }
    assertThat(first).isInstanceOf(BudgetExhausted.class);
  }

  @Test
  void ruleEvaluationExceptionBecomesFailedWithCauseClass() {
    Toy program =
        new Toy(
            "toy",
            (in, meter, trace) -> {
              meter.enter("R7");
              meter.charge(4);
              throw new RuleEvaluationException("R7", new ArithmeticException());
            });

    assertThat(executor.execute(program, "x", new CostBudget(10)))
        .isEqualTo(new Failed<String>("R7", "ArithmeticException", 4, List.of()));
  }

  @Test
  void unexpectedRuntimeExceptionBecomesFailedForTheCurrentRule() {
    Toy program =
        new Toy(
            "toy",
            (in, meter, trace) -> {
              meter.enter("R9");
              meter.charge(2);
              throw new IllegalStateException("boom");
            });

    assertThat(executor.execute(program, "x", new CostBudget(10)))
        .isEqualTo(new Failed<String>("R9", "IllegalStateException", 2, List.of()));
  }

  @Test
  void partialPathIsKeptOnFailure() {
    Toy program =
        new Toy(
            "toy",
            (in, meter, trace) -> {
              meter.enter("R1");
              meter.charge(1);
              trace.step("R1", true, 1);
              meter.enter("R2");
              meter.charge(1);
              trace.step("R2", true, 1);
              meter.charge(100);
              return in;
            });

    assertThat(executor.execute(program, "x", new CostBudget(10)))
        .isEqualTo(
            new BudgetExhausted<String>(
                "R2", 2, 10, List.of(new PathStep("R1", true, 1), new PathStep("R2", true, 1))));
  }

  @Test
  void negativeBudgetIsRejected() {
    assertThatThrownBy(() -> new CostBudget(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
