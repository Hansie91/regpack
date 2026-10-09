package dev.regpack.ports.security;

import java.util.Objects;

/** A KMS key id together with the purpose the caller intends to use it for. */
public record KeyRef(String keyId, KeyPurpose purpose) {
  public KeyRef {
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(purpose, "purpose");
  }
}
