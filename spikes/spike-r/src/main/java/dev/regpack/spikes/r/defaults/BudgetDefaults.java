package dev.regpack.spikes.r.defaults;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.spikes.r.cel.CelJavaExpression;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.table.RuleTableProgram;
import dev.regpack.spikes.r.table.TableDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The per-record cost budget and the per-rule ceiling, derived from the compiled pack and not
 * guessed (spec 6.6).
 *
 * <p>The static worst case of a record is the sum over every row of the pack of one unit for the
 * row, the node count of its {@code when} and every size surcharge at the declared limits (lists of
 * {@code maxListItems} items, strings of {@code maxStringCodePoints} code points), whether or not a
 * branch is taken. {@code perRecordBudget = 2 x staticWorstCase}; {@code perRuleCeiling = max(64, 4
 * x the largest node count)}. The consistency checks count violations: a budget below the static
 * worst case, a budget that does not exceed the static worst case of the pack without its last row
 * (it does not grow with the rule count), a ceiling below four times the largest node count, and
 * every clean record that fails, exhausts its budget or uses more than half of the budget.
 */
public final class BudgetDefaults {
  private static final long MIN_CEILING = 64;
  private static final long CEILING_FACTOR = 4;
  private static final long BUDGET_FACTOR = 2;

  /** The derived defaults and the evidence for them; {@code violations} must be 0. */
  public record Result(
      long perRecordBudgetUnits,
      long perRuleCeilingUnits,
      long staticWorstCaseUnits,
      long maxObservedCleanUnits,
      long violations) {}

  private BudgetDefaults() {}

  /**
   * Derives the defaults for the layer the pack was compiled with (the in-house layer or {@code
   * cel-java}) and checks them against the clean records.
   *
   * @throws IllegalArgumentException if the pack was compiled by another layer
   */
  public static Result derive(
      CompiledPack pack, Iterable<TradeRecord> clean, int maxListItems, int maxStringCodePoints) {
    long budget =
        Math.multiplyExact(BUDGET_FACTOR, staticWorstCase(pack, maxListItems, maxStringCodePoints));
    return deriveForBudget(pack, clean, maxListItems, maxStringCodePoints, budget);
  }

  /**
   * The same checks for a given candidate budget: how a deliberately small or large budget fares.
   */
  public static Result deriveForBudget(
      CompiledPack pack,
      Iterable<TradeRecord> clean,
      int maxListItems,
      int maxStringCodePoints,
      long budgetUnits) {
    Objects.requireNonNull(pack, "pack");
    Objects.requireNonNull(clean, "clean");
    List<Long> perRow = perRowWorstCase(pack, maxListItems, maxStringCodePoints);
    long staticWorstCase = 0;
    for (long units : perRow) {
      staticWorstCase = Math.addExact(staticWorstCase, units);
    }
    long withoutLast = staticWorstCase - perRow.get(perRow.size() - 1);
    long largestNodes = largestNodeCount(pack);
    long ceiling = Math.max(MIN_CEILING, CEILING_FACTOR * largestNodes);

    long violations = 0;
    if (budgetUnits < staticWorstCase) {
      violations++;
    }
    if (budgetUnits <= withoutLast) {
      violations++;
    }
    if (ceiling < CEILING_FACTOR * largestNodes) {
      violations++;
    }

    RuleTableProgram program = new RuleTableProgram(pack);
    MeteredExecutor executor = new MeteredExecutor();
    CostBudget unlimited = new CostBudget(Long.MAX_VALUE);
    long maxObserved = 0;
    for (TradeRecord record : clean) {
      ExecutionResult<TableDecision> result = executor.execute(program, record, unlimited);
      if (result instanceof ExecutionResult.Completed<TableDecision> done) {
        maxObserved = Math.max(maxObserved, done.costUnits());
        if (done.costUnits() > budgetUnits / 2) {
          violations++;
        }
      } else {
        violations++;
      }
    }
    return new Result(budgetUnits, ceiling, staticWorstCase, maxObserved, violations);
  }

  private static long staticWorstCase(
      CompiledPack pack, int maxListItems, int maxStringCodePoints) {
    long total = 0;
    for (long units : perRowWorstCase(pack, maxListItems, maxStringCodePoints)) {
      total = Math.addExact(total, units);
    }
    return total;
  }

  private static List<Long> perRowWorstCase(CompiledPack pack, int maxList, int maxString) {
    List<Long> out = new ArrayList<>(pack.ruleCount());
    for (List<CompiledPack.Row> table : pack.tables()) {
      for (CompiledPack.Row row : table) {
        out.add(1 + staticUnits(row.when(), maxList, maxString));
      }
    }
    return out;
  }

  private static long largestNodeCount(CompiledPack pack) {
    long largest = 0;
    for (List<CompiledPack.Row> table : pack.tables()) {
      for (CompiledPack.Row row : table) {
        largest = Math.max(largest, nodeCount(row.when()));
      }
    }
    return largest;
  }

  private static long staticUnits(CompiledExpression expression, int maxList, int maxString) {
    if (expression instanceof InHouseExpression.Compiled c) {
      return c.staticWorstCaseUnits(maxList, maxString);
    }
    if (expression instanceof CelJavaExpression.Compiled c) {
      return c.staticWorstCaseUnits(maxList, maxString);
    }
    throw unsupported(expression);
  }

  private static long nodeCount(CompiledExpression expression) {
    if (expression instanceof InHouseExpression.Compiled c) {
      return c.nodeCount();
    }
    if (expression instanceof CelJavaExpression.Compiled c) {
      return c.nodeCount();
    }
    throw unsupported(expression);
  }

  private static IllegalArgumentException unsupported(CompiledExpression expression) {
    return new IllegalArgumentException(
        "the layer of " + expression.getClass().getName() + " does not report static costs");
  }
}
