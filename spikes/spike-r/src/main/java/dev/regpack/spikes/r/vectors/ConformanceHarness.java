package dev.regpack.spikes.r.vectors;

import dev.regpack.model.json.Json;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DateValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the semantic vectors against one {@link ExpressionLayer} and classifies each answer: PASS,
 * FAIL, UNSUPPORTED (the layer says it cannot express the vector) or FUNCTIONAL (the vector passes
 * only through the layer's function-syntax rewrite). Nothing is hidden: a FUNCTIONAL result names
 * the rewrite in {@code actual}.
 */
public final class ConformanceHarness {
  /** Cost units a vector gets when its row names no budget. */
  public static final long DEFAULT_BUDGET = 1_000_000L;

  /** Node-count ceiling a vector gets when its row names none. */
  public static final long DEFAULT_CEILING = 10_000L;

  private static final int MAX_ACTUAL_CHARS = 160;

  private ConformanceHarness() {}

  /** Evaluates every vector on the layer, in file order. */
  public static List<VectorResult> run(ExpressionLayer layer, List<Vector> vectors) {
    List<VectorResult> results = new ArrayList<>();
    for (Vector v : vectors) {
      results.add(runOne(layer, v));
    }
    return List.copyOf(results);
  }

  /**
   * Whether an outcome satisfies an expectation. Syntax: {@code T}, {@code F}, {@code i:<n>},
   * {@code d:<decimal>} (numeric equality), {@code s:<text>}, {@code t:<instant>}, {@code ERR} (any
   * evaluation error), {@code ERR:<KIND>}, {@code COMPILE_ERROR} and {@code ONEOF:<a>|<b>|...}.
   * Only the kind of an error is compared, never a message; an {@link Outcome.Unsupported}
   * satisfies nothing.
   */
  public static boolean matches(String expected, Outcome actual) {
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
    String body = expected.length() > 2 ? expected.substring(2) : "";
    if (expected.equals("T") || expected.equals("F")) {
      return actual instanceof Outcome.Ok(BoolValue(boolean b)) && b == expected.equals("T");
    }
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

  /** The number of FAIL and UNSUPPORTED results among the given vector classes. */
  public static long failures(List<VectorResult> results, String... vectorClasses) {
    Set<String> classes = Set.of(vectorClasses);
    return results.stream()
        .filter(r -> classes.contains(r.vectorClass()))
        .filter(r -> r.result().equals("FAIL") || r.result().equals("UNSUPPORTED"))
        .count();
  }

  /** The outcome of one call, or the text of what the layer threw (a contract breach). */
  private record Attempt(Outcome outcome, String threw) {}

  private static VectorResult runOne(ExpressionLayer layer, Vector v) {
    Map<String, Value> bindings = decodeBindings(v.bindings());
    long budget = v.budget().orElse(DEFAULT_BUDGET);
    long ceiling = v.ceiling().orElse(DEFAULT_CEILING);
    String expression = expand(v.expression());
    Attempt direct = attempt(layer, expression, bindings, budget, ceiling);
    if (direct.threw() != null) {
      return new VectorResult(v.id(), v.vectorClass(), "FAIL", clip("threw " + direct.threw()));
    }
    if (matches(v.expected(), direct.outcome())) {
      return new VectorResult(v.id(), v.vectorClass(), "PASS", describe(direct.outcome()));
    }
    Optional<String> rewrite = layer.rewriteFor(v.id());
    String note = "";
    if (rewrite.isPresent()) {
      Attempt viaRewrite = attempt(layer, rewrite.get(), bindings, budget, ceiling);
      if (viaRewrite.threw() == null && matches(v.expected(), viaRewrite.outcome())) {
        return new VectorResult(
            v.id(),
            v.vectorClass(),
            "FUNCTIONAL",
            clip("via `" + rewrite.get() + "`: " + describe(viaRewrite.outcome())));
      }
      note =
          "; rewrite `"
              + rewrite.get()
              + "` gave "
              + (viaRewrite.threw() != null
                  ? "threw " + viaRewrite.threw()
                  : describe(viaRewrite.outcome()));
    }
    String result = direct.outcome() instanceof Outcome.Unsupported ? "UNSUPPORTED" : "FAIL";
    return new VectorResult(
        v.id(), v.vectorClass(), result, clip(describe(direct.outcome()) + note));
  }

  private static Attempt attempt(
      ExpressionLayer layer,
      String expression,
      Map<String, Value> bindings,
      long budget,
      long ceiling) {
    try {
      return new Attempt(layer.evaluate(expression, bindings, budget, ceiling), null);
    } catch (RuntimeException e) {
      return new Attempt(null, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** {@code @chain(n)} is a sum of n ones. */
  private static String expand(String expression) {
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

  private static Map<String, Value> decodeBindings(Map<String, String> bindings) {
    Map<String, Value> decoded = new HashMap<>();
    for (Map.Entry<String, String> e : bindings.entrySet()) {
      decoded.put(e.getKey(), decodeValue(e.getValue()));
    }
    return Map.copyOf(decoded);
  }

  private static Value decodeValue(String text) {
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
      int n = genCount(body);
      Value item = new StringValue("a");
      for (int i = 0; i < n; i++) {
        items.add(item);
      }
      return new ListValue(items);
    }
    if (!(Json.parse(body) instanceof List<?> raw)) {
      throw new IllegalArgumentException("list binding must be a JSON array: " + body);
    }
    for (Object element : raw) {
      if (!(element instanceof String s)) {
        throw new IllegalArgumentException("list items must be prefixed strings: " + body);
      }
      items.add(decodeValue(s));
    }
    return new ListValue(items);
  }

  private static boolean isGen(String body) {
    return body.startsWith("@gen(") && body.endsWith(")");
  }

  private static int genCount(String body) {
    return Integer.parseInt(body.substring("@gen(".length(), body.length() - 1));
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
      case ListValue l -> {
        StringBuilder out = new StringBuilder("l:[");
        int shown = Math.min(l.items().size(), 5);
        for (int i = 0; i < shown; i++) {
          if (i > 0) {
            out.append(',');
          }
          out.append(render(l.items().get(i)));
        }
        if (l.items().size() > shown) {
          out.append(",...(").append(l.items().size()).append(" items)");
        }
        yield out.append(']').toString();
      }
    };
  }

  /** One line, bounded: the matrix file is tab-separated and a vector can hold a megabyte. */
  private static String clip(String text) {
    String flat = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    return flat.length() <= MAX_ACTUAL_CHARS ? flat : flat.substring(0, MAX_ACTUAL_CHARS) + "...";
  }
}
