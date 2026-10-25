package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.evidence.s3.fake.FakeS3Server.Fault;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceRetentionException;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Retention and legal hold of the P-WORM adapter (T8b; D-W5, D-W6; WF6): the lock date is the
 * retain-until rounded up to whole seconds and clamped, an extension locks first and tags second, a
 * lock is never shortened by the adapter and a lock shorter than the record is an alarm, and a hold
 * is a separate flag on the oldest data version. Faults come from the fake service, which is why
 * these methods live in a {@code *Test} class.
 */
class WormRetentionTest {
  private static final EvidenceKey K = EvidenceKey.of("landing", "k1");
  private static final String TAG = "regpack-retain-until";
  private static final String LOCK_DATE = "x-amz-object-lock-retain-until-date";
  private static final String LOCK_MODE = "x-amz-object-lock-mode";
  private static final String HOLD = "x-amz-object-lock-legal-hold";
  private static final Instant Y2045 = Instant.parse("2045-03-01T00:00:00Z");
  private static final Instant Y2046 = Instant.parse("2046-03-01T00:00:00Z");

  private FakeWormFixture f;

  @BeforeEach
  void start() {
    f = new FakeWormFixture();
  }

  @AfterEach
  void stop() {
    f.close();
  }

  // ---- helpers

  private static byte[] utf8(String text) {
    return FakeWormFixture.utf8(text);
  }

  private PutOutcome putAt(EvidenceKey key, String text, Instant retainUntil) {
    return f.store.put(
        new PutRequest(
            key,
            utf8(text),
            "text/plain",
            new Retention(new RetentionClass("landing"), retainUntil)));
  }

  private List<VersionEntry> entries(EvidenceKey key) {
    return f.raw()
        .listVersions(f.bucket, key.path(), Optional.empty(), Optional.empty(), 100)
        .entries();
  }

  /** The service lists newest first, so the oldest version is the last entry. */
  private String oldestVersionId(EvidenceKey key) {
    List<VersionEntry> all = entries(key);
    return all.get(all.size() - 1).versionId();
  }

  private Map<String, String> headOfOldest(EvidenceKey key) {
    return f.raw().head(f.bucket, key.path(), oldestVersionId(key)).orElseThrow().headers();
  }

  private Instant lockOf(EvidenceKey key) {
    return Instant.parse(headOfOldest(key).get(LOCK_DATE));
  }

  private Map<String, String> tagsOf(EvidenceKey key) {
    return f.raw().tags(f.bucket, key.path(), oldestVersionId(key));
  }

  private Instant retainUntilOf(EvidenceKey key) {
    return f.store.get(key).orElseThrow().retention().retainUntil();
  }

  /** The kinds of the PUT requests received since {@code mark}: object, retention, tagging... */
  private List<String> putsSince(int mark) {
    List<String> lines = f.server().requestLines();
    List<String> kinds = new ArrayList<>();
    for (String line : lines.subList(mark, lines.size())) {
      if (!line.startsWith("PUT ")) {
        continue;
      }
      int query = line.indexOf('?');
      List<String> names =
          query < 0 ? List.of() : Arrays.asList(line.substring(query + 1).split("&"));
      if (names.contains("retention")) {
        kinds.add("retention");
      } else if (names.contains("tagging")) {
        kinds.add("tagging");
      } else if (names.contains("legal-hold")) {
        kinds.add("legal-hold");
      } else {
        kinds.add("object");
      }
    }
    return kinds;
  }

