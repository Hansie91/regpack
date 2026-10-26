package dev.regpack.ports.security;

import java.util.Arrays;
import java.util.Objects;

/**
 * A data key wrapped by an envelope key: the wrapping key id and the opaque ciphertext. Equality
 * and hash code are by content (the bytes of {@code ciphertext}); {@code toString} shows the length
 * of the ciphertext only.
 */
public record WrappedKey(String keyId, byte[] ciphertext) {
  public WrappedKey {
    Objects.requireNonNull(keyId, "keyId");
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WrappedKey that
        && keyId.equals(that.keyId)
        && Arrays.equals(ciphertext, that.ciphertext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(keyId, Arrays.hashCode(ciphertext));
  }

  @Override
  public String toString() {
    return "WrappedKey[ciphertextLength=" + ciphertext.length + "]";
  }
}
