package dev.regpack.evidence.s3.fake;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Client.VersionPage;
import dev.regpack.evidence.s3.client.S3Exception;
import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.client.SigV4.Param;
import dev.regpack.evidence.s3.fake.FakeS3Server.Fault;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What only the fake can show: that it checks signatures, that each fault switch and each hook does
 * what it says, and a few of its own properties. What the service does is stated once, in {@code
 * S3BehaviourContract}, and bound to the fake in {@code S3BehaviourFakeTest}.
 */
class FakeS3ServerTest {
  private static final String KEY_ID = "AKIAFAKETEST0000001";
  private static final String SECRET = "fake-test-secret-0123456789abcdef";
  private static final String REGION = "us-east-1";
  private static final String UNTIL = "2045-03-01T00:00:00Z";
  private static final String MODE_HEADER = "x-amz-object-lock-mode";
  private static final String UNTIL_HEADER = "x-amz-object-lock-retain-until-date";
  private static final byte[] NO_BODY = new byte[0];

  private final ManualClock clock = new ManualClock(Instant.parse("2040-03-01T00:00:00Z"));
  private FakeS3Server server;
  private S3Client client;

  @BeforeEach
  void start() {
    server = FakeS3Server.start(KEY_ID, SECRET);
    client = clientWith(new Credentials(KEY_ID, SECRET), new JdkTransport(Duration.ofSeconds(10)));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private S3Client clientWith(Credentials credentials, S3Transport transport) {
    return new S3Client(server.endpoint(), REGION, credentials, clock, transport);
  }

  private String bucket() {
    String bucket = "rp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    client.createBucket(bucket, true);
    return bucket;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<VersionEntry> exactKey(String bucket, String key) {
    List<VersionEntry> entries = new ArrayList<>();
    for (VersionEntry e :
        client.listVersions(bucket, key, Optional.empty(), Optional.empty(), 100).entries()) {
      if (e.key().equals(key)) {
        entries.add(e);
      }
    }
    return entries;
  }

  private static void assertRefused(Throwable thrown, int status, String code) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(status);
              assertThat(e.code()).isEqualTo(code);
            });
  }

  /** A transport that lets a test change a request after it was signed. */
  private record Tampering(S3Transport inner, UnaryOperator<S3Request> change)
      implements S3Transport {
    @Override
    public S3Response send(S3Request request) throws IOException {
      return inner.send(change.apply(request));
    }
  }

  private S3Client tamperedClient(UnaryOperator<S3Request> change) {
    return clientWith(
        new Credentials(KEY_ID, SECRET),
        new Tampering(new JdkTransport(Duration.ofSeconds(10)), change));
  }

  // ---- signatures

  @Test
  void aRequestSignedWithAnotherSecretIsRefusedWithSignatureDoesNotMatch() {
    String bucket = bucket();
    S3Client other =
        clientWith(
            new Credentials(KEY_ID, "another-secret-0123456789abcdef"),
            new JdkTransport(Duration.ofSeconds(10)));
    assertThatThrownBy(() -> other.putObject(bucket, "k", bytes("x"), Map.of()))
        .satisfies(e -> assertRefused(e, 403, "SignatureDoesNotMatch"));
    assertThat(client.listVersions(bucket, "", Optional.empty(), Optional.empty(), 10).entries())
        .isEmpty();
  }

  @Test
  void anUnknownAccessKeyIdIsRefused() {
    S3Client other =
        clientWith(
            new Credentials("AKIAUNKNOWN000000001", SECRET),
            new JdkTransport(Duration.ofSeconds(10)));
    assertThatThrownBy(() -> other.createBucket("rp-unknown00001", true))
        .satisfies(e -> assertRefused(e, 403, "InvalidAccessKeyId"));
    assertThatThrownBy(() -> client.versioningEnabled("rp-unknown00001"))
        .satisfies(e -> assertRefused(e, 404, "NoSuchBucket"));
  }

  @Test
  void aTamperedPathOrQueryIsRefused() {
    String bucket = bucket();
    S3Client pathTampered =
        tamperedClient(
            r ->
                new S3Request(
                    r.method(),
                    URI.create(r.uri().toString().replace("tamper-a", "tamper-b")),
                    r.headers(),
                    r.body()));
    assertThatThrownBy(() -> pathTampered.putObject(bucket, "tamper-a", bytes("x"), Map.of()))
        .satisfies(e -> assertRefused(e, 403, "SignatureDoesNotMatch"));
    S3Client queryTampered =
        tamperedClient(
            r ->
                new S3Request(
                    r.method(),
                    URI.create(r.uri().toString().replace("max-keys=5", "max-keys=6")),
                    r.headers(),
                    r.body()));
    assertThatThrownBy(
            () -> queryTampered.listVersions(bucket, "", Optional.empty(), Optional.empty(), 5))
        .satisfies(e -> assertRefused(e, 403, "SignatureDoesNotMatch"));
    assertThat(client.listVersions(bucket, "", Optional.empty(), Optional.empty(), 10).entries())
        .isEmpty();
  }

  @Test
  void aTamperedHeaderIsRefused() {
    String bucket = bucket();
    S3Client headerTampered =
        tamperedClient(
            r -> {
              Map<String, String> headers = new TreeMap<>(r.headers());
              headers.put("x-amz-meta-regpack-class", "changed");
              return new S3Request(r.method(), r.uri(), headers, r.body());
            });
    // the header is signed with another value: the signature no longer matches
    assertThatThrownBy(
            () ->
                headerTampered.putObject(
                    bucket, "tamper-h", bytes("x"), Map.of("x-amz-meta-regpack-class", "journal")))
        .satisfies(e -> assertRefused(e, 403, "SignatureDoesNotMatch"));
    assertThat(exactKey(bucket, "tamper-h")).isEmpty();
  }

  @Test
  void aHeaderThatWasSentButNotSignedIsRefused() {
    String bucket = bucket();
    S3Client unsigned =
        tamperedClient(
            r -> {
              Map<String, String> headers = new TreeMap<>(r.headers());
              headers.put("x-amz-meta-extra", "1");
              return new S3Request(r.method(), r.uri(), headers, r.body());
            });
    assertThatThrownBy(() -> unsigned.putObject(bucket, "unsigned", bytes("x"), Map.of()))
        .satisfies(e -> assertRefused(e, 403, "AccessDenied"));
    assertThat(exactKey(bucket, "unsigned")).isEmpty();
  }

  // ---- faults and hooks

  @Test
  void eachFaultSwitchDoesWhatItSays() {
    shortVersionPages();
    endlessTruncation();
    loseResponseAfterStore();
    serverError();
    dropConnection();
    corruptBytes();
    noVersionId();
    failTagging();
    olderVersionOnNextPut();
  }

  private void shortVersionPages() {
    String bucket = bucket();
    for (int i = 0; i < 5; i++) {
      client.putObject(bucket, "s/k" + i, bytes("x"), Map.of());
    }
    server.fault(Fault.SHORT_VERSION_PAGES, true);
    VersionPage page = client.listVersions(bucket, "s/", Optional.empty(), Optional.empty(), 1000);
    assertThat(page.entries()).hasSize(2);
    assertThat(page.truncated()).isTrue();
    assertThat(page.nextKeyMarker()).contains("s/k1");
    VersionPage next =
        client.listVersions(bucket, "s/", page.nextKeyMarker(), page.nextVersionIdMarker(), 1000);
    assertThat(next.entries()).extracting(VersionEntry::key).containsExactly("s/k2", "s/k3");
    server.fault(Fault.SHORT_VERSION_PAGES, false);
    assertThat(
            client.listVersions(bucket, "s/", Optional.empty(), Optional.empty(), 1000).entries())
        .hasSize(5);
  }

  private void endlessTruncation() {
    String bucket = bucket();
    client.putObject(bucket, "e/k", bytes("x"), Map.of());
    server.fault(Fault.ENDLESS_TRUNCATION, true);
    VersionPage first = client.listVersions(bucket, "e/", Optional.empty(), Optional.empty(), 10);
    assertThat(first.entries()).hasSize(1);
    assertThat(first.truncated()).isTrue();
    assertThat(first.nextKeyMarker()).contains("e/k");
    assertThat(first.nextVersionIdMarker()).isPresent();
    VersionPage second =
        client.listVersions(bucket, "e/", first.nextKeyMarker(), first.nextVersionIdMarker(), 10);
    assertThat(second.entries()).isEmpty();
    assertThat(second.truncated()).isTrue();
    assertThat(second.nextKeyMarker()).isEqualTo(first.nextKeyMarker());
    assertThat(second.nextVersionIdMarker()).isEqualTo(first.nextVersionIdMarker());
    server.fault(Fault.ENDLESS_TRUNCATION, false);
    assertThat(
            client.listVersions(bucket, "e/", Optional.empty(), Optional.empty(), 10).truncated())
        .isFalse();
  }

  private void loseResponseAfterStore() {
    String bucket = bucket();
    server.fault(Fault.LOSE_RESPONSE_AFTER_STORE, true);
    assertThatThrownBy(() -> client.putObject(bucket, "l/k", bytes("lost"), Map.of()))
        .satisfies(e -> assertRefused(e, 0, "IOException"));
    // a read is answered normally while the switch is on
    assertThat(exactKey(bucket, "l/k")).hasSize(1);
    server.fault(Fault.LOSE_RESPONSE_AFTER_STORE, false);
    String version = exactKey(bucket, "l/k").get(0).versionId();
    assertThat(client.get(bucket, "l/k", version).orElseThrow().body()).isEqualTo(bytes("lost"));
  }

  private void serverError() {
    String bucket = bucket();
    server.fault(Fault.SERVER_ERROR, true);
    assertThatThrownBy(() -> client.putObject(bucket, "x/k", bytes("x"), Map.of()))
        .satisfies(e -> assertRefused(e, 500, "InternalError"));
    assertThatThrownBy(
            () -> client.listVersions(bucket, "", Optional.empty(), Optional.empty(), 10))
        .satisfies(e -> assertRefused(e, 500, "InternalError"));
    server.fault(Fault.SERVER_ERROR, false);
    assertThat(exactKey(bucket, "x/k")).isEmpty();
  }

  private void dropConnection() {
    String bucket = bucket();
    server.fault(Fault.DROP_CONNECTION, true);
    assertThatThrownBy(() -> client.putObject(bucket, "d/k", bytes("x"), Map.of()))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isZero());
    assertThatThrownBy(
            () -> client.listVersions(bucket, "", Optional.empty(), Optional.empty(), 10))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isZero());
    server.fault(Fault.DROP_CONNECTION, false);
    assertThat(exactKey(bucket, "d/k")).isEmpty();
  }

  private void corruptBytes() {
    String bucket = bucket();
    String version = client.putObject(bucket, "c/k", bytes("payload"), Map.of()).orElseThrow();
    server.fault(Fault.CORRUPT_BYTES, true);
    byte[] corrupt = client.get(bucket, "c/k", version).orElseThrow().body();
    assertThat(corrupt).isNotEqualTo(bytes("payload"));
    assertThat(corrupt).hasSameSizeAs(bytes("payload"));
    server.fault(Fault.CORRUPT_BYTES, false);
    assertThat(client.get(bucket, "c/k", version).orElseThrow().body()).isEqualTo(bytes("payload"));
  }

  private void noVersionId() {
    String bucket = bucket();
    server.fault(Fault.NO_VERSION_ID, true);
    assertThat(client.putObject(bucket, "n/k", bytes("x"), Map.of())).isEmpty();
    server.fault(Fault.NO_VERSION_ID, false);
    assertThat(exactKey(bucket, "n/k")).hasSize(1);
    assertThat(client.putObject(bucket, "n/k", bytes("y"), Map.of())).isPresent();
  }

  private void failTagging() {
    String bucket = bucket();
    String version = client.putObject(bucket, "t/k", bytes("x"), Map.of()).orElseThrow();
    server.fault(Fault.FAIL_TAGGING, true);
    assertThatThrownBy(() -> client.putTags(bucket, "t/k", version, Map.of("a", "b")))
        .satisfies(e -> assertRefused(e, 500, "InternalError"));
    assertThat(client.tags(bucket, "t/k", version)).isEmpty();
    server.fault(Fault.FAIL_TAGGING, false);
    client.putTags(bucket, "t/k", version, Map.of("a", "b"));
    assertThat(client.tags(bucket, "t/k", version)).containsExactly(Map.entry("a", "b"));
  }

  private void olderVersionOnNextPut() {
    String bucket = bucket();
    byte[] own = bytes("own");
    byte[] foreign = bytes("foreign");
    Map<String, String> headers = new TreeMap<>();
    headers.put("content-type", "application/octet-stream");
    headers.put("x-amz-meta-regpack-sha256", sha256Hex(own));
    headers.put("x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z");
    headers.put(MODE_HEADER, "GOVERNANCE");
    headers.put(UNTIL_HEADER, UNTIL);
    server.addOlderVersionOnNextPut(bucket, "h/k", foreign);
    String ownVersion = client.putObject(bucket, "h/k", own, headers).orElseThrow();

    List<VersionEntry> entries = exactKey(bucket, "h/k");
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).versionId()).isEqualTo(ownVersion);
    assertThat(entries.get(0).latest()).isTrue();
    assertThat(entries.get(1).latest()).isFalse();
    S3Response oldest = client.get(bucket, "h/k", entries.get(1).versionId()).orElseThrow();
    assertThat(oldest.body()).isEqualTo(foreign);
    assertThat(oldest.headers())
        .containsEntry("x-amz-meta-regpack-sha256", sha256Hex(foreign))
        .containsEntry("x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z")
        .containsEntry(MODE_HEADER, "GOVERNANCE")
        .containsEntry(UNTIL_HEADER, UNTIL);
    S3Response newest = client.get(bucket, "h/k", ownVersion).orElseThrow();
    assertThat(newest.body()).isEqualTo(own);
    assertThat(newest.headers()).containsEntry("x-amz-meta-regpack-sha256", sha256Hex(own));

    // the hook is used up by the PUT that met it
    client.putObject(bucket, "h/k", bytes("again"), headers);
    assertThat(exactKey(bucket, "h/k")).hasSize(3);
  }

  // ---- hooks of the test

  @Test
  void aVersionAddedBehindTheBackIsTheNewestAndCarriesNoMetadataOrLock() {
    String bucket = bucket();
    String first = client.putObject(bucket, "b/k", bytes("first"), Map.of()).orElseThrow();
    server.addVersionBehindTheBack(bucket, "b/k", bytes("second"));
    List<VersionEntry> entries = exactKey(bucket, "b/k");
    assertThat(entries).hasSize(2);
    assertThat(entries.get(1).versionId()).isEqualTo(first);
    S3Response newest =
        client.send("GET", bucket, Optional.of("b/k"), List.of(), Map.of(), NO_BODY);
    assertThat(newest.body()).isEqualTo(bytes("second"));
    assertThat(newest.headers()).doesNotContainKey(MODE_HEADER);
    assertThat(newest.headers().keySet()).noneMatch(name -> name.startsWith("x-amz-meta-"));
  }

  @Test
  void aDeleteMarkerAddedBehindTheBackHidesTheKeyAndStaysInTheList() {
    String bucket = bucket();
    client.putObject(bucket, "m/k", bytes("one"), Map.of());
    server.addDeleteMarker(bucket, "m/k");
    assertThatThrownBy(
            () -> client.send("GET", bucket, Optional.of("m/k"), List.of(), Map.of(), NO_BODY))
        .satisfies(e -> assertRefused(e, 404, "NoSuchKey"));
    List<VersionEntry> entries = exactKey(bucket, "m/k");
    assertThat(entries).extracting(VersionEntry::deleteMarker).containsExactly(true, false);
    assertThat(entries).extracting(VersionEntry::latest).containsExactly(true, false);
  }

  @Test
  void shortenLockShortensTheOldestDataVersionOnly() {
    String bucket = bucket();
    Map<String, String> locked = new TreeMap<>();
    locked.put(MODE_HEADER, "GOVERNANCE");
    locked.put(UNTIL_HEADER, UNTIL);
    String oldest = client.putObject(bucket, "s/k", bytes("one"), locked).orElseThrow();
    String newest = client.putObject(bucket, "s/k", bytes("two"), locked).orElseThrow();
    server.shortenLock(bucket, "s/k", Instant.parse("2044-03-01T00:00:00Z"));
    assertThat(client.head(bucket, "s/k", oldest).orElseThrow().headers())
        .containsEntry(UNTIL_HEADER, "2044-03-01T00:00:00Z");
    assertThat(client.head(bucket, "s/k", newest).orElseThrow().headers())
        .containsEntry(UNTIL_HEADER, UNTIL);
  }

  @Test
  void rankNextPutBelowExistingStoresThePutAsTheOldestVersionOnce() {
    String bucket = bucket();
    String first = client.putObject(bucket, "r/k", bytes("first"), Map.of()).orElseThrow();
    server.rankNextPutBelowExisting(bucket, "r/k");
    String ranked = client.putObject(bucket, "r/k", bytes("ranked"), Map.of()).orElseThrow();
    // the PUT finished later and is listed as the oldest; the version that was there stays newest
    assertThat(exactKey(bucket, "r/k"))
        .extracting(VersionEntry::versionId)
        .containsExactly(first, ranked);
    assertThat(exactKey(bucket, "r/k"))
        .extracting(VersionEntry::latest)
        .containsExactly(true, false);
    // the hook is used up by the PUT that met it, and applies to that key only
    String other = client.putObject(bucket, "r/other", bytes("o"), Map.of()).orElseThrow();
    String third = client.putObject(bucket, "r/k", bytes("third"), Map.of()).orElseThrow();
    assertThat(exactKey(bucket, "r/k"))
        .extracting(VersionEntry::versionId)
        .containsExactly(third, first, ranked);
    assertThat(exactKey(bucket, "r/other"))
        .extracting(VersionEntry::versionId)
        .containsExactly(other);
  }

  // ---- properties of the fake

  @Test
  void aVersionListHidesNothingAndKeepsMarkersInOrder() {
    String bucket = bucket();
    String a1 = client.putObject(bucket, "v/a", bytes("a1"), Map.of()).orElseThrow();
    String a2 = client.putObject(bucket, "v/a", bytes("a2"), Map.of()).orElseThrow();
    server.addDeleteMarker(bucket, "v/a");
    String b1 = client.putObject(bucket, "v/b", bytes("b1"), Map.of()).orElseThrow();
    server.addDeleteMarker(bucket, "v/c");

    List<VersionEntry> entries =
        client.listVersions(bucket, "v/", Optional.empty(), Optional.empty(), 100).entries();
    assertThat(entries)
        .extracting(VersionEntry::key)
        .containsExactly("v/a", "v/a", "v/a", "v/b", "v/c");
    assertThat(entries)
        .extracting(VersionEntry::deleteMarker)
        .containsExactly(true, false, false, false, true);
    assertThat(entries)
        .extracting(VersionEntry::latest)
        .containsExactly(true, false, false, true, true);
    assertThat(entries.get(1).versionId()).isEqualTo(a2);
    assertThat(entries.get(2).versionId()).isEqualTo(a1);
    assertThat(entries.get(3).versionId()).isEqualTo(b1);
  }

  @Test
  void requestLinesAreRecordedInOrderAsTheyWereSent() {
    String bucket = bucket();
    client.putObject(bucket, "a+b", bytes("x"), Map.of());
    client.listVersions(bucket, "a", Optional.empty(), Optional.empty(), 10);
    List<String> lines = server.requestLines();
    assertThat(lines).hasSize(3);
    assertThat(lines.get(0)).isEqualTo("PUT /" + bucket);
    assertThat(lines.get(1)).isEqualTo("PUT /" + bucket + "/a%2Bb");
    assertThat(lines.get(2)).startsWith("GET /" + bucket + "?").contains("versions");
  }

  @Test
  void aRequestIsRecordedWhetherOrNotItIsAccepted() {
    S3Client other =
        clientWith(
            new Credentials(KEY_ID, "another-secret-0123456789abcdef"),
            new JdkTransport(Duration.ofSeconds(10)));
    assertThatThrownBy(() -> other.createBucket("rp-refused00001", true))
        .isInstanceOf(S3Exception.class);
    assertThat(server.requestLines()).containsExactly("PUT /rp-refused00001");
  }

  @Test
  void aBucketThatDoesNotExistIsNoSuchBucketForEveryCall() {
    String missing = "rp-missing00001";
    assertThatThrownBy(() -> client.versioningEnabled(missing))
        .satisfies(e -> assertRefused(e, 404, "NoSuchBucket"));
    assertThatThrownBy(() -> client.objectLockEnabled(missing))
        .satisfies(e -> assertRefused(e, 404, "NoSuchBucket"));
    assertThatThrownBy(() -> client.putObject(missing, "k", bytes("x"), Map.of()))
        .satisfies(e -> assertRefused(e, 404, "NoSuchBucket"));
    assertThatThrownBy(() -> client.get(missing, "k", "v1"))
        .satisfies(e -> assertRefused(e, 404, "NoSuchBucket"));
    assertThatThrownBy(
            () -> client.listVersions(missing, "", Optional.empty(), Optional.empty(), 10))
        .satisfies(e -> assertRefused(e, 404, "NoSuchBucket"));
  }

  @Test
  void aBucketWithoutLockHasNoLockConfigurationAndRefusesLockHeaders() {
    String plain = "rp-nolock000001";
    client.createBucket(plain, false);
    assertThat(client.objectLockEnabled(plain)).isFalse();
    assertThat(client.versioningEnabled(plain)).isFalse();
    Map<String, String> locked = new TreeMap<>();
    locked.put(MODE_HEADER, "GOVERNANCE");
    locked.put(UNTIL_HEADER, UNTIL);
    assertThatThrownBy(() -> client.putObject(plain, "k", bytes("x"), locked))
        .satisfies(e -> assertRefused(e, 400, "InvalidRequest"));
    // versioning can be switched on by a request, as an operator would
    byte[] body =
        ("<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Status>Enabled</Status></VersioningConfiguration>")
            .getBytes(UTF_8);
    client.send(
        "PUT", plain, Optional.empty(), List.of(new Param("versioning", "")), Map.of(), body);
    assertThat(client.versioningEnabled(plain)).isTrue();
    assertThat(client.objectLockEnabled(plain)).isFalse();
  }

  @Test
  void anOperationTheFakeDoesNotKnowIsNotImplemented() {
    String bucket = bucket();
    assertThatThrownBy(
            () ->
                client.send(
                    "GET",
                    bucket,
                    Optional.empty(),
                    List.of(new Param("list-type", "2")),
                    Map.of(),
                    NO_BODY))
        .satisfies(e -> assertRefused(e, 501, "NotImplemented"));
  }

  @Test
  void theFakeListensOnALoopbackAddressOnly() {
    URI endpoint = server.endpoint();
    assertThat(endpoint.getScheme()).isEqualTo("http");
    assertThat(endpoint.getPort()).isPositive();
    String host = endpoint.getHost();
    String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    assertThat(InetAddress.ofLiteral(literal).isLoopbackAddress()).isTrue();
  }
}
