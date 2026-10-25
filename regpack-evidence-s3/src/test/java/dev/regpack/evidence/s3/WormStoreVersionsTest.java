package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.evidence.s3.fake.FakeS3Server.Fault;
import dev.regpack.evidence.verify.EvidenceVerifier;
import dev.regpack.evidence.verify.EvidenceVerifier.Finding;
import dev.regpack.evidence.verify.EvidenceVerifier.Kind;
import dev.regpack.evidence.verify.EvidenceVerifier.Report;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Invariant V and the other writers (D-W2, D-W3, D-W4; WF2, WF3, WF4) over the in-JVM fake service.
 * The methods that need only the store and the raw client come from {@link
 * AbstractWormVersionsTest}, which {@link WormVersionsIT} runs on the real service; the methods
 * here need a hook or a fault of the fake (a short page, a version ranked below another, a marker
 * made by hand, the request log) and so exist only on the fake.
 */
class WormStoreVersionsTest extends AbstractWormVersionsTest {
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

  // The seam of T8a over the fixture. The fixture exists when a test body runs, which is when the
  // shared methods first ask for the backend; their buckets are new ones on the same fake service.

  @Override
  S3Client rawClient() {
    return f.raw();
  }

  @Override
  String newBucket() {
    return f.backend.newBucket();
  }

  @Override
  Clock signingClock() {
    return f.backend.signingClock();
  }

  @Override
  S3Settings settings() {
    return f.backend.settings();
  }

  private static byte[] utf8(String text) {
    return FakeWormFixture.utf8(text);
  }

  // ---- WF2: a newer version behind the back

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
  void aPutOverADeleteMarkerSendsNoPutRequestAtAll() {
    f.store.put(f.request(K, "hello"));
    f.raw().send("DELETE", f.bucket, Optional.of(K.path()), List.of(), Map.of(), new byte[0]);
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> f.store.put(f.request(K, "other")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(f.kindsSince(mark)).doesNotContain("PUT");
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

  /**
   * The cross-process hole of D-W3, shown without a thread (plan section 3, statement (i)): two
   * store objects, as two processes would be, each lists first and puts second. Store B has listed
   * (nothing there) and its PUT is about to be forwarded when store A does its whole put; the
   * service then ranks B's upload below A's version, as a service may rank an upload that finished
   * later (probe P13: the rank is the server's; the hook {@code rankNextPutBelowExisting} models
   * it). B's second listing shows its own version as the oldest, so both writers are told {@code
   * CREATED}, {@code get} returns B's bytes, A's bytes stay as a newer locked version that nobody
   * reads, and a ledger row that A's seal would have recorded (A's hash) is a {@code HASH_MISMATCH}
   * with the detail {@code manifest-hash} in {@code verify}. The ledger row is made by hand here;
   * the seal itself is not driven, so this does not show a seal over a real race.
   */
  @Test
  void twoStoresThatAreBothToldCreatedLeaveTheLaterFinishedUploadAsTheObject() throws Exception {
    PutRequest a = f.request(K, "writer a");
    PutRequest b = f.request(K, "writer b");
    JdkTransport real = new JdkTransport(Duration.ofSeconds(30));
    PutOutcome[] outcomeOfA = new PutOutcome[1];
    boolean[] interleaved = new boolean[1];
    S3Transport transportOfB =
        request -> {
          if (!interleaved[0] && isObjectPut(request)) {
            interleaved[0] = true;
            // B's PUT is about to leave: A does its whole put now, on the store of the fixture
            outcomeOfA[0] = f.store.put(a);
            // and the service ranks B's upload, which finishes later, below A's version
            f.server().rankNextPutBelowExisting(f.bucket, K.path());
          }
          return real.send(request);
        };
    WormEvidenceStore storeB = f.openOn(transportOfB);

    PutOutcome outcomeOfB = storeB.put(b);

    assertThat(interleaved[0]).as("A's put ran between B's listing and B's PUT").isTrue();
    assertThat(outcomeOfA[0]).isEqualTo(PutOutcome.CREATED);
    assertThat(outcomeOfB).isEqualTo(PutOutcome.CREATED);
    // the object is B's, the oldest data version, for both stores
    assertThat(storeB.get(K).orElseThrow().bytes()).isEqualTo(utf8("writer b"));
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(utf8("writer b"));
    // A's bytes stay as the newer version, locked, and are never read through the port
    List<VersionEntry> versions =
        f.raw().listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 10).entries();
    assertThat(versions).hasSize(2);
    assertThat(f.raw().get(f.bucket, K.path(), versions.get(0).versionId()).orElseThrow().body())
        .isEqualTo(utf8("writer a"));
    assertThat(
            f.raw().head(f.bucket, K.path(), versions.get(0).versionId()).orElseThrow().headers())
        .containsKey("x-amz-object-lock-mode");
    assertThat(f.store.put(b)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThatThrownBy(() -> f.store.put(a)).isInstanceOf(EvidenceIntegrityException.class);

    // A was told CREATED, so its seal would have recorded A's hash: verify finds the difference,
    // after the fact; a row with B's hash is clean (so the finding is the race, not the fixture)
    assertThat(verifyWithRowOf(b).clean()).isTrue();
    assertThat(verifyWithRowOf(a).findings())
        .containsExactly(new Finding(Kind.HASH_MISMATCH, K.path(), EvidenceVerifier.MANIFEST_HASH));
  }

  private Report verifyWithRowOf(PutRequest request) {
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(f.dataClock);
    ledger.recordObjects(
        List.of(
            new ObjectEntry(
                request.key(),
                ObjectClass.OTHER,
                Sha256.of(request.bytes()),
                request.bytes().length)),
        Provenance.WRITTEN,
        Optional.empty());
    return new EvidenceVerifier(f.store, ledger).verify("");
  }

  /** The PutObject of a key: a PUT with no sub-resource in the query. */
  private static boolean isObjectPut(S3Request request) {
    return request.method().equals("PUT")
        && request.uri().getRawQuery() == null
        && request.uri().getRawPath().endsWith("/" + K.path());
  }

  /**
   * Two store objects putting different bytes under one key from two threads, 20 rounds. This fake
   * ranks the versions by the order in which its single lock admits the PUTs, so the writer that is
   * admitted first is the oldest in every listing that holds both, and exactly one writer is told
   * {@code CREATED} in every round: the other finds the first writer's version older and alarms.
   * The fake therefore cannot show the cross-process hole (two {@code CREATED}); {@link
   * #twoStoresThatAreBothToldCreatedLeaveTheLaterFinishedUploadAsTheObject} shows it with a hook.
   * What this test pins is what the fake can show: one winner per round, and one object for both
   * stores.
   */
  @Test
  void twoStoresRacingWithDifferentBytesLeaveExactlyOneCreatedAndReadAsOne() throws Exception {
    int rounds = 20;
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
        assertThat(created).as("round %d: the writers told CREATED", round).isEqualTo(1);
        // both stores read the same bytes afterwards, and they are one writer's
        StoredObject fromFirst = f.store.get(key).orElseThrow();
        StoredObject fromSecond = other.get(key).orElseThrow();
        assertThat(fromSecond.bytes()).isEqualTo(fromFirst.bytes());
        assertThat(List.of(utf8("writer a"), utf8("writer b"))).contains(fromFirst.bytes());
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static PutOutcome raced(CountDownLatch go, WormEvidenceStore store, PutRequest request)
      throws InterruptedException {
    go.await();
    return store.put(request);
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
