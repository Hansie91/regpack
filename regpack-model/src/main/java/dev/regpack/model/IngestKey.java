package dev.regpack.model;

/** The natural key of an ingested source record version. */
public record IngestKey(String sourceId, String sourceRecordId, long sourceVersion) {
  public IngestKey {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("IngestKey sourceId must not be blank");
    }
    if (sourceRecordId == null || sourceRecordId.isBlank()) {
      throw new IllegalArgumentException("IngestKey sourceRecordId must not be blank");
    }
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("IngestKey sourceVersion must be >= 0");
    }
  }
}
