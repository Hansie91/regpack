package dev.regpack.inmemory.crypto;

import static dev.regpack.inmemory.crypto.CryptoFixtures.APPLICATION;
import static dev.regpack.inmemory.crypto.CryptoFixtures.STRANGER;
import static dev.regpack.inmemory.crypto.CryptoFixtures.assertKeyUnavailable;
import static dev.regpack.inmemory.crypto.CryptoFixtures.range;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.crypto.CryptoFixtures.FailingKms;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.crypto.SubjectHasher;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsSignature;
import dev.regpack.ports.security.WrappedKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** The data-subject hash: the pinned V8 values, normalisation, key purpose and failure mapping. */
class SubjectHasherTest {
  private static final KeyRef INDEX = new KeyRef("index-key", KeyPurpose.INDEX_HMAC);
  private static final KeyRef TRN = new KeyRef("trn-key", KeyPurpose.TRN_HMAC);
  private static final String VALUE = "AB123456C";

  /** V8: key bytes 01 .. 20, kind NATIONAL_ID, value AB123456C. */
  private static final String V8_ID =
      "17dcb03c620db32755c278adbc34498234886cc55d270fcc27727de59133390c";

  /** V8: the value e followed by U+0301, which NFC turns into U+00E9 (also the hash of U+00E9). */
  private static final String V8_E_ACUTE =
      "be8ee7afe18dcc26fa236d92460e827a4119f78fe6ae473548620f0ed6941713";

  // Written as Java Unicode escapes only: composed and decomposed texts look alike in an editor,
  // and
  // a tool that normalises text would make the NFC tests vacuous without a failure.
  private static final String E_DECOMPOSED = "e\u0301";
  private static final String E_COMPOSED = "\u00e9";
  private static final String A_RING_DECOMPOSED = "A\u030a";
  private static final String ANGSTROM_SIGN = "\u212b";
  private static final String A_RING_COMPOSED = "\u00c5";

  /** Compatibility forms that NFC leaves alone (NFKC would fold them). */
  private static final String FI_LIGATURE = "\ufb01";

  private static final String FULLWIDTH_A = "\uff21";

  private static InMemoryKms raw() {
    return InMemoryKms.create()
        .createHmacKey(INDEX, range(1, 32), Set.of(APPLICATION))
        .createHmacKey(TRN, range(1, 32), Set.of(APPLICATION));
  }

  private static SubjectHasher hasher() {
    return new SubjectHasher(raw().as(APPLICATION), INDEX);
  }

  @Test
  void theGoldenHashIsPinned() {
    SubjectHasher hasher = hasher();

    assertThat(hasher.hash("NATIONAL_ID", VALUE).hex()).isEqualTo(V8_ID);
    assertThat(hasher.hash("NATIONAL_ID", E_DECOMPOSED).hex()).isEqualTo(V8_E_ACUTE);
    assertThat(hasher.hash("NATIONAL_ID", E_COMPOSED).hex()).isEqualTo(V8_E_ACUTE);
  }

  @Test
  void nfcVariantsHashAlike() {
    SubjectHasher hasher = hasher();

    assertThat(hasher.hash("NATIONAL_ID", E_DECOMPOSED))
        .isEqualTo(hasher.hash("NATIONAL_ID", E_COMPOSED));
    // U+212B (the angstrom sign) and A with a ring above both normalise to U+00C5
    SubjectHash angstrom = hasher.hash("NATIONAL_ID", ANGSTROM_SIGN);
    assertThat(angstrom).isEqualTo(hasher.hash("NATIONAL_ID", A_RING_DECOMPOSED));
    assertThat(angstrom).isEqualTo(hasher.hash("NATIONAL_ID", A_RING_COMPOSED));
    assertThat(angstrom).isNotEqualTo(hasher.hash("NATIONAL_ID", "A"));
  }

  /** The premise of the two tests above: the inputs differ as text, whatever an editor did. */
  @Test
  void theNfcTestInputsReallyDifferAsText() {
    assertThat(E_DECOMPOSED).isNotEqualTo(E_COMPOSED).hasSize(2);
    assertThat(E_COMPOSED).hasSize(1);
    assertThat(ANGSTROM_SIGN).isNotEqualTo(A_RING_COMPOSED).hasSize(1);
    assertThat(A_RING_DECOMPOSED).isNotEqualTo(A_RING_COMPOSED).hasSize(2);
    assertThat(A_RING_DECOMPOSED).isNotEqualTo(ANGSTROM_SIGN);
  }

