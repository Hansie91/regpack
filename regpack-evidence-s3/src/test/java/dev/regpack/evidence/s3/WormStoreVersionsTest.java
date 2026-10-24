package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.fake.FakeS3Server.Fault;
import dev.regpack.evidence.seal.EpochMarker;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.RetentionPolicy;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Invariant V and the other writers (D-W2, D-W3, D-W4; WF2, WF3, WF4): the object under a key is
 * its oldest data version, whatever is added behind the adapter's back, a delete marker hides it
 * loudly and never makes it absent, and a foreign version that ranks below the adapter's own
 * decides.
 */
class WormStoreVersionsTest {
  private static final EvidenceKey K = EvidenceKey.of("landing", "k1");

  private FakeWormFixture f;

  @BeforeEach
  void start() {
    f = new FakeWormFixture();
  }

  @AfterEach
  void stop() {
    f.close();
  }

  private static byte[] utf8(String text) {
    return FakeWormFixture.utf8(text);
  }

  // ---- WF2: a newer version behind the back

  @Test
  void aNewerVersionAddedBehindTheBackNeverChangesWhatGetReturns() {
    PutRequest first = f.request(K, "first");
    Instant writtenAt = f.dataClock.now();
    f.store.put(first);
    StoredObject before = f.store.get(K).orElseThrow();
    f.dataClock.advance(Duration.ofDays(3));
    // a second PUT through the raw client: another writer, other bytes, no metadata, no lock
    f.raw().putObject(f.bucket, K.path(), utf8("second"), Map.of());
    StoredObject after = f.store.get(K).orElseThrow();
    assertThat(after).isEqualTo(before);
    assertThat(after.bytes()).isEqualTo(utf8("first"));
    assertThat(after.writtenAt()).isEqualTo(writtenAt);
    assertThat(after.retention()).isEqualTo(first.retention());
    // a put of the first bytes is present, a put of the newer bytes is an alarm
    assertThat(f.store.put(first)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThatThrownBy(() -> f.store.put(f.request(K, "second")))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);
    assertThat(f.store.list("landing/", Optional.empty(), 10)).containsExactly(K);
  }

