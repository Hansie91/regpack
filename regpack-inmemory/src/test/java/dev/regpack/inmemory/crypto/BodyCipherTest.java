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
import static dev.regpack.inmemory.crypto.CryptoFixtures.stream;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.crypto.CryptoFixtures.FailingKms;
import dev.regpack.inmemory.crypto.CryptoFixtures.RecordingKms;
import dev.regpack.inmemory.crypto.CryptoFixtures.SteppingClock;
import dev.regpack.inmemory.crypto.CryptoFixtures.Wrap;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.BodyCipher;
import dev.regpack.ports.crypto.BodyContext;
import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.Envelope.AuthenticationException;
import dev.regpack.ports.crypto.Envelope.Header;
import dev.regpack.ports.crypto.Envelope.Kind;
import dev.regpack.ports.crypto.KekSelector;
import dev.regpack.ports.ledger.ReportLedger;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.WrappedKey;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The body cipher: one key per UTC day and KEK, rotation, no read cache, and the failure mapping.
 */
class BodyCipherTest {
  private static final String MORNING = "2026-06-15T08:00:00Z";
  private static final byte[] BODY = "{\"a\":1}".getBytes(UTF_8);

  /** V4: the context of the golden body envelope. */
  private static final String V4_CONTEXT =
      "regpack/body/v1|mifir.eu.rts22|FIRM1|EXEC9|MARKET|"
          + "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881";

  private static BodyContext context(String event, Leg leg, String hashTag) {
    return new BodyContext(
        new Regime("mifir.eu.rts22"),
        EventIdentity.of("FIRM1", event),
        leg,
        Sha256.ofUtf8(hashTag));
  }

  private static BodyContext context() {
    return context("EXEC9", Leg.MARKET, "x");
  }

  private static BodyCipher cipher(Kms kms, Clock clock) {
    return new BodyCipher(kms, KekSelector.perUtcYear("kek"), entropy(), clock);
  }

  @Test
  void oneWrapServesManyBodiesOfADay() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    ManualClock clock = clockAt(MORNING);
    BodyCipher cipher = cipher(kms, clock);
    List<byte[]> envelopes = new ArrayList<>();

    for (int i = 0; i < 5; i++) {
      envelopes.add(cipher.seal(context("EXEC" + i, Leg.MARKET, "h" + i), BODY));
      clock.advance(Duration.ofHours(1));
    }

