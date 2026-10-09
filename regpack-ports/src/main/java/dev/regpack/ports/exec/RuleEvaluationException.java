package dev.regpack.ports.exec;

import java.util.Objects;

/**
 * Thrown by a rule program or an expression when evaluation cannot continue. The executor reports
 * it as a failed evaluation of {@link #ruleId()}, naming the class of the cause.
 */
public class RuleEvaluationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String ruleId;

  /** The cause is passed through unchanged; the executor copes with an exception that has none. */
  public RuleEvaluationException(String ruleId, Throwable cause) {
    super("rule evaluation failed in rule " + Objects.requireNonNull(ruleId, "ruleId"), cause);
    this.ruleId = ruleId;
  }

  public String ruleId() {
    return ruleId;
  }
}
