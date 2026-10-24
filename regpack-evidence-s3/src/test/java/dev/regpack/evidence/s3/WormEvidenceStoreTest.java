package dev.regpack.evidence.s3;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.fake.FakeS3Server.Fault;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.EvidenceStore.Grade;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What belongs to the P-WORM adapter alone and not to the shared suites (T8a): the requests of a
 * put, the faults of the service and what each becomes (D-W3, D-W4, D-W7), the limits of the
 * adapter (A10), the grade, and that no message repeats a secret or the host (WF10). Faults come
 * from the fake service, which is why these methods live in a {@code *Test} class.
 */
class WormEvidenceStoreTest {
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

  // ---- the requests of a put

  @Test
  void aNewKeyIsPutOnceAndCheckedTwice() {
    int mark = f.mark();
    assertThat(f.store.put(f.request(K, "hello"))).isEqualTo(PutOutcome.CREATED);
    assertThat(f.kindsSince(mark)).containsExactly("versions", "PUT", "versions");
  }

  @Test
  void aNewObjectCarriesTheLockHeadersAndTheMetadataOfTheModel() {
    Instant retainUntil = Instant.parse("2045-03-01T00:00:00.000001Z");
    String mediaType = "application/x-odd; a=\"b c\"";
    String className = "cé";
    f.store.put(
        new PutRequest(
            K,
            FakeWormFixture.utf8("hello"),
            mediaType,
            new Retention(new RetentionClass(className), retainUntil)));
    Map<String, String> headers = headersOfTheOnlyVersion();
    assertThat(headers)
        .containsEntry("content-type", "application/octet-stream")
        .containsEntry("x-amz-object-lock-mode", "GOVERNANCE")
        // the lock is the retain-until rounded up to whole seconds, never shorter than the record
        .containsEntry("x-amz-object-lock-retain-until-date", "2045-03-01T00:00:01Z")
        .containsEntry(
            "x-amz-meta-regpack-sha256",
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        .containsEntry("x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z")
        .containsEntry("x-amz-meta-regpack-retain-until", "2045-03-01T00:00:00.000001Z")
        // UTF-8, base64url, no padding
        .containsEntry("x-amz-meta-regpack-class", base64url(className))
        .containsEntry("x-amz-meta-regpack-media-type", base64url(mediaType));
    assertThat(headers.get("x-amz-meta-regpack-class")).doesNotContain("=");
    // and the model reads back as it was put
    StoredObject got = f.store.get(K).orElseThrow();
    assertThat(got.mediaType()).isEqualTo(mediaType);
    assertThat(got.retention())
        .isEqualTo(new Retention(new RetentionClass(className), retainUntil));
  }

  @Test
  void aRetainUntilBeyondTheMaximumLockDateIsLockedUntilTheMaximumAndRecordedInFull() {
    Instant last = Instant.parse("9999-12-31T23:59:59.999999Z");
    f.store.put(
        new PutRequest(
            K,
            FakeWormFixture.utf8("hello"),
            "text/plain",
            new Retention(new RetentionClass("landing"), last)));
    assertThat(headersOfTheOnlyVersion())
        .containsEntry("x-amz-object-lock-retain-until-date", "9999-12-31T23:59:59Z");
    assertThat(f.store.get(K).orElseThrow().retention().retainUntil()).isEqualTo(last);
  }

  private Map<String, String> headersOfTheOnlyVersion() {
    String versionId =
        f.raw()
            .listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 10)
            .entries()
            .get(0)
            .versionId();
    return f.raw().head(f.bucket, K.path(), versionId).orElseThrow().headers();
  }

