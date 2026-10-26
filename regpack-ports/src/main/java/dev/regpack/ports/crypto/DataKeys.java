package dev.regpack.ports.crypto;

import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsKeyDestroyedException;
import dev.regpack.ports.security.WrappedKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * The calls that the ciphers make on the selector and on the {@link Kms}, with one failure mapping
 * (D-K2): every failure of the key service becomes a {@link KeyUnavailableException}, a destroyed
 * KEK becomes a {@link DataShreddedException}, and an unwrap that the service rejects as not
 * matching becomes an {@link Envelope.AuthenticationException}. Each exception is created fresh,
 * without the original as its cause, so no text of the service travels on.
 */
final class DataKeys {
  static final int KEY_BYTES = 32;
  static final int NONCE_BYTES = 12;
  private static final int MAX_WRAPPED_BYTES = 1024;

  private DataKeys() {}

  /**
   * The KEK of a write at {@code at}. A selector failure, or a KEK that cannot head an envelope, is
   * a {@link KeyUnavailableException}.
   */
  static KeyRef select(KekSelector selector, Instant at) {
    try {
      KeyRef kek = Objects.requireNonNull(selector.kekAt(at), "kek");
      if (!KekSelector.KEK_ID.matcher(kek.keyId()).matches()) {
        throw new IllegalArgumentException("not a KEK id of an envelope header");
      }
      return kek;
    } catch (RuntimeException e) {
      throw new KeyUnavailableException();
    }
  }

  /**
   * Wraps {@code dataKey} under {@code kek} and returns the wrapped bytes for the header.
   *
   * @throws KeyUnavailableException when the service fails or answers with bytes that no header can
   *     carry
   */
  static byte[] wrap(Kms kms, KeyRef kek, byte[] dataKey, byte[] aad) {
    byte[] wrapped;
    try {
      WrappedKey result = kms.wrap(kek, dataKey, aad);
      wrapped = result.ciphertext();
    } catch (RuntimeException e) {
      throw new KeyUnavailableException();
    }
    if (wrapped.length < 1 || wrapped.length > MAX_WRAPPED_BYTES) {
      throw new KeyUnavailableException();
    }
    return wrapped;
  }

  /**
   * Unwraps the data key of an envelope header; the caller zeroes the returned array. Nothing is
   * cached: every call asks the service.
   *
   * @throws DataShreddedException when the KEK was destroyed
   * @throws Envelope.AuthenticationException when the service rejects the wrapped key or the
   *     binding (a tampered wrapped key, a wrong AAD, an unknown KEK)
   * @throws KeyUnavailableException when the service is unreachable, refuses the caller or answers
   *     with something that is not a 32-byte key
   */
  static byte[] unwrap(Kms kms, Envelope.Header header, byte[] aad) {
    byte[] dataKey;
    try {
      dataKey =
          kms.unwrap(
              new KeyRef(header.kekId(), KeyPurpose.ENVELOPE_KEK),
              new WrappedKey(header.kekId(), header.wrappedKey()),
              aad);
    } catch (KmsKeyDestroyedException e) {
      throw new DataShreddedException();
    } catch (IllegalArgumentException e) {
      throw new Envelope.AuthenticationException();
    } catch (RuntimeException e) {
      throw new KeyUnavailableException();
    }
    if (dataKey == null || dataKey.length != KEY_BYTES) {
      if (dataKey != null) {
        zero(dataKey);
      }
      throw new KeyUnavailableException();
    }
    return dataKey;
  }

  /** Best-effort erasure of a key held in an array (the copies inside the JCA are out of reach). */
  static void zero(byte[] key) {
    Arrays.fill(key, (byte) 0);
  }
}
