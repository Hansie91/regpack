package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.seal.EpochMarker;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.testkit.clock.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Invariant V and the other writers (D-W2, D-W3, D-W4; WF2, WF3, WF4) as far as they need nothing
 * but the store and the raw client of the service: the object under a key is its oldest data
 * version, whatever is added behind the adapter's back; a delete marker hides it loudly and never
 * makes it absent; one store object puts exactly once (the stripe lock); retention and hold touch
 * only the oldest data version. The backend comes from the seam of T8a (the four abstract methods
 * of {@link AbstractWormContractTest}); {@link WormStoreVersionsTest} runs the methods over the
 * fake, {@link WormVersionsIT} over the real service. Whether a put created a version is shown by
 * counting the versions of the key through the raw client, never by the request log of the fake.
 * The methods that need a hook or a fault of the fake stay in {@link WormStoreVersionsTest}.
 *
 * <p>A subclass creates its backend lazily (the first call of a seam method): the set-up method of
 * a subclass runs after the one of this class, and this class has none, so the tier of a test is
 * made when the test first asks for it.
 */
abstract class AbstractWormVersionsTest {
  private static final EvidenceKey K = EvidenceKey.of("landing", "k1");
  private static final String LOCK_DATE = "x-amz-object-lock-retain-until-date";
  private static final String LOCK_MODE = "x-amz-object-lock-mode";
  private static final String HOLD = "x-amz-object-lock-legal-hold";
  private static final Instant Y2046 = Instant.parse("2046-03-01T00:00:00Z");

  /** The raw client of the service, with the test credentials. */
  abstract S3Client rawClient();

  /** A fresh bucket with object lock on. */
  abstract String newBucket();

  /** The clock of {@code X-Amz-Date}: real time against a real service, manual on the fake. */
  abstract Clock signingClock();

  /** The endpoint, region and credentials of the service. */
  abstract S3Settings settings();

  /** A store on a fresh bucket of its own, with a manual data clock at {@code 2040-03-01}. */
  private final class Tier {
    final ManualClock dataClock = new ManualClock(AbstractWormContractTest.START);
    final String bucket = newBucket();
    final WormEvidenceStore store;

    Tier() {
      S3Settings settings = settings();
      WormConfig config =
          WormConfig.governance(
              settings.endpoint(),
              settings.region(),
              bucket,
              settings.credentials(),
              new Durability(true, 1));
      store =
          WormEvidenceStore.open(
              config, dataClock, signingClock(), new JdkTransport(Duration.ofSeconds(60)));
    }

    PutRequest request(EvidenceKey key, String text) {
      return new PutRequest(
          key,
          utf8(text),
          "text/plain",
          new Retention(
              new RetentionClass("landing"), dataClock.now().plus(Duration.ofDays(1826))));
    }

    /** The entries of the exact key, newest first, read through the raw client. */
    List<VersionEntry> versionsOf(EvidenceKey key) {
      return rawClient()
          .listVersions(bucket, key.path(), Optional.empty(), Optional.empty(), 1_000)
          .entries()
          .stream()
          .filter(e -> e.key().equals(key.path()))
          .toList();
    }

    /** The data versions of the key, newest first. */
    List<VersionEntry> dataVersionsOf(EvidenceKey key) {
      return versionsOf(key).stream().filter(e -> !e.deleteMarker()).toList();
    }

    /** A DELETE without a version id: a marker even under lock, nothing is deleted (probe P7). */
    void deleteWithoutVersion(EvidenceKey key) {
      rawClient().send("DELETE", bucket, Optional.of(key.path()), List.of(), Map.of(), new byte[0]);
    }

    /** Another writer: bytes, no metadata, no lock. */
    void putBehindTheBack(EvidenceKey key, String text) {
      rawClient().putObject(bucket, key.path(), utf8(text), Map.of());
    }
  }

  private Tier tier;

