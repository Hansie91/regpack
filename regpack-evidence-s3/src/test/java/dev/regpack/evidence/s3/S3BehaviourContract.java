package dev.regpack.evidence.s3;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Client.VersionPage;
import dev.regpack.evidence.s3.client.S3Exception;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.SigV4.Param;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * What the adapter relies on from an S3-compatible object-lock service, stated once as recorded in
 * {@code docs/evidence/p3b2-probe.md} (the probe items are named at each method). It is bound to
 * the fake service here ({@code S3BehaviourFakeTest}) and to the real service in the job {@code
 * evidence-worm} (T10), so a fake that drifts from the service fails at the checkpoint that runs
 * both. Every method makes its own buckets and never deletes a version: the one DELETE without a
 * version id adds a delete marker, the DELETE of a locked version is expected to be refused.
 *
 * <p>An implementer gives a raw client (test credentials, a signing clock the service accepts) and
 * a supplier of new buckets that have object lock on.
 */
interface S3BehaviourContract {
  String GOVERNANCE = "GOVERNANCE";
  String COMPLIANCE = "COMPLIANCE";
  String UNTIL = "2045-03-01T00:00:00Z";
  String LATER = "2046-03-01T00:00:00Z";
  String SHORTER = "2044-03-01T00:00:00Z";
  String MODE_HEADER = "x-amz-object-lock-mode";
  String UNTIL_HEADER = "x-amz-object-lock-retain-until-date";
  String HOLD_HEADER = "x-amz-object-lock-legal-hold";
  String META_PREFIX = "x-amz-meta-";
  String XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/";
  byte[] NO_BODY = new byte[0];

  /** A raw client of the service, with the test credentials. */
  S3Client client();

  /** A new bucket with object lock on (a new one on every call). */
  String newBucket();

  // ---- P1

  /** P1: a bucket made with object lock is versioned and locked. */
  @Test
  default void aLockBucketIsVersionedAndLocked() {
    String bucket = newBucket();
    assertThat(client().versioningEnabled(bucket)).isTrue();
    assertThat(client().objectLockEnabled(bucket)).isTrue();
  }

  // ---- P7

  /** P7: a second PUT to a key makes a newer version, and the version list names it first. */
  @Test
  default void aSecondPutCreatesANewerVersionListedFirst() {
    String bucket = newBucket();
    String one = put(bucket, "sp/k", "one", lock(GOVERNANCE, UNTIL));
    String two = put(bucket, "sp/k", "two", lock(GOVERNANCE, UNTIL));
    assertThat(one).isNotEqualTo(two);
    List<VersionEntry> entries = exactKey(bucket, "sp/k");
    assertThat(entries).extracting(VersionEntry::versionId).containsExactly(two, one);
    assertThat(entries).extracting(VersionEntry::latest).containsExactly(true, false);
    assertThat(entries).extracting(VersionEntry::deleteMarker).containsExactly(false, false);
    assertThat(getCurrent(bucket, "sp/k").body()).isEqualTo(bytes("two"));
    assertThat(client().get(bucket, "sp/k", one).orElseThrow().body()).isEqualTo(bytes("one"));
    assertThat(client().get(bucket, "sp/k", two).orElseThrow().body()).isEqualTo(bytes("two"));
  }

