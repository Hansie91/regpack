package dev.regpack.spikes.r.inhouse;

import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.spikes.r.inhouse.InHouseExpression.EvalException;
import dev.regpack.spikes.r.vectors.ExpressionLayer;
import dev.regpack.spikes.r.vectors.Outcome;
import java.util.Map;
import java.util.Optional;

/** The in-house subset as a conformance candidate; it needs no function-syntax rewrites. */
public final class InHouseLayer implements ExpressionLayer {
  public InHouseLayer() {}

  @Override
  public String name() {
    return "inhouse";
  }

  @Override
  public Outcome evaluate(
      String expression, Map<String, Value> bindings, long budget, long ceiling) {
    CompiledExpression compiled;
    try {
      compiled = InHouseExpression.withCeiling(ceiling).compile(expression, Map.of());
    } catch (ExpressionException e) {
      return new Outcome.CompileError(String.valueOf(e.getMessage()));
    }
    try {
      CostMeter meter = new CostMeter(new CostBudget(budget));
      return new Outcome.Ok(
          compiled.evaluate(path -> Optional.ofNullable(bindings.get(path)), meter));
    } catch (EvalException e) {
      return new Outcome.Error(e.kind());
    } catch (BudgetExceededException e) {
      return new Outcome.Error("BUDGET");
    } catch (RuntimeException | StackOverflowError e) {
      return new Outcome.Error("OTHER");
    }
  }
}
