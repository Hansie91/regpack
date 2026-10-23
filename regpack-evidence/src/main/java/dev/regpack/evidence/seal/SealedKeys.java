package dev.regpack.evidence.seal;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import java.util.Objects;

/**
 * The key of a sealed file in the evidence tier (D-E1): {@code sealed/<first two hex digits>/<64
 * hex digits>} of the SHA-256 of its bytes. The key is content-addressed, so the same bytes are one
 * object, whatever the number of attempts that send them.
 */
public final class SealedKeys {
  private SealedKeys() {}

  public static EvidenceKey of(Sha256 sha) {
    String hex = Objects.requireNonNull(sha, "sha").hex();
    return EvidenceKey.of("sealed", hex.substring(0, 2), hex);
  }
}
