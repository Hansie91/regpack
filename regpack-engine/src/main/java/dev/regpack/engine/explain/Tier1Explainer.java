package dev.regpack.engine.explain;

import dev.regpack.model.Citation;
import dev.regpack.model.Leg;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.model.decision.PathStep;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tier-1 explanation of a decision: a fixed text rendering of the stored record. Lines end with LF
 * and the text has no trailing newline; an empty list of flags, legs or rule hits reads {@code
 * none}.
 */
public final class Tier1Explainer {
  private Tier1Explainer() {}

  public static String explain(DecisionRecord decision) {
    Objects.requireNonNull(decision, "decision");
    DecisionBody body = decision.body();
    StringBuilder out = new StringBuilder();
    out.append("Decision ")
        .append(decision.decisionId().value())
        .append(" (seq ")
        .append(decision.decisionSeq())
        .append(") for ")
        .append(body.eventIdentity().value())
        .append(" under ")
        .append(body.regime().id())
        .append('\n');
    out.append("Outcome: ").append(body.outcome().name()).append('\n');
    out.append("Reason: ")
        .append(body.reasonCode().value())
        .append(" (node ")
        .append(body.node())
        .append(")\n");
    out.append("Rule hits: ").append(joinOrNone(body.ruleIdsHit())).append('\n');
    out.append("Path:");
    List<PathStep> path = body.path();
    if (path.isEmpty()) {
      out.append(" none");
    }
    for (int i = 0; i < path.size(); i++) {
      PathStep step = path.get(i);
      out.append("\n  ")
          .append(i + 1)
          .append(". ")
          .append(step.ruleId())
          .append(": ")
          .append(step.matched() ? "MATCH" : "no match")
          .append(" (")
          .append(step.costUnits())
          .append(" units)");
    }
    out.append('\n');
    out.append("Citations:");
    if (body.citations().isEmpty()) {
      out.append(" none");
    }
    for (Citation citation : body.citations()) {
      out.append("\n  - ")
          .append(citation.sourceId())
          .append(' ')
          .append(citation.provisionPath())
          .append(" [")
          .append(citation.role().name())
          .append(']');
    }
    out.append('\n');
    out.append("Flags: ").append(joinOrNone(body.flags())).append('\n');
    out.append("Legs: ")
        .append(joinOrNone(body.legs().stream().map(Leg::name).collect(Collectors.toList())))
        .append('\n');
    out.append("Input digest: ").append(body.inputDigest().hex()).append('\n');
    out.append("Context: ").append(body.decisionContextId().hex());
    return out.toString();
  }

  private static String joinOrNone(List<String> items) {
    return items.isEmpty() ? "none" : String.join(", ", items);
  }
}
