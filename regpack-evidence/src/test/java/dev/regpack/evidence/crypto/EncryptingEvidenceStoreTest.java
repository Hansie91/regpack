package dev.regpack.evidence.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.KekSelector;
import dev.regpack.ports.crypto.ObjectCipher;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceRetentionException;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsAccessDeniedException;
import dev.regpack.ports.security.KmsKeyDestroyedException;
import dev.regpack.ports.security.KmsSignature;
import dev.regpack.ports.security.WrappedKey;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T6 (D-K3): the decorator that encrypts the evidence objects of the encrypted classes. The
 * shared contract suites run through it in the {@code Encrypted...ContractTest} bindings; the tests
 * here are about what only the decorator does: the envelope under the key, the comparison on a
 * repeated put, the mapping of a key service failure (an outage), of a destroyed KEK (shredded
 * data) and of bytes that are no valid envelope (a corruption alarm, never a downgrade to
 * plaintext).
 */
class EncryptingEvidenceStoreTest {
  private static final EvidenceKey K = EvidenceKey.of("landing", "k1");
  private static final Instant START = Instant.parse("2026-03-01T00:00:00Z");
  private static final String KEY_SERVICE = "the key service could not serve this request";

  private final ManualClock clock = new ManualClock(START);
  private final EncryptedFixtures fixtures = EncryptedFixtures.create();
  private final InMemoryEvidenceStore raw = new InMemoryEvidenceStore(clock);
  private final EncryptingEvidenceStore store = fixtures.encrypt(raw, clock);

  // -------------------------------------------------------------------------------------------
  // fixtures

  private static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  private Retention retention() {
    return new Retention(new RetentionClass("landing"), clock.now().plus(Duration.ofDays(1826)));
  }

  private PutRequest request(EvidenceKey key, String text) {
    return new PutRequest(key, utf8(text), "text/plain", retention());
  }

  private byte[] storedBytes(EvidenceKey key) {
    return raw.get(key).orElseThrow().bytes();
  }

  /** The inner store's put of bytes as they are, as a writer that bypasses the decorator would. */
  private void putRaw(EvidenceKey key, byte[] bytes) {
    raw.put(new PutRequest(key, bytes, "text/plain", retention()));
  }

  /** A store that delegates everything; a test overrides the call it wants to watch or break. */
  private static class DelegatingStore implements EvidenceStore {
    final EvidenceStore delegate;

    DelegatingStore(EvidenceStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Grade grade() {
      return delegate.grade();
    }

    @Override
    public Durability durability() {
      return delegate.durability();
    }

    @Override
    public PutOutcome put(PutRequest request) {
      return delegate.put(request);
    }

    @Override
    public Optional<StoredObject> get(EvidenceKey key) {
      return delegate.get(key);
    }

    @Override
    public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
      return delegate.list(prefix, after, limit);
    }

    @Override
    public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
      delegate.extendRetention(key, newRetainUntil);
    }

