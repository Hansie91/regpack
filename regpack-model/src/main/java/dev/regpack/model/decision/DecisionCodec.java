package dev.regpack.model.decision;

import dev.regpack.model.Citation;
import dev.regpack.model.Leg;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts decision types to the map form of canonical JSON. */
public final class DecisionCodec {
  private DecisionCodec() {}

  public static Map<String, Object> contextToMap(DecisionContext context) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("packDigest", context.packDigest().hex());
    context.overlayDigest().ifPresent(d -> map.put("overlayDigest", d.hex()));
    map.put("interpretationPins", context.interpretationPins());
    map.put("referenceSnapshotIds", context.referenceSnapshotIds());
    map.put("enrichmentSnapshotIds", context.enrichmentSnapshotIds());
    map.put("firmProfileVersion", context.firmProfileVersion());
    map.put("mappingVersion", context.mappingVersion());
    map.put("engineBuild", context.engineBuild());
    map.put("executor", context.executor());
    return map;
  }

  /** The flag omits evaluatedAt, and nothing else, when false. */
  public static Map<String, Object> bodyToMap(DecisionBody body, boolean includeEvaluatedAt) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("eventIdentity", body.eventIdentity().value());
    map.put("regime", body.regime().id());
    map.put("decisionContextId", body.decisionContextId().hex());
    map.put("asAt", body.asAt());
    if (includeEvaluatedAt) {
      map.put("evaluatedAt", body.evaluatedAt());
    }
    map.put("outcome", body.outcome().name());
    map.put("flags", body.flags());
    map.put("reasonCode", body.reasonCode().value());
    map.put("node", body.node());
    map.put("ruleIdsHit", body.ruleIdsHit());
    List<Object> path = new ArrayList<>();
    for (PathStep step : body.path()) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("ruleId", step.ruleId());
      s.put("matched", step.matched());
      s.put("costUnits", step.costUnits());
      path.add(s);
    }
    map.put("path", path);
    List<Object> citations = new ArrayList<>();
    for (Citation citation : body.citations()) {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("sourceId", citation.sourceId());
      c.put("provisionPath", citation.provisionPath());
      c.put("role", citation.role().name());
      citation.quoteSha256().ifPresent(q -> c.put("quoteSha256", q.hex()));
      citations.add(c);
    }
    map.put("citations", citations);
    List<Object> legs = new ArrayList<>();
    for (Leg leg : body.legs()) {
      legs.add(leg.name());
    }
    map.put("legs", legs);
    map.put("inputDigest", body.inputDigest().hex());
    return map;
  }

  public static Map<String, Object> recordToMap(DecisionRecord record) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("decisionId", record.decisionId().value());
    map.put("decisionSeq", record.decisionSeq());
    record.supersedesDecisionId().ifPresent(id -> map.put("supersedesDecisionId", id.value()));
    map.put("body", bodyToMap(record.body(), true));
    return map;
  }
}
