package dev.regpack.spikes.r.cel;

import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.spikes.r.vectors.ExpressionLayer;
import dev.regpack.spikes.r.vectors.Outcome;
import dev.regpack.spikes.r.vectors.VectorResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code cel-java} adapter as a conformance candidate. Decimal operators are native; the
 * function-syntax rewrites in {@code cel-rewrites.tsv} exist so that a vector that only the
 * function syntax can express is classified FUNCTIONAL rather than hidden.
 */
public final class CelJavaLayer implements ExpressionLayer {
  /** The vectors whose results classify the decimal support (D01 to D06 and D10). */
  private static final Set<String> DECIMAL_VECTORS =
      Set.of("D01", "D02", "D03", "D04", "D05", "D06", "D10");

  private static final String REWRITES = "/cel-rewrites.tsv";
  private static final Map<String, String> REWRITE_TABLE = loadRewrites();

  private final Map<Long, CelJavaExpression> byCeiling = new ConcurrentHashMap<>();

  public CelJavaLayer() {}

  @Override
  public String name() {
    return "cel";
  }

  @Override
  public Outcome evaluate(
      String expression, Map<String, Value> bindings, long budget, long ceiling) {
    CompiledExpression compiled;
    try {
      Map<String, ValueType> types = new TreeMap<>();
      for (Map.Entry<String, Value> e : bindings.entrySet()) {
        types.put(e.getKey(), CelJavaExpression.typeOf(e.getValue()));
      }
      compiled =
          byCeiling
              .computeIfAbsent(ceiling, CelJavaExpression::withCeiling)
              .compile(expression, types);
    } catch (RuntimeException | StackOverflowError e) {
      // a failure while compiling is a compile outcome, whatever the library threw
      return new Outcome.CompileError(String.valueOf(e.getMessage()));
    }
    try {
      CostMeter meter = new CostMeter(new CostBudget(budget));
      return new Outcome.Ok(
          compiled.evaluate(path -> Optional.ofNullable(bindings.get(path)), meter));
    } catch (CelJavaExpression.EvalException e) {
      return new Outcome.Error(e.kind());
    } catch (BudgetExceededException e) {
      return new Outcome.Error("BUDGET");
    } catch (RuntimeException | StackOverflowError e) {
      return new Outcome.Error(CelJavaExpression.errorKind(e));
    }
  }

  @Override
  public Optional<String> rewriteFor(String vectorId) {
    return Optional.ofNullable(REWRITE_TABLE.get(vectorId));
  }

  /**
   * NATIVE when D01 to D06 and D10 pass in operator form, FUNCTIONAL when they pass and at least
   * one only through a function-syntax rewrite, UNSUPPORTED otherwise (also when one is missing).
   */
  public static String decimalSupport(java.util.List<VectorResult> results) {
    boolean functional = false;
    int seen = 0;
    for (VectorResult r : results) {
      if (!DECIMAL_VECTORS.contains(r.id())) {
        continue;
      }
      seen++;
      if (r.result().equals("FUNCTIONAL")) {
        functional = true;
      } else if (!r.result().equals("PASS")) {
        return "UNSUPPORTED";
      }
    }
    if (seen != DECIMAL_VECTORS.size()) {
      return "UNSUPPORTED";
    }
    return functional ? "FUNCTIONAL" : "NATIVE";
  }

  /**
   * The matrix file: a {@code decimalSupport} line, then one line per vector with id, class, result
   * and actual, tab-separated, LF.
   */
  public static String matrixTsv(java.util.List<VectorResult> results) {
    StringBuilder out = new StringBuilder();
    out.append("decimalSupport\t").append(decimalSupport(results)).append('\n');
    for (VectorResult r : results) {
      out.append(r.id())
          .append('\t')
          .append(r.vectorClass())
          .append('\t')
          .append(r.result())
          .append('\t')
          .append(r.actual())
          .append('\n');
    }
    return out.toString();
  }

  private static Map<String, String> loadRewrites() {
    try (InputStream in = CelJavaLayer.class.getResourceAsStream(REWRITES)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + REWRITES);
      }
      Map<String, String> table = new TreeMap<>();
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      int lineNumber = 0;
      for (String line : text.split("\n", -1)) {
        lineNumber++;
        if (line.isEmpty()) {
          continue;
        }
        String[] cols = line.split("\t", -1);
        if (cols.length != 2 || cols[0].isEmpty() || cols[1].isEmpty()) {
          throw new IllegalStateException(
              REWRITES + " line " + lineNumber + ": need an id and an expression");
        }
        if (table.put(cols[0], cols[1]) != null) {
          throw new IllegalStateException(REWRITES + " line " + lineNumber + ": duplicate id");
        }
      }
      return Map.copyOf(table);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