  private static String base64url(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(text));
  }

  // ---- the lock date

  @Test
  void theLockDateIsTheRetainUntilRoundedUpToWholeSeconds() {
    EvidenceKey whole = EvidenceKey.of("landing", "whole");
    EvidenceKey micro = EvidenceKey.of("landing", "micro");
    EvidenceKey nearly = EvidenceKey.of("landing", "nearly");
    EvidenceKey half = EvidenceKey.of("landing", "half");
    putAt(whole, "a", Y2045);
    putAt(micro, "b", Y2045.plusNanos(1_000));
    putAt(nearly, "c", Y2045.plusNanos(999_999_000));
    putAt(half, "d", Y2045.plusMillis(500));
    assertThat(lockOf(whole)).isEqualTo(Y2045);
    assertThat(lockOf(micro)).isEqualTo(Y2045.plusSeconds(1));
    assertThat(lockOf(nearly)).isEqualTo(Y2045.plusSeconds(1));
    assertThat(lockOf(half)).isEqualTo(Y2045.plusSeconds(1));
    // the record keeps what was asked, to the microsecond
    assertThat(retainUntilOf(micro)).isEqualTo(Y2045.plusNanos(1_000));
    assertThat(retainUntilOf(nearly)).isEqualTo(Y2045.plusNanos(999_999_000));
    assertThat(retainUntilOf(half)).isEqualTo(Y2045.plusMillis(500));
    // the same rule for an extension
    f.store.extendRetention(whole, Y2046.plusNanos(1_000));
    assertThat(lockOf(whole)).isEqualTo(Y2046.plusSeconds(1));
    assertThat(retainUntilOf(whole)).isEqualTo(Y2046.plusNanos(1_000));
    f.store.extendRetention(micro, Y2046);
    assertThat(lockOf(micro)).isEqualTo(Y2046);
  }

  @Test
  void theLastSecondOfYear9999IsClampedAndStillRoundTrips() {
    Instant lastMicro = Instant.parse("9999-12-31T23:59:59.999999Z");
    Instant maxLock = Instant.parse("9999-12-31T23:59:59Z");
    putAt(K, "hello", lastMicro);
    assertThat(lockOf(K)).isEqualTo(maxLock);
    assertThat(retainUntilOf(K)).isEqualTo(lastMicro);

    // by extension: the lock is clamped to the maximum, the record keeps the micro value
    EvidenceKey other = EvidenceKey.of("landing", "k2");
    putAt(other, "world", Y2045);
    f.store.extendRetention(other, lastMicro);
    assertThat(lockOf(other)).isEqualTo(maxLock);
    assertThat(retainUntilOf(other)).isEqualTo(lastMicro);
    // and again to the same value: nothing is written
    int mark = f.mark();
    f.store.extendRetention(other, lastMicro);
    assertThat(putsSince(mark)).isEmpty();

    // the maximum itself, as a whole second, is a retain-until of its own
    EvidenceKey third = EvidenceKey.of("landing", "k3");
    putAt(third, "again", Y2045);
    f.store.extendRetention(third, maxLock);
    assertThat(lockOf(third)).isEqualTo(maxLock);
    assertThat(retainUntilOf(third)).isEqualTo(maxLock);
    // the last micro is a later retain-until than the last second: only the record moves
    mark = f.mark();
    f.store.extendRetention(third, lastMicro);
    assertThat(putsSince(mark)).containsExactly("tagging");
    assertThat(lockOf(third)).isEqualTo(maxLock);
    assertThat(retainUntilOf(third)).isEqualTo(lastMicro);
  }

  // ---- extending

  @Test
  void anExtendedRetentionIsLockedFirstAndTaggedSecond() {
    putAt(K, "hello", Y2045);
    Instant later = Y2046.plusNanos(1_000);
    int mark = f.mark();
    f.store.extendRetention(K, later);
    assertThat(putsSince(mark)).containsExactly("retention", "tagging");
    assertThat(lockOf(K)).isEqualTo(Y2046.plusSeconds(1));
    assertThat(headOfOldest(K)).containsEntry(LOCK_MODE, "GOVERNANCE");
    assertThat(tagsOf(K)).containsExactly(Map.entry(TAG, later.toString()));
    // the metadata of the object is the record of the put and stays so
    assertThat(headOfOldest(K)).containsEntry("x-amz-meta-regpack-retain-until", Y2045.toString());
    assertThat(entries(K)).hasSize(1);
    assertThat(retainUntilOf(K)).isEqualTo(later);

    // a get of an object with a tag reads the tags, a get of one without does not
    mark = f.mark();
    f.store.get(K);
    assertThat(f.kindsSince(mark)).containsExactly("versions", "GET", "GET");
    EvidenceKey plain = EvidenceKey.of("landing", "plain");
    putAt(plain, "plain", Y2045);
    mark = f.mark();
    f.store.get(plain);
    assertThat(f.kindsSince(mark)).containsExactly("versions", "GET");
  }

  /** A raw version that is a well-formed object of the adapter, with a lock of the given mode. */
  private void putRawObjectLockedAs(String mode, Instant until) {
    byte[] bytes = utf8("hello");
    Map<String, String> headers = new TreeMap<>();
    headers.put("x-amz-meta-regpack-sha256", Sha256.of(bytes).hex());
    headers.put("x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z");
    headers.put("x-amz-meta-regpack-retain-until", until.toString());
    headers.put("x-amz-meta-regpack-class", base64url("landing"));
    headers.put("x-amz-meta-regpack-media-type", base64url("text/plain"));
    headers.put(LOCK_MODE, mode);
    headers.put(LOCK_DATE, until.toString());
    f.raw().putObject(f.bucket, K.path(), bytes, headers);
  }

  @Test
  void anExtensionSendsTheLockModeTheVersionAlreadyHasNotTheModeOfTheConfiguration() {
    // the store is configured for governance; the version was locked in compliance mode
    putRawObjectLockedAs("COMPLIANCE", Y2045);
    assertThat(retainUntilOf(K)).isEqualTo(Y2045);
    int mark = f.mark();
    f.store.extendRetention(K, Y2046);
    assertThat(putsSince(mark)).containsExactly("retention", "tagging");
    // a governance request over a compliance lock would have been refused, or would have changed
    // the mode; the mode is the one the version had
    assertThat(headOfOldest(K)).containsEntry(LOCK_MODE, "COMPLIANCE");
    assertThat(lockOf(K)).isEqualTo(Y2046);
    assertThat(retainUntilOf(K)).isEqualTo(Y2046);
  }

  @Test
  void aTagOfAnotherWriterIsKeptWhenTheRetentionTagIsWritten() {
    putAt(K, "hello", Y2045);
    f.raw().putTags(f.bucket, K.path(), oldestVersionId(K), Map.of("owner", "operations"));
    f.store.extendRetention(K, Y2046);
    assertThat(tagsOf(K))
        .containsOnly(Map.entry("owner", "operations"), Map.entry(TAG, Y2046.toString()));
    assertThat(retainUntilOf(K)).isEqualTo(Y2046);
  }

  @Test
  void theRetentionTagIsReplacedByASecondExtensionAndNotAdded() {
    putAt(K, "hello", Y2045);
    f.store.extendRetention(K, Y2046);
    Instant later = Y2046.plusSeconds(3_600);
    f.store.extendRetention(K, later);
    assertThat(tagsOf(K)).containsExactly(Map.entry(TAG, later.toString()));
    assertThat(lockOf(K)).isEqualTo(later);
    assertThat(retainUntilOf(K)).isEqualTo(later);
  }

  @Test
  void theWritePathReadsTheTagsWhateverTheCountHeaderOfTheGetSays() {
    putAt(K, "hello", Y2045);
    f.raw().putTags(f.bucket, K.path(), oldestVersionId(K), Map.of("owner", "operations"));
    JdkTransport real = new JdkTransport(Duration.ofSeconds(10));
    S3Transport withoutCount =
        request -> {
          S3Response answer = real.send(request);
          if (answer.headers().containsKey("x-amz-tagging-count")) {
            Map<String, String> headers = new HashMap<>(answer.headers());
            headers.remove("x-amz-tagging-count");
            return new S3Response(answer.status(), headers, answer.body());
          }
          return answer;
        };
    WormEvidenceStore blind = f.openOn(withoutCount);
    // the read path does trust the header, so this store does not read the tags on a get ...
    int mark = f.mark();
    blind.get(K);
    assertThat(f.kindsSince(mark)).containsExactly("versions", "GET");
    // ... but the write path reads them, so that it neither drops nor misreads a tag
    mark = f.mark();
    blind.extendRetention(K, Y2046);
    assertThat(f.kindsSince(mark)).containsExactly("versions", "GET", "GET", "PUT", "PUT");
    assertThat(tagsOf(K))
        .containsOnly(Map.entry("owner", "operations"), Map.entry(TAG, Y2046.toString()));
  }

  @Test
  void aFailedTagAfterTheLockStillReportsAtLeastTheLock() {
    putAt(K, "hello", Y2045);
    Instant requested = Y2046.plusMillis(500);
    f.server().fault(Fault.FAIL_TAGGING, true);
    assertThatThrownBy(() -> f.store.extendRetention(K, requested))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 putObjectTagging status=500 code=InternalError");
    f.server().fault(Fault.FAIL_TAGGING, false);
    // the lock was extended first and cannot be taken back; the record has not followed
    assertThat(lockOf(K)).isEqualTo(Y2046.plusSeconds(1));
    assertThat(tagsOf(K)).isEmpty();
    Instant reported = retainUntilOf(K);
    assertThat(reported).isEqualTo(Y2046.plusSeconds(1));
    assertThat(reported).isAfterOrEqualTo(requested);
  }

  @Test
  void aRetryOfAnExtensionThatLostItsTagCompletesIt() {
    putAt(K, "hello", Y2045);
    Instant requested = Y2046.plusMillis(500);
    f.server().fault(Fault.FAIL_TAGGING, true);
    assertThatThrownBy(() -> f.store.extendRetention(K, requested))
        .isInstanceOf(EvidenceUnavailableException.class);
    f.server().fault(Fault.FAIL_TAGGING, false);

    int mark = f.mark();
    f.store.extendRetention(K, requested);
    // the lock is already there: only the tag is written
    assertThat(putsSince(mark)).containsExactly("tagging");
    assertThat(lockOf(K)).isEqualTo(Y2046.plusSeconds(1));
    assertThat(tagsOf(K)).containsExactly(Map.entry(TAG, requested.toString()));
    assertThat(retainUntilOf(K)).isEqualTo(requested);

    // and a third call has nothing left to do
    mark = f.mark();
    f.store.extendRetention(K, requested);
    assertThat(putsSince(mark)).isEmpty();
  }

  @Test
  void anExtensionWhoseRoundedDateIsNotLaterThanTheLockWritesOnlyTheTag() {
    Instant recorded = Y2045.plusNanos(1_000);
    putAt(K, "hello", recorded);
    assertThat(lockOf(K)).isEqualTo(Y2045.plusSeconds(1));
    Instant requested = Y2045.plusMillis(500);
    int mark = f.mark();
    f.store.extendRetention(K, requested);
    assertThat(putsSince(mark)).containsExactly("tagging");
    assertThat(lockOf(K)).isEqualTo(Y2045.plusSeconds(1));
    assertThat(retainUntilOf(K)).isEqualTo(requested);
  }

  @Test
  void theLockIsNeverShortenedByAnExtend() {
    putAt(K, "hello", Y2045);
    // a lock longer than the record, made behind the adapter's back
    Instant longer = Instant.parse("2050-03-01T00:00:00Z");
    f.raw().putRetention(f.bucket, K.path(), oldestVersionId(K), "GOVERNANCE", longer.toString());
    assertThat(retainUntilOf(K)).as("the lock is longer than the record").isEqualTo(longer);

    // an extension that is later than the record and earlier than the lock writes only the tag;
    // the service refuses a shortening without the bypass header, so a request that tried it
    // would have failed this call
    int mark = f.mark();
    f.store.extendRetention(K, Y2046);
    assertThat(putsSince(mark)).containsExactly("tagging");
    assertThat(lockOf(K)).isEqualTo(longer);
    assertThat(tagsOf(K)).containsExactly(Map.entry(TAG, Y2046.toString()));
    assertThat(retainUntilOf(K)).as("never less than the lock").isEqualTo(longer);

    // a later one than the lock extends it
    Instant latest = Instant.parse("2051-03-01T00:00:00Z");
    f.store.extendRetention(K, latest);
    assertThat(lockOf(K)).isEqualTo(latest);
    assertThat(retainUntilOf(K)).isEqualTo(latest);
  }

  @Test
  void anEarlierRetainUntilIsRefusedAndWritesNothing() {
    putAt(K, "hello", Y2045);
    f.store.extendRetention(K, Y2046);
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.extendRetention(K, Y2046.minusNanos(1_000)))
        .isInstanceOf(EvidenceRetentionException.class);
    assertThatThrownBy(() -> f.store.extendRetention(K, Y2045))
        .isInstanceOf(EvidenceRetentionException.class);
    assertThat(putsSince(mark)).isEmpty();
    assertThat(retainUntilOf(K)).isEqualTo(Y2046);
  }

  @Test
  void extendingToTheCurrentRetainUntilChangesNothing() {
    putAt(K, "hello", Y2045);
    int mark = f.mark();
    f.store.extendRetention(K, Y2045);
    assertThat(putsSince(mark)).isEmpty();
    f.store.extendRetention(K, Y2046);
    mark = f.mark();
    f.store.extendRetention(K, Y2046);
    assertThat(putsSince(mark)).isEmpty();
    assertThat(retainUntilOf(K)).isEqualTo(Y2046);
  }

  @Test
  void retentionAndHoldTouchOnlyTheOldestDataVersion() {
    putAt(K, "hello", Y2045);
    String oldest = oldestVersionId(K);
    f.raw().putObject(f.bucket, K.path(), utf8("newer"), Map.of());
    String newest = entries(K).get(0).versionId();
    assertThat(newest).isNotEqualTo(oldest);
    f.store.extendRetention(K, Y2046);
    f.store.setLegalHold(K, true);
    Map<String, String> oldHead = f.raw().head(f.bucket, K.path(), oldest).orElseThrow().headers();
    Map<String, String> newHead = f.raw().head(f.bucket, K.path(), newest).orElseThrow().headers();
    assertThat(oldHead).containsEntry(LOCK_DATE, Y2046.toString()).containsEntry(HOLD, "ON");
    assertThat(newHead).doesNotContainKeys(LOCK_DATE, LOCK_MODE, HOLD);
    StoredObject got = f.store.get(K).orElseThrow();
    assertThat(got.bytes()).isEqualTo(utf8("hello"));
    assertThat(got.retention().retainUntil()).isEqualTo(Y2046);
    assertThat(got.legalHold()).isTrue();
  }

  // ---- alarms

  @Test
  void aLockShorterThanTheRecordedRetentionIsACorruptionAlarm() {
    putAt(K, "hello", Y2045);
    f.server().shortenLock(f.bucket, K.path(), Instant.parse("2044-03-01T00:00:00Z"));
    assertThatThrownBy(() -> f.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.extendRetention(K, Y2046))
        .isInstanceOf(EvidenceCorruptionException.class);
    // nothing was changed, and the shortened lock was not quietly put right
    assertThat(putsSince(mark)).isEmpty();
    assertThat(lockOf(K)).isEqualTo(Instant.parse("2044-03-01T00:00:00Z"));

    // one second short of a record that has to be rounded up is short too
    EvidenceKey micro = EvidenceKey.of("landing", "micro");
    putAt(micro, "world", Y2045.plusNanos(1_000));
    f.server().shortenLock(f.bucket, micro.path(), Y2045);
    assertThatThrownBy(() -> f.store.get(micro)).isInstanceOf(EvidenceCorruptionException.class);
    // whereas a lock of exactly the rounded date is the one the adapter wrote
    f.server().shortenLock(f.bucket, micro.path(), Y2045.plusSeconds(1));
    assertThat(retainUntilOf(micro)).isEqualTo(Y2045.plusNanos(1_000));
  }

  @Test
  void anObjectWithoutARetentionLockIsACorruptionAlarm() {
    byte[] bytes = utf8("hello");
    Map<String, String> meta =
        Map.of(
            "x-amz-meta-regpack-sha256", Sha256.of(bytes).hex(),
            "x-amz-meta-regpack-written-at", "2040-03-01T00:00:00Z",
            "x-amz-meta-regpack-retain-until", Y2045.toString(),
            "x-amz-meta-regpack-class", base64url("landing"),
            "x-amz-meta-regpack-media-type", base64url("text/plain"));
    f.raw().putObject(f.bucket, K.path(), bytes, meta);
    assertThatThrownBy(() -> f.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.extendRetention(K, Y2046))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(putsSince(mark)).isEmpty();
  }

  @Test
  void aTagBelowTheRecordedRetainUntilIsACorruptionAlarm() {
    putAt(K, "hello", Y2045);
    f.raw()
        .putTags(
            f.bucket, K.path(), oldestVersionId(K), Map.of(TAG, Y2045.minusSeconds(1).toString()));
    assertThatThrownBy(() -> f.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.extendRetention(K, Y2046))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(putsSince(mark)).isEmpty();
    // a tag that is no instant is the same
    f.raw().putTags(f.bucket, K.path(), oldestVersionId(K), Map.of(TAG, "next-tuesday"));
    assertThatThrownBy(() -> f.store.get(K)).isInstanceOf(EvidenceCorruptionException.class);
  }

  @Test
  void aLockDateThatCannotBeReadIsUnavailable() {
    putAt(K, "hello", Y2045);
    JdkTransport real = new JdkTransport(Duration.ofSeconds(10));
    S3Transport garbled =
        request -> {
          S3Response answer = real.send(request);
          if (request.method().equals("GET") && answer.headers().containsKey(LOCK_DATE)) {
            Map<String, String> headers = new HashMap<>(answer.headers());
            headers.put(LOCK_DATE, "next tuesday");
            return new S3Response(answer.status(), headers, answer.body());
          }
          return answer;
        };
    assertThatThrownBy(() -> f.openOn(garbled).get(K))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("S3 getObject status=200 code=BadLockDate");
  }

  @Test
  void aKeyHiddenByADeleteMarkerIsACorruptionAlarmForRetentionAndHoldAndStaysUnchanged() {
    putAt(K, "hello", Y2045);
    f.raw().send("DELETE", f.bucket, Optional.of(K.path()), List.of(), Map.of(), new byte[0]);
    int mark = f.mark();
    assertThatThrownBy(() -> f.store.extendRetention(K, Y2046))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> f.store.setLegalHold(K, true))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(putsSince(mark)).isEmpty();
  }

  @Test
  void aKeyThatHoldsOnlyDeleteMarkersIsNoSuchKeyForRetentionAndHold() {
    f.raw().send("DELETE", f.bucket, Optional.of(K.path()), List.of(), Map.of(), new byte[0]);
    assertThatThrownBy(() -> f.store.extendRetention(K, Y2046))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no such evidence key: " + K.path());
    assertThatThrownBy(() -> f.store.setLegalHold(K, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no such evidence key: " + K.path());
  }

  // ---- legal hold

  @Test
  void aLegalHoldIsIndependentOfRetentionAndOfTheBytes() {
    putAt(K, "hello", Y2045);
    StoredObject before = f.store.get(K).orElseThrow();
    assertThat(before.legalHold()).isFalse();
    String version = oldestVersionId(K);
    Map<String, String> headBefore = headOfOldest(K);
    assertThat(headBefore).doesNotContainKey(HOLD);

    int mark = f.mark();
    f.store.setLegalHold(K, true);
    assertThat(putsSince(mark)).containsExactly("legal-hold");
    Map<String, String> headOn = headOfOldest(K);
    assertThat(headOn).containsEntry(HOLD, "ON");
    // the lock, the metadata and the bytes are what they were
    assertThat(headOn.get(LOCK_DATE)).isEqualTo(headBefore.get(LOCK_DATE));
    assertThat(headOn.get(LOCK_MODE)).isEqualTo(headBefore.get(LOCK_MODE));
    assertThat(headOn.get("x-amz-meta-regpack-sha256"))
        .isEqualTo(headBefore.get("x-amz-meta-regpack-sha256"));
    StoredObject held = f.store.get(K).orElseThrow();
    assertThat(held.legalHold()).isTrue();
    assertThat(held.retention()).isEqualTo(before.retention());
    assertThat(held.bytes()).isEqualTo(before.bytes());
    assertThat(held.writtenAt()).isEqualTo(before.writtenAt());
    assertThat(entries(K)).hasSize(1);
    assertThat(oldestVersionId(K)).isEqualTo(version);

    // setting it again is the same state; clearing it leaves "OFF" on the service and false here
    f.store.setLegalHold(K, true);
    assertThat(f.store.get(K).orElseThrow().legalHold()).isTrue();
    f.store.setLegalHold(K, false);
    assertThat(headOfOldest(K)).containsEntry(HOLD, "OFF");
    StoredObject released = f.store.get(K).orElseThrow();
    assertThat(released.legalHold()).isFalse();
    assertThat(released.retention()).isEqualTo(before.retention());
    assertThat(released.bytes()).isEqualTo(before.bytes());

    // and retention moves with the hold on without touching it
    f.store.setLegalHold(K, true);
    f.store.extendRetention(K, Y2046);
    assertThat(headOfOldest(K)).containsEntry(HOLD, "ON");
    assertThat(f.store.get(K).orElseThrow().legalHold()).isTrue();
  }
}
