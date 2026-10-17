package dev.regpack.engine.expr.conformance;

import dev.regpack.engine.expr.ExprException;
import dev.regpack.engine.expr.InHouseExpression;
import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DateValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import dev.regpack.ports.expr.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the conformance vectors against one {@link Layer} and classifies each answer as PASS, FAIL
 * (the layer answered, or threw, something else) or UNSUPPORTED (the layer says it cannot express
 * the vector; this satisfies nothing). Nothing is hidden: {@code actual} always names the answer.
 *
 * <p>Design read from the SP-R spike harness; written fresh (plan Convention 4).
 */
final class ConformanceHarness {
  /** Cost units a vector gets when its row names no budget. */
  static final long DEFAULT_BUDGET = 1_000_000L;

  private static final int MAX_ACTUAL_CHARS = 160;
  private static final int LIST_ITEM_LIMIT = 256;
  private static final int STRING_LIMIT = 4_096;

  private ConformanceHarness() {}

  /** What a layer answers for one vector. A layer never throws; it returns one of these. */
  sealed interface Outcome {
    /** The expression evaluated to a value. */
    record Ok(Value value) implements Outcome {
      public Ok {
        Objects.requireNonNull(value, "value");
      }
    }

    /** The expression compiled and failed at evaluation; only the kind is compared. */
    record Error(String kind) implements Outcome {
      public Error {
        Objects.requireNonNull(kind, "kind");
      }
    }

    /** The layer refused to compile the expression; the reason is data, never compared. */
    record CompileError(String reason) implements Outcome {
      public CompileError {
        Objects.requireNonNull(reason, "reason");
      }
    }

    /** The layer cannot express the vector at all. */
    record Unsupported(String reason) implements Outcome {
      public Unsupported {
        Objects.requireNonNull(reason, "reason");
      }
    }
  }

  /** The decoded bindings cell: the bound values and the declared type of every path. */
  record Bound(Map<String, Value> values, Map<String, ValueType> declared) {
    Bound {
      values = Map.copyOf(values);
      declared = Map.copyOf(declared);
    }
  }

  /** A candidate expression layer as the harness sees it. */
  interface Layer {
    /** Compiles and evaluates once; a budget or node-ceiling breach is an outcome. */
    Outcome evaluate(String expression, Bound bound, long budget, long ceiling);
  }

  /** The result of one vector on one layer. */
  record Result(String id, String vectorClass, String result, String actual) {}

  /** The production layer: {@link InHouseExpression} with the given rulings. */
  static Layer production(Rulings rulings) {
    return (expression, bound, budget, ceiling) -> {
      int maxNodes = ceiling > 0 ? (int) ceiling : Limits.defaults().maxNodes();
      InHouseExpression layer =
          new InHouseExpression(new Limits(maxNodes, LIST_ITEM_LIMIT, STRING_LIMIT), rulings);
      InHouseExpression.Compiled compiled;
      try {
        compiled = layer.compile(expression, bound.declared());
      } catch (ExpressionException e) {
        return new Outcome.CompileError(e.getClass().getSimpleName());
      }
      Map<String, Value> values = bound.values();
      Bindings bindings = path -> Optional.ofNullable(values.get(path));
      try {
        return new Outcome.Ok(compiled.evaluate(bindings, new CostMeter(new CostBudget(budget))));
      } catch (BudgetExceededException e) {
        return new Outcome.Error("BUDGET");
      } catch (ExprException e) {
        return new Outcome.Error(e.kind().name());
      } catch (ExpressionException e) {
        return new Outcome.Error("OTHER");
      }
    };
  }

  /**
   * Evaluates every vector on the layer, in file order. {@code rulings} gives the expected value of
   * each {@code @ruling} vector by id.
   */
  static List<Result> run(Layer layer, List<Vector> vectors, Map<String, String> rulings) {
    List<Result> results = new ArrayList<>();
    for (Vector v : vectors) {
      results.add(runOne(layer, v, rulings));
    }
    return List.copyOf(results);
  }

  /** The expected text of a vector once a {@code @ruling} is resolved. */
  static String expectedOf(Vector v, Map<String, String> rulings) {
    if (!v.expected().equals(Vector.RULING)) {
      return v.expected();
    }
    String resolved = rulings.get(v.id());
    if (resolved == null) {
      throw new IllegalArgumentException("no ruling for " + v.id());
    }
    return resolved;
  }

