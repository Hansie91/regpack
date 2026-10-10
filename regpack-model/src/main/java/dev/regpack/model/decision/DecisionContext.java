package dev.regpack.model.decision;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The pinned inputs of a decision (spec 6.3). Its id is the hash of its canonical JSON. */
public record DecisionContext(
    Sha256 packDigest,
    Optional<Sha256> overlayDigest,
    List<String> interpretationPins,
    List<String> referenceSnapshotIds,
    List<String> enrichmentSnapshotIds,
    String firmProfileVersion,
    String mappingVersion,
    String engineBuild,
    String executor) {

  public DecisionContext {
    Objects.requireNonNull(packDigest, "packDigest");
    Objects.requireNonNull(overlayDigest, "overlayDigest");
    interpretationPins = List.copyOf(interpretationPins);
    referenceSnapshotIds = List.copyOf(referenceSnapshotIds);
    enrichmentSnapshotIds = List.copyOf(enrichmentSnapshotIds);
    Objects.requireNonNull(firmProfileVersion, "firmProfileVersion");
    Objects.requireNonNull(mappingVersion, "mappingVersion");
    Objects.requireNonNull(engineBuild, "engineBuild");
    Objects.requireNonNull(executor, "executor");
  }

  /** The SHA-256 of the canonical JSON of this context. */
  public Sha256 id() {
    return Sha256.ofUtf8(Json.canonical(DecisionCodec.contextToMap(this)));
  }
}