  /**
   * P7: a DELETE without a version id adds a delete marker (204, marker header) even under lock; a
   * GET of the key then answers 404 {@code NoSuchKey}; the versions stay readable by id; and the
   * marker is the first entry of the key's list and the latest one (the flag {@code mkFirst} of the
   * probe record, CP-1), which is where the adapter looks for it.
   */
  @Test
  default void aDeleteWithoutAVersionAddsAMarkerEvenUnderLock() {
    String bucket = newBucket();
    String one = put(bucket, "dm/k", "one", lock(GOVERNANCE, UNTIL));
    String two = put(bucket, "dm/k", "two", lock(GOVERNANCE, UNTIL));
    S3Response deleted =
        client().send("DELETE", bucket, Optional.of("dm/k"), List.of(), Map.of(), NO_BODY);
    assertThat(deleted.status()).isEqualTo(204);
    assertThat(deleted.headers()).containsEntry("x-amz-delete-marker", "true");
    assertThatThrownBy(() -> getCurrent(bucket, "dm/k"))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(404);
              assertThat(e.code()).isEqualTo("NoSuchKey");
            });
    List<VersionEntry> entries = exactKey(bucket, "dm/k");
    assertThat(entries).hasSize(3);
    assertThat(entries.stream().filter(VersionEntry::deleteMarker)).hasSize(1);
    assertThat(entries.get(0).deleteMarker()).as("the marker is the first entry").isTrue();
    assertThat(entries.get(0).latest()).as("the marker is the latest entry").isTrue();
    assertThat(entries.stream().filter(e -> !e.deleteMarker()))
        .extracting(VersionEntry::versionId)
        .containsExactlyInAnyOrder(one, two);
    assertThat(client().get(bucket, "dm/k", one).orElseThrow().body()).isEqualTo(bytes("one"));
    assertThat(client().get(bucket, "dm/k", two).orElseThrow().body()).isEqualTo(bytes("two"));
  }

  /** P7: the DELETE of a locked version by its id is refused (403 {@code AccessDenied}). */
  @Test
  default void aLockedVersionCannotBeDeletedByIdWithoutBypass() {
    String bucket = newBucket();
    String version = put(bucket, "ld/k", "locked", lock(GOVERNANCE, UNTIL));
    assertThatThrownBy(
            () ->
                client()
                    .send(
                        "DELETE",
                        bucket,
                        Optional.of("ld/k"),
                        List.of(new Param("versionId", version)),
                        Map.of(),
                        NO_BODY))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(403);
              assertThat(e.code()).isEqualTo("AccessDenied");
            });
    assertThat(client().get(bucket, "ld/k", version).orElseThrow().body())
        .isEqualTo(bytes("locked"));
    assertThat(exactKey(bucket, "ld/k")).hasSize(1);
  }

  // ---- P2, P5

  /**
   * P5: a governance lock can be extended; shortening it is refused (403 {@code AccessDenied})
   * unless the bypass header is sent; a compliance lock cannot be shortened even with it. The
   * bypass header is sent here, by the raw client of a test, and nowhere in the main code.
   */
  @Test
  default void retentionCanBeExtendedButNotShortenedWithoutBypass() {
    String bucket = newBucket();
    String version = put(bucket, "re/g", "g", lock(GOVERNANCE, UNTIL));
    client().putRetention(bucket, "re/g", version, GOVERNANCE, LATER);
    assertThat(retainUntil(bucket, "re/g", version)).isEqualTo(Instant.parse(LATER));

    assertThatThrownBy(() -> client().putRetention(bucket, "re/g", version, GOVERNANCE, SHORTER))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(403);
              assertThat(e.code()).isEqualTo("AccessDenied");
            });
    assertThat(retainUntil(bucket, "re/g", version)).isEqualTo(Instant.parse(LATER));

    S3Response bypassed = retentionRaw(bucket, "re/g", version, GOVERNANCE, SHORTER, true);
    assertThat(bypassed.status()).isEqualTo(200);
    assertThat(retainUntil(bucket, "re/g", version)).isEqualTo(Instant.parse(SHORTER));

    // a compliance lock lives in a bucket of its own
    String complianceBucket = newBucket();
    String locked = put(complianceBucket, "re/c", "c", lock(COMPLIANCE, UNTIL));
    assertThatThrownBy(
            () -> retentionRaw(complianceBucket, "re/c", locked, COMPLIANCE, SHORTER, true))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(403);
              assertThat(e.code()).isEqualTo("AccessDenied");
            });
    assertThat(retainUntil(complianceBucket, "re/c", locked)).isEqualTo(Instant.parse(UNTIL));
  }

  /**
   * P2, P12: the retain-until text of a whole second comes back as it was sent; the last second of
   * year 9999 is accepted; a past date is refused (400 {@code InvalidArgument}) and nothing is
   * stored. The contract states the whole-second round trip that the adapter relies on, not that
   * the service cuts finer digits.
   */
  @Test
  default void theRetainUntilResolutionIsWholeSeconds() {
    String bucket = newBucket();
    String version = put(bucket, "rs/s", "s", lock(GOVERNANCE, UNTIL));
    S3Response head = client().head(bucket, "rs/s", version).orElseThrow();
    assertThat(head.headers()).containsEntry(MODE_HEADER, GOVERNANCE);
    assertThat(head.headers()).containsEntry(UNTIL_HEADER, UNTIL);
    assertThat(head.headers()).containsKey("x-amz-version-id");

    String last = "9999-12-31T23:59:59Z";
    String far = put(bucket, "rs/far", "far", lock(GOVERNANCE, last));
    assertThat(retainUntil(bucket, "rs/far", far)).isEqualTo(Instant.parse(last));

    assertThatThrownBy(() -> put(bucket, "rs/past", "p", lock(GOVERNANCE, "2001-01-01T00:00:00Z")))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(400);
              assertThat(e.code()).isEqualTo("InvalidArgument");
            });
    assertThat(exactKey(bucket, "rs/past")).isEmpty();
  }

  // ---- P6

  /**
   * P6: a legal hold can be set and cleared on a version; the header is absent before any hold was
   * set and reads {@code ON} or {@code OFF} on HEAD and GET afterwards.
   */
  @Test
  default void aLegalHoldCanBeSetAndCleared() {
    String bucket = newBucket();
    String version = put(bucket, "lh/k", "k", Map.of());
    assertThat(client().head(bucket, "lh/k", version).orElseThrow().headers())
        .doesNotContainKey(HOLD_HEADER);
    client().putLegalHold(bucket, "lh/k", version, true);
    assertThat(client().head(bucket, "lh/k", version).orElseThrow().headers())
        .containsEntry(HOLD_HEADER, "ON");
    assertThat(client().get(bucket, "lh/k", version).orElseThrow().headers())
        .containsEntry(HOLD_HEADER, "ON");
    client().putLegalHold(bucket, "lh/k", version, false);
    assertThat(client().head(bucket, "lh/k", version).orElseThrow().headers())
        .containsEntry(HOLD_HEADER, "OFF");
  }

  // ---- P4

  /**
   * P4: tags can be written on a locked version, come back verbatim (with the microsecond text of
   * the adapter), are counted in {@code x-amz-tagging-count} on GET, and can be replaced.
   */
  @Test
  default void tagsOfALockedVersionCanBeChangedAndKeepTheirValue() {
    String bucket = newBucket();
    String version = put(bucket, "tg/k", "k", lock(GOVERNANCE, UNTIL));
    String first = "2040-03-01T00:00:00.123456Z";
    client().putTags(bucket, "tg/k", version, Map.of("regpack-retain-until", first));
    assertThat(client().tags(bucket, "tg/k", version))
        .containsExactly(Map.entry("regpack-retain-until", first));
    S3Response got = client().get(bucket, "tg/k", version).orElseThrow();
    assertThat(got.headers()).containsEntry("x-amz-tagging-count", "1");

    String second = "2046-03-01T00:00:00.654321Z";
    client().putTags(bucket, "tg/k", version, Map.of("regpack-retain-until", second));
    assertThat(client().tags(bucket, "tg/k", version))
        .containsExactly(Map.entry("regpack-retain-until", second));
    assertThat(client().get(bucket, "tg/k", version).orElseThrow().body()).isEqualTo(bytes("k"));
  }

  // ---- P3

  /**
   * P3: the worst case of the adapter's metadata (five names, 1,676 bytes with the prefixes) comes
   * back on HEAD and GET exactly as sent, under lower-case names.
   */
  @Test
  default void userMetadataRoundTripsAsSent() {
    String bucket = newBucket();
    Map<String, String> meta = new TreeMap<>();
    meta.put("regpack-sha256", sha256Hex(bytes("m")));
    meta.put("regpack-written-at", "2040-03-01T00:00:00.123456Z");
    meta.put("regpack-retain-until", "2045-03-01T00:00:00.123456Z");
    meta.put("regpack-class", base64Url("€".repeat(100)));
    meta.put("regpack-media-type", base64Url("€".repeat(255)));
    Map<String, String> headers = new TreeMap<>();
    int size = 0;
    for (Map.Entry<String, String> e : meta.entrySet()) {
      headers.put(META_PREFIX + e.getKey(), e.getValue());
      size += (META_PREFIX + e.getKey()).length() + e.getValue().length();
    }
    assertThat(size).isEqualTo(1676);
    String version = put(bucket, "um/k", "m", headers);
    S3Response head = client().head(bucket, "um/k", version).orElseThrow();
    S3Response get = client().get(bucket, "um/k", version).orElseThrow();
    for (Map.Entry<String, String> e : meta.entrySet()) {
      assertThat(head.headers()).containsEntry(META_PREFIX + e.getKey(), e.getValue());
      assertThat(get.headers()).containsEntry(META_PREFIX + e.getKey(), e.getValue());
    }
  }

  // ---- P8

  /**
   * P8: the version list pages up to max-keys, in ascending key order, a version list that ends a
   * page inside a key continues with the markers it returned, and a key marker without a version-id
   * marker starts strictly after that key.
   */
  @Test
  default void listVersionsFollowsTheMarkersAndKeyMarker() {
    String bucket = newBucket();
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      String key = String.format(Locale.ROOT, "lv/k%02d", i);
      keys.add(key);
      put(bucket, key, "v" + i, Map.of());
    }
    put(bucket, "lv/k06", "again", Map.of());

    List<VersionEntry> all = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    Optional<String> keyMarker = Optional.empty();
    Optional<String> versionMarker = Optional.empty();
    for (int page = 0; page < 10; page++) {
      VersionPage p = client().listVersions(bucket, "lv/", keyMarker, versionMarker, 7);
      all.addAll(p.entries());
      sizes.add(p.entries().size());
      if (!p.truncated()) {
        break;
      }
      assertThat(p.nextKeyMarker()).isPresent();
      keyMarker = p.nextKeyMarker();
      versionMarker = p.nextVersionIdMarker();
    }
    assertThat(sizes).containsExactly(7, 7, 7, 5);
    assertThat(all).hasSize(26);
    Set<String> distinct = new HashSet<>();
    String previous = "";
    for (VersionEntry e : all) {
      distinct.add(e.key() + "@" + e.versionId());
      assertThat(e.key().compareTo(previous)).isGreaterThanOrEqualTo(0);
      previous = e.key();
    }
    assertThat(distinct).hasSize(26);
    assertThat(all.stream().map(VersionEntry::key).distinct().toList()).isEqualTo(keys);
    assertThat(all.stream().filter(e -> e.key().equals("lv/k06"))).hasSize(2);

    VersionPage after =
        client().listVersions(bucket, "lv/", Optional.of("lv/k09"), Optional.empty(), 7);
    assertThat(after.entries().get(0).key()).isEqualTo("lv/k10");
  }

  /**
   * P8: a key hidden by a delete marker stays in the version listing: marker and version, the
   * marker first and latest.
   */
  @Test
  default void aKeyHiddenByAMarkerStaysInTheVersionListing() {
    String bucket = newBucket();
    String version = put(bucket, "hk/k", "k", Map.of());
    S3Response deleted =
        client().send("DELETE", bucket, Optional.of("hk/k"), List.of(), Map.of(), NO_BODY);
    assertThat(deleted.status()).isEqualTo(204);
    List<VersionEntry> entries = exactKey(bucket, "hk/k");
    assertThat(entries).hasSize(2);
    assertThat(entries.stream().filter(VersionEntry::deleteMarker)).hasSize(1);
    assertThat(entries.get(0).deleteMarker()).as("the marker is the first entry").isTrue();
    assertThat(entries.get(0).latest()).as("the marker is the latest entry").isTrue();
    assertThat(entries.stream().filter(e -> !e.deleteMarker()))
        .extracting(VersionEntry::versionId)
        .containsExactly(version);
  }

  // ---- P9

  /** P9: keys with plus, equals, comma and dollar and a key of 512 characters round-trip. */
  @Test
  default void keysWithPlusEqualsAndCommaRoundTripThroughThePath() {
    String bucket = newBucket();
    String odd = "a+b=c,d$e";
    String longKey = "p9/" + "x".repeat(509);
    put(bucket, odd, "odd", Map.of());
    put(bucket, longKey, "long", Map.of());
    assertThat(getCurrent(bucket, odd).body()).isEqualTo(bytes("odd"));
    assertThat(getCurrent(bucket, longKey).body()).isEqualTo(bytes("long"));
    List<String> listed =
        client()
            .listVersions(bucket, "a", Optional.empty(), Optional.empty(), 10)
            .entries()
            .stream()
            .map(VersionEntry::key)
            .toList();
    assertThat(listed).containsExactly(odd);
    assertThat(exactKey(bucket, longKey)).hasSize(1);
  }

  // ---- P10

  /**
   * P10: a PUT whose {@code x-amz-content-sha256} is not the hash of the body is refused (400
   * {@code XAmzContentSHA256Mismatch}), as is one whose Content-MD5 is not the MD5 of the body (400
   * {@code BadDigest}); nothing is stored.
   */
  @Test
  default void aWrongPayloadHashIsRefused() {
    String bucket = newBucket();
    byte[] payload = bytes("payload");
    byte[] other = bytes("another payload");
    Map<String, String> wrongSha = new TreeMap<>();
    wrongSha.put("content-md5", md5Base64(payload));
    wrongSha.put("x-amz-content-sha256", sha256Hex(other));
    assertThatThrownBy(
            () -> client().send("PUT", bucket, Optional.of("wp/sha"), List.of(), wrongSha, payload))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(400);
              assertThat(e.code()).isEqualTo("XAmzContentSHA256Mismatch");
            });
    Map<String, String> wrongMd5 = Map.of("content-md5", md5Base64(other));
    assertThatThrownBy(
            () -> client().send("PUT", bucket, Optional.of("wp/md5"), List.of(), wrongMd5, payload))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(400);
              assertThat(e.code()).isEqualTo("BadDigest");
            });
    assertThat(
            client().listVersions(bucket, "wp/", Optional.empty(), Optional.empty(), 10).entries())
        .isEmpty();
  }

  // ---- helpers

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  private static Map<String, String> lock(String mode, String until) {
    Map<String, String> headers = new TreeMap<>();
    headers.put("content-type", "application/octet-stream");
    headers.put(MODE_HEADER, mode);
    headers.put(UNTIL_HEADER, until);
    return headers;
  }

  /** Puts text and returns the version id the service gave. */
  private String put(String bucket, String key, String text, Map<String, String> headers) {
    return client()
        .putObject(bucket, key, bytes(text), headers)
        .orElseThrow(() -> new AssertionError("the service gave no version id"));
  }

  /** The entries of exactly this key, in the order the service lists them (one page of 100). */
  private List<VersionEntry> exactKey(String bucket, String key) {
    List<VersionEntry> entries = new ArrayList<>();
    VersionPage page = client().listVersions(bucket, key, Optional.empty(), Optional.empty(), 100);
    for (VersionEntry e : page.entries()) {
      if (e.key().equals(key)) {
        entries.add(e);
      }
    }
    return entries;
  }

  /** GET of the key without a version id: the newest version (an S3Exception when hidden). */
  private S3Response getCurrent(String bucket, String key) {
    return client().send("GET", bucket, Optional.of(key), List.of(), Map.of(), NO_BODY);
  }

  private Instant retainUntil(String bucket, String key, String version) {
    S3Response head = client().head(bucket, key, version).orElseThrow();
    return Instant.parse(head.headers().get(UNTIL_HEADER));
  }

  /** PutObjectRetention with a Content-MD5 and, if asked, the governance-bypass header. */
  private S3Response retentionRaw(
      String bucket, String key, String version, String mode, String until, boolean bypass) {
    byte[] body =
        ("<Retention xmlns=\""
                + XMLNS
                + "\"><Mode>"
                + mode
                + "</Mode><RetainUntilDate>"
                + until
                + "</RetainUntilDate></Retention>")
            .getBytes(UTF_8);
    Map<String, String> headers = new TreeMap<>();
    headers.put("content-md5", md5Base64(body));
    if (bypass) {
      headers.put("x-amz-bypass-" + "governance-retention", "true");
    }
    return client()
        .send(
            "PUT",
            bucket,
            Optional.of(key),
            List.of(new Param("retention", ""), new Param("versionId", version)),
            headers,
            body);
  }

  private static String base64Url(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(text));
  }

  private static String md5Base64(byte[] body) {
    return Base64.getEncoder().encodeToString(digest("MD5", body));
  }

  private static String sha256Hex(byte[] body) {
    return HexFormat.of().formatHex(digest("SHA-256", body));
  }

  private static byte[] digest(String algorithm, byte[] body) {
    try {
      return MessageDigest.getInstance(algorithm).digest(body);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
  }
}