    assertThat(kms.wraps).hasSize(1);
    Set<String> wrappedKeys = new HashSet<>();
    Set<String> nonces = new HashSet<>();
    for (int i = 0; i < envelopes.size(); i++) {
      byte[] envelope = envelopes.get(i);
      wrappedKeys.add(HEX.formatHex(Envelope.header(envelope).wrappedKey()));
      nonces.add(HEX.formatHex(nonceOf(envelope)));
      assertThat(cipher.open(context("EXEC" + i, Leg.MARKET, "h" + i), envelope)).isEqualTo(BODY);
    }
    assertThat(wrappedKeys).hasSize(1);
    assertThat(nonces).hasSize(5);
    assertThat(kms.unwraps.get()).as("every open unwraps again: no read cache").isEqualTo(5);
  }

  @Test
  void aNewUtcDayDrawsANewKeyAndTheOldEnvelopeStillOpens() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    ManualClock clock = clockAt("2026-06-15T23:59:59.999999Z");
    BodyCipher cipher = cipher(kms, clock);

    byte[] lastOfTheDay = cipher.seal(context(), BODY);
    clock.advance(Duration.ofNanos(1000));
    byte[] firstOfTheNext = cipher.seal(context(), BODY);

    assertThat(kms.wraps).hasSize(2);
    assertThat(kms.wraps.get(0).dataKey()).isNotEqualTo(kms.wraps.get(1).dataKey());
    assertThat(Envelope.header(lastOfTheDay).wrappedKey())
        .isNotEqualTo(Envelope.header(firstOfTheNext).wrappedKey());
    assertThat(cipher.open(context(), lastOfTheDay)).isEqualTo(BODY);
    assertThat(cipher.open(context(), firstOfTheNext)).isEqualTo(BODY);
  }

  @Test
  void aSealAtTheYearBoundaryUsesOneInstant() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    SteppingClock clock =
        new SteppingClock(
            Instant.parse("2026-12-31T23:59:59.999999Z"), Instant.parse("2027-01-01T00:00:00Z"));
    BodyCipher cipher = cipher(kms, clock);

    byte[] first = cipher.seal(context(), BODY);

    assertThat(clock.reads()).as("one clock read per seal").isEqualTo(1);
    assertThat(Envelope.header(first).kekId()).isEqualTo("kek-2026");
    assertThat(kms.wraps.get(0).kek()).isEqualTo(kek(2026));
    assertThat(new String(kms.wraps.get(0).aad(), UTF_8))
        .isEqualTo("regpack/dek/v1|BODY|kek-2026|body");

    byte[] second = cipher.seal(context(), BODY);

    assertThat(clock.reads()).isEqualTo(2);
    assertThat(Envelope.header(second).kekId()).isEqualTo("kek-2027");
    assertThat(kms.wraps).hasSize(2);
    assertThat(cipher.open(context(), first)).isEqualTo(BODY);
    assertThat(cipher.open(context(), second)).isEqualTo(BODY);
  }

  @Test
  void aChangedKekWithinOneDayDrawsANewKey() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    AtomicReference<KeyRef> chosen = new AtomicReference<>(kek(2026));
    BodyCipher cipher = new BodyCipher(kms, at -> chosen.get(), entropy(), clockAt(MORNING));

    byte[] first = cipher.seal(context(), BODY);
    chosen.set(kek(2027));
    byte[] second = cipher.seal(context(), BODY);
    byte[] third = cipher.seal(context(), BODY);

    assertThat(kms.wraps).extracting(Wrap::kek).containsExactly(kek(2026), kek(2027));
    assertThat(Envelope.header(first).kekId()).isEqualTo("kek-2026");
    assertThat(Envelope.header(second).kekId()).isEqualTo("kek-2027");
    assertThat(Envelope.header(third).wrappedKey()).isEqualTo(Envelope.header(second).wrappedKey());
    assertThat(cipher.open(context(), first)).isEqualTo(BODY);
    assertThat(cipher.open(context(), third)).isEqualTo(BODY);
  }

  @Test
  void theKeyRotatesAfterTheSealLimit() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    BodyCipher cipher =
        new BodyCipher(kms, KekSelector.perUtcYear("kek"), entropy(), clockAt(MORNING), 3);
    List<byte[]> envelopes = new ArrayList<>();

    for (int i = 0; i < 4; i++) {
      envelopes.add(cipher.seal(context(), BODY));
    }

    assertThat(kms.wraps).hasSize(2);
    byte[] shared = Envelope.header(envelopes.get(0)).wrappedKey();
    assertThat(Envelope.header(envelopes.get(1)).wrappedKey()).isEqualTo(shared);
    assertThat(Envelope.header(envelopes.get(2)).wrappedKey()).isEqualTo(shared);
    assertThat(Envelope.header(envelopes.get(3)).wrappedKey()).isNotEqualTo(shared);
    for (int i = 4; i < 7; i++) {
      envelopes.add(cipher.seal(context(), BODY));
    }
    assertThat(kms.wraps).hasSize(3);
    for (byte[] envelope : envelopes) {
      assertThat(cipher.open(context(), envelope)).isEqualTo(BODY);
    }
    assertThat(BodyCipher.MAX_SEALS_PER_KEY).isEqualTo(1L << 30);
  }

  @Test
  void aNonPositiveSealLimitIsRefused() {
    Kms kms = kms().as(APPLICATION);
    KekSelector selector = KekSelector.perUtcYear("kek");

    assertThatThrownBy(() -> new BodyCipher(kms, selector, entropy(), clockAt(MORNING), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BodyCipher(kms, selector, entropy(), clockAt(MORNING), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBodyMovedToAnotherSubjectOrHashIsRefused() {
    BodyCipher cipher = cipher(kms().as(APPLICATION), clockAt(MORNING));
    byte[] envelope = cipher.seal(context(), BODY);

    assertThat(cipher.open(context(), envelope)).isEqualTo(BODY);
    List<BodyContext> others =
        List.of(
            context("EXEC8", Leg.MARKET, "x"),
            context("EXEC9", Leg.CLIENT, "x"),
            context("EXEC9", Leg.MARKET, "y"),
            new BodyContext(
                new Regime("emir.eu.refit"),
                EventIdentity.of("FIRM1", "EXEC9"),
                Leg.MARKET,
                Sha256.ofUtf8("x")));
    for (BodyContext other : others) {
      assertThatThrownBy(() -> cipher.open(other, envelope))
          .isInstanceOf(AuthenticationException.class);
    }
  }

  @Test
  void aDestroyedKekIsDataShredded() {
    InMemoryKms raw = kms();
    BodyCipher cipher = cipher(raw.as(APPLICATION), clockAt(MORNING));
    byte[] envelope = cipher.seal(context(), BODY);
    assertThat(cipher.open(context(), envelope)).isEqualTo(BODY);

    raw.destroy(kek(2026));

    Throwable thrown = catchThrowable(() -> cipher.open(context(), envelope));
    assertThat(thrown).isInstanceOf(DataShreddedException.class).hasMessage(SHREDDED_MESSAGE);
    assertThat(thrown).hasNoCause();
    assertThat(thrown.getSuppressed()).isEmpty();
  }

  @Test
  void concurrentSealsNeverShareANonce() throws Exception {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    BodyCipher cipher = cipher(kms, clockAt(MORNING));
    int threads = 8;
    int perThread = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<List<byte[]>>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < threads; t++) {
        Callable<List<byte[]>> task =
            () -> {
              start.await();
              List<byte[]> envelopes = new ArrayList<>();
              for (int i = 0; i < perThread; i++) {
                envelopes.add(cipher.seal(context(), BODY));
              }
              return envelopes;
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      Set<String> nonces = new HashSet<>();
      for (Future<List<byte[]>> future : futures) {
        for (byte[] envelope : future.get(60, TimeUnit.SECONDS)) {
          nonces.add(HEX.formatHex(nonceOf(envelope)));
          assertThat(cipher.open(context(), envelope)).isEqualTo(BODY);
        }
      }
      assertThat(nonces).hasSize(threads * perThread);
      assertThat(kms.wraps).as("one generation for the whole day").hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void bodyContextBytesArePinned() {
    assertThat(new String(context().bytes(), UTF_8)).isEqualTo(V4_CONTEXT);
    ReportLedger.NewVersion version =
        LedgerFixtures.newVersion(
            new ReportLedger.SubjectKey(
                new Regime("mifir.eu.rts22"), EventIdentity.of("FIRM1", "EXEC9"), Leg.MARKET),
            "LEI1",
            Optional.empty(),
            "NEW",
            "x");

    assertThat(BodyContext.of(version)).isEqualTo(context());
    assertThat(BodyContext.of(version).bytes()).isEqualTo(context().bytes());
  }

  @Test
  void theWrapAadOfABodyKeyIsPinned() {
    Kms kms = kms().as(APPLICATION);
    byte[] envelope = cipher(kms, clockAt(MORNING)).seal(context(), BODY);
    Header header = Envelope.header(envelope);
    WrappedKey wrapped = new WrappedKey(header.kekId(), header.wrappedKey());
    byte[] right = "regpack/dek/v1|BODY|kek-2026|body".getBytes(UTF_8);

    byte[] dataKey = kms.unwrap(kek(2026), wrapped, right);

    assertThat(Envelope.open(envelope, dataKey, context().bytes())).isEqualTo(BODY);
    assertThatThrownBy(
            () ->
                kms.unwrap(
                    kek(2026), wrapped, "regpack/dek/v1|OBJECT|kek-2026|body".getBytes(UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                kms.unwrap(kek(2026), wrapped, "regpack/dek/v1|BODY|kek-2027|body".getBytes(UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                kms.unwrap(
                    kek(2026), wrapped, "regpack/dek/v1|BODY|kek-2026|other".getBytes(UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bodyContextToStringHidesItsParts() {
    String shown = context().toString();

    assertThat(shown)
        .doesNotContain("mifir")
        .doesNotContain("FIRM1")
        .doesNotContain("EXEC9")
        .doesNotContain("MARKET")
        .doesNotContain(Sha256.ofUtf8("x").hex().substring(0, 8));
  }

  @Test
  void aSealIntoAYearWithoutAKekFailsClosedAndNeverReusesTheOldKey() {
    InMemoryKms raw = InMemoryKms.create().createKek(kek(2026), Set.of(APPLICATION));
    RecordingKms kms = new RecordingKms(raw.as(APPLICATION));
    ManualClock clock = clockAt("2026-12-31T12:00:00Z");
    BodyCipher cipher = cipher(kms, clock);
    cipher.seal(context(), BODY);
    clock.set(Instant.parse("2027-01-01T00:00:00Z"));

    assertKeyUnavailable(catchThrowable(() -> cipher.seal(context(), BODY)));

    raw.createKek(kek(2027), Set.of(APPLICATION));
    byte[] next = cipher.seal(context(), BODY);
    assertThat(Envelope.header(next).kekId()).isEqualTo("kek-2027");
    // the 2026 wrap, the refused 2027 wrap, and the 2027 wrap that worked: a new key each time
    assertThat(kms.wraps).extracting(Wrap::kek).containsExactly(kek(2026), kek(2027), kek(2027));
    assertThat(kms.wraps.get(1).dataKey()).isNotEqualTo(kms.wraps.get(2).dataKey());
    assertThat(cipher.open(context(), next)).isEqualTo(BODY);
  }

  @Test
  void aPlainOrForeignInputIsRefusedWithoutAKmsCall() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    BodyCipher cipher = cipher(kms, clockAt(MORNING));
    byte[] objectEnvelope =
        Envelope.seal(
            new Header(Kind.OBJECT, "kek-2026", range(0, 60)),
            range(0, 32),
            range(0xa0, 12),
            context().bytes(),
            BODY);

    assertThatThrownBy(() -> cipher.open(context(), BODY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an envelope");
    assertThatThrownBy(() -> cipher.open(context(), objectEnvelope))
        .isInstanceOf(AuthenticationException.class);
    assertThat(kms.unwraps.get()).isZero();
  }

  @Test
  void aRotatedKeyAndAnUnwrappedKeyAreZeroed() {
    RecordingKms kms = new RecordingKms(kms().as(APPLICATION));
    ManualClock clock = clockAt("2026-06-15T23:59:59.999999Z");
    BodyCipher cipher = cipher(kms, clock);

    byte[] first = cipher.seal(context(), BODY);
    assertThat(kms.wraps.get(0).live())
        .as("the generation key is held while its day lasts")
        .isEqualTo(kms.wraps.get(0).dataKey());
    clock.advance(Duration.ofNanos(1000));
    byte[] second = cipher.seal(context(), BODY);

    assertThat(kms.wraps.get(0).live()).as("zeroed when its day ended").isEqualTo(new byte[32]);
    assertThat(kms.wraps.get(1).live()).isEqualTo(kms.wraps.get(1).dataKey());

    assertThat(cipher.open(context(), first)).isEqualTo(BODY);
    assertThat(kms.unwrapped.get(0)).isEqualTo(new byte[32]);
    byte[] flipped = second.clone();
    flipped[flipped.length - 1] ^= 0x01;
    assertThatThrownBy(() -> cipher.open(context(), flipped))
        .isInstanceOf(AuthenticationException.class);
    assertThat(kms.unwrapped.get(1)).isEqualTo(new byte[32]);
  }

  @Test
  void aKmsFailureIsKeyUnavailableWithAFixedMessageAndNoCause() {
    for (ThrowingCallable failure : kmsFailures()) {
      assertKeyUnavailable(catchThrowable(failure));
    }
  }

  @Test
  void noMessageHoldsAKeyAKekIdOrASubject() {
    InMemoryKms raw = kms();
    BodyCipher cipher = cipher(raw.as(APPLICATION), clockAt(MORNING));
    byte[] envelope = cipher.seal(context(), BODY);
    byte[] flipped = envelope.clone();
    flipped[flipped.length - 1] ^= 0x01;
    byte[] tamperedKey = envelope.clone();
    tamperedKey[7 + "kek-2026".length() + 2 + 20] ^= 0x01;
    List<ThrowingCallable> failures = new ArrayList<>(kmsFailures());
    failures.add(() -> cipher.open(context("EXEC8", Leg.MARKET, "x"), envelope));
    failures.add(() -> cipher.open(context(), flipped));
    failures.add(() -> cipher.open(context(), tamperedKey));
    failures.add(() -> cipher.open(context(), "plain".getBytes(UTF_8)));
    InMemoryKms doomedKms = kms();
    BodyCipher doomedCipher = cipher(doomedKms.as(APPLICATION), clockAt(MORNING));
    byte[] doomed = doomedCipher.seal(context(), BODY);
    doomedKms.destroy(kek(2026));
    failures.add(() -> doomedCipher.open(context(), doomed));

    List<String> needles =
        List.of(
            HEX.formatHex(stream(32)),
            HEX.formatHex(stream(32)).toUpperCase(Locale.ROOT),
            "kek-",
            "mifir",
            "emir",
            "FIRM1",
            "EXEC9",
            "EXEC8",
            Sha256.ofUtf8("x").hex());
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
  private static List<ThrowingCallable> kmsFailures() {
    InMemoryKms raw = kms();
    Kms application = raw.as(APPLICATION);
    Kms denied = raw.as(STRANGER);
    IllegalStateException backend =
        new IllegalStateException("backend failure for kek-2026 FIRM1 EXEC9 and a key");
    Kms broken = new FailingKms(backend);
    byte[] sealed = cipher(application, clockAt(MORNING)).seal(context(), BODY);
    return List.of(
        () -> cipher(denied, clockAt(MORNING)).seal(context(), BODY),
        () -> cipher(application, clockAt("2030-01-01T00:00:00Z")).seal(context(), BODY),
        () -> cipher(broken, clockAt(MORNING)).seal(context(), BODY),
        () ->
            new BodyCipher(
                    application,
                    at -> {
                      throw backend;
                    },
                    entropy(),
                    clockAt(MORNING))
                .seal(context(), BODY),
        () -> cipher(denied, clockAt(MORNING)).open(context(), sealed),
        () -> cipher(broken, clockAt(MORNING)).open(context(), sealed));
  }
}