  @Test
  void theOldestVersionIsFoundWhenTheVersionListSpansPages() {
    PutRequest first = f.request(K, "first");
    f.store.put(first);
    for (int i = 2; i <= 6; i++) {
      f.raw().putObject(f.bucket, K.path(), utf8("version " + i), Map.of());
    }
    f.server().fault(Fault.SHORT_VERSION_PAGES, true);
    int mark = f.mark();
    StoredObject got = f.store.get(K).orElseThrow();
    assertThat(got.bytes()).isEqualTo(utf8("first"));
    assertThat(got.retention()).isEqualTo(first.retention());
    // six entries at two per page: at least three listing requests, then one GET
    assertThat(f.kindsSince(mark)).containsExactly("versions", "versions", "versions", "GET");
    assertThat(f.store.put(first)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThatThrownBy(() -> f.store.put(f.request(K, "version 6")))
        .isInstanceOf(EvidenceIntegrityException.class);
  }

  // ---- WF3: delete markers

  @Test
  void aDeleteMarkerOverAnObjectIsACorruptionAlarmForGetAndPutAndTheKeyStaysListed() {
    f.store.put(f.request(K, "hello"));
    // a DELETE without a version id adds a marker, even under lock (probe result P7)
    f.raw().send("DELETE", f.bucket, Optional.of(K.path()), List.of(), Map.of(), new byte[0]);
    assertThatThrownBy(() -> f.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    int mark = f.mark();
    // a put must not resurrect the key, with the same bytes or with others
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> f.store.put(f.request(K, "other")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(f.kindsSince(mark)).doesNotContain("PUT");
    // the key is still listed: the loss is for the caller's next get to find
    assertThat(f.store.list("landing/", Optional.empty(), 10)).containsExactly(K);
    assertThat(f.store.list("", Optional.empty(), 10)).containsExactly(K);
  }

  @Test
  void aMarkerOverAnEpochMarkerKeyIsStillListedAndItsGetIsAnAlarm() {
    EpochMarker epochs = new EpochMarker(f.store, RetentionPolicy.fixedDays(1826), f.dataClock);
    epochs.declare(3);
    assertThat(epochs.current()).isEqualTo(3);
    EvidenceKey marker = EpochMarker.key(3);
    f.raw().send("DELETE", f.bucket, Optional.of(marker.path()), List.of(), Map.of(), new byte[0]);
    // listed, so that the marker cannot be read as a lower epoch
    assertThat(f.store.list("control/epoch/", Optional.empty(), 10)).containsExactly(marker);
    assertThatThrownBy(() -> f.store.get(marker)).isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(epochs::current).isInstanceOf(EvidenceCorruptionException.class);
  }

  @Test
  void aKeyThatHoldsOnlyDeleteMarkersIsAbsentAndAPutCreatesIt() {
    // a DELETE of a key that was never written still adds a marker on a versioned bucket
    f.server().addDeleteMarker(f.bucket, K.path());
    f.server().addDeleteMarker(f.bucket, K.path());
    assertThat(f.store.get(K)).isEmpty();
    assertThat(f.store.list("landing/", Optional.empty(), 10)).isEmpty();
    assertThat(f.store.put(f.request(K, "hello"))).isEqualTo(PutOutcome.CREATED);
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(f.store.list("landing/", Optional.empty(), 10)).containsExactly(K);
  }

  @Test
  void aMarkerBelowANewerDataVersionDoesNotHideTheOldestObject() {
    PutRequest first = f.request(K, "first");
    f.store.put(first);
    f.raw().send("DELETE", f.bucket, Optional.of(K.path()), List.of(), Map.of(), new byte[0]);
    // someone writes over the marker: the newest entry is data again
    f.raw().putObject(f.bucket, K.path(), utf8("newer"), Map.of());
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(utf8("first"));
    assertThat(f.store.put(first)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
  }

  // ---- WF4: a foreign version that ranks below the adapter's own

  @Test
  void
      aForeignOlderVersionAppearingDuringThePutWithOtherBytesIsAnIntegrityAlarmAndGetReturnsTheForeignBytes() {
    PutRequest ours = f.request(K, "ours");
    f.server().addOlderVersionOnNextPut(f.bucket, K.path(), utf8("foreign"));
    assertThatThrownBy(() -> f.store.put(ours))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);
    // the foreign version is the oldest: it is the object, with the metadata it was given
    StoredObject got = f.store.get(K).orElseThrow();
    assertThat(got.bytes()).isEqualTo(utf8("foreign"));
    assertThat(got.writtenAt()).isEqualTo(f.dataClock.now());
    assertThat(got.retention()).isEqualTo(ours.retention());
    // our own version stays as a harmless extra version, not returned by the port
    assertThat(
            f.raw()
                .listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 10)
                .entries())
        .hasSize(2);
    assertThatThrownBy(() -> f.store.put(ours)).isInstanceOf(EvidenceIntegrityException.class);
  }

  @Test
  void aForeignOlderVersionAppearingDuringThePutWithTheSameBytesIsAlreadyPresentIdentical() {
    PutRequest ours = f.request(K, "same");
    f.server().addOlderVersionOnNextPut(f.bucket, K.path(), utf8("same"));
    assertThat(f.store.put(ours)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(utf8("same"));
  }

  @Test
  void twoStoresRacingWithDifferentBytesAreCountedAndReadAsOne() throws Exception {
    int rounds = 20;
    int bothCreated = 0;
    WormEvidenceStore other = f.secondStore();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < rounds; round++) {
        EvidenceKey key = EvidenceKey.of("race", "r" + round);
        CountDownLatch go = new CountDownLatch(1);
        Future<PutOutcome> a = pool.submit(() -> raced(go, f.store, f.request(key, "writer a")));
        Future<PutOutcome> b = pool.submit(() -> raced(go, other, f.request(key, "writer b")));
        go.countDown();
        int created = 0;
        for (Future<PutOutcome> outcome : List.of(a, b)) {
          try {
            if (outcome.get() == PutOutcome.CREATED) {
              created++;
            }
          } catch (ExecutionException e) {
            // the writer that found the other's version older is told so
            assertThat(e.getCause()).isInstanceOf(EvidenceIntegrityException.class);
          }
        }
        assertThat(created)
            .as("round %d has a writer that was told CREATED", round)
            .isGreaterThanOrEqualTo(1);
        if (created == 2) {
          bothCreated++;
        }
        // both stores read the same bytes afterwards, and they are one writer's
        StoredObject fromFirst = f.store.get(key).orElseThrow();
        StoredObject fromSecond = other.get(key).orElseThrow();
        assertThat(fromSecond.bytes()).isEqualTo(fromFirst.bytes());
        assertThat(List.of(utf8("writer a"), utf8("writer b"))).contains(fromFirst.bytes());
      }
    } finally {
      pool.shutdownNow();
    }
    // the stated race: counted and printed, not a failure (D-W3)
    System.out.println(
        "twoStoresRacingWithDifferentBytes: rounds in which both writers were told CREATED = "
            + bothCreated
            + " of "
            + rounds);
  }

  private static PutOutcome raced(CountDownLatch go, WormEvidenceStore store, PutRequest request)
      throws InterruptedException {
    go.await();
    return store.put(request);
  }

  // ---- D-W3: within one store object the put is exact (the stripe lock)

  /**
   * The fake ranks versions by the order in which it admits them, so that without the stripe lock
   * every outcome would still be right; the one visible effect of a missing lock is the extra,
   * non-deletable versions. These two tests count the versions.
   */
  @Test
  void concurrentPutsOfTheSameBytesThroughOneStoreLeaveExactlyOneVersion() throws Exception {
    List<Object> results = concurrently(8, i -> f.request(K, "same bytes"));
    assertThat(results.stream().filter(PutOutcome.CREATED::equals)).hasSize(1);
    assertThat(results.stream().filter(PutOutcome.ALREADY_PRESENT_IDENTICAL::equals)).hasSize(7);
    assertThat(versionsOfK()).as("versions of the key").isEqualTo(1);
  }

  @Test
  void concurrentPutsOfDifferentBytesThroughOneStoreLeaveExactlyOneVersion() throws Exception {
    List<Object> results = concurrently(8, i -> f.request(K, "writer " + i));
    assertThat(results.stream().filter(PutOutcome.CREATED::equals)).hasSize(1);
    assertThat(results.stream().filter(EvidenceIntegrityException.class::isInstance)).hasSize(7);
    assertThat(versionsOfK()).as("versions of the key").isEqualTo(1);
  }

  private int versionsOfK() {
    return f.raw()
        .listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 100)
        .entries()
        .size();
  }

  /** Puts from {@code n} threads that start together through the one store of the fixture. */
  private List<Object> concurrently(int n, IntFunction<PutRequest> requests) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<PutOutcome>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        PutRequest request = requests.apply(i);
        futures.add(pool.submit(() -> raced(go, f.store, request)));
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
  void listCountsAKeyOnceHoweverManyVersionsItHasAndSkipsAKeyOfMarkersOnly() {
    List<EvidenceKey> expected = new ArrayList<>();
    for (String name : List.of("a", "b", "d", "e")) {
      EvidenceKey key = EvidenceKey.of("many", name);
      expected.add(key);
      f.store.put(f.request(key, name));
      f.raw().putObject(f.bucket, key.path(), utf8(name + "2"), Map.of());
    }
    f.server().addDeleteMarker(f.bucket, "many/c");
    assertThat(f.store.list("many/", Optional.empty(), 100)).containsExactlyElementsOf(expected);
    assertThat(f.store.list("many/", Optional.empty(), 3))
        .containsExactlyElementsOf(expected.subList(0, 3));
  }
}
