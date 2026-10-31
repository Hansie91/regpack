package dev.regpack.ports.security;

/**
 * Key management service bound to one workload identity. A key id that does not exist throws {@link
 * IllegalArgumentException} (checked first); an existing key the bound identity may not use, or
 * that has another purpose, throws {@link KmsAccessDeniedException}.
 *
 * <p>A key that was destroyed on purpose (crypto-shredding) is not an unknown key: {@link #wrap}
 * and {@link #unwrap} of a destroyed KEK throw {@link KmsKeyDestroyedException}, a {@link
 * RuntimeException} that is not an {@link IllegalArgumentException}. An adapter must answer a KEK
 * that the destruction job destroyed, a pending deletion included, that way and not as unknown;
 * otherwise the evidence verifier reports every shredded object as corrupt. Destruction itself is
 * an administrative act and not a method of this port.
 */
public interface Kms {
  /** PACK_SIGNING or MANIFEST_SIGNING keys only. */
  KmsSignature sign(KeyRef key, byte[] message);

  /** Open to every identity. */
  boolean verify(KeyRef key, byte[] message, KmsSignature signature);

  /** TRN_HMAC and INDEX_HMAC keys only. */
  byte[] hmac(KeyRef key, byte[] message);

  /**
   * ENVELOPE_KEK keys only. A destroyed KEK throws {@link KmsKeyDestroyedException}; an unknown one
   * {@link IllegalArgumentException}.
   */
  WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad);

  /**
   * Requires {@code wrapped.keyId()} to equal {@code kek.keyId()} (else {@link
   * IllegalArgumentException}) and authorises only the {@code kek}. A wrong {@code aad} or a
   * tampered {@link WrappedKey} throws {@link IllegalArgumentException}. A destroyed KEK throws
   * {@link KmsKeyDestroyedException}, whatever the {@code wrapped} key and the {@code aad}.
   */
  byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad);
}
