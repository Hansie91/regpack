package dev.regpack.pack.model;

import dev.regpack.model.CitationRole;
import dev.regpack.model.Sha256;
import java.util.Objects;
import java.util.Optional;

/**
 * One provision of a source in the Legal Source Registry (spec 6.4).
 *
 * @param path the provision path inside the source, for example {@code art/26/par/2}
 * @param role BASIS or EXCEPTION (the law), INTERPRETATION or GUIDANCE
 * @param status whether the provision is in force
 * @param quote an optional short quotation (at most 240 code points, a plan limit)
 * @param quoteSha256 the SHA-256 of the quote as parsed
 */
public record Provision(
    String path,
    CitationRole role,
    Status status,
    Optional<String> quote,
    Optional<Sha256> quoteSha256) {
  /** The legal status of a provision; exact names in {@code sources.yaml}. */
  public enum Status {
    IN_FORCE,
    REPEALED,
    PROPOSED
  }

  public Provision {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(quote, "quote");
    Objects.requireNonNull(quoteSha256, "quoteSha256");
  }
}
