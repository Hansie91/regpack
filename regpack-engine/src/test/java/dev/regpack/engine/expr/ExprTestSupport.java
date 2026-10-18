package dev.regpack.engine.expr;

import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.ValueType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small helpers shared by the evaluation tests of the expression layer. */
final class ExprTestSupport {
  /** A budget that no test expression comes near. */
  static final long BIG = 1_000_000_000L;

  private ExprTestSupport() {}

  /** Bindings over a map; every {@code get} is recorded. */
  static final class Recording implements Bindings {
    private final Map<String, Value> values;
    private final List<String> reads = new ArrayList<>();

    Recording(Map<String, Value> values) {
      this.values = Map.copyOf(values);
    }

    @Override
    public Optional<Value> get(String path) {
      reads.add(path);
      return Optional.ofNullable(values.get(path));
    }

    List<String> reads() {
      return List.copyOf(reads);
    }
  }

  /** Bindings from alternating path and value arguments. */
  static Recording bind(Object... pairs) {
    Map<String, Value> values = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      values.put((String) pairs[i], (Value) pairs[i + 1]);
    }
    return new Recording(values);
  }

  /** Variable map declaring every given path (the type is irrelevant: typing is dynamic). */
  static Map<String, ValueType> declare(Iterable<String> paths) {
    Map<String, ValueType> vars = new HashMap<>();
    for (String path : paths) {
      vars.put(path, ValueType.STRING);
    }
    return Map.copyOf(vars);
  }

  /** Compiles against the paths the bindings hold plus the extra declared paths. */
  static InHouseExpression.Compiled compile(
      InHouseExpression layer, String source, Recording bindings, String... extraDeclared) {
    List<String> paths = new ArrayList<>(bindings.values.keySet());
    paths.addAll(List.of(extraDeclared));
    return layer.compile(source, declare(paths));
  }

  static Value eval(String source, Recording bindings, String... extraDeclared) {
    return eval(new InHouseExpression(), source, bindings, extraDeclared);
  }

  static Value eval(
      InHouseExpression layer, String source, Recording bindings, String... extraDeclared) {
    return compile(layer, source, bindings, extraDeclared)
        .evaluate(bindings, new CostMeter(new CostBudget(BIG)));
  }

  static Value eval(String source) {
    return eval(source, bind());
  }

  /** Cost units one evaluation of the source spends. */
  static long units(String source, Recording bindings, String... extraDeclared) {
    CostMeter meter = new CostMeter(new CostBudget(BIG));
    compile(new InHouseExpression(), source, bindings, extraDeclared).evaluate(bindings, meter);
    return meter.used();
  }

  static BoolValue bool(boolean value) {
    return new BoolValue(value);
  }

  static IntValue integer(long value) {
    return new IntValue(value);
  }

  static DecimalValue decimal(String value) {
    return new DecimalValue(new BigDecimal(value));
  }

  static StringValue text(String value) {
    return new StringValue(value);
  }
}
