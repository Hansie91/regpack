package dev.regpack.spikes.r.table;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.model.Outcome;
import dev.regpack.model.Sha256;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.ExpressionException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A generated pack with every {@code when} compiled by one expression layer. Rows of a table are
 * ordered by ascending priority; every table ends in exactly one catch-all row (the literal {@code
 * true} with the highest priority). Compiled expressions are immutable and shared between threads.
 */
public final class CompiledPack {
  /** One compiled row; {@code outcome} is the name of a model {@link Outcome}. */
  public record Row(
      String id, int priority, String outcome, boolean catchAll, CompiledExpression when) {
    public Row {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(when, "when");
    }
  }

  private static final String CATCH_ALL = "true";

  private final String id;
  private final Sha256 digest;
  private final int ruleCount;
  private final List<List<Row>> tables;

  private CompiledPack(String id, Sha256 digest, int ruleCount, List<List<Row>> tables) {
    this.id = id;
    this.digest = digest;
    this.ruleCount = ruleCount;
    this.tables = tables;
  }

  /**
   * Compiles every row against {@link TradeBindings#VARIABLES}.
   *
   * @throws IllegalArgumentException for duplicate priorities in a table, a table that does not end
   *     in its catch-all (or has one earlier), an unknown outcome name, or a row that does not
   *     compile
   */
  public static CompiledPack compile(GeneratedPack pack, Expression layer) {
    Objects.requireNonNull(pack, "pack");
    Objects.requireNonNull(layer, "layer");
    List<List<Row>> tables = new ArrayList<>(pack.tables().size());
    int count = 0;
    for (List<GeneratedRule> source : pack.tables()) {
      List<GeneratedRule> ordered = new ArrayList<>(source);
      ordered.sort(Comparator.comparingInt(GeneratedRule::priority));
      checkTable(pack, ordered);
      List<Row> rows = new ArrayList<>(ordered.size());
      for (int i = 0; i < ordered.size(); i++) {
        GeneratedRule rule = ordered.get(i);
        Outcome.valueOf(rule.outcome());
        rows.add(
            new Row(
                rule.id(),
                rule.priority(),
                rule.outcome(),
                i == ordered.size() - 1,
                compileWhen(rule, layer)));
        count++;
      }
      tables.add(List.copyOf(rows));
    }
    return new CompiledPack(pack.id(), pack.digest(), count, List.copyOf(tables));
  }

  private static void checkTable(GeneratedPack pack, List<GeneratedRule> ordered) {
    if (ordered.isEmpty()) {
      throw new IllegalArgumentException("pack " + pack.id() + " has an empty table");
    }
    Set<Integer> priorities = new HashSet<>();
    for (GeneratedRule rule : ordered) {
      if (!priorities.add(rule.priority())) {
        throw new IllegalArgumentException(
            "duplicate priority " + rule.priority() + " in a table of pack " + pack.id());
      }
    }
    for (int i = 0; i < ordered.size(); i++) {
      boolean last = i == ordered.size() - 1;
      boolean catchAll = ordered.get(i).when().strip().equals(CATCH_ALL);
      if (last && !catchAll) {
        throw new IllegalArgumentException(
            "the last row of a table must be the catch-all: " + ordered.get(i).id());
      }
      if (!last && catchAll) {
        throw new IllegalArgumentException(
            "a catch-all must be the last row of its table: " + ordered.get(i).id());
      }
    }
  }

  private static CompiledExpression compileWhen(GeneratedRule rule, Expression layer) {
    try {
      return layer.compile(rule.when(), TradeBindings.VARIABLES);
    } catch (ExpressionException e) {
      throw new IllegalArgumentException(
          "rule " + rule.id() + " does not compile: " + e.getMessage(), e);
    }
  }

  public String id() {
    return id;
  }

  /** The digest of the source pack (its canonical IR JSON), not of the compiled form. */
  public Sha256 digest() {
    return digest;
  }

  public int ruleCount() {
    return ruleCount;
  }

  /** The tables in evaluation order, each with its rows by ascending priority. */
  public List<List<Row>> tables() {
    return tables;
  }
}
