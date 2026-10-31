package dev.regpack.ports.crypto;

import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM over the JDK provider: a 32-byte key, a 12-byte nonce and a 16-byte tag. A new {@link
 * Cipher} is made for every call, so the class holds no state and may be used from any thread. The
 * caller owns nonce uniqueness; the envelope code draws every nonce from an entropy source.
 */
final class AesGcm {
  static final int KEY_BYTES = 32;
  static final int NONCE_BYTES = 12;
  static final int TAG_BYTES = 16;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private AesGcm() {}

  /** Returns the ciphertext followed by the 16-byte tag. */
  static byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
    Objects.requireNonNull(aad, "aad");
    Objects.requireNonNull(plaintext, "plaintext");
    Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
    try {
      cipher.updateAAD(aad);
      return cipher.doFinal(plaintext);
    } catch (GeneralSecurityException e) {
      throw unavailable();
    }
  }

  /**
   * Checks the tag over the ciphertext and the AAD and only then returns the plaintext.
   *
   * @throws Envelope.AuthenticationException when the tag does not verify, or the input is shorter
   *     than a tag
   */
  static byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextAndTag) {
    Objects.requireNonNull(aad, "aad");
    Objects.requireNonNull(ciphertextAndTag, "ciphertextAndTag");
    Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, nonce);
    try {
      cipher.updateAAD(aad);
      return cipher.doFinal(ciphertextAndTag);
    } catch (AEADBadTagException e) {
      throw new Envelope.AuthenticationException();
    } catch (GeneralSecurityException e) {
      throw unavailable();
    }
  }

  private static Cipher cipher(int mode, byte[] key, byte[] nonce) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(nonce, "nonce");
    if (key.length != KEY_BYTES) {
      throw new IllegalArgumentException("the data key must be 32 bytes");
    }
    if (nonce.length != NONCE_BYTES) {
      throw new IllegalArgumentException("the nonce must be 12 bytes");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
      return cipher;
    } catch (GeneralSecurityException e) {
      throw unavailable();
    }
  }

  /** No cause: nothing from the provider is carried into a message. */
  private static IllegalStateException unavailable() {
    return new IllegalStateException("AES-GCM is not available");
  }
}
