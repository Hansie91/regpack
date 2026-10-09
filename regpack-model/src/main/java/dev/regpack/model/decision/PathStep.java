package dev.regpack.model.decision;

/** One step of an evaluation path: the rule, whether it matched, and its cost units. */
public record PathStep(String ruleId, boolean matched, long costUnits) {
  public PathStep {
    if (ruleId == null || ruleId.isBlank()) {
      throw new IllegalArgumentException("PathStep ruleId must not be blank");
    }
    if (costUnits < 0) {
      throw new IllegalArgumentException("PathStep costUnits must be >= 0");
    }
  }
}
