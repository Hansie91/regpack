package dev.regpack.model;

/** Identifies one slice of a landed file: {@code <digest hex>:<slice>}. */
public record BatchId(String value) {
  public BatchId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("BatchId must not be blank");
    }
  }

  public static BatchId of(Sha256 landingDigest, int slice) {
    if (slice < 0) {
      throw new IllegalArgumentException("slice must be >= 0");
    }
    return new BatchId(landingDigest.hex() + ":" + slice);
  }
}
