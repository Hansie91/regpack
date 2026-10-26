package dev.regpack.inmemory.crypto;

import static dev.regpack.inmemory.crypto.CryptoFixtures.APPLICATION;
import static dev.regpack.inmemory.crypto.CryptoFixtures.HEX;
import static dev.regpack.inmemory.crypto.CryptoFixtures.SHREDDED_MESSAGE;
import static dev.regpack.inmemory.crypto.CryptoFixtures.STRANGER;
import static dev.regpack.inmemory.crypto.CryptoFixtures.assertKeyUnavailable;
import static dev.regpack.inmemory.crypto.CryptoFixtures.clockAt;
import static dev.regpack.inmemory.crypto.CryptoFixtures.entropy;
import static dev.regpack.inmemory.crypto.CryptoFixtures.kek;
import static dev.regpack.inmemory.crypto.CryptoFixtures.kms;
import static dev.regpack.inmemory.crypto.CryptoFixtures.nonceOf;
import static dev.regpack.inmemory.crypto.CryptoFixtures.range;
import static dev.regpack.inmemory.crypto.CryptoFixtures.sha256Hex;
import static dev.regpack.inmemory.crypto.CryptoFixtures.stream;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.crypto.CryptoFixtures.FailingKms;
import dev.regpack.inmemory.crypto.CryptoFixtures.RecordingKms;
import dev.regpack.inmemory.crypto.CryptoFixtures.StubKms;
import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.Envelope.AuthenticationException;
import dev.regpack.ports.crypto.Envelope.Header;
import dev.regpack.ports.crypto.Envelope.Kind;
import dev.regpack.ports.crypto.KekSelector;
import dev.regpack.ports.crypto.ObjectCipher;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.WrappedKey;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.crypto.CounterEntropy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** The object cipher: the pinned V6 envelope, key binding, KEK years and the failure mapping. */
class ObjectCipherTest {
  private static final EvidenceKey K1 = new EvidenceKey("landing/k1");
  private static final EvidenceKey K2 = new EvidenceKey("landing/k2");
  private static final byte[] HELLO = "hello".getBytes(UTF_8);
  private static final String IN_2026 = "2026-06-15T10:00:00Z";

  /** V6: the stub wraps to 00 .. 3b, the entropy is the V5 stream. */
  private static final String V6_SHA256 =
      "a4e2034605d3a214b4327c7085e5aada2647e441084c124d7ca1b47391e3fde5";

  /** V9: the wrap AAD of an OBJECT, kek-2026, landing/k1. */
  private static final String V9_WRAP_AAD_HEX =
      "7265677061636b2f64656b2f76317c4f424a4543547c6b656b2d323032367c6c616e64696e672f6b31";

  private static final int HEADER_BYTES = 77;

  private static ObjectCipher cipher(Kms kms, Clock clock) {
    return new ObjectCipher(kms, KekSelector.perUtcYear("kek"), entropy(), clock);
  }

  @Test
  void sealDrawsTheKeyThenTheNonceAndBindsTheKeyPath() {
    StubKms kms = new StubKms(range(0, 60));

    byte[] envelope = cipher(kms, clockAt(IN_2026)).seal(K1, HELLO);

    assertThat(envelope).hasSize(110);
    assertThat(sha256Hex(envelope)).isEqualTo(V6_SHA256);
    byte[] stream = stream(64);
    assertThat(kms.wraps).hasSize(1);
    assertThat(kms.wraps.get(0).dataKey()).isEqualTo(Arrays.copyOfRange(stream, 0, 32));
    assertThat(Arrays.copyOfRange(envelope, HEADER_BYTES, HEADER_BYTES + 12))
        .isEqualTo(Arrays.copyOfRange(stream, 32, 44));
    assertThat(HEX.formatHex(kms.wraps.get(0).aad())).isEqualTo(V9_WRAP_AAD_HEX);
    assertThat(kms.wraps.get(0).kek()).isEqualTo(kek(2026));
  }

