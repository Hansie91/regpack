package dev.regpack.model.decision;

import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pinned inputs of a decision (spec 6.3). Its id is the hash of its canonical JSON.
 *
 * <p>{@code circuitEpoch} is the generation of the poison circuit of the pack digest (INTERP-3,
 * option A): a close of an open rule raises it, so a decision made under an open circuit and its
 * re-evaluation after the close have different context ids and the ledger supersedes instead of
 * treating the re-evaluation as a redelivery. Epoch 0 is omitted from the canonical form, so every
 * context id that existed before the epoch is unchanged. The epoch is process-local (D-L1).
 *
 * <p>{@code engineBuild} has a text column of its own in the real store, so it is a key text
 * ({@link KeyText}); every other text is part of the canonical JSON, which refuses an unpaired
 * surrogate.
 */
public record DecisionContext(
    Sha256 packDigest,
    Optional<Sha256> overlayDigest,
    List<String> interpretationPins,
    List<String> referenceSnapshotIds,
    List<String> enrichmentSnapshotIds,
    String firmProfileVersion,
    String mappingVersion,
    String engineBuild,
    String executor,
    long circuitEpoch) {

  /** A context at circuit epoch 0. */
  public DecisionContext(
      Sha256 packDigest,
      Optional<Sha256> overlayDigest,
      List<String> interpretationPins,
      List<String> referenceSnapshotIds,
      List<String> enrichmentSnapshotIds,
      String firmProfileVersion,
      String mappingVersion,
      String engineBuild,
      String executor) {
    this(
        packDigest,
        overlayDigest,
        interpretationPins,
        referenceSnapshotIds,
        enrichmentSnapshotIds,
        firmProfileVersion,
        mappingVersion,
        engineBuild,
        executor,
        0L);
  }

  public DecisionContext {
    Objects.requireNonNull(packDigest, "packDigest");
    Objects.requireNonNull(overlayDigest, "overlayDigest");
    interpretationPins = List.copyOf(interpretationPins);
    referenceSnapshotIds = List.copyOf(referenceSnapshotIds);
    enrichmentSnapshotIds = List.copyOf(enrichmentSnapshotIds);
    Objects.requireNonNull(firmProfileVersion, "firmProfileVersion");
    Objects.requireNonNull(mappingVersion, "mappingVersion");
    Objects.requireNonNull(engineBuild, "engineBuild");
    // stored in a text column of its own (not only inside the canonical JSON): a key text
    KeyText.require("engineBuild", engineBuild);
    Objects.requireNonNull(executor, "executor");
    if (circuitEpoch < 0) {
      throw new IllegalArgumentException("circuitEpoch must not be negative: " + circuitEpoch);
    }
  }

  /** The SHA-256 of the canonical JSON of this context. */
  public Sha256 id() {
    return Sha256.ofUtf8(Json.canonical(DecisionCodec.contextToMap(this)));
  }
}