  @Test
  void onlyCanonicalFormsAreFolded() {
    SubjectHasher hasher = hasher();

    assertThat(hasher.hash("NATIONAL_ID", FI_LIGATURE))
        .isNotEqualTo(hasher.hash("NATIONAL_ID", "fi"));
    assertThat(hasher.hash("NATIONAL_ID", FULLWIDTH_A))
        .isNotEqualTo(hasher.hash("NATIONAL_ID", "A"));
  }

  @Test
  void kindsAreSeparated() {
    SubjectHasher hasher = hasher();

    assertThat(hasher.hash("NATIONAL_ID", VALUE)).isNotEqualTo(hasher.hash("PASSPORT", VALUE));
    assertThat(hasher.hash("AB", "C")).isNotEqualTo(hasher.hash("A", "BC"));
    assertThat(hasher.hash("A", "")).isNotEqualTo(hasher.hash("A", "\0"));
  }

  @Test
  void aBadKindIsRefused() {
    SubjectHasher hasher = hasher();

    for (String kind :
        List.of(
            "", "national_id", "NATIONAL ID", "NATIONAL-ID", "NATIONAL1", "A".repeat(33), "É")) {
      assertThatThrownBy(() -> hasher.hash(kind, VALUE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("invalid subject kind");
    }
    assertThat(hasher.hash("A".repeat(32), VALUE).hex()).hasSize(64);
    assertThat(hasher.hash("_", VALUE).hex()).hasSize(64);
    assertThatThrownBy(() -> hasher.hash(null, VALUE)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> hasher.hash("NATIONAL_ID", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aTrnHmacKeyIsRefused() {
    Kms kms = raw().as(APPLICATION);

    assertThatThrownBy(() -> new SubjectHasher(kms, TRN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SubjectHasher(kms, new KeyRef("kek-2026", KeyPurpose.ENVELOPE_KEK)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new SubjectHasher(kms, INDEX)).isNotNull();
  }

  @Test
  void anUnpairedSurrogateIsRefusedNotReplaced() {
    SubjectHasher hasher = hasher();

    assertThatThrownBy(() -> hasher.hash("NATIONAL_ID", "AB\ud800"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the value is not valid text");
  }

  @Test
  void theValueIsNeverInAMessage() {
    InMemoryKms raw = raw();
    Kms denied = raw.as(STRANGER);
    Kms broken = new FailingKms(new IllegalStateException("backend failure for " + VALUE));
    List<ThrowingCallable> failures = new ArrayList<>();
    failures.add(
        () -> new SubjectHasher(raw.as(APPLICATION), INDEX).hash("national " + VALUE, VALUE));
    failures.add(
        () -> new SubjectHasher(raw.as(APPLICATION), INDEX).hash("NATIONAL_ID", VALUE + "\ud800"));
    failures.add(() -> new SubjectHasher(denied, INDEX).hash("NATIONAL_ID", VALUE));
    failures.add(() -> new SubjectHasher(broken, INDEX).hash("NATIONAL_ID", VALUE));
    failures.add(
        () ->
            new SubjectHasher(raw.as(APPLICATION), new KeyRef("missing", KeyPurpose.INDEX_HMAC))
                .hash("NATIONAL_ID", VALUE));
    failures.add(() -> new SubjectHasher(raw.as(APPLICATION), TRN));

    for (ThrowingCallable failure : failures) {
      Throwable thrown = catchThrowable(failure);
      assertThat(thrown).isNotNull();
      assertThat(String.valueOf(thrown.getMessage()) + " " + thrown)
          .doesNotContain(VALUE)
          .doesNotContain("national")
          .doesNotContain(V8_ID);
      assertThat(thrown).hasNoCause();
      assertThat(thrown.getSuppressed()).isEmpty();
    }
  }

  @Test
  void aKmsFailureIsKeyUnavailableWithAFixedMessageAndNoCause() {
    InMemoryKms raw = raw();
    List<Kms> brokenKms =
        List.of(
            raw.as(STRANGER),
            new FailingKms(new IllegalStateException("backend failure for " + VALUE)),
            new FixedMacKms(new byte[16]),
            new FixedMacKms(new byte[0]),
            new FixedMacKms(null));

    for (Kms kms : brokenKms) {
      assertKeyUnavailable(
          catchThrowable(() -> new SubjectHasher(kms, INDEX).hash("NATIONAL_ID", VALUE)));
    }
    assertKeyUnavailable(
        catchThrowable(
            () ->
                new SubjectHasher(raw.as(APPLICATION), new KeyRef("missing", KeyPurpose.INDEX_HMAC))
                    .hash("NATIONAL_ID", VALUE)));
    // a key of another purpose under an INDEX_HMAC reference: the KMS refuses the purpose
    assertKeyUnavailable(
        catchThrowable(
            () ->
                new SubjectHasher(
                        raw.as(APPLICATION), new KeyRef(TRN.keyId(), KeyPurpose.INDEX_HMAC))
                    .hash("NATIONAL_ID", VALUE)));
  }

  @Test
  void theIdentifierBytesAreZeroedAfterTheCall() {
    RecordingMacKms kms = new RecordingMacKms(raw().as(APPLICATION));

    new SubjectHasher(kms, INDEX).hash("NATIONAL_ID", VALUE);

    assertThat(kms.copy).isEqualTo("regpack/subject/v1\0NATIONAL_ID\0AB123456C".getBytes(UTF_8));
    assertThat(kms.live).isEqualTo(new byte[kms.copy.length]);
  }

  @Test
  void theIdentifierBytesAreZeroedWhenTheKmsFails() {
    RecordingMacKms kms = new RecordingMacKms(raw().as(STRANGER));

    assertKeyUnavailable(
        catchThrowable(() -> new SubjectHasher(kms, INDEX).hash("NATIONAL_ID", VALUE)));

    assertThat(kms.copy).isEqualTo("regpack/subject/v1\0NATIONAL_ID\0AB123456C".getBytes(UTF_8));
    assertThat(kms.live).isEqualTo(new byte[kms.copy.length]);
  }

  @Test
  void aSubjectHashIsSixtyFourLowercaseHexDigits() {
    String valid = "0123456789abcdef".repeat(4);

    assertThat(new SubjectHash(valid).hex()).isEqualTo(valid);
    assertThat(new SubjectHash(valid)).isEqualTo(new SubjectHash(valid));
    assertThat(new SubjectHash(valid)).isNotEqualTo(new SubjectHash("f".repeat(64)));
    assertThat(new SubjectHash(valid)).hasSameHashCodeAs(new SubjectHash(valid));
    for (String bad :
        List.of("", "ab", valid.toUpperCase(Locale.ROOT), valid + "0", "g".repeat(64))) {
      assertThatThrownBy(() -> new SubjectHash(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("a subject hash is 64 lowercase hex digits");
    }
    assertThatThrownBy(() -> new SubjectHash(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toStringHidesTheHash() {
    SubjectHash hash = hasher().hash("NATIONAL_ID", VALUE);

    assertThat(hash.toString())
        .doesNotContain(hash.hex())
        .doesNotContain(hash.hex().substring(0, 8));
    assertThat(hash.toString()).doesNotContain(VALUE);
    assertThat(hash.toString()).isEqualTo("SubjectHash[hidden]");
  }

  /** Answers every hmac with the given bytes. */
  private static final class FixedMacKms implements Kms {
    private final byte[] mac;

    FixedMacKms(byte[] mac) {
      this.mac = mac == null ? null : mac.clone();
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
    public byte[] hmac(KeyRef key, byte[] message) {
      return mac == null ? null : mac.clone();
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

  /** Forwards hmac and keeps the message array it was handed, live and as a copy. */
  private static final class RecordingMacKms implements Kms {
    private final Kms delegate;
    byte[] live;
    byte[] copy;

    RecordingMacKms(Kms delegate) {
      this.delegate = delegate;
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
    public byte[] hmac(KeyRef key, byte[] message) {
      live = message;
      copy = message.clone();
      return delegate.hmac(key, message);
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
