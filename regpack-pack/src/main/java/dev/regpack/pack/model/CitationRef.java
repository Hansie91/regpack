package dev.regpack.pack.model;

import dev.regpack.model.CitationRole;
import java.util.Objects;

/**
 * A citation written on a table row: the Legal Source Registry entry, a provision path inside it
 * and the role the provision plays. It resolves in {@code sources.yaml} (checked by the registry
 * checks, not by the table checks).
 */
public record CitationRef(String sourceId, String provisionPath, CitationRole role) {
  public CitationRef {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(provisionPath, "provisionPath");
    Objects.requireNonNull(role, "role");
  }
}
