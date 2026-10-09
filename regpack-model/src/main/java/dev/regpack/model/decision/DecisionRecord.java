package dev.regpack.model.decision;

import java.util.Objects;
import java.util.Optional;

/** A decision as stored in a chain: its id, its position and the decision it supersedes. */
public record DecisionRecord(
    DecisionId decisionId,
    long decisionSeq,
    Optional<DecisionId> supersedesDecisionId,
    DecisionBody body) {

  public DecisionRecord {
    Objects.requireNonNull(decisionId, "decisionId");
    Objects.requireNonNull(supersedesDecisionId, "supersedesDecisionId");
    Objects.requireNonNull(body, "body");
  }
}
