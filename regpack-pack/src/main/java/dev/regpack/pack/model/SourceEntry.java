package dev.regpack.pack.model;

import dev.regpack.model.Sha256;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One legal source (spec 6.4): a pinned version of a text, identified by the hash of its content.
 *
 * @param sourceId the identifier citations use
 * @param version the version of the text that was read
 * @param retrievedAt the UTC date the text was retrieved
 * @param contentSha256 the SHA-256 of the retrieved text
 * @param eli an optional European Legislation Identifier
 * @param provisions the provisions, keyed by path, in sorted order
 */
public record SourceEntry(
    String sourceId,
    String version,
    LocalDate retrievedAt,
    Sha256 contentSha256,
    Optional<String> eli,
    Map<String, Provision> provisions) {
  public SourceEntry {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(retrievedAt, "retrievedAt");
    Objects.requireNonNull(contentSha256, "contentSha256");
    Objects.requireNonNull(eli, "eli");
    provisions = Collections.unmodifiableMap(new TreeMap<>(provisions));
  }
}