  /**
   * Whether an outcome satisfies an expectation. Syntax: {@code T}, {@code F}, {@code i:<n>},
   * {@code d:<decimal>} (numeric equality), {@code s:<text>}, {@code t:<instant>}, {@code ERR} (any
   * evaluation error), {@code ERR:<KIND>}, {@code COMPILE_ERROR} and {@code ONEOF:<a>|<b>|...}.
   * Only the kind of an error is compared, never a message; an {@link Outcome.Unsupported}
   * satisfies nothing.
   */
  static boolean matches(String expected, Outcome actual) {
    if (actual instanceof Outcome.Unsupported) {
      return false;
    }
    if (expected.startsWith("ONEOF:")) {
      for (String alternative : expected.substring("ONEOF:".length()).split("\\|", -1)) {
        if (matches(alternative, actual)) {
          return true;
        }
      }
      return false;
    }
    if (expected.equals("COMPILE_ERROR")) {
      return actual instanceof Outcome.CompileError;
    }
    if (expected.equals("ERR")) {
      return actual instanceof Outcome.Error;
    }
    if (expected.startsWith("ERR:")) {
      return actual instanceof Outcome.Error e && e.kind().equals(expected.substring(4));
    }
    if (expected.equals("T") || expected.equals("F")) {
      return actual instanceof Outcome.Ok(BoolValue(boolean b)) && b == expected.equals("T");
    }
    String body = expected.length() > 2 ? expected.substring(2) : "";
    if (expected.startsWith("i:")) {
      return actual instanceof Outcome.Ok(IntValue(long n)) && n == Long.parseLong(body);
    }
    if (expected.startsWith("d:")) {
      return actual instanceof Outcome.Ok(DecimalValue(BigDecimal d))
          && d.compareTo(new BigDecimal(body)) == 0;
    }
    if (expected.startsWith("s:")) {
      return actual instanceof Outcome.Ok(StringValue(String s)) && s.equals(body);
    }
    if (expected.startsWith("t:")) {
      return actual instanceof Outcome.Ok(TimestampValue(Instant t))
          && t.equals(Instant.parse(body));
    }
    throw new IllegalArgumentException("unknown expectation " + expected);
  }

  /** The number of results that are not PASS among the given vector classes. */
  static long failures(List<Result> results, String... vectorClasses) {
    Set<String> classes = Set.of(vectorClasses);
    return results.stream()
        .filter(r -> classes.contains(r.vectorClass()))
        .filter(r -> !r.result().equals("PASS"))
        .count();
  }

