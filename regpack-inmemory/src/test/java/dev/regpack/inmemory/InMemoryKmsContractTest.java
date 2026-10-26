package dev.regpack.inmemory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.inmemory.InMemoryKms.WorkloadIdentity;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsKeyDestroyedException;
import dev.regpack.ports.security.WrappedKey;
import dev.regpack.testkit.contract.KmsContract;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryKmsContractTest implements KmsContract {
  private static final WorkloadIdentity SIGNER = new WorkloadIdentity("signer");
  private static final WorkloadIdentity APPLICATION = new WorkloadIdentity("application");

  private final InMemoryKms kms =
      InMemoryKms.create()
          .createSigningKey(packKey(), Set.of(SIGNER))
          .createSigningKey(manifestKey(), Set.of(SIGNER))
          .createHmacKey(trnKey(), "trn-material-1".getBytes(UTF_8), Set.of(APPLICATION))
          .createHmacKey(trnKey2(), "trn-material-2".getBytes(UTF_8), Set.of(APPLICATION))
          .createHmacKey(indexKey(), "index-material".getBytes(UTF_8), Set.of(APPLICATION))
          .createKek(kek(), Set.of(APPLICATION))
          .createKek(kek2(), Set.of(SIGNER));

  @Override
  public Kms signer() {
    return kms.as(SIGNER);
  }

  @Override
  public Kms application() {
    return kms.as(APPLICATION);
  }

  @Override
  public KeyRef packKey() {
    return new KeyRef("pack-key", KeyPurpose.PACK_SIGNING);
  }

  @Override
  public KeyRef manifestKey() {
    return new KeyRef("manifest-key", KeyPurpose.MANIFEST_SIGNING);
  }

  @Override
  public KeyRef trnKey() {
    return new KeyRef("trn-key", KeyPurpose.TRN_HMAC);
  }

  @Override
  public KeyRef trnKey2() {
    return new KeyRef("trn-key-2", KeyPurpose.TRN_HMAC);
  }

  @Override
  public KeyRef kek() {
    return new KeyRef("kek-1", KeyPurpose.ENVELOPE_KEK);
  }

  @Override
  public KeyRef kek2() {
    return new KeyRef("kek-2", KeyPurpose.ENVELOPE_KEK);
  }

  @Override
  public KeyRef indexKey() {
    return new KeyRef("index-key", KeyPurpose.INDEX_HMAC);
  }

  @Override
  public void destroyForTest(KeyRef kek) {
    kms.destroy(kek);
  }

  @Test
  void hmacMatchesRfc4231TestCase2() {
    KeyRef rfc = new KeyRef("rfc-4231-2", KeyPurpose.INDEX_HMAC);
    Kms bound =
        InMemoryKms.create()
            .createHmacKey(rfc, "Jefe".getBytes(UTF_8), Set.of(APPLICATION))
            .as(APPLICATION);
    assertThat(
            HexFormat.of()
                .formatHex(bound.hmac(rfc, "what do ya want for nothing?".getBytes(UTF_8))))
        .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
  }

  @Test
  void aDestroyedKekIsReportedBeforeTheIdentityIsChecked() {
    kms.destroy(kek());
    byte[] data = "data".getBytes(UTF_8);
    // The signer is not allowed on kek-1, yet the answer is "destroyed", not "denied".
    assertThatThrownBy(() -> signer().wrap(kek(), data, data))
        .isInstanceOf(KmsKeyDestroyedException.class);
    assertThatThrownBy(() -> signer().unwrap(kek(), new WrappedKey("kek-1", data), data))
        .isInstanceOf(KmsKeyDestroyedException.class);
  }

  @Test
  void aDestroyedKekMessageNamesNoKey() {
    kms.destroy(kek());
    byte[] data = "data".getBytes(UTF_8);
    assertThatThrownBy(() -> application().wrap(kek(), data, data))
        .isInstanceOf(KmsKeyDestroyedException.class)
        .hasMessageNotContaining("kek-1")
        .hasNoCause();
  }

  @Test
  void aDestroyedKekIdCannotBeCreatedAgain() {
    kms.destroy(kek());
    assertThatThrownBy(() -> kms.createKek(kek(), Set.of(APPLICATION)))
        .isInstanceOf(IllegalArgumentException.class);
    // The tombstone stays: the old id still reports destroyed.
    byte[] data = "data".getBytes(UTF_8);
    assertThatThrownBy(() -> application().wrap(kek(), data, data))
        .isInstanceOf(KmsKeyDestroyedException.class);
  }

  @Test
  void destroyIsIdempotentAndRefusesWhatIsNotAKek() {
    kms.destroy(kek());
    kms.destroy(kek());
    assertThatThrownBy(() -> kms.destroy(new KeyRef("nope", KeyPurpose.ENVELOPE_KEK)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> kms.destroy(trnKey())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> kms.destroy(indexKey())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> kms.destroy(packKey())).isInstanceOf(IllegalArgumentException.class);
    // The id of a key that is not a KEK, named as a KEK, is not destroyed by the call either.
    assertThatThrownBy(() -> kms.destroy(new KeyRef(trnKey().keyId(), KeyPurpose.ENVELOPE_KEK)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(application().hmac(trnKey(), "x".getBytes(UTF_8))).hasSize(32);
  }

  @Test
  void hmacKeysAcceptOnlyTheTwoHmacPurposes() {
    assertThatThrownBy(
            () ->
                InMemoryKms.create()
                    .createHmacKey(
                        new KeyRef("k", KeyPurpose.ENVELOPE_KEK), new byte[] {1}, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                InMemoryKms.create()
                    .createHmacKey(
                        new KeyRef("k", KeyPurpose.MANIFEST_SIGNING), new byte[] {1}, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hmacMatchesRfc4231TestCase1() {
    byte[] key = new byte[20];
    java.util.Arrays.fill(key, (byte) 0x0b);
    KeyRef rfc = new KeyRef("rfc-4231", KeyPurpose.TRN_HMAC);
    Kms bound = InMemoryKms.create().createHmacKey(rfc, key, Set.of(APPLICATION)).as(APPLICATION);
    assertThat(HexFormat.of().formatHex(bound.hmac(rfc, "Hi There".getBytes(UTF_8))))
        .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
  }
}
