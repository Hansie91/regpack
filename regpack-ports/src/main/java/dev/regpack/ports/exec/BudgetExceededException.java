package dev.regpack.ports.exec;

import java.util.Objects;

/** Thrown by {@link CostMeter#charge} when a charge would take the used units above the limit. */
public class BudgetExceededException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String ruleId;
  private final long used;
  private final long limit;

  public BudgetExceededException(String ruleId, long used, long limit) {
    super(
        "cost budget exceeded in rule "
            + Objects.requireNonNull(ruleId, "ruleId")
            + ": used "
            + used
            + " of "
            + limit);
    this.ruleId = ruleId;
    this.used = used;
    this.limit = limit;
  }

  /** The rule that was running when the failing charge was made. */
  public String ruleId() {
    return ruleId;
  }

  /** Units used before the failing charge. */
  public long used() {
    return used;
  }

  public long limit() {
    return limit;
  }
}
