package dev.regpack.model.decision;

import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.json.Json;

/** The input digest of spec 6.3, with an empty list of enrichment response hashes. */
public final class InputDigest {
  private InputDigest() {}

  /**
   * The canonical input concatenated with the ingest key, so a source state that recurs at a later
   * version gets a new digest.
   */
  public static Sha256 of(CanonicalExecution execution, IngestKey ingestKey) {
    return Sha256.ofUtf8(
        Json.canonical(CanonicalExecutionCodec.toMap(execution))
            + "\n"
            + ingestKey.sourceId()
            + "|"
            + ingestKey.sourceRecordId()
            + "|"
            + ingestKey.sourceVersion());
  }
}