    @Override
    public void setLegalHold(EvidenceKey key, boolean hold) {
      delegate.setLegalHold(key, hold);
    }
  }

  /** Records the calls that reach the inner store. */
  private static class SpyStore extends DelegatingStore {
    final List<PutRequest> puts = new CopyOnWriteArrayList<>();
    final List<String> calls = new CopyOnWriteArrayList<>();

    SpyStore(EvidenceStore delegate) {
      super(delegate);
    }

    @Override
    public Grade grade() {
      calls.add("grade");
      return super.grade();
    }

    @Override
    public Durability durability() {
      calls.add("durability");
      return super.durability();
    }

    @Override
    public PutOutcome put(PutRequest request) {
      puts.add(request);
      calls.add("put");
      return super.put(request);
    }

    @Override
    public Optional<StoredObject> get(EvidenceKey key) {
      calls.add("get");
      return super.get(key);
    }

    @Override
    public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
      calls.add("list:" + prefix + ":" + after.map(EvidenceKey::path).orElse("-") + ":" + limit);
      return super.list(prefix, after, limit);
    }

    @Override
    public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
      calls.add("extendRetention:" + key.path() + ":" + newRetainUntil);
      super.extendRetention(key, newRetainUntil);
    }

    @Override
    public void setLegalHold(EvidenceKey key, boolean hold) {
      calls.add("setLegalHold:" + key.path() + ":" + hold);
      super.setLegalHold(key, hold);
    }
  }

  /** A KMS that can be told to fail, and counts its calls. */
  private static final class ScriptedKms implements Kms {
    private final Kms delegate;
    volatile RuntimeException wrapFailure;
    volatile RuntimeException unwrapFailure;
    final AtomicInteger wraps = new AtomicInteger();
    final AtomicInteger unwraps = new AtomicInteger();

    ScriptedKms(Kms delegate) {
      this.delegate = delegate;
    }

    @Override
    public KmsSignature sign(KeyRef key, byte[] message) {
      return delegate.sign(key, message);
    }

    @Override
    public boolean verify(KeyRef key, byte[] message, KmsSignature signature) {
      return delegate.verify(key, message, signature);
    }

    @Override
    public byte[] hmac(KeyRef key, byte[] message) {
      return delegate.hmac(key, message);
    }

    @Override
    public WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad) {
      wraps.incrementAndGet();
      if (wrapFailure != null) {
        throw wrapFailure;
      }
      return delegate.wrap(kek, dataKey, aad);
    }

    @Override
    public byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad) {
      unwraps.incrementAndGet();
      if (unwrapFailure != null) {
        throw unwrapFailure;
      }
      return delegate.unwrap(kek, wrapped, aad);
    }
  }

  /** The decorator over the raw store with a KMS that can fail, one KEK. */
  private EncryptingEvidenceStore overScriptedKms(ScriptedKms kms) {
    ObjectCipher cipher =
        fixtures.cipher(
            kms, KekSelector.single(EncryptedFixtures.kek(EncryptedFixtures.SINGLE_KEK)), clock);
    return new EncryptingEvidenceStore(raw, cipher, EncryptedFixtures.everyKey());
  }

  private static void assertOutage(Throwable thrown) {
    assertThat(thrown)
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage(KEY_SERVICE)
        .hasNoCause();
    assertThat(thrown.getSuppressed()).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // the envelope under the key

  @Test
  void anEncryptedKeyIsStoredAsAnEnvelopeAndReadBackAsPlaintext() {
    Retention retention = retention();
    PutRequest request = new PutRequest(K, utf8("hello"), "text/plain", retention);

    assertThat(store.put(request)).isEqualTo(PutOutcome.CREATED);

    StoredObject inner = raw.get(K).orElseThrow();
    assertThat(Envelope.isEnvelope(inner.bytes())).isTrue();
    assertThat(inner.bytes()).isNotEqualTo(utf8("hello"));
    Envelope.Header header = Envelope.header(inner.bytes());
    assertThat(header.kind()).isEqualTo(Envelope.Kind.OBJECT);
    assertThat(header.kekId()).isEqualTo(EncryptedFixtures.SINGLE_KEK);
    assertThat(inner.mediaType()).isEqualTo("text/plain");
    assertThat(inner.retention()).isEqualTo(retention);

    StoredObject got = store.get(K).orElseThrow();
    assertThat(got.key()).isEqualTo(K);
    assertThat(got.bytes()).isEqualTo(utf8("hello"));
    assertThat(got.sha256()).isEqualTo(Sha256.of(utf8("hello")));
    assertThat(inner.sha256())
        .as("the store's own hash is that of the envelope")
        .isNotEqualTo(got.sha256());
    assertThat(got.mediaType()).isEqualTo("text/plain");
    assertThat(got.writtenAt()).isEqualTo(inner.writtenAt());
    assertThat(got.retention()).isEqualTo(retention);
    assertThat(got.legalHold()).isFalse();
    // the key service and the KEK are all a reader needs: a second decorator reads it too
    assertThat(fixtures.encrypt(raw, clock).get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  @Test
  void theEnvelopeAddsAFixedOverheadWhateverThePlaintextSize() {
    EvidenceKey empty = EvidenceKey.of("landing", "empty");
    EvidenceKey large = EvidenceKey.of("landing", "large");
    byte[] largeBytes = new byte[100_000];
    Arrays.fill(largeBytes, (byte) 7);
    store.put(new PutRequest(empty, new byte[0], "text/plain", retention()));
    store.put(new PutRequest(large, largeBytes, "text/plain", retention()));

    int overheadOfEmpty = storedBytes(empty).length;
    int overheadOfLarge = storedBytes(large).length - largeBytes.length;

    assertThat(overheadOfLarge).isEqualTo(overheadOfEmpty);
    assertThat(overheadOfEmpty).as("header, wrapped key, nonce and tag").isBetween(60, 255);
    assertThat(store.get(empty).orElseThrow().bytes()).isEmpty();
    assertThat(store.get(large).orElseThrow().bytes()).isEqualTo(largeBytes);
  }

  @Test
  void theDefaultPolicyIsTheNamespacesSealedLandingAndEnrichment() {
    Predicate<EvidenceKey> policy = EncryptingEvidenceStore.DEFAULT_POLICY;

    for (String encrypted : List.of("sealed/ab/abc", "landing/k", "enrichment/x/y", "sealed")) {
      assertThat(policy.test(new EvidenceKey(encrypted))).as(encrypted).isTrue();
    }
    for (String plain :
        List.of(
            "journal/a",
            "control/epoch/e000000000001",
            "manifest/2026/01/01/1",
            "landingx/k",
            "Landing/k",
            "a/landing/k",
            "sealedx")) {
      assertThat(policy.test(new EvidenceKey(plain))).as(plain).isFalse();
    }
  }

  @Test
  void aKeyOutsideThePolicyPassesThroughUnencrypted() {
    EncryptingEvidenceStore byDefault =
        EncryptingEvidenceStore.withDefaultPolicy(raw, fixtures.singleKekCipher(clock));
    EvidenceKey journal = EvidenceKey.of("journal", "a");
    EvidenceKey marker = EvidenceKey.of("control", "epoch", "e000000000001");
    EvidenceKey sealed = EvidenceKey.of("sealed", "ab", "ab".repeat(32));
    EvidenceKey enrichment = EvidenceKey.of("enrichment", "e1");

    for (EvidenceKey key : List.of(journal, marker, sealed, K, enrichment)) {
      assertThat(byDefault.put(request(key, "value of " + key.path())))
          .isEqualTo(PutOutcome.CREATED);
    }

    assertThat(storedBytes(journal)).isEqualTo(utf8("value of journal/a"));
    assertThat(storedBytes(marker)).isEqualTo(utf8("value of " + marker.path()));
    for (EvidenceKey key : List.of(sealed, K, enrichment)) {
      assertThat(Envelope.isEnvelope(storedBytes(key))).as(key.path()).isTrue();
    }
    for (EvidenceKey key : List.of(journal, marker, sealed, K, enrichment)) {
      StoredObject got = byDefault.get(key).orElseThrow();
      assertThat(got.bytes()).isEqualTo(utf8("value of " + key.path()));
      assertThat(got.sha256()).isEqualTo(Sha256.of(got.bytes()));
    }
    // a plain object under a plain key is served as it is, and a repeated put is the store's
    assertThat(byDefault.put(request(journal, "value of journal/a")))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThatThrownBy(() -> byDefault.put(request(journal, "another")))
        .isInstanceOf(EvidenceIntegrityException.class);
    // a custom policy decides alone
    EncryptingEvidenceStore onlyOwn =
        new EncryptingEvidenceStore(
            raw, fixtures.singleKekCipher(clock), key -> key.path().startsWith("own/"));
    onlyOwn.put(request(EvidenceKey.of("own", "x"), "secret"));
    onlyOwn.put(request(EvidenceKey.of("landing", "plain"), "visible"));
    assertThat(Envelope.isEnvelope(storedBytes(EvidenceKey.of("own", "x")))).isTrue();
    assertThat(storedBytes(EvidenceKey.of("landing", "plain"))).isEqualTo(utf8("visible"));
  }

  @Test
  void theConstructorsRefuseNulls() {
    ObjectCipher cipher = fixtures.singleKekCipher(clock);
    assertThatThrownBy(() -> new EncryptingEvidenceStore(null, cipher, key -> true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EncryptingEvidenceStore(raw, null, key -> true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EncryptingEvidenceStore(raw, cipher, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> EncryptingEvidenceStore.withDefaultPolicy(null, cipher))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> EncryptingEvidenceStore.withDefaultPolicy(raw, null))
        .isInstanceOf(NullPointerException.class);
  }

  // -------------------------------------------------------------------------------------------
  // no downgrade: what is not an envelope of this key is a corruption alarm (KF2, KF3)

  @Test
  void plaintextUnderAnEncryptedKeyIsACorruptionAlarmNotAFallback() {
    putRaw(K, utf8("plain"));

    assertThatThrownBy(() -> store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    // the plaintext equals the stored bytes: an implementation that fell back would say "present"
    assertThatThrownBy(() -> store.put(request(K, "plain")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.put(request(K, "something else")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(storedBytes(K)).as("nothing was written").isEqualTo(utf8("plain"));

    // bytes that start like an envelope, and nothing at all, are no envelope either
    EvidenceKey magic = EvidenceKey.of("landing", "magic");
    EvidenceKey nothing = EvidenceKey.of("landing", "nothing");
    byte[] magicThenGarbage = {'R', 'P', 'E', 'N', 1, 1, 'g', 'a', 'r', 'b', 'a', 'g', 'e'};
    putRaw(magic, magicThenGarbage);
    putRaw(nothing, new byte[0]);
    assertThatThrownBy(() -> store.get(magic)).isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.get(nothing)).isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.put(request(nothing, "")))
        .isInstanceOf(EvidenceCorruptionException.class);
  }

  @Test
  void anEnvelopeCopiedToAnotherKeyIsACorruptionAlarm() {
    store.put(request(K, "hello"));
    EvidenceKey other = EvidenceKey.of("landing", "k2");
    putRaw(other, storedBytes(K));

    assertThatThrownBy(() -> store.get(other)).isInstanceOf(EvidenceCorruptionException.class);
    // the copy cannot be shown to hold the plaintext of the put: nothing is acknowledged
    assertThatThrownBy(() -> store.put(request(other, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(store.get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  @Test
  void anEnvelopeOfTheWrongKindIsACorruptionAlarmWithoutAKmsCall() {
    ScriptedKms kms = new ScriptedKms(fixtures.kms());
    EncryptingEvidenceStore counted = overScriptedKms(kms);
    byte[] wrongKind =
        Envelope.seal(
            new Envelope.Header(Envelope.Kind.BODY, EncryptedFixtures.SINGLE_KEK, new byte[60]),
            new byte[32],
            new byte[12],
            utf8(K.path()),
            utf8("hello"));
    putRaw(K, wrongKind);

    assertThatThrownBy(() -> counted.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> counted.put(request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(kms.unwraps.get())
        .as("the kind is checked before the key service is asked")
        .isZero();
  }

  @Test
  void everySingleByteChangeOfAStoredEnvelopeIsACorruptionAlarmAndNeverPlaintext() {
    store.put(request(K, "hello"));
    byte[] envelope = storedBytes(K);

    for (int position = 0; position < envelope.length; position++) {
      int at = position;
      EvidenceStore flipping =
          new DelegatingStore(raw) {
            @Override
            public Optional<StoredObject> get(EvidenceKey key) {
              return super.get(key)
                  .map(
                      s -> {
                        byte[] changed = s.bytes();
                        changed[at] ^= 0x01;
                        return new StoredObject(
                            s.key(),
                            changed,
                            s.mediaType(),
                            s.sha256(),
                            s.writtenAt(),
                            s.retention(),
                            s.legalHold());
                      });
            }
          };
      EncryptingEvidenceStore tampered = fixtures.encrypt(flipping, clock);

      Throwable thrown = catchThrowable(() -> tampered.get(K));

      assertThat(thrown).as("byte %d", at).isInstanceOf(EvidenceCorruptionException.class);
      Throwable put = catchThrowable(() -> tampered.put(request(K, "hello")));
      assertThat(put).as("put, byte %d", at).isInstanceOf(EvidenceCorruptionException.class);
    }
  }

  @Test
  void anUnknownKekOnGetReadsAsCorruptionAsTheKmsPortCannotTellItFromATamperedKey() {
    store.put(request(K, "hello"));
    // a restore into a key service without the KEKs: the port says "unknown", as for a bad key
    Kms withoutTheKeys = InMemoryKms.create().as(EncryptedFixtures.APPLICATION);
    EncryptingEvidenceStore restored =
        new EncryptingEvidenceStore(
            raw,
            fixtures.cipher(
                withoutTheKeys,
                KekSelector.single(EncryptedFixtures.kek(EncryptedFixtures.SINGLE_KEK)),
                clock),
            EncryptedFixtures.everyKey());

    assertThatThrownBy(() -> restored.get(K))
        .isInstanceOf(EvidenceCorruptionException.class)
        .isNotInstanceOf(EvidenceUnavailableException.class);
    assertThat(raw.get(K)).as("nothing changed").isPresent();
  }

  /**
   * A decorator over a fresh inner store that holds {@code stored} with the KEK id of its header (8
   * characters, from byte 7; byte 6 is the length) replaced by {@code kekId}, as a writer of the
   * file could do. The authentication tag is not recomputed.
   */
  private EncryptingEvidenceStore overHeaderRewrittenTo(byte[] stored, String kekId) {
    byte[] id = utf8(kekId);
    assertThat((int) stored[6]).as("length of the KEK id in the header").isEqualTo(id.length);
    byte[] rewritten = stored.clone();
    System.arraycopy(id, 0, rewritten, 7, id.length);
    InMemoryEvidenceStore copy = new InMemoryEvidenceStore(clock);
    copy.put(new PutRequest(K, rewritten, "text/plain", retention()));
    return fixtures.encrypt(copy, clock);
  }

  /**
   * Pins the consequence of D-K2 for a header chosen by whoever can write the file: the KMS port
   * answers "this identity may not use that key" and "the service is down" alike, so a header
   * rewritten to name a key the service holds but this identity may not use as a KEK reads as an
   * outage, while one that names no key at all reads as a corruption alarm. It is not a statement
   * that this is the wanted classification; the tamper evidence of the headers is P3c2.
   */
  @Test
  void aHeaderThatNamesAKeyThisIdentityCannotUseReadsAsAnOutageAndOneThatNamesNoKeyAsCorruption() {
    // a key of another purpose that the identity may use, and a KEK that no identity may use
    fixtures
        .admin()
        .createHmacKey(
            new KeyRef("trn-key1", KeyPurpose.TRN_HMAC),
            new byte[32],
            Set.of(EncryptedFixtures.APPLICATION));
    fixtures.admin().createKek(EncryptedFixtures.kek("kek-oth1"), Set.of());
    store.put(request(K, "hello"));
    byte[] stored = storedBytes(K);

    // the control: the header names the KEK that wrapped the key, so the rewrite changes nothing
    EncryptingEvidenceStore same = overHeaderRewrittenTo(stored, EncryptedFixtures.SINGLE_KEK);
    assertThat(same.get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(same.put(request(K, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);

    for (String named : List.of("trn-key1", "kek-oth1")) {
      EncryptingEvidenceStore rewritten = overHeaderRewrittenTo(stored, named);
      Throwable get = catchThrowable(() -> rewritten.get(K));
      Throwable put = catchThrowable(() -> rewritten.put(request(K, "hello")));

      assertOutage(get);
      assertOutage(put);
      assertThat(List.of(get.getMessage(), put.getMessage()))
          .as("the message of the outage for %s", named)
          .allSatisfy(
              message ->
                  assertThat(message)
                      .doesNotContain(named)
                      .doesNotContain(EncryptedFixtures.SINGLE_KEK));
    }

    EncryptingEvidenceStore nowhere = overHeaderRewrittenTo(stored, "kek-nope");
    assertThatThrownBy(() -> nowhere.get(K))
        .isInstanceOf(EvidenceCorruptionException.class)
        .isNotInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> nowhere.put(request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class)
        .isNotInstanceOf(EvidenceUnavailableException.class);
  }

  // -------------------------------------------------------------------------------------------
  // a repeated put, racing writers, other bytes (KF9, KF10)

  @Test
  void aPutOfAnExistingKeyPutsTheStoredEnvelopeAgain() {
    SpyStore spy = new SpyStore(raw);
    EncryptingEvidenceStore spied = fixtures.encrypt(spy, clock);
    Retention first = retention();
    spied.put(new PutRequest(K, utf8("hello"), "text/plain", first));
    byte[] stored = storedBytes(K);
    spy.puts.clear();
    clock.advance(Duration.ofHours(1));
    Retention later =
        new Retention(new RetentionClass("landing"), first.retainUntil().plus(Duration.ofDays(30)));

    PutOutcome outcome =
        spied.put(new PutRequest(K, utf8("hello"), "application/octet-stream", later));

    assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(spy.puts).as("one put of the stored envelope, no new envelope").hasSize(1);
    assertThat(spy.puts.get(0).key()).isEqualTo(K);
    assertThat(spy.puts.get(0).bytes()).isEqualTo(stored);
    assertThat(spy.puts.get(0).mediaType())
        .as("the request's media type, which the inner store validates and then ignores")
        .isEqualTo("application/octet-stream");
    assertThat(spy.puts.get(0).retention())
        .as("the request's retention, which the inner store validates and then ignores")
        .isEqualTo(later);
    StoredObject got = store.get(K).orElseThrow();
    assertThat(got.mediaType()).isEqualTo("text/plain");
    assertThat(got.retention()).isEqualTo(first);
    assertThat(storedBytes(K)).isEqualTo(stored);
  }

  /** The six instants of {@code EvidenceStoreContract} that no evidence store can hold. */
  private static List<Instant> unstorableRetainUntils() {
    return List.of(
        Instant.parse("2030-01-01T00:00:00.000000001Z"),
        Instant.parse("2030-01-01T00:00:00.123456789Z"),
        Instant.parse("+10000-01-01T00:00:00Z"),
        Instant.parse("-0001-12-31T23:59:59Z"),
        Instant.MAX,
        Instant.MIN);
  }

  @Test
  void aRepeatedPutWithAnUnstorableRetainUntilIsRefusedLikeTheRawStore() {
    store.put(request(K, "hello"));
    byte[] before = storedBytes(K);
    Retention firstRetention = raw.get(K).orElseThrow().retention();

    for (Instant instant : unstorableRetainUntils()) {
      PutRequest bad =
          new PutRequest(
              K,
              utf8("hello"),
              "text/plain",
              new Retention(new RetentionClass("landing"), instant));
      assertThatThrownBy(() -> store.put(bad))
          .as("retain-until %s through the decorator", instant)
          .isInstanceOf(IllegalArgumentException.class);
      // the reference: the raw store refuses the same request for the same reason
      assertThatThrownBy(() -> raw.put(new PutRequest(K, before, "text/plain", bad.retention())))
          .as("retain-until %s through the raw store", instant)
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(storedBytes(K)).as("the stored envelope is unchanged").isEqualTo(before);
    assertThat(raw.get(K).orElseThrow().retention())
        .as("the first put's retention stays")
        .isEqualTo(firstRetention);
  }

  @Test
  void aPutOfOtherBytesUnderAnExistingKeyIsAnIntegrityAlarm() {
    store.put(request(K, "hello"));
    byte[] stored = storedBytes(K);

    for (String other : List.of("other", "hellp", "hell", "hello!", "")) {
      Throwable thrown = catchThrowable(() -> store.put(request(K, other)));
      assertThat(thrown)
          .as("plaintext '%s'", other)
          .isInstanceOf(EvidenceIntegrityException.class)
          .isNotInstanceOf(EvidenceCorruptionException.class);
    }

    assertThat(store.get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(storedBytes(K)).as("the stored envelope is unchanged").isEqualTo(stored);
  }

  @Test
  void racingPutsOfOnePlaintextAllSucceedWithOneCreated() throws Exception {
    int threads = 8;
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<PutOutcome>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<PutOutcome> task =
            () -> {
              assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
              return store.put(request(K, "same plaintext"));
            };
        futures.add(pool.submit(task));
      }
      release.countDown();
      int created = 0;
      int identical = 0;
      for (Future<PutOutcome> future : futures) {
        PutOutcome outcome = future.get(30, TimeUnit.SECONDS);
        if (outcome == PutOutcome.CREATED) {
          created++;
        } else {
          assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
          identical++;
        }
      }
      assertThat(created).isEqualTo(1);
      assertThat(identical).isEqualTo(threads - 1);
      assertThat(store.get(K).orElseThrow().bytes()).isEqualTo(utf8("same plaintext"));
      assertThat(raw.list("landing/", Optional.empty(), 10)).containsExactly(K);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void racingPutsOfOtherPlaintextsHaveOneWinnerAndIntegrityAlarmsForTheRest() throws Exception {
    int threads = 8;
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<PutOutcome>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        String text = "writer-" + i;
        Callable<PutOutcome> task =
            () -> {
              assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
              return store.put(request(K, text));
            };
        futures.add(pool.submit(task));
      }
      release.countDown();
      int created = 0;
      int alarms = 0;
      String winner = "";
      for (int i = 0; i < threads; i++) {
        try {
          if (futures.get(i).get(30, TimeUnit.SECONDS) == PutOutcome.CREATED) {
            created++;
            winner = "writer-" + i;
          }
        } catch (ExecutionException e) {
          assertThat(e.getCause())
              .isInstanceOf(EvidenceIntegrityException.class)
              .isNotInstanceOf(EvidenceCorruptionException.class);
          alarms++;
        }
      }
      assertThat(created).isEqualTo(1);
      assertThat(alarms).isEqualTo(threads - 1);
      assertThat(store.get(K).orElseThrow().bytes()).isEqualTo(utf8(winner));
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * A store that, before it takes the first put, lets a competitor write the key with the given
   * plaintext (through a second decorator over the same raw store): the state in which the read of
   * a put saw nothing and the put of its own envelope meets another envelope.
   */
  private EvidenceStore loserOfTheRace(String competitorPlaintext) {
    EncryptingEvidenceStore competitor = fixtures.encrypt(raw, clock);
    AtomicInteger puts = new AtomicInteger();
    return new DelegatingStore(raw) {
      @Override
      public PutOutcome put(PutRequest request) {
        if (puts.getAndIncrement() == 0) {
          competitor.put(request(K, competitorPlaintext));
        }
        return super.put(request);
      }
    };
  }

  @Test
  void aPutThatLosesARaceReadsAgainAndAcknowledgesTheSamePlaintext() {
    SpyStore spy = new SpyStore(loserOfTheRace("hello"));
    EncryptingEvidenceStore loser = fixtures.encrypt(spy, clock);

    PutOutcome outcome = loser.put(request(K, "hello"));

    assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(spy.puts).as("its own envelope, then the stored one").hasSize(2);
    assertThat(spy.puts.get(1).bytes()).isEqualTo(storedBytes(K));
    assertThat(spy.puts.get(0).bytes()).isNotEqualTo(storedBytes(K));
    assertThat(store.get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  @Test
  void aPutThatLosesARaceToOtherPlaintextIsAnIntegrityAlarm() {
    EncryptingEvidenceStore loser = fixtures.encrypt(loserOfTheRace("the winner"), clock);

    assertThat(catchThrowable(() -> loser.put(request(K, "the loser"))))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);

    assertThat(store.get(K).orElseThrow().bytes()).isEqualTo(utf8("the winner"));
  }

  @Test
  void anIntegrityAlarmOfTheInnerStoreForAKeyItDoesNotHoldIsRethrownAsItIs() {
    EvidenceIntegrityException alarm = new EvidenceIntegrityException("scripted");
    EvidenceStore refusing =
        new DelegatingStore(raw) {
          @Override
          public PutOutcome put(PutRequest request) {
            throw alarm;
          }
        };

    assertThat(catchThrowable(() -> fixtures.encrypt(refusing, clock).put(request(K, "hello"))))
        .isSameAs(alarm);
  }

  @Test
  void aCorruptionAlarmOfTheInnerStoreIsRethrown() {
    EvidenceCorruptionException onGet = new EvidenceCorruptionException("damaged on get");
    EvidenceCorruptionException onPut = new EvidenceCorruptionException("damaged on put");
    SpyStore spy =
        new SpyStore(raw) {
          @Override
          public Optional<StoredObject> get(EvidenceKey key) {
            if (key.path().equals("landing/damaged-get")) {
              throw onGet;
            }
            return super.get(key);
          }

          @Override
          public PutOutcome put(PutRequest request) {
            if (request.key().path().equals("landing/damaged-put")) {
              calls.add("put");
              throw onPut;
            }
            return super.put(request);
          }
        };
    EncryptingEvidenceStore spied = fixtures.encrypt(spy, clock);

    // a read that meets the alarm: get and put both rethrow it, and nothing is written
    EvidenceKey damagedGet = EvidenceKey.of("landing", "damaged-get");
    assertThat(catchThrowable(() -> spied.get(damagedGet))).isSameAs(onGet);
    assertThat(catchThrowable(() -> spied.put(request(damagedGet, "hello")))).isSameAs(onGet);
    assertThat(spy.puts).isEmpty();
    // a put that meets it: not swallowed, and not followed by a second read that could hide it
    EvidenceKey damagedPut = EvidenceKey.of("landing", "damaged-put");
    spy.calls.clear();
    assertThat(catchThrowable(() -> spied.put(request(damagedPut, "hello")))).isSameAs(onPut);
    assertThat(spy.calls).containsExactly("get", "put");
    assertThat(raw.list("", Optional.empty(), 10)).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // destroyed keys (crypto-shredding), the clock and the key service (KF5, KF6)

  @Test
  void aShreddedObjectIsDataShreddedOnGetAndPutAndStaysListed() {
    store.put(request(K, "hello"));
    byte[] stored = storedBytes(K);
    fixtures.destroy(EncryptedFixtures.SINGLE_KEK);

    Throwable onGet = catchThrowable(() -> store.get(K));
    Throwable onPut = catchThrowable(() -> store.put(request(K, "hello")));
    Throwable onOtherPut = catchThrowable(() -> store.put(request(K, "something else")));

    for (Throwable thrown : List.of(onGet, onPut, onOtherPut)) {
      assertThat(thrown)
          .isInstanceOf(DataShreddedException.class)
          .isNotInstanceOf(EvidenceIntegrityException.class)
          .isNotInstanceOf(EvidenceUnavailableException.class);
    }
    assertThat(store.list("landing/", Optional.empty(), 10)).containsExactly(K);
    assertThat(storedBytes(K)).as("nothing was written or removed").isEqualTo(stored);
    // the retention record stays plain: it can still be extended and the hold set
    Instant later = raw.get(K).orElseThrow().retention().retainUntil().plus(Duration.ofDays(30));
    store.extendRetention(K, later);
    store.setLegalHold(K, true);
    StoredObject viaRaw = raw.get(K).orElseThrow();
    assertThat(viaRaw.retention().retainUntil()).isEqualTo(later);
    assertThat(viaRaw.legalHold()).isTrue();
    // a new object cannot be sealed into the destroyed KEK: that is an outage, not shredded data
    Throwable onNew =
        catchThrowable(() -> store.put(request(EvidenceKey.of("landing", "k2"), "x")));
    assertOutage(onNew);
    assertThat(raw.get(EvidenceKey.of("landing", "k2"))).isEmpty();
  }

  /**
   * Pins a consequence, not a wish. The sealed key is content-addressed and the store port has no
   * delete, so the same bytes sealed again after the KEK of the first copy was destroyed meet the
   * husk of that copy: the decorator cannot open it to compare, and the seal ends in a {@link
   * DataShreddedException}, not in a {@link SealStatus}. The delivery stays queued and an attempt
   * id is spent; that artifact hash stays blocked until a job removes the object. Hand-off to P4
   * and P8: the destruction job must remove the objects of a destroyed cohort once their retention
   * and any lock have ended, or the sender must be told that a byte-identical resend of an expired
   * report is impossible.
   */
  @Test
  void
      aResealOfByteIdenticalBytesAfterTheirCohortWasDestroyedIsADataShreddedExceptionAndNotAStatus() {
    ManualClock sealClock = new ManualClock(Instant.parse("2026-06-01T00:00:00Z"));
    InMemoryEvidenceStore thatStore = new InMemoryEvidenceStore(sealClock);
    EncryptingEvidenceStore evidence = fixtures.encryptPerYear(thatStore, sealClock);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(sealClock);
    DurableSeal sealing =
        new DurableSeal(
            ledger,
            evidence,
            sealClock,
            RetentionPolicy.fixedDays(1826),
            new SendGate(SendGate.Requirement.none()));
    EndpointKey endpoint = LedgerFixtures.endpoint("RESEAL");
    byte[] bytes = SealScenarioContract.reportOf("BK-1");
    LeaseToken firstToken = SealScenarioContract.lease(ledger, endpoint, "A");
    DeliveryId first = SealScenarioContract.queued(ledger, endpoint, "BK-1");
    assertThat(
            sealing
                .seal(SealScenarioContract.request(endpoint, firstToken, first, "BK-1", bytes))
                .status())
        .isEqualTo(SealStatus.SEALED);
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(bytes));
    assertThat(Envelope.header(thatStore.get(sealedKey).orElseThrow().bytes()).kekId())
        .isEqualTo("kek-2026");

    // the KEK of the cohort is destroyed; the lease of the first seal has long expired
    sealClock.set(Instant.parse("2028-02-01T00:00:00Z"));
    fixtures.destroy("kek-2026");
    LeaseToken secondToken = SealScenarioContract.lease(ledger, endpoint, "B");
    DeliveryId second = SealScenarioContract.queued(ledger, endpoint, "BK-2");
    SealRequest again = SealScenarioContract.request(endpoint, secondToken, second, "BK-2", bytes);

    assertThatThrownBy(() -> sealing.seal(again)).isInstanceOf(DataShreddedException.class);
    assertThat(ledger.delivery(second).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.attempt(new AttemptId(2))).as("the attempt id was spent").isEmpty();
    assertThat(ledger.manifestRows("", Optional.empty(), 100))
        .hasSize(2)
        .noneMatch(row -> row.attempt().equals(Optional.of(new AttemptId(2))));

    // other bytes seal as before, under the KEK of the clock's year
    byte[] other = SealScenarioContract.reportOf("BK-3");
    DeliveryId third = SealScenarioContract.queued(ledger, endpoint, "BK-3");
    SealOutcome fresh =
        sealing.seal(SealScenarioContract.request(endpoint, secondToken, third, "BK-3", other));
    assertThat(fresh.status()).isEqualTo(SealStatus.SEALED);
    assertThat(fresh.attempt()).contains(new AttemptId(3));
    assertThat(
            Envelope.header(thatStore.get(SealedKeys.of(Sha256.of(other))).orElseThrow().bytes())
                .kekId())
        .isEqualTo("kek-2028");
  }

  @Test
  void aShreddedKekDoesNotTouchTheObjectsOfAnotherKek() {
    EncryptingEvidenceStore perYear = fixtures.encryptPerYear(raw, clock);
    EvidenceKey of2026 = EvidenceKey.of("landing", "y2026");
    EvidenceKey of2027 = EvidenceKey.of("landing", "y2027");
    perYear.put(request(of2026, "written in 2026"));
    clock.set(Instant.parse("2027-02-03T04:05:06Z"));
    perYear.put(request(of2027, "written in 2027"));

    fixtures.destroy("kek-2026");

    assertThatThrownBy(() -> perYear.get(of2026)).isInstanceOf(DataShreddedException.class);
    assertThat(perYear.get(of2027).orElseThrow().bytes()).isEqualTo(utf8("written in 2027"));
    // and the destroyed year takes no new object even if the clock is set back into it
    clock.set(Instant.parse("2026-12-31T23:59:59.999999Z"));
    assertOutage(catchThrowable(() -> perYear.put(request(EvidenceKey.of("landing", "n"), "x"))));
  }

  @Test
  void retentionHoldGradeAndDurabilityPassThrough() {
    SpyStore spy = new SpyStore(raw);
    EncryptingEvidenceStore spied = fixtures.encrypt(spy, clock);
    spied.put(request(K, "hello"));
    Instant later = raw.get(K).orElseThrow().retention().retainUntil().plus(Duration.ofDays(1));
    spy.calls.clear();

    assertThat(spied.grade()).isEqualTo(raw.grade());
    assertThat(spied.durability()).isEqualTo(raw.durability());
    spied.extendRetention(K, later);
    spied.setLegalHold(K, true);
    List<EvidenceKey> listed = spied.list("landing/", Optional.of(EvidenceKey.of("a")), 7);

    assertThat(spy.calls)
        .containsExactly(
            "grade",
            "durability",
            "extendRetention:landing/k1:" + later,
            "setLegalHold:landing/k1:true",
            "list:landing/:a:7");
    assertThat(listed).isEqualTo(raw.list("landing/", Optional.of(EvidenceKey.of("a")), 7));
    StoredObject got = spied.get(K).orElseThrow();
    assertThat(got.retention().retainUntil()).isEqualTo(later);
    assertThat(got.legalHold()).isTrue();
    assertThat(got.bytes()).isEqualTo(utf8("hello"));
    // a refused shortening is the store's exception, unchanged
    assertThatThrownBy(() -> spied.extendRetention(K, later.minus(Duration.ofDays(2))))
        .isInstanceOf(EvidenceRetentionException.class);
  }

  @Test
  void aClockStepIntoTheNextYearUsesTheNextKek() {
    EncryptingEvidenceStore perYear = fixtures.encryptPerYear(raw, clock);
    EvidenceKey a = EvidenceKey.of("landing", "a");
    EvidenceKey b = EvidenceKey.of("landing", "b");
    EvidenceKey c = EvidenceKey.of("landing", "c");
    Map<EvidenceKey, String> plaintexts =
        Map.of(a, "last microsecond of 2026", b, "first microsecond of 2027", c, "mid 2028");

    clock.set(Instant.parse("2026-12-31T23:59:59.999999Z"));
    perYear.put(request(a, plaintexts.get(a)));
    clock.set(Instant.parse("2027-01-01T00:00:00Z"));
    perYear.put(request(b, plaintexts.get(b)));
    clock.set(Instant.parse("2028-07-01T00:00:00Z"));
    perYear.put(request(c, plaintexts.get(c)));

    assertThat(Envelope.header(storedBytes(a)).kekId()).isEqualTo("kek-2026");
    assertThat(Envelope.header(storedBytes(b)).kekId()).isEqualTo("kek-2027");
    assertThat(Envelope.header(storedBytes(c)).kekId()).isEqualTo("kek-2028");
    // reading uses the KEK of the header, not that of the clock
    for (EvidenceKey key : List.of(a, b, c)) {
      assertThat(perYear.get(key).orElseThrow().bytes()).isEqualTo(utf8(plaintexts.get(key)));
    }
  }

  @Test
  void aKmsFailureOnPutOrGetIsAnEvidenceOutageAndStoresNothing() {
    ScriptedKms kms = new ScriptedKms(fixtures.kms());
    EncryptingEvidenceStore scripted = overScriptedKms(kms);
    List<RuntimeException> failures =
        List.of(
            new IllegalStateException("secret-kms-host kek-test landing/k1"),
            new KmsAccessDeniedException("secret-denied kek-test"),
            new IllegalArgumentException("unknown key: kek-test"),
            new KmsKeyDestroyedException());

    // a put: whatever the key service says about wrap, nothing is stored
    for (RuntimeException failure : failures) {
      kms.wrapFailure = failure;
      Throwable thrown = catchThrowable(() -> scripted.put(request(K, "hello")));
      assertOutage(thrown);
      assertThat(raw.list("", Optional.empty(), 10)).as(failure.toString()).isEmpty();
    }
    // the same store works as soon as the key service does: nothing was left behind
    kms.wrapFailure = null;
    scripted.put(request(K, "hello"));
    byte[] stored = storedBytes(K);

    // a get and a put of an existing key: unwrap fails; neither a corruption nor shredded data
    for (RuntimeException failure : failures.subList(0, 2)) {
      kms.unwrapFailure = failure;
      Throwable onGet = catchThrowable(() -> scripted.get(K));
      Throwable onPut = catchThrowable(() -> scripted.put(request(K, "hello")));
      assertOutage(onGet);
      assertOutage(onPut);
    }
    kms.unwrapFailure = null;
    assertThat(storedBytes(K)).isEqualTo(stored);
    assertThat(scripted.get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));

    // a clock stepped into a year whose KEK does not exist: the same outage, nothing stored
    EncryptingEvidenceStore perYear = fixtures.encryptPerYear(raw, clock);
    clock.set(Instant.parse("2029-01-01T00:00:00Z"));
    EvidenceKey late = EvidenceKey.of("landing", "late");
    assertOutage(catchThrowable(() -> perYear.put(request(late, "hello"))));
    assertThat(raw.get(late)).isEmpty();
    assertThat(raw.list("", Optional.empty(), 10)).containsExactly(K);
  }

  @Test
  void theKeyServiceIsCalledOncePerOperation() {
    ScriptedKms kms = new ScriptedKms(fixtures.kms());
    EncryptingEvidenceStore counted = overScriptedKms(kms);

    assertThat(counted.put(request(K, "hello"))).isEqualTo(PutOutcome.CREATED);
    assertThat(kms.wraps.get()).as("one wrap per object").isEqualTo(1);
    assertThat(kms.unwraps.get()).as("a new object is not opened").isZero();
    assertThat(fixtures.drawnHex()).as("one data key and one nonce").hasSize(2);

    assertThat(counted.put(request(K, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(kms.wraps.get()).as("a repeated put wraps nothing").isEqualTo(1);
    assertThat(kms.unwraps.get()).as("one unwrap per repeated put").isEqualTo(1);
    assertThat(fixtures.drawnHex()).as("a repeated put draws nothing").hasSize(2);

    assertThat(counted.get(K)).isPresent();
    assertThat(kms.wraps.get()).as("a read wraps nothing").isEqualTo(1);
    assertThat(kms.unwraps.get()).as("one unwrap per read").isEqualTo(2);
  }

  @Test
  void thePlainClassesNeedNoKeyServiceAtAll() {
    ScriptedKms kms = new ScriptedKms(fixtures.kms());
    ObjectCipher cipher =
        fixtures.cipher(
            kms, KekSelector.single(EncryptedFixtures.kek(EncryptedFixtures.SINGLE_KEK)), clock);
    EncryptingEvidenceStore byDefault = EncryptingEvidenceStore.withDefaultPolicy(raw, cipher);
    EvidenceKey sealedKey = EvidenceKey.of("sealed", "ab", "ab".repeat(32));
    EvidenceKey journal = EvidenceKey.of("journal", "a");
    EvidenceKey marker = EvidenceKey.of("control", "epoch", "e000000000001");
    byDefault.put(request(sealedKey, "sealed value"));
    int wraps = kms.wraps.get();
    int unwraps = kms.unwraps.get();

    // assumption A5: the journal and the control namespace are read and written without a KEK
    kms.wrapFailure = new IllegalStateException("down");
    kms.unwrapFailure = new IllegalStateException("down");
    assertThat(byDefault.put(request(journal, "journal value"))).isEqualTo(PutOutcome.CREATED);
    assertThat(byDefault.put(request(marker, "marker value"))).isEqualTo(PutOutcome.CREATED);
    assertThat(byDefault.put(request(journal, "journal value")))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(byDefault.get(journal).orElseThrow().bytes()).isEqualTo(utf8("journal value"));
    assertThat(byDefault.get(marker).orElseThrow().bytes()).isEqualTo(utf8("marker value"));
    assertThat(byDefault.list("", Optional.empty(), 10)).hasSize(3);

    assertThat(kms.wraps.get()).as("no wrap for a plain class").isEqualTo(wraps);
    assertThat(kms.unwraps.get()).as("no unwrap for a plain class").isEqualTo(unwraps);
    // and the encrypted namespace does need it
    assertOutage(catchThrowable(() -> byDefault.get(sealedKey)));
  }

  @Test
  void aClockInAYearWithoutAKekIsAnOutageOnPutAndTheOldObjectsStillRead() {
    EncryptingEvidenceStore perYear = fixtures.encryptPerYear(raw, clock);
    perYear.put(request(K, "written in 2026"));

    clock.set(Instant.parse("2029-01-01T00:00:00Z"));

    assertOutage(
        catchThrowable(() -> perYear.put(request(EvidenceKey.of("landing", "k2"), "too late"))));
    assertThat(raw.get(EvidenceKey.of("landing", "k2"))).isEmpty();
    assertThat(perYear.get(K).orElseThrow().bytes()).isEqualTo(utf8("written in 2026"));
  }

  @Test
  void aRuntimeFailureOfTheEntropyOrTheClockIsNotMappedToAnOutage() {
    // decision D9: a fault of the host is not a fault of the key service
    IllegalStateException fault = new IllegalStateException("entropy source failed");
    ObjectCipher cipher =
        new ObjectCipher(
            fixtures.kms(),
            KekSelector.single(EncryptedFixtures.kek(EncryptedFixtures.SINGLE_KEK)),
            out -> {
              throw fault;
            },
            clock);
    EncryptingEvidenceStore broken =
        new EncryptingEvidenceStore(raw, cipher, EncryptedFixtures.everyKey());

    assertThat(catchThrowable(() -> broken.put(request(K, "hello")))).isSameAs(fault);
    assertThat(raw.get(K)).isEmpty();
  }

  @Test
  void aSealOverAKmsOutageIsEvidenceUnavailableAndLeavesTheLedgerUnchanged() {
    ScriptedKms kms = new ScriptedKms(fixtures.kms());
    EvidenceStore evidence = overScriptedKms(kms);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    DurableSeal sealing =
        new DurableSeal(
            ledger,
            evidence,
            clock,
            RetentionPolicy.fixedDays(1826),
            new SendGate(SendGate.Requirement.none()));
    EndpointKey endpoint = LedgerFixtures.endpoint("KMSDOWN");
    LeaseToken token = SealScenarioContract.lease(ledger, endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger, endpoint, "BK-KMS");
    SealRequest request =
        SealScenarioContract.request(
            endpoint, token, delivery, "BK-KMS", SealScenarioContract.reportOf("BK-KMS"));
    kms.wrapFailure = new IllegalStateException("secret-kms-host");

    SealOutcome outcome = sealing.seal(request);

    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail())
        .doesNotContain("secret-kms-host")
        .doesNotContain(EncryptedFixtures.SINGLE_KEK)
        .doesNotContain("BK-KMS");
    assertThat(raw.list("", Optional.empty(), 10)).isEmpty();
    assertThat(ledger.manifestRows("", Optional.empty(), 1_000)).isEmpty();
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.attempt(new AttemptId(1))).isEmpty();

    // the key service is back: the same request seals, and the tier holds two envelopes
    kms.wrapFailure = null;
    assertThat(sealing.seal(request).status()).isEqualTo(SealStatus.SEALED);
    assertThat(ledger.manifestRows("", Optional.empty(), 1_000)).hasSize(2);
  }

  // -------------------------------------------------------------------------------------------
  // messages (KF7)

  @Test
  void noMessageHoldsAKeyAKekIdOrAPath() {
    ScriptedKms kms = new ScriptedKms(fixtures.kms());
    EncryptingEvidenceStore scripted = overScriptedKms(kms);
    List<Throwable> failures = new ArrayList<>();

    // an integrity alarm, a corruption alarm of each kind, an outage of each side, shredded data
    store.put(request(K, "hello"));
    failures.add(catchThrowable(() -> store.put(request(K, "other plaintext"))));
    EvidenceKey copied = EvidenceKey.of("landing", "copied-envelope");
    putRaw(copied, storedBytes(K));
    failures.add(catchThrowable(() -> store.get(copied)));
    failures.add(catchThrowable(() -> store.put(request(copied, "hello"))));
    EvidenceKey plain = EvidenceKey.of("landing", "plain-bytes");
    putRaw(plain, utf8("plain"));
    failures.add(catchThrowable(() -> store.get(plain)));
    kms.wrapFailure = new IllegalStateException("secret-kms-host kek-test landing/k1");
    failures.add(catchThrowable(() -> scripted.put(request(EvidenceKey.of("landing", "n"), "x"))));
    kms.wrapFailure = null;
    kms.unwrapFailure = new KmsAccessDeniedException("secret-denied kek-test landing/k1");
    failures.add(catchThrowable(() -> scripted.get(K)));
    failures.add(catchThrowable(() -> scripted.put(request(K, "hello"))));
    kms.unwrapFailure = null;
    fixtures.destroy(EncryptedFixtures.SINGLE_KEK);
    failures.add(catchThrowable(() -> store.get(K)));
    failures.add(catchThrowable(() -> store.put(request(K, "hello"))));
    failures.add(catchThrowable(() -> store.put(request(EvidenceKey.of("landing", "z"), "x"))));

    assertThat(failures).hasSize(10).doesNotContainNull();
    List<String> secrets = new ArrayList<>(fixtures.drawnHex());
    secrets.addAll(
        List.of(
            "kek-test",
            "kek-2026",
            "landing",
            "k1",
            "copied-envelope",
            "plain-bytes",
            "secret-kms-host",
            "secret-denied",
            "hello",
            "plaintext"));
    for (Throwable thrown : failures) {
      String text = thrown.getMessage() + "|" + thrown;
      for (String secret : secrets) {
        assertThat(text.toLowerCase(Locale.ROOT))
            .as("%s must not hold '%s'", thrown.getClass().getSimpleName(), secret)
            .doesNotContain(secret.toLowerCase(Locale.ROOT));
      }
      assertThat(thrown.getCause()).as(thrown.getClass().getSimpleName()).isNull();
      assertThat(thrown.getSuppressed()).isEmpty();
    }
    // and the types are the ones the mapping promises
    assertThat(failures.get(0)).isInstanceOf(EvidenceIntegrityException.class);
    assertThat(failures.get(1)).isInstanceOf(EvidenceCorruptionException.class);
    assertThat(failures.get(4)).isInstanceOf(EvidenceUnavailableException.class);
    assertThat(failures.get(5)).isInstanceOf(EvidenceUnavailableException.class);
    assertThat(failures.get(7)).isInstanceOf(DataShreddedException.class);
    assertThat(failures.get(9)).isInstanceOf(EvidenceUnavailableException.class);
  }
}
