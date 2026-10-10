package dev.regpack.model.decision;

import dev.regpack.model.Citation;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** The content of a decision: everything except its ledger position. */
public record DecisionBody(
    EventIdentity eventIdentity,
    Regime regime,
    Sha256 decisionContextId,
    Instant asAt,
    Instant evaluatedAt,
    Outcome outcome,
    List<String> flags,
    ReasonCode reasonCode,
    String node,
    List<String> ruleIdsHit,
    List<PathStep> path,
    List<Citation> citations,
    List<Leg> legs,
    Sha256 inputDigest) {

  public DecisionBody {
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(decisionContextId, "decisionContextId");
    Objects.requireNonNull(asAt, "asAt");
    Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    Objects.requireNonNull(outcome, "outcome");
    flags = List.copyOf(flags);
    Objects.requireNonNull(reasonCode, "reasonCode");
    Objects.requireNonNull(node, "node");
    ruleIdsHit = List.copyOf(ruleIdsHit);
    path = List.copyOf(path);
    citations = List.copyOf(citations);
    legs = List.copyOf(legs);
    Objects.requireNonNull(inputDigest, "inputDigest");
  }

  /** The SHA-256 of the canonical JSON without evaluatedAt (I3). */
  public Sha256 replayDigest() {
    return Sha256.ofUtf8(Json.canonical(DecisionCodec.bodyToMap(this, false)));
  }
}