  /** {@code @chain(n)} is a sum of n ones; any other expression is unchanged. */
  static String expand(String expression) {
    if (expression.startsWith("@chain(") && expression.endsWith(")")) {
      int n = Integer.parseInt(expression.substring("@chain(".length(), expression.length() - 1));
      StringBuilder out = new StringBuilder(n * 4);
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          out.append(" + ");
        }
        out.append('1');
      }
      return out.toString();
    }
    return expression;
  }

  /**
   * Decodes a bindings cell: {@code ;}-separated {@code path=type:value} items. Types: {@code s:}
   * string, {@code i:} int, {@code d:} decimal, {@code b:} bool, {@code t:} timestamp, {@code D:}
   * date, {@code e:Type.NAME} enum, {@code l:<item>|<item>} list of typed items, {@code
   * absent:<VALUETYPE>} (declared, unbound). {@code @gen(n)} is a string of n characters or a list
   * of n items. An empty cell declares nothing.
   */
  static Bound decode(String cell) {
    Map<String, Value> values = new HashMap<>();
    Map<String, ValueType> declared = new HashMap<>();
    if (!cell.isEmpty()) {
      for (String item : cell.split(";", -1)) {
        int eq = item.indexOf('=');
        if (eq <= 0) {
          throw new IllegalArgumentException("binding without a path: " + item);
        }
        String path = item.substring(0, eq);
        String typed = item.substring(eq + 1);
        if (declared.containsKey(path)) {
          throw new IllegalArgumentException("path bound twice: " + path);
        }
        if (typed.startsWith("absent:")) {
          declared.put(path, ValueType.valueOf(typed.substring("absent:".length())));
        } else {
          Value value = decodeValue(typed);
          values.put(path, value);
          declared.put(path, typeOf(value));
        }
      }
    }
    return new Bound(values, declared);
  }

  private static ValueType typeOf(Value value) {
    return switch (value) {
      case BoolValue b -> ValueType.BOOL;
      case IntValue i -> ValueType.INT;
      case DecimalValue d -> ValueType.DECIMAL;
      case StringValue s -> ValueType.STRING;
      case TimestampValue t -> ValueType.TIMESTAMP;
      case DateValue d -> ValueType.DATE;
      case EnumValue e -> ValueType.ENUM;
      case ListValue l -> ValueType.LIST;
    };
  }

  static Value decodeValue(String text) {
    if (text.length() < 2 || text.charAt(1) != ':') {
      throw new IllegalArgumentException("binding value without a type prefix: " + text);
    }
    String body = text.substring(2);
    return switch (text.charAt(0)) {
      case 's' -> isGen(body) ? new StringValue("a".repeat(genCount(body))) : new StringValue(body);
      case 'i' -> new IntValue(Long.parseLong(body));
      case 'd' -> new DecimalValue(new BigDecimal(body));
      case 'b' -> {
        if (!body.equals("true") && !body.equals("false")) {
          throw new IllegalArgumentException("not a bool: " + body);
        }
        yield new BoolValue(body.equals("true"));
      }
      case 't' -> new TimestampValue(Instant.parse(body));
      case 'D' -> new DateValue(LocalDate.parse(body));
      case 'e' -> {
        int dot = body.lastIndexOf('.');
        if (dot <= 0) {
          throw new IllegalArgumentException("enum needs Type.NAME: " + body);
        }
        yield new EnumValue(body.substring(0, dot), body.substring(dot + 1));
      }
      case 'l' -> decodeList(body);
      default -> throw new IllegalArgumentException("unknown binding prefix in " + text);
    };
  }

  private static Value decodeList(String body) {
    List<Value> items = new ArrayList<>();
    if (isGen(body)) {
      Value item = new StringValue("a");
      for (int i = 0, n = genCount(body); i < n; i++) {
        items.add(item);
      }
    } else if (!body.isEmpty()) {
      for (String element : body.split("\\|", -1)) {
        items.add(decodeValue(element));
      }
    }
    return new ListValue(items);
  }

  private static boolean isGen(String body) {
    return body.startsWith("@gen(") && body.endsWith(")");
  }

  private static int genCount(String body) {
    return Integer.parseInt(body.substring("@gen(".length(), body.length() - 1));
  }

  /** The outcome of one call, or the text of what the layer threw (a contract breach). */
  private record Attempt(Outcome outcome, String threw) {}

  private static Result runOne(Layer layer, Vector v, Map<String, String> rulings) {
    String expected = expectedOf(v, rulings);
    Bound bound = decode(v.bindings());
    long budget = v.budget().orElse(DEFAULT_BUDGET);
    long ceiling = v.ceiling().orElse(0L);
    Attempt attempt;
    try {
      attempt = new Attempt(layer.evaluate(expand(v.expression()), bound, budget, ceiling), null);
    } catch (RuntimeException | StackOverflowError e) {
      attempt = new Attempt(null, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    if (attempt.threw() != null) {
      return new Result(v.id(), v.vectorClass(), "FAIL", clip("threw " + attempt.threw()));
    }
    String result;
    if (matches(expected, attempt.outcome())) {
      result = "PASS";
    } else if (attempt.outcome() instanceof Outcome.Unsupported) {
      result = "UNSUPPORTED";
    } else {
      result = "FAIL";
    }
    return new Result(v.id(), v.vectorClass(), result, clip(describe(attempt.outcome())));
  }

  private static String describe(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Ok ok -> "Ok(" + render(ok.value()) + ")";
      case Outcome.Error e -> "Error(" + e.kind() + ")";
      case Outcome.CompileError c -> "CompileError(" + c.reason() + ")";
      case Outcome.Unsupported u -> "Unsupported(" + u.reason() + ")";
    };
  }

  private static String render(Value value) {
    return switch (value) {
      case BoolValue b -> b.value() ? "T" : "F";
      case IntValue i -> "i:" + i.value();
      case DecimalValue d -> "d:" + d.value().toPlainString();
      case StringValue s -> "s:" + s.value();
      case TimestampValue t -> "t:" + t.value();
      case DateValue d -> "D:" + d.value();
      case EnumValue e -> "e:" + e.type() + "." + e.name();
      case ListValue l -> "l:(" + l.items().size() + " items)";
    };
  }

  /** One line, bounded: a vector can hold a megabyte. */
  private static String clip(String text) {
    String flat = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    return flat.length() <= MAX_ACTUAL_CHARS ? flat : flat.substring(0, MAX_ACTUAL_CHARS) + "...";
  }
}