  private static String base64url(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(UTF_8));
  }

  @Test
  void aPutOfAnExistingKeyWithTheSameBytesSendsNoPut() {
    f.store.put(f.request(K, "hello"));
    int mark = f.mark();
    f.dataClock.advance(Duration.ofHours(1));
    assertThat(f.store.put(f.request(K, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    // the existence test reads the stored bytes with GET, not HEAD, and puts nothing
    assertThat(f.kindsSince(mark)).containsExactly("versions", "GET");
    assertThat(
            f.raw()
                .listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 10)
                .entries())
        .hasSize(1);
  }

  @Test
  void aPutOfAnExistingKeyWithOtherBytesIsAnIntegrityAlarmAndSendsNoPut() {
    f.store.put(f.request(K, "hello"));
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.put(f.request(K, "other")))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);
    assertThat(f.kindsSince(mark)).containsExactly("versions", "GET");
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(FakeWormFixture.utf8("hello"));
  }

  @Test
  void aPutOfAnExistingKeyWhoseStoredBytesAreDamagedIsACorruptionAlarmAndSendsNoPut() {
    f.store.put(f.request(K, "hello"));
    f.server().fault(Fault.CORRUPT_BYTES, true);
    int mark = f.mark();
    // the stored bytes are hashed before the request's hash is looked at: same and other bytes
    // alike
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> f.store.put(f.request(K, "other")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(f.kindsSince(mark)).doesNotContain("PUT");
    f.server().fault(Fault.CORRUPT_BYTES, false);
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(FakeWormFixture.utf8("hello"));
  }

  // ---- unknown outcomes and what each becomes

  @Test
  void aPutWhoseResponseIsLostIsUnavailableAndTheRetryFindsTheObject() {
    f.server().fault(Fault.LOSE_RESPONSE_AFTER_STORE, true);
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageMatching("S3 putObject status=0 code=[A-Za-z0-9]+");
    f.server().fault(Fault.LOSE_RESPONSE_AFTER_STORE, false);
    // the object was stored: the retry finds it, writes nothing and reports it as present
    int mark = f.mark();
    assertThat(f.store.put(f.request(K, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(f.kindsSince(mark)).doesNotContain("PUT");
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(FakeWormFixture.utf8("hello"));
  }

  @Test
  void a403A5xxAndAConnectionResetAreUnavailableWithStatusAndCodeOnly() {
    // 403: the store signs with a secret the service does not know
    var wrongSecret =
        WormEvidenceStore.open(
            f.configWith(
                new Credentials(FakeWormBackend.KEY_ID, "a-different-secret-0123456789ab")),
            f.dataClock,
            f.backend.signingClock(),
            new JdkTransport(Duration.ofSeconds(10)));
    assertThatThrownBy(() -> wrongSecret.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=403 code=SignatureDoesNotMatch");
    assertThatThrownBy(() -> wrongSecret.get(K))
        .hasMessage("S3 listObjectVersions status=403 code=SignatureDoesNotMatch");
    assertThatThrownBy(() -> wrongSecret.list("landing/", Optional.empty(), 10))
        .hasMessage("S3 listObjectVersions status=403 code=SignatureDoesNotMatch");
    assertThatThrownBy(wrongSecret::grade)
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 getBucketVersioning status=403 code=SignatureDoesNotMatch");

    // 5xx
    f.server().fault(Fault.SERVER_ERROR, true);
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=500 code=InternalError");
    assertThatThrownBy(() -> f.store.get(K))
        .hasMessage("S3 listObjectVersions status=500 code=InternalError");
    assertThatThrownBy(() -> f.store.list("landing/", Optional.empty(), 10))
        .hasMessage("S3 listObjectVersions status=500 code=InternalError");
    f.server().fault(Fault.SERVER_ERROR, false);

    // a connection that is dropped without an answer
    f.server().fault(Fault.DROP_CONNECTION, true);
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageMatching("S3 listObjectVersions status=0 code=[A-Za-z0-9]+");
    assertThatThrownBy(() -> f.store.get(K))
        .hasMessageMatching("S3 listObjectVersions status=0 code=[A-Za-z0-9]+");
    f.server().fault(Fault.DROP_CONNECTION, false);

    // none of it stored anything
    assertThat(f.store.get(K)).isEmpty();
    assertThat(f.store.list("", Optional.empty(), 10)).isEmpty();
  }

  /** A transport that answers every request with this status and an S3 error document. */
  private static S3Transport answering(int status, String code) {
    String body =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>"
            + code
            + "</Code><Message>marker-in-message</Message><Resource>marker-in-resource</Resource>"
            + "<RequestId>marker-in-request-id</RequestId></Error>";
    return request ->
        new S3Response(status, Map.of("content-type", "application/xml"), body.getBytes(UTF_8));
  }

  @Test
  void anyStatusTheCallDoesNotExpectIsUnavailableWithStatusAndCodeOnly() {
    Map<Integer, String> answers =
        Map.of(
            301, "PermanentRedirect",
            304, "NotModified",
            404, "NoSuchBucket",
            409, "OperationAborted",
            412, "PreconditionFailed",
            429, "SlowDown",
            501, "NotImplemented",
            503, "ServiceUnavailable");
    for (Map.Entry<Integer, String> answer : answers.entrySet()) {
      WormEvidenceStore scripted = f.openOn(answering(answer.getKey(), answer.getValue()));
      String expected =
          "S3 listObjectVersions status=" + answer.getKey() + " code=" + answer.getValue();
      assertThatThrownBy(() -> scripted.put(f.request(K, "hello")))
          .as(expected)
          .isInstanceOf(EvidenceUnavailableException.class)
          .hasMessage(expected);
      assertThatThrownBy(() -> scripted.get(K)).as(expected).hasMessage(expected);
      assertThatThrownBy(() -> scripted.list("landing/", Optional.empty(), 10))
          .as(expected)
          .hasMessage(expected);
    }
    // a 2xx the call does not expect, and an answer that cannot be read
    WormEvidenceStore noContent = f.openOn(request -> new S3Response(204, Map.of(), new byte[0]));
    assertThatThrownBy(() -> noContent.get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=204 code=none");
    WormEvidenceStore garbage =
        f.openOn(request -> new S3Response(200, Map.of(), "not xml at all".getBytes(UTF_8)));
    assertThatThrownBy(() -> garbage.get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=200 code=unparsable");
    // the service is untouched by every one of them
    assertThat(f.store.list("", Optional.empty(), 10)).isEmpty();
  }

  @Test
  void aListedVersionThatAnswers404IsACorruptionAlarm() {
    f.store.put(f.request(K, "hello"));
    JdkTransport real = new JdkTransport(Duration.ofSeconds(10));
    S3Transport versionsVanish =
        request -> {
          String query = request.uri().getRawQuery();
          if (request.method().equals("GET") && query != null && query.contains("versionId=")) {
            return new S3Response(
                404,
                Map.of("content-type", "application/xml"),
                ("<Error><Code>NoSuchVersion</Code><Message>m</Message></Error>").getBytes(UTF_8));
          }
          return real.send(request);
        };
    WormEvidenceStore store = f.openOn(versionsVanish);
    assertThatThrownBy(() -> store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    // a 404 with no body at all is the same
    S3Transport emptyNotFound =
        request -> {
          String query = request.uri().getRawQuery();
          if (request.method().equals("GET") && query != null && query.contains("versionId=")) {
            return new S3Response(404, Map.of(), new byte[0]);
          }
          return real.send(request);
        };
    assertThatThrownBy(() -> f.openOn(emptyNotFound).get(K))
        .isInstanceOf(EvidenceCorruptionException.class);
  }

  // ---- listing

  @Test
  void listFollowsTheMarkersUntilThePageIsFull() {
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 1; i <= 25; i++) {
      EvidenceKey key = EvidenceKey.of("full", String.format(Locale.ROOT, "k%02d", i));
      keys.add(key);
      f.store.put(f.request(key, "v" + i));
    }
    f.server().fault(Fault.SHORT_VERSION_PAGES, true);
    int mark = f.mark();
    List<EvidenceKey> first = f.store.list("full/", Optional.empty(), 10);
    assertThat(first).containsExactlyElementsOf(keys.subList(0, 10));
    // two entries per page: ten keys need exactly five requests
    assertThat(f.kindsSince(mark)).hasSize(5).containsOnly("versions");
    List<EvidenceKey> second = f.store.list("full/", Optional.of(first.get(9)), 10);
    assertThat(second).containsExactlyElementsOf(keys.subList(10, 20));
    List<EvidenceKey> third = f.store.list("full/", Optional.of(second.get(9)), 10);
    assertThat(third).containsExactlyElementsOf(keys.subList(20, 25));
    assertThat(f.store.list("full/", Optional.of(third.get(4)), 10)).isEmpty();
    assertThat(f.store.list("full/", Optional.empty(), 100)).containsExactlyElementsOf(keys);
  }

  @Test
  void aLimitAboveWhatOnePageHoldsIsCappedInEveryRequestAndStillReturnsAllKeys() {
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      EvidenceKey key = EvidenceKey.of("cap", "k" + i);
      keys.add(key);
      f.store.put(f.request(key, "v" + i));
    }
    int mark = f.mark();
    assertThat(f.store.list("cap/", Optional.empty(), 5000)).containsExactlyElementsOf(keys);
    assertThat(f.store.list("cap/", Optional.empty(), Integer.MAX_VALUE))
        .containsExactlyElementsOf(keys);
    f.server().fault(Fault.SHORT_VERSION_PAGES, true);
    assertThat(f.store.list("cap/", Optional.empty(), 1_000_000)).containsExactlyElementsOf(keys);
    List<String> lines = f.server().requestLines();
    List<String> requests = lines.subList(mark, lines.size());
    assertThat(requests).isNotEmpty();
    Pattern maxKeys = Pattern.compile("max-keys=(\\d+)");
    for (String line : requests) {
      Matcher found = maxKeys.matcher(line);
      assertThat(found.find()).as(line).isTrue();
      assertThat(Integer.parseInt(found.group(1))).as(line).isBetween(1, 1_000);
    }
    // the first request of a large limit asks for a whole page
    assertThat(requests.get(0)).contains("max-keys=1000");
  }

  @Test
  void aKeyWhoseEntriesSpanPagesIsCountedOnce() {
    List<EvidenceKey> keys = new ArrayList<>();
    for (String name : List.of("a", "b", "c", "d", "e")) {
      EvidenceKey key = EvidenceKey.of("span", name);
      keys.add(key);
      f.store.put(f.request(key, name));
      // two more versions behind the back: three entries per key
      f.raw().putObject(f.bucket, key.path(), FakeWormFixture.utf8(name + "2"), Map.of());
      f.raw().putObject(f.bucket, key.path(), FakeWormFixture.utf8(name + "3"), Map.of());
    }
    f.server().fault(Fault.SHORT_VERSION_PAGES, true);
    assertThat(f.store.list("span/", Optional.empty(), 3))
        .containsExactlyElementsOf(keys.subList(0, 3));
    assertThat(f.store.list("span/", Optional.empty(), 100)).containsExactlyElementsOf(keys);
    assertThat(f.store.list("span/", Optional.of(keys.get(1)), 100))
        .containsExactlyElementsOf(keys.subList(2, 5));
  }

  @Test
  void aForeignKeyWithInvalidCharactersIsSkippedByListAndStillFillsThePage() {
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      EvidenceKey key = EvidenceKey.of("mix", "k0" + i);
      keys.add(key);
      f.store.put(f.request(key, "v" + i));
    }
    // written through the raw client: a space and a non-ASCII letter are no evidence key
    f.raw().putObject(f.bucket, "mix/k02 b", FakeWormFixture.utf8("x"), Map.of());
    f.raw().putObject(f.bucket, "mix/k03é", FakeWormFixture.utf8("y"), Map.of());
    f.raw().putObject(f.bucket, "mix/" + "x".repeat(101), FakeWormFixture.utf8("z"), Map.of());
    assertThat(f.store.list("mix/", Optional.empty(), 100)).containsExactlyElementsOf(keys);
    // the page is filled from the valid keys that follow the foreign ones
    assertThat(f.store.list("mix/", Optional.empty(), 4))
        .containsExactlyElementsOf(keys.subList(0, 4));
    assertThat(f.store.list("mix/", Optional.of(keys.get(1)), 2))
        .containsExactlyElementsOf(keys.subList(2, 4));
    f.server().fault(Fault.SHORT_VERSION_PAGES, true);
    assertThat(f.store.list("mix/", Optional.empty(), 4))
        .containsExactlyElementsOf(keys.subList(0, 4));
  }

  @Test
  void aValidLookingForeignKeyWithoutMetadataIsListedAndItsGetIsACorruptionAlarm() {
    EvidenceKey foreign = EvidenceKey.of("mix", "foreign");
    f.raw().putObject(f.bucket, foreign.path(), FakeWormFixture.utf8("x"), Map.of());
    assertThat(f.store.list("mix/", Optional.empty(), 10)).containsExactly(foreign);
    assertThatThrownBy(() -> f.store.get(foreign)).isInstanceOf(EvidenceCorruptionException.class);
  }

  @Test
  void aLimitOfZeroSendsNoRequestAndANegativeLimitIsRefused() {
    int mark = f.mark();
    assertThat(f.store.list("landing/", Optional.empty(), 0)).isEmpty();
    assertThat(f.kindsSince(mark)).isEmpty();
    assertThatThrownBy(() -> f.store.list("landing/", Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(f.kindsSince(mark)).isEmpty();
  }

  @Test
  void aListingThatNeverEndsIsUnavailable() {
    f.store.put(f.request(K, "hello"));
    f.server().fault(Fault.ENDLESS_TRUNCATION, true);
    // the service keeps saying "truncated" and hands back the markers it was given
    assertThatThrownBy(() -> f.store.get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=200 code=NoProgress");
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> f.store.list("landing/", Optional.empty(), 10))
        .isInstanceOf(EvidenceUnavailableException.class);
    // an empty bucket is no escape: nothing is ever read as absent from an endless listing
    try (FakeWormFixture other = new FakeWormFixture()) {
      other.server().fault(Fault.ENDLESS_TRUNCATION, true);
      assertThatThrownBy(() -> other.store.get(K)).isInstanceOf(EvidenceUnavailableException.class);
      assertThatThrownBy(() -> other.store.list("", Optional.empty(), 10))
          .isInstanceOf(EvidenceUnavailableException.class);
    }
  }

  /** The body of a truncated page with one entry of another key and the given markers. */
  private static String truncatedPage(String nextKey, String nextVersion) {
    return "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>true"
        + "</IsTruncated>"
        + (nextKey == null ? "" : "<NextKeyMarker>" + nextKey + "</NextKeyMarker>")
        + (nextVersion == null
            ? ""
            : "<NextVersionIdMarker>" + nextVersion + "</NextVersionIdMarker>")
        + "<Version><Key>zzz/other</Key><VersionId>v</VersionId><IsLatest>true</IsLatest></Version>"
        + "</ListVersionsResult>";
  }

  @Test
  void aTruncatedListingWithoutBothNextMarkersIsUnavailable() {
    for (String body :
        List.of(truncatedPage(null, null), truncatedPage("k", null), truncatedPage(null, "v"))) {
      WormEvidenceStore scripted =
          f.openOn(request -> new S3Response(200, Map.of(), body.getBytes(UTF_8)));
      assertThatThrownBy(() -> scripted.get(K))
          .isInstanceOf(EvidenceUnavailableException.class)
          .hasMessage("S3 listObjectVersions status=200 code=MissingMarkers");
      assertThatThrownBy(() -> scripted.list("", Optional.empty(), 10))
          .hasMessage("S3 listObjectVersions status=200 code=MissingMarkers");
    }
  }

  @Test
  void aListingThatKeepsAdvancingWithoutEndingIsStoppedAtTenThousandPages() {
    AtomicInteger pages = new AtomicInteger();
    S3Transport endless =
        request -> {
          int n = pages.incrementAndGet();
          return new S3Response(200, Map.of(), truncatedPage("zzz/k" + n, "v" + n).getBytes(UTF_8));
        };
    WormEvidenceStore scripted = f.openOn(endless);
    assertThatThrownBy(() -> scripted.get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=200 code=TooManyPages");
    assertThat(pages.get()).isEqualTo(10_000);
  }

  @Test
  void aTruncatedPageThatRepeatsItsMarkersIsUnavailableEvenWhenItHoldsEntries() {
    // every page lists the same two keys and says "truncated" with the same markers: without a
    // check that the markers move, list would answer [a, b, a, b, ...] until the limit is full
    String body =
        "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>true"
            + "</IsTruncated><NextKeyMarker>landing/b</NextKeyMarker>"
            + "<NextVersionIdMarker>v2</NextVersionIdMarker>"
            + "<Version><Key>landing/a</Key><VersionId>v1</VersionId><IsLatest>true</IsLatest>"
            + "</Version>"
            + "<Version><Key>landing/b</Key><VersionId>v2</VersionId><IsLatest>true</IsLatest>"
            + "</Version></ListVersionsResult>";
    AtomicInteger pages = new AtomicInteger();
    WormEvidenceStore scripted =
        f.openOn(
            request -> {
              pages.incrementAndGet();
              return new S3Response(200, Map.of(), body.getBytes(UTF_8));
            });
    assertThatThrownBy(() -> scripted.list("landing/", Optional.empty(), 100))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=200 code=NoProgress");
    // the first page moves the markers (they were empty), the second repeats them
    assertThat(pages.get()).isEqualTo(2);
    pages.set(0);
    // the version list of one key: the same page with the key as the only entry
    String ofOneKey = body.replace("landing/a", "landing/k1").replace("landing/b", "landing/k1");
    WormEvidenceStore versions =
        f.openOn(
            request -> {
              pages.incrementAndGet();
              return new S3Response(200, Map.of(), ofOneKey.getBytes(UTF_8));
            });
    assertThatThrownBy(() -> versions.get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=200 code=NoProgress");
    assertThat(pages.get()).isEqualTo(2);
  }

  // ---- the version id and the second listing

  @Test
  void anOwnVersionMissingFromTheSecondListingIsUnavailable() {
    JdkTransport real = new JdkTransport(Duration.ofSeconds(10));
    // the service answers the PUT with a version id that no listing will ever show
    S3Transport wrongId =
        request -> {
          S3Response answer = real.send(request);
          if (request.method().equals("PUT") && answer.headers().containsKey("x-amz-version-id")) {
            Map<String, String> headers = new HashMap<>(answer.headers());
            headers.put("x-amz-version-id", "not-in-any-listing");
            return new S3Response(answer.status(), headers, answer.body());
          }
          return answer;
        };
    WormEvidenceStore store = f.openOn(wrongId);
    assertThatThrownBy(() -> store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=200 code=OwnVersionMissing");
    // never CREATED; the object is there, and the retry says so
    assertThat(f.store.put(f.request(K, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
  }

  @Test
  void anOwnVersionThatTheSecondListingShowsAsADeleteMarkerIsMissingToo() {
    JdkTransport real = new JdkTransport(Duration.ofSeconds(10));
    AtomicBoolean putSeen = new AtomicBoolean();
    // after the PUT the service lists the version it just answered as a delete marker
    S3Transport relabelled =
        request -> {
          S3Response answer = real.send(request);
          if (request.method().equals("PUT")) {
            putSeen.set(true);
          } else if (putSeen.get() && request.method().equals("GET")) {
            String query = request.uri().getRawQuery();
            if (query != null && query.contains("versions")) {
              String body =
                  new String(answer.body(), UTF_8)
                      .replace("<Version>", "<DeleteMarker>")
                      .replace("</Version>", "</DeleteMarker>");
              return new S3Response(answer.status(), answer.headers(), body.getBytes(UTF_8));
            }
          }
          return answer;
        };
    WormEvidenceStore store = f.openOn(relabelled);
    assertThatThrownBy(() -> store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=200 code=OwnVersionMissing");
    assertThat(putSeen).isTrue();
  }

  @Test
  void aPutWithoutAVersionIdInTheResponseIsUnavailable() {
    f.server().fault(Fault.NO_VERSION_ID, true);
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 putObject status=200 code=NoVersionId");
    f.server().fault(Fault.NO_VERSION_ID, false);
    assertThat(f.store.put(f.request(K, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
  }

  // ---- get

  @Test
  void getRefusesBytesThatDoNotMatchTheRecordedSha256() {
    f.store.put(f.request(K, "hello"));
    f.server().fault(Fault.CORRUPT_BYTES, true);
    assertThatThrownBy(() -> f.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    f.server().fault(Fault.CORRUPT_BYTES, false);
    assertThat(f.store.get(K).orElseThrow().bytes()).isEqualTo(FakeWormFixture.utf8("hello"));
  }

  @Test
  void getRefusesAnObjectWithoutRegpackMetadata() {
    f.raw().putObject(f.bucket, K.path(), FakeWormFixture.utf8("hello"), Map.of());
    assertThatThrownBy(() -> f.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    // a put finds it as the oldest version, cannot vouch for it and writes nothing
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.put(f.request(K, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(f.kindsSince(mark)).doesNotContain("PUT");
  }

  @Test
  void getRefusesMetadataThatCannotBeRead() {
    String sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    List<Map<String, String>> unreadable =
        List.of(
            // not a hash
            Map.of("x-amz-meta-regpack-sha256", "not-a-hash"),
            // an upper-case hash
            Map.of(
                "x-amz-meta-regpack-sha256", sha.toUpperCase(Locale.ROOT),
                "x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z",
                "x-amz-meta-regpack-retain-until", "2045-03-01T00:00:00Z",
                "x-amz-meta-regpack-class", "bGFuZGluZw",
                "x-amz-meta-regpack-media-type", "dGV4dC9wbGFpbg"),
            // a write time that is no instant
            Map.of(
                "x-amz-meta-regpack-sha256", sha,
                "x-amz-meta-regpack-written-at", "yesterday",
                "x-amz-meta-regpack-retain-until", "2045-03-01T00:00:00Z",
                "x-amz-meta-regpack-class", "bGFuZGluZw",
                "x-amz-meta-regpack-media-type", "dGV4dC9wbGFpbg"),
            // a retain-until that is no instant
            Map.of(
                "x-amz-meta-regpack-sha256", sha,
                "x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z",
                "x-amz-meta-regpack-retain-until", "2045-03-01",
                "x-amz-meta-regpack-class", "bGFuZGluZw",
                "x-amz-meta-regpack-media-type", "dGV4dC9wbGFpbg"),
            // a class that is no base64url
            Map.of(
                "x-amz-meta-regpack-sha256", sha,
                "x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z",
                "x-amz-meta-regpack-retain-until", "2045-03-01T00:00:00Z",
                "x-amz-meta-regpack-class", "not base64!",
                "x-amz-meta-regpack-media-type", "dGV4dC9wbGFpbg"),
            // a media type that is not UTF-8 once decoded
            Map.of(
                "x-amz-meta-regpack-sha256", sha,
                "x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z",
                "x-amz-meta-regpack-retain-until", "2045-03-01T00:00:00Z",
                "x-amz-meta-regpack-class", "bGFuZGluZw",
                "x-amz-meta-regpack-media-type", "_w"),
            // the media type is missing
            Map.of(
                "x-amz-meta-regpack-sha256", sha,
                "x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z",
                "x-amz-meta-regpack-retain-until", "2045-03-01T00:00:00Z",
                "x-amz-meta-regpack-class", "bGFuZGluZw"));
    for (int i = 0; i < unreadable.size(); i++) {
      EvidenceKey key = EvidenceKey.of("bad", "m" + i);
      f.raw().putObject(f.bucket, key.path(), FakeWormFixture.utf8("hello"), unreadable.get(i));
      assertThatThrownBy(() -> f.store.get(key))
          .as("unreadable metadata, case %s", key.path())
          .isInstanceOf(EvidenceCorruptionException.class);
    }
  }

  // ---- the limits of the adapter (A10)

  @Test
  void aMediaTypeOver255CharactersAndAClassNameOver100AreRefusedBeforeAnyRequest() {
    int mark = f.mark();
    Retention retention = f.retention();
    assertThatThrownBy(
            () ->
                f.store.put(
                    new PutRequest(K, FakeWormFixture.utf8("x"), "m".repeat(256), retention)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                f.store.put(
                    new PutRequest(
                        K,
                        FakeWormFixture.utf8("x"),
                        "text/plain",
                        new Retention(
                            new RetentionClass("c".repeat(101)), retention.retainUntil()))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(f.kindsSince(mark)).isEmpty();
    // the caps themselves are accepted
    f.store.put(
        new PutRequest(
            K,
            FakeWormFixture.utf8("x"),
            "m".repeat(255),
            new Retention(new RetentionClass("c".repeat(100)), retention.retainUntil())));
    assertThat(f.store.get(K)).isPresent();
  }

  @Test
  void aMediaTypeOrClassNameWithAnUnpairedSurrogateIsRefusedBeforeAnyRequest() {
    int mark = f.mark();
    Retention retention = f.retention();
    for (String bad : List.of("\ud800", "a\udc00b", "x\ud83d", "\udc00\ud800")) {
      assertThatThrownBy(
              () -> f.store.put(new PutRequest(K, FakeWormFixture.utf8("x"), bad, retention)))
          .as("media type")
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  f.store.put(
                      new PutRequest(
                          K,
                          FakeWormFixture.utf8("x"),
                          "text/plain",
                          new Retention(new RetentionClass(bad), retention.retainUntil()))))
          .as("class name")
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(f.kindsSince(mark)).isEmpty();
    // a valid surrogate pair is an ordinary character
    f.store.put(new PutRequest(K, FakeWormFixture.utf8("x"), "text/😀", retention));
    assertThat(f.store.get(K).orElseThrow().mediaType()).isEqualTo("text/😀");
  }

  @Test
  void anEmptyMediaTypeAndAnEmptyClassNameRoundTrip() {
    Retention retention = new Retention(new RetentionClass(""), f.retention().retainUntil());
    assertThat(f.store.put(new PutRequest(K, FakeWormFixture.utf8("x"), "", retention)))
        .isEqualTo(PutOutcome.CREATED);
    StoredObject got = f.store.get(K).orElseThrow();
    assertThat(got.mediaType()).isEmpty();
    assertThat(got.retention()).isEqualTo(retention);
  }

  @Test
  void aRetainUntilNotAfterTheClockIsRefusedBeforeThePutOfANewKeyButNotForAnExistingOne() {
    Instant now = f.dataClock.now();
    for (Instant notAfter : List.of(now, now.minusSeconds(1), now.minus(Duration.ofDays(400)))) {
      int mark = f.mark();
      PutRequest request =
          new PutRequest(
              K,
              FakeWormFixture.utf8("hello"),
              "text/plain",
              new Retention(new RetentionClass("landing"), notAfter));
      assertThatThrownBy(() -> f.store.put(request))
          .as("retain-until %s", notAfter)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(f.kindsSince(mark)).as("retain-until %s", notAfter).doesNotContain("PUT");
    }
    assertThat(f.store.get(K)).isEmpty();
    // an instant after the clock is allowed by the adapter, even a close one
    Instant justAfter = now.plus(Duration.ofSeconds(5));
    f.store.put(
        new PutRequest(
            K,
            FakeWormFixture.utf8("hello"),
            "text/plain",
            new Retention(new RetentionClass("landing"), justAfter)));
    // for an existing key the check does not apply: same bytes succeed, other bytes alarm
    f.dataClock.advance(Duration.ofDays(1));
    PutRequest past =
        new PutRequest(
            K,
            FakeWormFixture.utf8("hello"),
            "text/plain",
            new Retention(new RetentionClass("landing"), now));
    assertThat(f.store.put(past)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    PutRequest pastOther =
        new PutRequest(
            K,
            FakeWormFixture.utf8("other"),
            "text/plain",
            new Retention(new RetentionClass("landing"), now));
    assertThatThrownBy(() -> f.store.put(pastOther)).isInstanceOf(EvidenceIntegrityException.class);
  }

  // ---- grade

  @Test
  void gradeThrowsUnavailableWhenTheServiceCannotBeReached() {
    f.transport.setDown(true);
    assertThatThrownBy(() -> f.store.grade())
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageMatching("S3 getBucketVersioning status=0 code=[A-Za-z0-9]+");
    f.transport.setDown(false);
    assertThat(f.store.grade()).isEqualTo(new Grade("P-WORM", "governance (unverified)"));
  }

  @Test
  void gradeDoesNotJudgeTheAnswerOfTheService() {
    // a bucket without versioning still grades: the judgement is the durability check's (T8b)
    String plain = "rp-plain00000a";
    f.raw().createBucket(plain, false);
    WormEvidenceStore store =
        WormEvidenceStore.open(
            WormConfig.governance(
                f.server().endpoint(),
                FakeWormBackend.REGION,
                plain,
                new Credentials(FakeWormBackend.KEY_ID, FakeWormBackend.SECRET),
                new Durability(true, 1)),
            f.dataClock,
            f.backend.signingClock());
    assertThat(store.grade()).isEqualTo(new Grade("P-WORM", "governance (unverified)"));
  }

  // ---- WF10: no secret, key id, host or signature in any message

  @Test
  void noMessageContainsTheSecretOrTheHost() {
    String plantedHost = "storage-host.internal";
    String plantedSignature = "Signature=0123456789abcdef0123456789abcdef";
    OutageTransport hostile =
        new OutageTransport(
            new JdkTransport(Duration.ofSeconds(10)),
            "connect to " + plantedHost + ":9000 refused, " + plantedSignature);
    hostile.setDown(true);
    WormEvidenceStore store = f.openOn(hostile);
    List<Throwable> thrown = new ArrayList<>();
    thrown.add(catchThrowable(() -> store.put(f.request(K, "hello"))));
    thrown.add(catchThrowable(() -> store.get(K)));
    thrown.add(catchThrowable(() -> store.list("landing/", Optional.empty(), 10)));
    thrown.add(catchThrowable(store::grade));
    // and with a service that answers with errors: the S3 message and the request id stay out too
    thrown.add(catchThrowable(() -> f.openOn(answering(500, "InternalError")).get(K)));
    for (Throwable t : thrown) {
      assertThat(t).isInstanceOf(EvidenceUnavailableException.class);
    }
    String host = f.server().endpoint().getHost();
    List<String> forbidden =
        List.of(
            plantedHost,
            plantedSignature,
            "Signature",
            FakeWormBackend.SECRET,
            FakeWormBackend.KEY_ID,
            host,
            String.valueOf(f.server().endpoint().getPort()),
            "marker-in-message",
            "marker-in-request-id",
            "marker-in-resource");
    for (Throwable t : thrown) {
      assertNothingForbidden(t, forbidden);
    }
  }

  private static Throwable catchThrowable(Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      return e;
    }
    throw new AssertionError("the call did not throw");
  }

  /** Walks the message, the cause and the suppressed exceptions, and the text of each. */
  private static void assertNothingForbidden(Throwable root, List<String> forbidden) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> todo = new ArrayDeque<>();
    todo.add(root);
    while (!todo.isEmpty()) {
      Throwable t = todo.poll();
      if (!seen.add(t)) {
        continue;
      }
      for (String text : forbidden) {
        assertThat(String.valueOf(t.getMessage())).doesNotContain(text);
        assertThat(t.toString()).doesNotContain(text);
      }
      if (t.getCause() != null) {
        todo.add(t.getCause());
      }
      todo.addAll(List.of(t.getSuppressed()));
    }
    // the exception of a transport is not chained at all
    assertThat(root.getCause()).isNull();
    assertThat(root.getSuppressed()).isEmpty();
  }

  // ---- interrupts and failures of the transport that are no IOException (D-W7)

  @Test
  void anInterruptedRequestIsUnavailableAndTheInterruptFlagIsRestored() {
    S3Transport interrupted =
        request -> {
          throw new InterruptedIOException("interrupted while talking to storage-host.internal");
        };
    WormEvidenceStore store = f.openOn(interrupted);
    Thread.interrupted();
    try {
      assertThatThrownBy(() -> store.get(K))
          .isInstanceOf(EvidenceUnavailableException.class)
          .hasMessage("S3 listObjectVersions status=0 code=InterruptedIOException");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aTimeoutIsUnavailable() {
    S3Transport slow =
        request -> {
          throw new HttpTimeoutException("timeout on storage-host.internal");
        };
    assertThatThrownBy(() -> f.openOn(slow).put(f.request(K, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=0 code=HttpTimeoutException");
  }

  @Test
  void aRuntimeFailureOfATransportIsUnavailableWithItsClassNameOnly() {
    S3Transport broken =
        request -> {
          throw new IllegalStateException("secret text about storage-host.internal");
        };
    assertThatThrownBy(() -> f.openOn(broken).get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=0 code=IllegalStateException")
        .hasNoCause();
  }

  @Test
  void aFailureWhoseClassHasNoSimpleNameIsReportedAsInvalidAndNeverQuoted() {
    // an anonymous class has the empty simple name: the code must not be built from it
    S3Transport anonymous =
        request -> {
          throw new RuntimeException("secret text about storage-host.internal") {};
        };
    assertThatThrownBy(() -> f.openOn(anonymous).get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 listObjectVersions status=0 code=invalid")
        .hasNoCause();
  }

  // ---- the stripe locks of D-W3: per key, per store object, interruptible

  /** A key that does not share a stripe (64 locks by key hash) with {@code key}. */
  private static EvidenceKey keyOnAnotherStripeThan(EvidenceKey key) {
    int stripe = Math.floorMod(key.path().hashCode(), 64);
    for (int i = 0; ; i++) {
      EvidenceKey candidate = EvidenceKey.of("landing", "s" + i);
      if (Math.floorMod(candidate.path().hashCode(), 64) != stripe) {
        return candidate;
      }
    }
  }

  private static Thread startDaemon(Runnable task) {
    Thread thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Waits (bounded, no sleep) until the thread is parked on a lock; a thread that ends fails. */
  private static void awaitParked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (thread.getState() != Thread.State.WAITING) {
      assertThat(thread.getState())
          .as("the thread must wait for the key lock")
          .isNotEqualTo(Thread.State.TERMINATED);
      assertThat(System.nanoTime()).as("the thread never parked").isLessThan(deadline);
      Thread.onSpinWait();
    }
  }

  private static <T> T within(Callable<T> call) throws Exception {
    FutureTask<T> task = new FutureTask<>(call);
    startDaemon(task);
    return task.get(10, TimeUnit.SECONDS);
  }

  @Test
  void aSecondPutOfTheSameKeyWaitsForTheStripeWhileOtherKeysAndOtherStoreObjectsGoOn()
      throws Exception {
    GatedTransport gate = new GatedTransport(new JdkTransport(Duration.ofSeconds(30)));
    WormEvidenceStore held = f.openOn(gate);
    FutureTask<PutOutcome> first = new FutureTask<>(() -> held.put(f.request(K, "same")));
    FutureTask<PutOutcome> second = new FutureTask<>(() -> held.put(f.request(K, "same")));
    try {
      startDaemon(first);
      gate.awaitHeld();
      assertThat(gate.sent()).isEqualTo(1);
      Thread waiting = startDaemon(second);
      awaitParked(waiting);
      // the second put of the key sent nothing: it is inside the lock of the first
      assertThat(gate.sent()).isEqualTo(1);

      // a key on another stripe of the same store object is not held up
      EvidenceKey other = keyOnAnotherStripeThan(K);
      assertThat(within(() -> held.put(f.request(other, "other")))).isEqualTo(PutOutcome.CREATED);
      // the same key through a second store object (a second process) is not held up either
      WormEvidenceStore process = f.secondStore();
      assertThat(within(() -> process.put(f.request(K, "same")))).isEqualTo(PutOutcome.CREATED);
    } finally {
      gate.release();
    }
    // both blocked puts go on and find what the other store object wrote
    assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(
            f.raw()
                .listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 10)
                .entries())
        .hasSize(1);
  }

  @Test
  void aThreadInterruptedWhileWaitingForTheKeyLockIsUnavailableAndKeepsItsInterruptFlag()
      throws Exception {
    GatedTransport gate = new GatedTransport(new JdkTransport(Duration.ofSeconds(30)));
    WormEvidenceStore held = f.openOn(gate);
    FutureTask<PutOutcome> first = new FutureTask<>(() -> held.put(f.request(K, "same")));
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean flagKept = new AtomicBoolean();
    try {
      startDaemon(first);
      gate.awaitHeld();
      Thread waiting =
          startDaemon(
              () -> {
                try {
                  held.put(f.request(K, "same"));
                } catch (Throwable e) {
                  thrown.set(e);
                }
                flagKept.set(Thread.currentThread().isInterrupted());
              });
      awaitParked(waiting);
      waiting.interrupt();
      waiting.join(TimeUnit.SECONDS.toMillis(30));
      assertThat(waiting.isAlive()).as("an interrupt ends the wait").isFalse();
      assertThat(thrown.get())
          .isInstanceOf(EvidenceUnavailableException.class)
          .hasMessage("interrupted while waiting for the key lock");
      assertThat(flagKept).isTrue();
      assertThat(gate.sent()).isEqualTo(1);
    } finally {
      gate.release();
    }
    assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(PutOutcome.CREATED);
  }
}
