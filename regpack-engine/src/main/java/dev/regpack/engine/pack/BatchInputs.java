package dev.regpack.engine.pack;

import dev.regpack.model.KeyText;
import java.util.List;
import java.util.Objects;

/**
 * The four components of a {@code DecisionContext} that come from the caller of a batch and not
 * from the pack (decision D-W6): the version of the mapping that read the batch, the version of the
 * Firm Profile, and the ids of the reference and enrichment snapshots the batch was decided
 * against. {@link PackRegime#contextFor} stamps them on the provider's own context.
 *
 * <p>Every text is a key text ({@link KeyText}: at most {@value KeyText#MAX_CHARS} characters, no
 * control character, no unpaired surrogate) and each list holds at most {@value #MAX_SNAPSHOT_IDS}
 * entries, because the context is stored whole and its id is the hash of its canonical form. The
 * two versions must not be blank. A refusal names the component and the rule and never echoes the
 * text. The order of the snapshot ids is kept, so the caller sorts them if the order must not
 * matter.
 *
 * @param mappingVersion the mapping version, for example {@code id@digest}
 * @param firmProfileVersion the Firm Profile version
 * @param referenceSnapshotIds the ids of the reference data snapshots
 * @param enrichmentSnapshotIds the ids of the enrichment snapshots
 */
public record BatchInputs(
    String mappingVersion,
    String firmProfileVersion,
    List<String> referenceSnapshotIds,
    List<String> enrichmentSnapshotIds) {
  /** The most snapshot ids of one kind. */
  public static final int MAX_SNAPSHOT_IDS = 64;

  public BatchInputs {
    Objects.requireNonNull(mappingVersion, "mappingVersion");
    Objects.requireNonNull(firmProfileVersion, "firmProfileVersion");
    Objects.requireNonNull(referenceSnapshotIds, "referenceSnapshotIds");
    Objects.requireNonNull(enrichmentSnapshotIds, "enrichmentSnapshotIds");
    version("mappingVersion", mappingVersion);
    version("firmProfileVersion", firmProfileVersion);
    referenceSnapshotIds = ids("referenceSnapshotIds", referenceSnapshotIds);
    enrichmentSnapshotIds = ids("enrichmentSnapshotIds", enrichmentSnapshotIds);
  }

  private static void version(String field, String value) {
    KeyText.require(field, value);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static List<String> ids(String field, List<String> values) {
    if (values.size() > MAX_SNAPSHOT_IDS) {
      throw new IllegalArgumentException(
          field + " must hold at most " + MAX_SNAPSHOT_IDS + " entries, not " + values.size());
    }
    List<String> copy = List.copyOf(values);
    for (String value : copy) {
      KeyText.require(field, value);
    }
    return copy;
  }
}
