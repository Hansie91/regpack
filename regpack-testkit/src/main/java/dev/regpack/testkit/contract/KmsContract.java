package dev.regpack.testkit.contract;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsAccessDeniedException;
import dev.regpack.ports.security.KmsSignature;
import dev.regpack.ports.security.WrappedKey;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link Kms} adapter must satisfy. Each test is a default method. The two bound
 * instances share one key store; the key accessors return the same references on every call.
 */
public interface KmsContract {
  /**
   * Identity "signer": allowed on PACK_SIGNING "pack-key", MANIFEST_SIGNING "manifest-key" and
   * ENVELOPE_KEK "kek-2".
   */
  Kms signer();

  /**
   * Identity "application": not allowed on the signing keys; allowed on TRN_HMAC "trn-key",
   * "trn-key-2" and ENVELOPE_KEK "kek-1".
   */
  Kms application();

  KeyRef packKey();

  KeyRef manifestKey();

  KeyRef trnKey();

  KeyRef trnKey2();

  KeyRef kek();

  /** ENVELOPE_KEK "kek-2": allowed to the signer identity only, not to application. */
  KeyRef kek2();

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  @Test
  default void signAndVerifyRoundTrip() {
    byte[] message = bytes("manifest-bytes");
    KmsSignature signature = signer().sign(manifestKey(), message);
    assertThat(signer().verify(manifestKey(), message, signature)).isTrue();
    assertThat(application().verify(manifestKey(), message, signature)).isTrue();
    byte[] changed = message.clone();
    changed[0] ^= 1;
    assertThat(application().verify(manifestKey(), changed, signature)).isFalse();
  }

  @Test
  default void signingKeysAreDeniedToTheApplicationIdentity() {
    byte[] message = bytes("manifest-bytes");
    assertThatThrownBy(() -> application().sign(packKey(), message))
        .isInstanceOf(KmsAccessDeniedException.class);
    assertThatThrownBy(() -> application().sign(manifestKey(), message))
        .isInstanceOf(KmsAccessDeniedException.class);
  }

  @Test
  default void purposeIsEnforcedPerKey() {
    byte[] data = bytes("data");
    assertThatThrownBy(() -> signer().sign(trnKey(), data))
        .isInstanceOf(KmsAccessDeniedException.class);
    assertThatThrownBy(() -> application().wrap(trnKey(), data, data))
        .isInstanceOf(KmsAccessDeniedException.class);
    assertThatThrownBy(() -> application().hmac(packKey(), data))
        .isInstanceOf(KmsAccessDeniedException.class);
    assertThatThrownBy(() -> application().hmac(kek(), data))
        .isInstanceOf(KmsAccessDeniedException.class);
  }

