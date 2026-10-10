package dev.regpack.ports.exec;

/** The most cost units one rule evaluation may spend. Units are deterministic, never time. */
public record CostBudget(long maxUnits) {
  public CostBudget {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("CostBudget maxUnits must be >= 0");
    }
  }
}
