package dev.regpack.ports.security;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * A signature with the id of the signing key and the algorithm name. Equality and hash code are by
 * content (the bytes of {@code value}); {@code toString} shows the length of the value only.
 */
public record KmsSignature(String keyId, String algorithm, byte[] value) {
  public KmsSignature {
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(algorithm, "algorithm");
    value = value.clone();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof KmsSignature that
        && keyId.equals(that.keyId)
        && algorithm.equals(that.algorithm)
        && MessageDigest.isEqual(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(keyId, algorithm, Arrays.hashCode(value));
  }

  @Override
  public String toString() {
    return "KmsSignature[valueLength=" + value.length + "]";
  }
}