  @Test
  void openRoundTripsOnTheInMemoryKms() {
    InMemoryKms kms = kms();
    ObjectCipher sealing = cipher(kms.as(APPLICATION), clockAt(IN_2026));
    ObjectCipher opening =
        new ObjectCipher(
            kms.as(APPLICATION),
            KekSelector.perUtcYear("other"),
            new CounterEntropy("another seed".getBytes(UTF_8)),
            clockAt("2031-01-01T00:00:00Z"));

    byte[] envelope = sealing.seal(K1, HELLO);

    assertThat(Envelope.isEnvelope(envelope)).isTrue();
    assertThat(Envelope.header(envelope).kind()).isEqualTo(Kind.OBJECT);
    assertThat(Envelope.header(envelope).kekId()).isEqualTo("kek-2026");
    assertThat(opening.open(K1, envelope)).isEqualTo(HELLO);
  }

  @Test
  void twoSealsOfOnePlaintextDifferInKeyAndNonce() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    ObjectCipher cipher = cipher(kms, clockAt(IN_2026));

    byte[] first = cipher.seal(K1, HELLO);
    byte[] second = cipher.seal(K1, HELLO);

    assertThat(first).isNotEqualTo(second);
    assertThat(nonceOf(first)).isNotEqualTo(nonceOf(second));
    assertThat(kms.wraps).hasSize(2);
    assertThat(kms.wraps.get(0).dataKey()).isNotEqualTo(kms.wraps.get(1).dataKey());
    assertThat(Envelope.header(first).wrappedKey())
        .isNotEqualTo(Envelope.header(second).wrappedKey());
  }

  @Test
  void anEnvelopeMovedToAnotherKeyIsRefused() {
    ObjectCipher cipher = cipher(kms().as(APPLICATION), clockAt(IN_2026));
    byte[] envelope = cipher.seal(K1, HELLO);

    assertThat(cipher.open(K1, envelope)).isEqualTo(HELLO);
    assertThatThrownBy(() -> cipher.open(K2, envelope)).isInstanceOf(AuthenticationException.class);
  }

  @Test
  void aTamperedWrappedKeyIsRefused() {
    ObjectCipher cipher = cipher(kms().as(APPLICATION), clockAt(IN_2026));
    byte[] envelope = cipher.seal(K1, HELLO);
    int wrappedAt = 7 + "kek-2026".length() + 2;
    byte[] tampered = envelope.clone();
    tampered[wrappedAt + 20] ^= 0x01;

    assertThatThrownBy(() -> cipher.open(K1, tampered)).isInstanceOf(AuthenticationException.class);
  }

  @Test
  void aWrappedKeyIsBoundToItsKekAndPath() {
    Kms kms = kms().as(APPLICATION);
    byte[] envelope = cipher(kms, clockAt(IN_2026)).seal(K1, HELLO);
    Header header = Envelope.header(envelope);
    KeyRef kek = kek(2026);
    WrappedKey wrapped = new WrappedKey(header.kekId(), header.wrappedKey());

    byte[] dataKey = kms.unwrap(kek, wrapped, Envelope.wrapAad(Kind.OBJECT, "kek-2026", K1.path()));

    assertThat(dataKey).hasSize(32);
    assertThat(Envelope.open(envelope, dataKey, K1.path().getBytes(UTF_8))).isEqualTo(HELLO);
    assertThatThrownBy(
            () -> kms.unwrap(kek, wrapped, Envelope.wrapAad(Kind.OBJECT, "kek-2026", K2.path())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> kms.unwrap(kek, wrapped, Envelope.wrapAad(Kind.OBJECT, "kek-2027", K1.path())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> kms.unwrap(kek, wrapped, Envelope.wrapAad(Kind.BODY, "kek-2026", K1.path())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDestroyedKekIsDataShreddedNotCorruption() {
    InMemoryKms kms = kms();
    ObjectCipher cipher = cipher(kms.as(APPLICATION), clockAt(IN_2026));
    byte[] envelope = cipher.seal(K1, HELLO);
    kms.destroy(kek(2026));

    Throwable thrown = catchThrowable(() -> cipher.open(K1, envelope));

    assertThat(thrown).isInstanceOf(DataShreddedException.class).hasMessage(SHREDDED_MESSAGE);
    assertThat(thrown).hasNoCause();
    assertThat(thrown.getSuppressed()).isEmpty();
    assertThat(thrown).isNotInstanceOf(AuthenticationException.class);
    assertThat(thrown).isNotInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anUnknownKekOnOpenReadsAsCorruption() {
    byte[] envelope = cipher(kms().as(APPLICATION), clockAt(IN_2026)).seal(K1, HELLO);
    InMemoryKms other = InMemoryKms.create().createKek(kek(2027), Set.of(APPLICATION));

    assertThatThrownBy(() -> cipher(other.as(APPLICATION), clockAt(IN_2026)).open(K1, envelope))
        .isInstanceOf(AuthenticationException.class);
  }

  @Test
  void aNewYearUsesTheNewKekAndTheOldEnvelopeStillOpens() {
    ManualClock clock = clockAt("2026-12-31T23:59:59.999999Z");
    ObjectCipher cipher = cipher(kms().as(APPLICATION), clock);

    byte[] lastOf2026 = cipher.seal(K1, HELLO);
    clock.advance(Duration.ofNanos(1000));
    byte[] firstOf2027 = cipher.seal(K1, HELLO);

    assertThat(Envelope.header(lastOf2026).kekId()).isEqualTo("kek-2026");
    assertThat(Envelope.header(firstOf2027).kekId()).isEqualTo("kek-2027");
    assertThat(cipher.open(K1, lastOf2026)).isEqualTo(HELLO);
    assertThat(cipher.open(K1, firstOf2027)).isEqualTo(HELLO);
  }

  @Test
  void aKmsFailureIsKeyUnavailableWithAFixedMessageAndNoCause() {
    for (ThrowingCallable failure : kmsFailures().values()) {
      assertKeyUnavailable(catchThrowable(failure));
    }
  }

  @Test
  void aPlainOrForeignInputIsRefusedWithoutAKmsCall() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    ObjectCipher cipher = cipher(kms, clockAt(IN_2026));
    byte[] bodyEnvelope =
        Envelope.seal(
            new Header(Kind.BODY, "kek-2026", range(0, 60)),
            range(0, 32),
            range(0xa0, 12),
            K1.path().getBytes(UTF_8),
            HELLO);

    assertThatThrownBy(() -> cipher.open(K1, "{\"a\":1}".getBytes(UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an envelope");
    assertThatThrownBy(() -> cipher.open(K1, bodyEnvelope))
        .isInstanceOf(AuthenticationException.class);
    assertThat(kms.unwraps.get()).isZero();
  }

  @Test
  void theDrawnAndTheUnwrappedKeyArraysAreZeroedAfterUse() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    ObjectCipher cipher = cipher(kms, clockAt(IN_2026));

    byte[] envelope = cipher.seal(K1, HELLO);
    assertThat(kms.wraps.get(0).live()).isEqualTo(new byte[32]);
    assertThat(kms.wraps.get(0).dataKey()).isNotEqualTo(new byte[32]);

    assertThat(cipher.open(K1, envelope)).isEqualTo(HELLO);
    assertThat(kms.unwrapped.get(0)).isEqualTo(new byte[32]);

    byte[] flipped = envelope.clone();
    flipped[flipped.length - 1] ^= 0x01;
    assertThatThrownBy(() -> cipher.open(K1, flipped)).isInstanceOf(AuthenticationException.class);
    assertThat(kms.unwrapped.get(1)).isEqualTo(new byte[32]);
  }

  @Test
  void noMessageHoldsAKeyAKekIdOrAPath() {
    InMemoryKms raw = kms();
    ObjectCipher cipher = cipher(raw.as(APPLICATION), clockAt(IN_2026));
    byte[] envelope = cipher.seal(K1, HELLO);
    byte[] flipped = envelope.clone();
    flipped[flipped.length - 1] ^= 0x01;
    byte[] tamperedKey = envelope.clone();
    tamperedKey[7 + "kek-2026".length() + 2 + 20] ^= 0x01;
    List<ThrowingCallable> failures = new ArrayList<>(kmsFailures().values());
    failures.add(() -> cipher.open(K2, envelope));
    failures.add(() -> cipher.open(K1, flipped));
    failures.add(() -> cipher.open(K1, tamperedKey));
    failures.add(() -> cipher.open(K1, "plain".getBytes(UTF_8)));
    failures.add(() -> cipher.open(K1, new byte[0]));
    InMemoryKms destroyed = kms();
    ObjectCipher sealedBeforeDestruction = cipher(destroyed.as(APPLICATION), clockAt(IN_2026));
    byte[] doomed = sealedBeforeDestruction.seal(K1, HELLO);
    destroyed.destroy(kek(2026));
    failures.add(() -> sealedBeforeDestruction.open(K1, doomed));

    List<String> needles =
        List.of(
            HEX.formatHex(stream(32)),
            HEX.formatHex(stream(32)).toUpperCase(Locale.ROOT),
            "kek-2026",
            "kek-2027",
            "kek-",
            K1.path(),
            K2.path(),
            "landing");
    for (ThrowingCallable failure : failures) {
      Throwable thrown = catchThrowable(failure);
      assertThat(thrown).isNotNull();
      String shown = String.valueOf(thrown.getMessage()) + " " + thrown;
      for (String needle : needles) {
        assertThat(shown).doesNotContain(needle);
      }
      assertThat(thrown).hasNoCause();
      assertThat(thrown.getSuppressed()).isEmpty();
    }
  }

  /** Every way the key service can fail on seal or open; each is a KeyUnavailableException. */
  private static Map<String, ThrowingCallable> kmsFailures() {
    InMemoryKms raw = kms();
    Kms application = raw.as(APPLICATION);
    Kms denied = raw.as(STRANGER);
    IllegalStateException backend =
        new IllegalStateException("backend failure for kek-2026 landing/k1 and a key");
    Kms broken = new FailingKms(backend);
    byte[] sealed = cipher(application, clockAt(IN_2026)).seal(K1, HELLO);
    Map<String, ThrowingCallable> cases = new LinkedHashMap<>();
    cases.put("seal, access denied", () -> cipher(denied, clockAt(IN_2026)).seal(K1, HELLO));
    cases.put(
        "seal, unknown KEK",
        () -> cipher(application, clockAt("2030-01-01T00:00:00Z")).seal(K1, HELLO));
    cases.put("seal, backend failure", () -> cipher(broken, clockAt(IN_2026)).seal(K1, HELLO));
    cases.put(
        "seal, selector failure",
        () ->
            new ObjectCipher(
                    application,
                    at -> {
                      throw backend;
                    },
                    entropy(),
                    clockAt(IN_2026))
                .seal(K1, HELLO));
    cases.put(
        "seal, selector names a key that cannot head an envelope",
        () ->
            new ObjectCipher(
                    application,
                    at -> new KeyRef("not allowed/in a header", KeyPurpose.ENVELOPE_KEK),
                    entropy(),
                    clockAt(IN_2026))
                .seal(K1, HELLO));
    cases.put("open, access denied", () -> cipher(denied, clockAt(IN_2026)).open(K1, sealed));
    cases.put("open, backend failure", () -> cipher(broken, clockAt(IN_2026)).open(K1, sealed));
    return cases;
  }
}
