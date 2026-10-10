package dev.regpack.ports.security;

/**
 * Key management service bound to one workload identity. A key id that does not exist throws {@link
 * IllegalArgumentException} (checked first); an existing key the bound identity may not use, or
 * that has another purpose, throws {@link KmsAccessDeniedException}.
 */
public interface Kms {
  /** PACK_SIGNING or MANIFEST_SIGNING keys only. */
  KmsSignature sign(KeyRef key, byte[] message);

  /** Open to every identity. */
  boolean verify(KeyRef key, byte[] message, KmsSignature signature);

  /** TRN_HMAC keys only. */
  byte[] hmac(KeyRef key, byte[] message);

  /** ENVELOPE_KEK keys only. */
  WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad);

  /**
   * Requires {@code wrapped.keyId()} to equal {@code kek.keyId()} (else {@link
   * IllegalArgumentException}) and authorises only the {@code kek}. A wrong {@code aad} or a
   * tampered {@link WrappedKey} throws {@link IllegalArgumentException}.
   */
  byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad);
}
