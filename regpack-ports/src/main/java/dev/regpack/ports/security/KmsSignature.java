package dev.regpack.ports.security;

import java.util.Objects;

/** A signature with the id of the signing key and the algorithm name. */
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
}