  @Test
  default void unwrapWithMismatchedKekIsRejected() {
    byte[] dataKey = bytes("0123456789abcdef0123456789abcdef");
    byte[] aad = bytes("aad");
    WrappedKey wrapped = application().wrap(kek(), dataKey, aad);
    byte[] ciphertext = wrapped.ciphertext();
    assertThatThrownBy(() -> application().unwrap(kek(), new WrappedKey("kek-2", ciphertext), aad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> signer().unwrap(kek2(), new WrappedKey("kek-2", ciphertext), aad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  default void hmacIsDeniedToIdentitiesNotAllowedOnTheKey() {
    byte[] data = bytes("data");
    assertThatThrownBy(() -> signer().hmac(trnKey(), data))
        .isInstanceOf(KmsAccessDeniedException.class);
    assertThatThrownBy(() -> signer().hmac(trnKey2(), data))
        .isInstanceOf(KmsAccessDeniedException.class);
    // The allowed identity still works on the same keys.
    assertThat(application().hmac(trnKey(), data)).hasSize(32);
  }

  @Test
  default void unwrapIsDeniedToIdentitiesNotAllowedOnTheKek() {
    byte[] dataKey = bytes("0123456789abcdef0123456789abcdef");
    byte[] aad = bytes("aad");
    WrappedKey wrapped = application().wrap(kek(), dataKey, aad);
    // signer is not allowed on kek-1, so it cannot unwrap what the application wrapped.
    assertThatThrownBy(() -> signer().unwrap(kek(), wrapped, aad))
        .isInstanceOf(KmsAccessDeniedException.class);
    // application is not allowed on kek-2, even for a well-formed wrapped key naming kek-2.
    byte[] ciphertext = wrapped.ciphertext();
    assertThatThrownBy(() -> application().unwrap(kek2(), new WrappedKey("kek-2", ciphertext), aad))
        .isInstanceOf(KmsAccessDeniedException.class);
    WrappedKey underKek2 = signer().wrap(kek2(), dataKey, aad);
    assertThatThrownBy(() -> application().unwrap(kek2(), underKek2, aad))
        .isInstanceOf(KmsAccessDeniedException.class);
    assertThat(signer().unwrap(kek2(), underKek2, aad)).isEqualTo(dataKey);
    assertThat(application().unwrap(kek(), wrapped, aad)).isEqualTo(dataKey);
  }

  @Test
  default void verifyRejectsSignaturesOfAnotherKeyOrWithAlteredMetadata() {
    byte[] message = bytes("pack-bytes");
    KmsSignature signature = signer().sign(packKey(), message);
    assertThat(application().verify(packKey(), message, signature)).isTrue();
    // The signature was made by pack-key: it does not verify under manifest-key, as issued or
    // relabelled.
    assertThat(application().verify(manifestKey(), message, signature)).isFalse();
    KmsSignature relabelled = new KmsSignature(manifestKey().keyId(), "Ed25519", signature.value());
    assertThat(application().verify(manifestKey(), message, relabelled)).isFalse();
    // A signature that names another key or algorithm is rejected under the right key.
    assertThat(application().verify(packKey(), message, relabelled)).isFalse();
    KmsSignature otherAlgorithm = new KmsSignature(packKey().keyId(), "RSA", signature.value());
    assertThat(application().verify(packKey(), message, otherAlgorithm)).isFalse();
    // The signature of manifest-key over the same bytes is its own and does not verify under
    // pack-key either.
    KmsSignature manifestSignature = signer().sign(manifestKey(), message);
    assertThat(application().verify(packKey(), message, manifestSignature)).isFalse();
    assertThat(application().verify(manifestKey(), message, manifestSignature)).isTrue();
  }

  @Test
  default void kekNotPermittedToTheApplicationIsDenied() {
    byte[] data = bytes("data");
    assertThatThrownBy(() -> application().wrap(kek2(), data, data))
        .isInstanceOf(KmsAccessDeniedException.class);
  }

  @Test
  default void hmacIsDeterministicPerKeyAndDiffersAcrossKeys() {
    byte[] message = bytes("trn-input");
    byte[] first = application().hmac(trnKey(), message);
    assertThat(application().hmac(trnKey(), message)).isEqualTo(first);
    assertThat(application().hmac(trnKey2(), message)).isNotEqualTo(first);
    assertThat(first).hasSize(32);
  }

  @Test
  default void wrapUnwrapRoundTripWithAad() {
    byte[] dataKey = bytes("0123456789abcdef0123456789abcdef");
    byte[] aad = bytes("run-1");
    WrappedKey wrapped = application().wrap(kek(), dataKey, aad);
    assertThat(wrapped.keyId()).isEqualTo(kek().keyId());
    assertThat(application().unwrap(kek(), wrapped, aad)).isEqualTo(dataKey);
    assertThatThrownBy(() -> application().unwrap(kek(), wrapped, bytes("run-2")))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] tampered = wrapped.ciphertext();
    tampered[tampered.length - 1] ^= 1;
    assertThatThrownBy(
            () -> application().unwrap(kek(), new WrappedKey(wrapped.keyId(), tampered), aad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  default void wrapIsRandomizedButUnwrapsToTheSameKey() {
    byte[] dataKey = bytes("0123456789abcdef0123456789abcdef");
    byte[] aad = bytes("run-1");
    WrappedKey first = application().wrap(kek(), dataKey, aad);
    WrappedKey second = application().wrap(kek(), dataKey, aad);
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    assertThat(application().unwrap(kek(), first, aad))
        .isEqualTo(application().unwrap(kek(), second, aad));
  }

  @Test
  default void signaturesNameKeyAndAlgorithm() {
    KmsSignature signature = signer().sign(packKey(), bytes("pack"));
    assertThat(signature.keyId()).isEqualTo(packKey().keyId());
    assertThat(signature.algorithm()).isEqualTo("Ed25519");
  }

  @Test
  default void unknownKeyIsRejected() {
    byte[] data = bytes("data");
    assertThatThrownBy(() -> signer().sign(new KeyRef("nope", KeyPurpose.PACK_SIGNING), data))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(KmsAccessDeniedException.class);
    assertThatThrownBy(() -> application().hmac(new KeyRef("nope", KeyPurpose.TRN_HMAC), data))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(KmsAccessDeniedException.class);
    KeyRef unknownKek = new KeyRef("nope", KeyPurpose.ENVELOPE_KEK);
    assertThatThrownBy(() -> application().wrap(unknownKek, data, data))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(KmsAccessDeniedException.class);
    assertThatThrownBy(() -> application().unwrap(unknownKek, new WrappedKey("nope", data), data))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(KmsAccessDeniedException.class);
  }
}
