package dev.regpack.ports.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The primitive against the two published AES-256 test cases (V1, V2) and its refusals. */
class AesGcmTest {
  private static final HexFormat HEX = HexFormat.of();

  private static final byte[] CASE16_KEY =
      HEX.parseHex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308");
  private static final byte[] CASE16_IV = HEX.parseHex("cafebabefacedbaddecaf888");
  private static final byte[] CASE16_AAD = HEX.parseHex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
  private static final byte[] CASE16_PLAINTEXT =
      HEX.parseHex(
          "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
              + "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
  private static final String CASE16_CIPHERTEXT =
      "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa"
          + "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662";
  private static final String CASE16_TAG = "76fc6ece0f4e1768cddf8853bb2d551b";

  @Test
  void gcmTestCase13HasThePinnedTag() {
    byte[] out = AesGcm.encrypt(new byte[32], new byte[12], new byte[0], new byte[0]);

    // empty plaintext: the output is the tag alone (V1)
    assertThat(HEX.formatHex(out)).isEqualTo("530f8afbc74536b9a963b4f1c4cb738b");
    assertThat(AesGcm.decrypt(new byte[32], new byte[12], new byte[0], out)).isEmpty();
  }

  @Test
  void gcmTestCase16HasThePinnedCiphertextAndTag() {
    byte[] out = AesGcm.encrypt(CASE16_KEY, CASE16_IV, CASE16_AAD, CASE16_PLAINTEXT);

    assertThat(out).hasSize(CASE16_PLAINTEXT.length + 16);
    String all = HEX.formatHex(out);
    assertThat(all.substring(0, all.length() - 32)).isEqualTo(CASE16_CIPHERTEXT); // V2
    assertThat(all.substring(all.length() - 32)).isEqualTo(CASE16_TAG);
    assertThat(AesGcm.decrypt(CASE16_KEY, CASE16_IV, CASE16_AAD, out)).isEqualTo(CASE16_PLAINTEXT);
  }

  @Test
  void aFlippedTagBitIsRefused() {
    byte[] out = AesGcm.encrypt(CASE16_KEY, CASE16_IV, CASE16_AAD, CASE16_PLAINTEXT);

    // every one of the 128 tag bits
    for (int bit = 0; bit < 128; bit++) {
      byte[] changed = out.clone();
      changed[out.length - 16 + bit / 8] ^= (byte) (1 << (bit % 8));
      assertThatThrownBy(() -> AesGcm.decrypt(CASE16_KEY, CASE16_IV, CASE16_AAD, changed))
          .isInstanceOf(Envelope.AuthenticationException.class);
    }
  }

  @Test
  void aChangedCiphertextBitKeyIvOrAadIsRefused() {
    byte[] out = AesGcm.encrypt(CASE16_KEY, CASE16_IV, CASE16_AAD, CASE16_PLAINTEXT);

    byte[] ciphertextBit = out.clone();
    ciphertextBit[0] ^= 1;
    byte[] key = CASE16_KEY.clone();
    key[31] ^= 1;
    byte[] iv = CASE16_IV.clone();
    iv[11] ^= 1;
    byte[] aad = CASE16_AAD.clone();
    aad[0] ^= 1;

    assertThatThrownBy(() -> AesGcm.decrypt(CASE16_KEY, CASE16_IV, CASE16_AAD, ciphertextBit))
        .isInstanceOf(Envelope.AuthenticationException.class);
    assertThatThrownBy(() -> AesGcm.decrypt(key, CASE16_IV, CASE16_AAD, out))
        .isInstanceOf(Envelope.AuthenticationException.class);
    assertThatThrownBy(() -> AesGcm.decrypt(CASE16_KEY, iv, CASE16_AAD, out))
        .isInstanceOf(Envelope.AuthenticationException.class);
    assertThatThrownBy(() -> AesGcm.decrypt(CASE16_KEY, CASE16_IV, aad, out))
        .isInstanceOf(Envelope.AuthenticationException.class);
    assertThatThrownBy(() -> AesGcm.decrypt(CASE16_KEY, CASE16_IV, new byte[0], out))
        .isInstanceOf(Envelope.AuthenticationException.class);
  }

  @Test
  void anInputShorterThanTheTagIsRefused() {
    for (int length = 0; length < 16; length++) {
      byte[] input = new byte[length];
      assertThatThrownBy(() -> AesGcm.decrypt(CASE16_KEY, CASE16_IV, CASE16_AAD, input))
          .isInstanceOf(Envelope.AuthenticationException.class);
    }
  }

  @Test
  void aWrongSizedKeyOrNonceIsRefusedAndTheMessageHoldsNoKey() {
    byte[] shortKey = Arrays.copyOf(CASE16_KEY, 31);
    byte[] longKey = Arrays.copyOf(CASE16_KEY, 33);
    byte[] shortNonce = Arrays.copyOf(CASE16_IV, 11);
    byte[] longNonce = Arrays.copyOf(CASE16_IV, 13);

    for (byte[] key : new byte[][] {shortKey, longKey}) {
      assertThatThrownBy(() -> AesGcm.encrypt(key, CASE16_IV, CASE16_AAD, CASE16_PLAINTEXT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("feff");
    }
    for (byte[] nonce : new byte[][] {shortNonce, longNonce}) {
      assertThatThrownBy(() -> AesGcm.encrypt(CASE16_KEY, nonce, CASE16_AAD, CASE16_PLAINTEXT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> AesGcm.decrypt(CASE16_KEY, nonce, CASE16_AAD, new byte[32]))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> AesGcm.decrypt(shortKey, CASE16_IV, CASE16_AAD, new byte[32]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theCallerKeepsItsArrays() {
    byte[] key = CASE16_KEY.clone();
    byte[] plaintext = CASE16_PLAINTEXT.clone();

    AesGcm.encrypt(key, CASE16_IV, CASE16_AAD, plaintext);

    assertThat(key).isEqualTo(CASE16_KEY);
    assertThat(plaintext).isEqualTo(CASE16_PLAINTEXT);
  }
}
