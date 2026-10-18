package dev.regpack.engine.report;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.report.TrnDerivation.Derived;
import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.InMemoryKms.WorkloadIdentity;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsAccessDeniedException;
import dev.regpack.ports.security.KmsSignature;
import dev.regpack.ports.security.WrappedKey;
import java.math.BigInteger;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** TRN derivation (spec 10, policy {@code derive}; OI-10 puts the regime in the message). */
class TrnDerivationTest {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final WorkloadIdentity APPLICATION = new WorkloadIdentity("application");
  private static final WorkloadIdentity SIGNER = new WorkloadIdentity("signer");
  private static final KeyRef KEY_ONE = new KeyRef("synthetic-trn-key-1", KeyPurpose.TRN_HMAC);
  private static final KeyRef KEY_TWO = new KeyRef("synthetic-trn-key-2", KeyPurpose.TRN_HMAC);
  private static final KeyRef SIGNING =
      new KeyRef("synthetic-signing", KeyPurpose.MANIFEST_SIGNING);
  private static final Regime REGIME_A = new Regime("test.regime.alpha");
  private static final Regime REGIME_B = new Regime("test.regime.beta");
  private static final EventIdentity IDENTITY = EventIdentity.of("synthetic", "event", "1");

  /** Synthetic key material, visibly not a real secret. */
  private final Kms kms =
      InMemoryKms.create()
          .createHmacKey(KEY_ONE, "SYNTHETIC-TEST-KEY-ONE".getBytes(UTF_8), Set.of(APPLICATION))
          .createHmacKey(KEY_TWO, "SYNTHETIC-TEST-KEY-TWO".getBytes(UTF_8), Set.of(APPLICATION))
          .createSigningKey(SIGNING, Set.of(APPLICATION, SIGNER))
          .as(APPLICATION);

  private Derived derive(KeyRef key, Regime regime, Leg leg, int length) {
    return TrnDerivation.derive(kms, key, regime, IDENTITY, leg, length);
  }

  @Test
  void deriveIsDeterministic() {
    Derived first = derive(KEY_ONE, REGIME_A, Leg.MARKET, TrnDerivation.DEFAULT_LENGTH);
    Derived second = derive(KEY_ONE, REGIME_A, Leg.MARKET, TrnDerivation.DEFAULT_LENGTH);
    assertThat(second).isEqualTo(first);
    assertThat(first.trn()).hasSize(32);
  }

  @Test
  void differentLegsGiveDifferentTrns() {
    String market = derive(KEY_ONE, REGIME_A, Leg.MARKET, 32).trn();
    String client = derive(KEY_ONE, REGIME_A, Leg.CLIENT, 32).trn();
    String allocation = derive(KEY_ONE, REGIME_A, Leg.ALLOCATION, 32).trn();
    assertThat(Set.of(market, client, allocation)).hasSize(3);
  }

  @Test
  void differentRegimesGiveDifferentTrns() {
    assertThat(derive(KEY_ONE, REGIME_A, Leg.MARKET, 32).trn())
        .isNotEqualTo(derive(KEY_ONE, REGIME_B, Leg.MARKET, 32).trn());
  }

  @Test
  void differentKeysGiveDifferentTrnsAndRecordTheKeyId() {
    Derived one = derive(KEY_ONE, REGIME_A, Leg.MARKET, 32);
    Derived two = derive(KEY_TWO, REGIME_A, Leg.MARKET, 32);
    assertThat(one.trn()).isNotEqualTo(two.trn());
    assertThat(one.keyId()).isEqualTo("synthetic-trn-key-1");
    assertThat(two.keyId()).isEqualTo("synthetic-trn-key-2");
  }

  @Test
  void valueUsesOnlyTheBase32AlphabetAndTheRequestedLength() {
    for (int length : new int[] {1, 20, 32, 52}) {
      String trn = derive(KEY_ONE, REGIME_A, Leg.CLIENT, length).trn();
      assertThat(trn).hasSize(length);
      assertThat(trn.chars()).allMatch(c -> ALPHABET.indexOf(c) >= 0);
    }
  }

  @Test
  void aShorterValueIsThePrefixOfALongerOne() {
    String full = derive(KEY_ONE, REGIME_A, Leg.CLIENT, 52).trn();
    assertThat(derive(KEY_ONE, REGIME_A, Leg.CLIENT, 20).trn()).isEqualTo(full.substring(0, 20));
  }

  @Test
  void rejectsLengthOutsideOneToFiftyTwo() {
    assertThatThrownBy(() -> derive(KEY_ONE, REGIME_A, Leg.MARKET, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> derive(KEY_ONE, REGIME_A, Leg.MARKET, 53))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theMessageIsTheRegimeIdentityAndLegSeparatedByNewlines() {
    byte[][] seen = new byte[1][];
    Kms recording =
        new FixedMacKms(new byte[32]) {
          @Override
          public byte[] hmac(KeyRef key, byte[] message) {
            seen[0] = message.clone();
            return super.hmac(key, message);
          }
        };
    TrnDerivation.derive(recording, KEY_ONE, REGIME_A, IDENTITY, Leg.CLIENT, 32);
    assertThat(new String(seen[0], UTF_8))
        .isEqualTo("test.regime.alpha\nsynthetic|event|1\nCLIENT");
  }

  @Test
  void knownAnswer() {
    byte[] mac = new byte[32];
    for (int i = 0; i < mac.length; i++) {
      mac[i] = (byte) i;
    }
    Kms fake = new FixedMacKms(mac);
    Derived full = TrnDerivation.derive(fake, KEY_ONE, REGIME_A, IDENTITY, Leg.MARKET, 52);
    // The first five bytes 00 01 02 03 04 are the 5-bit groups 0 0 0 16 4 0 24 4: AAAQEAYE.
    assertThat(full.trn()).startsWith("AAAQEAYE");
    assertThat(full.trn()).isEqualTo(referenceBase32(mac).substring(0, 52));
    assertThat(full.keyId()).isEqualTo("synthetic-trn-key-1");
    assertThat(TrnDerivation.derive(fake, KEY_ONE, REGIME_A, IDENTITY, Leg.MARKET, 8).trn())
        .isEqualTo("AAAQEAYE");
  }

  @Test
  void aSigningKeyCannotBeUsedForTheMac() {
    assertThatThrownBy(() -> derive(SIGNING, REGIME_A, Leg.MARKET, 32))
        .isInstanceOf(KmsAccessDeniedException.class);
  }

  /** Independent routine: the big-endian bit string of the bytes, five bits at a time. */
  private static String referenceBase32(byte[] bytes) {
    BigInteger value = new BigInteger(1, bytes);
    int bits = bytes.length * 8;
    int chars = (bits + 4) / 5;
    int padded = chars * 5;
    value = value.shiftLeft(padded - bits);
    StringBuilder out = new StringBuilder();
    for (int i = chars - 1; i >= 0; i--) {
      out.append(ALPHABET.charAt(value.shiftRight(i * 5).intValue() & 31));
    }
    return out.toString();
  }

  /** A {@link Kms} whose HMAC is fixed; every other operation is unsupported. */
  private static class FixedMacKms implements Kms {
    private final byte[] mac;

    FixedMacKms(byte[] mac) {
      this.mac = mac.clone();
    }

    @Override
    public byte[] hmac(KeyRef key, byte[] message) {
      return mac.clone();
    }

    @Override
    public KmsSignature sign(KeyRef key, byte[] message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean verify(KeyRef key, byte[] message, KmsSignature signature) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad) {
      throw new UnsupportedOperationException();
    }
  }
}
