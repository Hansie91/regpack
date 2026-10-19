package dev.regpack.model;

/**
 * The natural key of an ingested source record version. The two texts are key texts ({@link
 * KeyText}): no control character, no unpaired surrogate, at most {@link KeyText#MAX_CHARS} chars.
 */
public record IngestKey(String sourceId, String sourceRecordId, long sourceVersion) {
  public IngestKey {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("IngestKey sourceId must not be blank");
    }
    if (sourceRecordId == null || sourceRecordId.isBlank()) {
      throw new IllegalArgumentException("IngestKey sourceRecordId must not be blank");
    }
    KeyText.require("IngestKey sourceId", sourceId);
    KeyText.require("IngestKey sourceRecordId", sourceRecordId);
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("IngestKey sourceVersion must be >= 0");
    }
  }
}
