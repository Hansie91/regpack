package dev.regpack.ports.security;

import java.util.Objects;

/** A data key wrapped by an envelope key: the wrapping key id and the opaque ciphertext. */
public record WrappedKey(String keyId, byte[] ciphertext) {
  public WrappedKey {
    Objects.requireNonNull(keyId, "keyId");
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
