package dev.regpack.ports.exec;

import java.util.Objects;

/**
 * Deterministic cost units, never time. The executor owns one meter per evaluation and hands it to
 * the program. Not thread-safe: one meter belongs to one evaluation on one thread.
 */
public final class CostMeter {
  private final long limit;
  private long used;
  private String currentRule = "";

  public CostMeter(CostBudget budget) {
    this.limit = Objects.requireNonNull(budget, "budget").maxUnits();
  }

  /** Sets the rule that later charges belong to. */
  public void enter(String ruleId) {
    if (ruleId == null || ruleId.isBlank()) {
      throw new IllegalArgumentException("ruleId must not be blank");
    }
    this.currentRule = ruleId;
  }

  /**
   * Adds units to the running total.
   *
   * @throws BudgetExceededException if {@code used + units > limit}; {@code used} is then unchanged
   */
  public void charge(long units) {
    if (units < 0) {
      throw new IllegalArgumentException("charge must be >= 0");
    }
    if (units > limit - used) {
      throw new BudgetExceededException(currentRule, used, limit);
    }
    used += units;
  }

  public long used() {
    return used;
  }

  /** The rule most recently entered; empty before the first {@link #enter}. */
  public String currentRule() {
    return currentRule;
  }
}
