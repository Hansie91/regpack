package dev.regpack.engine.pack;

import dev.regpack.pack.model.PackLimits;
import java.util.List;
import java.util.Objects;

/**
 * The deterministic executor budget of a compiled report (spec 6.6, cost model v1; the factor 2 is
 * the SP-R default and MODELLED, not measured).
 *
 * @param staticWorstCaseUnits the sum over all rows of 1 plus the static worst case of the row's
 *     {@code when}; an upper bound of the units one record can charge for inputs within the limits
 * @param perRecordUnits the budget of one record: {@code limits.record_budget} if the pack declares
 *     one, else 2 times {@code staticWorstCaseUnits}
 * @param largestRuleNodes the node count of the largest rule
 * @param recommendedRuleCeiling max(64, 4 times the largest rule), the SP-R formula; printed for
 *     information, the enforced ceiling is {@code limits.rule_nodes}
 */
public record PackBudget(
    long staticWorstCaseUnits,
    long perRecordUnits,
    long largestRuleNodes,
    long recommendedRuleCeiling) {
  public PackBudget {
    if (staticWorstCaseUnits < 0
        || perRecordUnits < 0
        || largestRuleNodes < 0
        || recommendedRuleCeiling < 0) {
      throw new IllegalArgumentException("a budget figure cannot be negative");
    }
  }

  /**
   * Computes the budget of the given tables. The sums use exact arithmetic: with the caps of {@code
   * PackFormat} (at most 4,096 nodes of at most a few thousand units each per rule, and a pack of
   * at most 1 MiB per file and 256 files) no sum comes near the range of a long, so an overflow
   * would be a bug and fails loudly.
   */
  public static PackBudget of(List<CompiledPack.Table> tables, PackLimits limits) {
    Objects.requireNonNull(tables, "tables");
    Objects.requireNonNull(limits, "limits");
    long sum = 0;
    long largest = 0;
    for (CompiledPack.Table table : tables) {
      for (CompiledPack.Row row : table.rows()) {
        sum = Math.addExact(sum, Math.addExact(1L, row.when().staticWorstCaseUnits()));
        largest = Math.max(largest, row.when().nodeCount());
      }
    }
    long perRecord =
        limits.recordBudget().isPresent()
            ? limits.recordBudget().getAsLong()
            : Math.multiplyExact(2L, sum);
    long recommended = Math.max(64L, Math.multiplyExact(4L, largest));
    return new PackBudget(sum, perRecord, largest, recommended);
  }
}