  private Tier tier() {
    if (tier == null) {
      tier = new Tier();
    }
    return tier;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  // ---- WF2: a newer version behind the back

  @Test
  void aNewerVersionAddedBehindTheBackNeverChangesWhatGetReturns() {
    Tier t = tier();
    PutRequest first = t.request(K, "first");
    Instant writtenAt = t.dataClock.now();
    t.store.put(first);
    StoredObject before = t.store.get(K).orElseThrow();
    t.dataClock.advance(Duration.ofDays(3));
    // a second PUT through the raw client: another writer, other bytes, no metadata, no lock
    t.putBehindTheBack(K, "second");
    assertThat(t.versionsOf(K)).hasSize(2);
    StoredObject after = t.store.get(K).orElseThrow();
    assertThat(after).isEqualTo(before);
    assertThat(after.bytes()).isEqualTo(utf8("first"));
    assertThat(after.writtenAt()).isEqualTo(writtenAt);
    assertThat(after.retention()).isEqualTo(first.retention());
    // a put of the first bytes is present, a put of the newer bytes is an alarm
    assertThat(t.store.put(first)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThatThrownBy(() -> t.store.put(t.request(K, "second")))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);
    assertThat(t.versionsOf(K)).as("no put created a version").hasSize(2);
    assertThat(t.store.list("landing/", Optional.empty(), 10)).containsExactly(K);
  }

  @Test
  void theObjectIsTheOldestOfSeveralVersions() {
    Tier t = tier();
    PutRequest first = t.request(K, "first");
    t.store.put(first);
    for (int i = 2; i <= 6; i++) {
      t.putBehindTheBack(K, "version " + i);
    }
    assertThat(t.versionsOf(K)).hasSize(6);
    StoredObject got = t.store.get(K).orElseThrow();
    assertThat(got.bytes()).isEqualTo(utf8("first"));
    assertThat(got.retention()).isEqualTo(first.retention());
    assertThat(t.store.put(first)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThatThrownBy(() -> t.store.put(t.request(K, "version 6")))
        .isInstanceOf(EvidenceIntegrityException.class);
    assertThat(t.versionsOf(K)).as("no put created a version").hasSize(6);
    assertThat(t.store.list("landing/", Optional.empty(), 10)).containsExactly(K);
  }

  // ---- WF3: delete markers

  @Test
  void aDeleteMarkerOverAnObjectIsACorruptionAlarmForGetAndPutAndTheKeyStaysListed() {
    Tier t = tier();
    t.store.put(t.request(K, "hello"));
    // a DELETE without a version id adds a marker, even under lock (probe result P7)
    t.deleteWithoutVersion(K);
    assertThat(t.versionsOf(K)).hasSize(2);
    assertThatThrownBy(() -> t.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    // a put must not resurrect the key, with the same bytes or with others
    assertThatThrownBy(() -> t.store.put(t.request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> t.store.put(t.request(K, "other")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(t.versionsOf(K)).as("no put created a version").hasSize(2);
    assertThat(t.dataVersionsOf(K)).hasSize(1);
    // the key is still listed: the loss is for the caller's next get to find
    assertThat(t.store.list("landing/", Optional.empty(), 10)).containsExactly(K);
    assertThat(t.store.list("", Optional.empty(), 10)).containsExactly(K);
  }

  @Test
  void aMarkerOverAnEpochMarkerKeyIsStillListedAndItsGetIsAnAlarm() {
    Tier t = tier();
    EpochMarker epochs = new EpochMarker(t.store, RetentionPolicy.fixedDays(1826), t.dataClock);
    epochs.declare(3);
    assertThat(epochs.current()).isEqualTo(3);
    EvidenceKey marker = EpochMarker.key(3);
    t.deleteWithoutVersion(marker);
    // listed, so that the marker cannot be read as a lower epoch
    assertThat(t.store.list("control/epoch/", Optional.empty(), 10)).containsExactly(marker);
    assertThatThrownBy(() -> t.store.get(marker)).isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(epochs::current).isInstanceOf(EvidenceCorruptionException.class);
  }

  /**
   * The key was never written, so what it holds is at most delete markers. That a DELETE of such a
   * key adds a marker on a versioned bucket is the behaviour of S3 and is not among the probe
   * items, so it is not asserted here; the adapter must treat the key as absent and a put must
   * create it, whether or not the service added a marker. The fake-bound class shows the case with
   * markers only, made by a hook.
   */
  @Test
  void aKeyThatWasOnlyDeletedIsAbsentAndAPutCreatesIt() {
    Tier t = tier();
    t.deleteWithoutVersion(K);
    t.deleteWithoutVersion(K);
    assertThat(t.dataVersionsOf(K)).isEmpty();
    assertThat(t.store.get(K)).isEmpty();
    assertThat(t.store.list("landing/", Optional.empty(), 10)).isEmpty();
    assertThat(t.store.put(t.request(K, "hello"))).isEqualTo(PutOutcome.CREATED);
    assertThat(t.dataVersionsOf(K)).hasSize(1);
    assertThat(t.store.get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(t.store.list("landing/", Optional.empty(), 10)).containsExactly(K);
  }

  @Test
  void aMarkerBelowANewerDataVersionDoesNotHideTheOldestObject() {
    Tier t = tier();
    PutRequest first = t.request(K, "first");
    t.store.put(first);
    t.deleteWithoutVersion(K);
    // someone writes over the marker: the newest entry is data again
    t.putBehindTheBack(K, "newer");
    assertThat(t.versionsOf(K)).hasSize(3);
    assertThat(t.versionsOf(K).get(0).deleteMarker()).as("the newest entry is data").isFalse();
    assertThat(t.store.get(K).orElseThrow().bytes()).isEqualTo(utf8("first"));
    assertThat(t.store.put(first)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(t.versionsOf(K)).as("no put created a version").hasSize(3);
  }

  // ---- WF6: retention and hold go to the object

  @Test
  void retentionAndHoldTouchOnlyTheOldestDataVersion() {
    Tier t = tier();
    t.store.put(t.request(K, "hello"));
    String oldest = t.dataVersionsOf(K).get(0).versionId();
    t.putBehindTheBack(K, "newer");
    List<VersionEntry> data = t.dataVersionsOf(K);
    assertThat(data).hasSize(2);
    String newest = data.get(0).versionId();
    assertThat(newest).isNotEqualTo(oldest);
    assertThat(data.get(1).versionId()).isEqualTo(oldest);
    t.store.extendRetention(K, Y2046);
    t.store.setLegalHold(K, true);
    Map<String, String> oldHead =
        rawClient().head(t.bucket, K.path(), oldest).orElseThrow().headers();
    Map<String, String> newHead =
        rawClient().head(t.bucket, K.path(), newest).orElseThrow().headers();
    assertThat(Instant.parse(oldHead.get(LOCK_DATE))).isEqualTo(Y2046);
    assertThat(oldHead).containsEntry(HOLD, "ON");
    assertThat(newHead).doesNotContainKeys(LOCK_DATE, LOCK_MODE);
    assertThat(newHead.get(HOLD)).as("the newer version has no hold").isNotEqualTo("ON");
    StoredObject got = t.store.get(K).orElseThrow();
    assertThat(got.bytes()).isEqualTo(utf8("hello"));
    assertThat(got.retention().retainUntil()).isEqualTo(Y2046);
    assertThat(got.legalHold()).isTrue();
  }

  // ---- D-W3: within one store object the put is exact (the stripe lock)

  /**
   * The fake ranks versions by the order in which it admits them, so that without the stripe lock
   * every outcome would still be right there; the one visible effect of a missing lock is the
   * extra, non-deletable versions. These two tests count the versions through the raw client. On
   * the service the rank is the server's, and the lock is what keeps one store object from making
   * two versions.
   */
  @Test
  void concurrentPutsOfTheSameBytesThroughOneStoreLeaveExactlyOneVersion() throws Exception {
    Tier t = tier();
    List<Object> results = concurrently(t, 8, i -> t.request(K, "same bytes"));
    assertThat(results.stream().filter(PutOutcome.CREATED::equals)).hasSize(1);
    assertThat(results.stream().filter(PutOutcome.ALREADY_PRESENT_IDENTICAL::equals)).hasSize(7);
    assertThat(t.versionsOf(K)).as("versions of the key").hasSize(1);
  }

  @Test
  void concurrentPutsOfDifferentBytesThroughOneStoreLeaveExactlyOneVersion() throws Exception {
    Tier t = tier();
    List<Object> results = concurrently(t, 8, i -> t.request(K, "writer " + i));
    assertThat(results.stream().filter(PutOutcome.CREATED::equals)).hasSize(1);
    assertThat(results.stream().filter(EvidenceIntegrityException.class::isInstance)).hasSize(7);
    assertThat(t.versionsOf(K)).as("versions of the key").hasSize(1);
  }

  /** Puts from {@code n} threads that start together through the one store of the tier. */
  private static List<Object> concurrently(Tier t, int n, IntFunction<PutRequest> requests)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<PutOutcome>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        PutRequest request = requests.apply(i);
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return t.store.put(request);
                }));
      }
      go.countDown();
      List<Object> results = new ArrayList<>();
      for (Future<PutOutcome> future : futures) {
        try {
          results.add(future.get(60, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          results.add(e.getCause());
        }
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- list across keys with several versions

  @Test
  void listCountsAKeyOnceHoweverManyVersionsItHasAndSkipsAKeyThatWasOnlyDeleted() {
    Tier t = tier();
    List<EvidenceKey> expected = new ArrayList<>();
    for (String name : List.of("a", "b", "d", "e")) {
      EvidenceKey key = EvidenceKey.of("many", name);
      expected.add(key);
      t.store.put(t.request(key, name));
      t.putBehindTheBack(key, name + "2");
    }
    t.deleteWithoutVersion(EvidenceKey.of("many", "c"));
    assertThat(t.store.list("many/", Optional.empty(), 100)).containsExactlyElementsOf(expected);
    assertThat(t.store.list("many/", Optional.empty(), 3))
        .containsExactlyElementsOf(expected.subList(0, 3));
  }
}
