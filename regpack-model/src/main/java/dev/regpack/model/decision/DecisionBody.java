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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The content of a decision: everything except its ledger position.
 *
 * <p>{@code attributes} (BRK-10) holds the {@code set:} values of the scope row that decided; it is
 * a sorted, immutable copy and part of the replay digest. The canonical form carries the key only
 * when the map is not empty, so a decision without attributes keeps the digest it always had.
 */
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
    Sha256 inputDigest,
    Map<String, String> attributes) {

  /** A body without attributes: the 14-component constructor of before BRK-10. */
  public DecisionBody(
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
    this(
        eventIdentity,
        regime,
        decisionContextId,
        asAt,
        evaluatedAt,
        outcome,
        flags,
        reasonCode,
        node,
        ruleIdsHit,
        path,
        citations,
        legs,
        inputDigest,
        Map.of());
  }

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
    attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
  }

  /** The SHA-256 of the canonical JSON without evaluatedAt (I3). */
  public Sha256 replayDigest() {
    return Sha256.ofUtf8(Json.canonical(DecisionCodec.bodyToMap(this, false)));
  }
}
