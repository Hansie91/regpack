package dev.regpack.model;

import java.util.Objects;
import java.util.Optional;

/** A reference to a provision of a source, with the role it plays and an optional quote hash. */
public record Citation(
    String sourceId, String provisionPath, CitationRole role, Optional<Sha256> quoteSha256) {
  public Citation {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("Citation sourceId must not be blank");
    }
    if (provisionPath == null || provisionPath.isBlank()) {
      throw new IllegalArgumentException("Citation provisionPath must not be blank");
    }
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(quoteSha256, "quoteSha256");
  }
}
