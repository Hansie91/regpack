package dev.regpack.engine.skeleton;

import dev.regpack.engine.skeleton.WalkingSkeletonRules.Row;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.exec.RuleEvaluationException;
import dev.regpack.ports.exec.RuleProgram;
import dev.regpack.ports.exec.Trace;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * WALKING SKELETON: replaced by P2a.
 *
 * <p>Evaluates the placeholder rows in ascending priority (hit policy FIRST). Each row charges 1
 * for itself and then whatever its expression charges; the trace step records that total.
 */
public final class ScopeProgram implements RuleProgram<CanonicalExecution, Row> {
  /** The variables every row expression is compiled against. */
  static final Map<String, ValueType> VARIABLES =
      Map.of(
          "event.kind", ValueType.ENUM,
          "client.kind", ValueType.ENUM,
          "instrument.assetClass", ValueType.ENUM,
          "instrument.tradedOnVenue", ValueType.BOOL);

  private record Compiled(Row row, CompiledExpression when) {}

  private final List<Compiled> compiled;

  /** Compiles every row's {@code when} once. */
  public ScopeProgram(Expression expression) {
    Objects.requireNonNull(expression, "expression");
    this.compiled =
        WalkingSkeletonRules.rows().stream()
            .map(row -> new Compiled(row, expression.compile(row.when(), VARIABLES)))
            .toList();
  }

  @Override
  public String id() {
    return "walking-skeleton-scope-v0";
  }

  @Override
  public Row run(CanonicalExecution input, CostMeter meter, Trace trace) {
    Bindings bindings = bindings(input);
    for (Compiled entry : compiled) {
      Row row = entry.row();
      meter.enter(row.id());
      long before = meter.used();
      meter.charge(1);
      boolean matched;
      try {
        // Fail closed (I5): a non-bool result is an error, never a silent skip to the next row.
        if (!(entry.when().evaluate(bindings, meter) instanceof BoolValue result)) {
          throw new ExpressionException("when must be bool: " + row.id());
        }
        matched = result.value();
      } catch (ExpressionException e) {
        throw new RuleEvaluationException(row.id(), e);
      }
      trace.step(row.id(), matched, meter.used() - before);
      if (matched) {
        return row;
      }
    }
    throw new IllegalStateException("no row matched; the last row must be the catch-all");
  }

  private static Bindings bindings(CanonicalExecution e) {
    Map<String, Value> values =
        Map.of(
            "event.kind", new EnumValue("LifecycleKind", e.eventKind().name()),
            "client.kind", new EnumValue("PartyKind", e.clientKind().name()),
            "instrument.assetClass", new EnumValue("AssetClass", e.assetClass().name()),
            "instrument.tradedOnVenue", new BoolValue(e.tradedOnVenue()));
    return path -> Optional.ofNullable(values.get(path));
  }
}
