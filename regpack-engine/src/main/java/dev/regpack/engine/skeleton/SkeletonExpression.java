package dev.regpack.engine.skeleton;

import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.ValueType;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * WALKING SKELETON: replaced by P2a.
 *
 * <p>A stand-in {@link Expression} that compiles exactly the four sources of the placeholder rows
 * and rejects anything else. Evaluation charges the declared cost to the meter before it reads any
 * binding, so the cost does not depend on the data.
 */
public final class SkeletonExpression implements Expression {
  /** The known sources, their declared cost, their variables and their evaluation. */
  private enum Known {
    LIFECYCLE_EXCLUSION(
        "event.kind in ['NOVATION','ASSIGNMENT']",
        2,
        Map.of("event.kind", ValueType.ENUM),
        b -> new BoolValue(Set.of("NOVATION", "ASSIGNMENT").contains(enumName(b, "event.kind")))),
    NATURAL_PERSON(
        "client.kind == 'NATURAL_PERSON'",
        1,
        Map.of("client.kind", ValueType.ENUM),
        b -> new BoolValue(enumName(b, "client.kind").equals("NATURAL_PERSON"))),
    EQUITY_ON_VENUE(
        "instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue",
        2,
        Map.of("instrument.assetClass", ValueType.ENUM, "instrument.tradedOnVenue", ValueType.BOOL),
        b ->
            new BoolValue(
                enumName(b, "instrument.assetClass").equals("EQUITY")
                    && bool(b, "instrument.tradedOnVenue"))),
    ALWAYS("true", 0, Map.of(), b -> new BoolValue(true));

    private final String source;
    private final long cost;
    private final Map<String, ValueType> required;
    private final Function<Bindings, Value> evaluation;

    Known(
        String source,
        long cost,
        Map<String, ValueType> required,
        Function<Bindings, Value> evaluation) {
      this.source = source;
      this.cost = cost;
      this.required = required;
      this.evaluation = evaluation;
    }
  }

  @Override
  public CompiledExpression compile(String source, Map<String, ValueType> variables) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(variables, "variables");
    for (Known known : Known.values()) {
      if (known.source.equals(source)) {
        for (Map.Entry<String, ValueType> need : known.required.entrySet()) {
          if (variables.get(need.getKey()) != need.getValue()) {
            throw new ExpressionException(
                "variable " + need.getKey() + " must be declared as " + need.getValue());
          }
        }
        return new Compiled(known);
      }
    }
    throw new ExpressionException("source is not part of the walking skeleton: " + source);
  }

  private record Compiled(Known known) implements CompiledExpression {
    @Override
    public String source() {
      return known.source;
    }

    @Override
    public Value evaluate(Bindings bindings, CostMeter meter) {
      meter.charge(known.cost);
      return known.evaluation.apply(bindings);
    }
  }

  private static Value read(Bindings bindings, String path) {
    return bindings
        .get(path)
        .orElseThrow(() -> new ExpressionException("absent binding without guard: " + path));
  }

  private static String enumName(Bindings bindings, String path) {
    if (read(bindings, path) instanceof EnumValue e) {
      return e.name();
    }
    throw new ExpressionException("binding " + path + " is not an enum");
  }

  private static boolean bool(Bindings bindings, String path) {
    if (read(bindings, path) instanceof BoolValue b) {
      return b.value();
    }
    throw new ExpressionException("binding " + path + " is not a bool");
  }
}
