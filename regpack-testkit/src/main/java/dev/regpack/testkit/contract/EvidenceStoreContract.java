package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceRetentionException;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.testkit.clock.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Contract every {@link EvidenceStore} adapter must satisfy. Each test is a default method. */
public interface EvidenceStoreContract {
  EvidenceKey K = EvidenceKey.of("landing", "k1");

  /** A fresh store for each test. */
  EvidenceStore store();

  ManualClock clock();

  /** Adapter-specific way to make every call fail. */
  void simulateOutage(boolean down);

  /**
   * A store over the same persisted state, as after a restart or reconnect; the in-memory adapter
   * returns itself.
   */
  EvidenceStore reopen();

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private Retention fiveYears() {
    return new Retention(new RetentionClass("landing"), clock().now().plus(Duration.ofDays(1826)));
  }

  private PutRequest request(EvidenceKey key, String text, String mediaType, Retention retention) {
    return new PutRequest(key, utf8(text), mediaType, retention);
  }

  @Test
  default void putThenGetReturnsIdenticalBytesAndSha256() {
    Retention retention = fiveYears();
    Instant putAt = clock().now();
    assertThat(store().put(request(K, "hello", "text/plain", retention)))
        .isEqualTo(PutOutcome.CREATED);
    StoredObject got = store().get(K).orElseThrow();
    assertThat(got.key()).isEqualTo(K);
    assertThat(got.bytes()).isEqualTo(utf8("hello"));
    assertThat(got.sha256())
        .isEqualTo(new Sha256("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
    assertThat(got.writtenAt()).isEqualTo(putAt);
    assertThat(got.legalHold()).isFalse();
    assertThat(got.mediaType()).isEqualTo("text/plain");
    assertThat(got.retention()).isEqualTo(retention);
  }

  @Test
  default void putSameKeySameBytesIsIdempotent() {
    Retention first = fiveYears();
    Instant firstWrittenAt = clock().now();
    store().put(request(K, "hello", "text/plain", first));
    clock().advance(Duration.ofHours(1));
    Retention later =
        new Retention(new RetentionClass("landing"), first.retainUntil().plus(Duration.ofDays(30)));
    assertThat(store().put(request(K, "hello", "application/octet-stream", later)))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    StoredObject got = store().get(K).orElseThrow();
    assertThat(got.writtenAt()).isEqualTo(firstWrittenAt);
    assertThat(got.mediaType()).isEqualTo("text/plain");
    assertThat(got.retention()).isEqualTo(first);
  }

  @Test
  default void putSameKeyDifferentBytesIsIntegrityAlarmAndKeepsOriginal() {
    store().put(request(K, "hello", "text/plain", fiveYears()));
    assertThatThrownBy(() -> store().put(request(K, "other", "text/plain", fiveYears())))
        .isInstanceOf(EvidenceIntegrityException.class);
    assertThat(store().get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  @Test
  default void getMissingReturnsEmpty() {
    assertThat(store().get(K)).isEmpty();
  }

  @Test
  default void listReturnsKeysInCodeUnitOrderWithPrefixAfterAndLimit() {
    EvidenceKey upperB = EvidenceKey.of("a", "B");
    EvidenceKey lowerA = EvidenceKey.of("a", "a");
    EvidenceKey underscore = EvidenceKey.of("a", "_");
    EvidenceKey other = EvidenceKey.of("b", "x");
    for (EvidenceKey key : List.of(upperB, lowerA, underscore, other)) {
      store().put(request(key, "v", "text/plain", fiveYears()));
    }
    assertThat(store().list("a/", Optional.empty(), 10))
        .containsExactly(upperB, underscore, lowerA);
    assertThat(store().list("a/", Optional.of(upperB), 1)).containsExactly(underscore);
    // The limit applies without an after key too, and keeps the first keys in order.
    assertThat(store().list("a/", Optional.empty(), 2)).containsExactly(upperB, underscore);
    assertThat(store().list("a/", Optional.empty(), 0)).isEmpty();
    assertThat(store().list("zzz", Optional.empty(), 10)).isEmpty();
  }

  @Test
  default void retentionCanOnlyBeExtended() {
    Retention original = fiveYears();
    store().put(request(K, "hello", "text/plain", original));
    Instant later = original.retainUntil().plus(Duration.ofDays(365));
    store().extendRetention(K, later);
    assertThat(store().get(K).orElseThrow().retention().retainUntil()).isEqualTo(later);
    Instant earlier = later.minus(Duration.ofDays(1));
    assertThatThrownBy(() -> store().extendRetention(K, earlier))
        .isInstanceOf(EvidenceRetentionException.class);
    assertThat(store().get(K).orElseThrow().retention().retainUntil()).isEqualTo(later);
    // Extending to the current retain-until is not a shortening and changes nothing.
    store().extendRetention(K, later);
    assertThat(store().get(K).orElseThrow().retention().retainUntil()).isEqualTo(later);
    // Extending touches the retain-until only; everything else of the object is kept.
    StoredObject extended = store().get(K).orElseThrow();
    assertThat(extended.bytes()).isEqualTo(utf8("hello"));
    assertThat(extended.mediaType()).isEqualTo("text/plain");
    assertThat(extended.retention().retentionClass()).isEqualTo(original.retentionClass());
    assertThat(extended.writtenAt()).isEqualTo(clock().now());
  }

  /**
   * Retain-until instants no store holds (SPEC-7): more than microsecond precision, and a year
   * outside 0000 to 9999.
   */
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
  default void putRefusesARetainUntilTheStoresCannotHoldAndStoresNothing() {
    for (Instant bad : unstorableRetainUntils()) {
      PutRequest request =
          request(K, "hello", "text/plain", new Retention(new RetentionClass("landing"), bad));
      assertThatThrownBy(() -> store().put(request))
          .as("retain-until %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(store().get(K)).as("retain-until %s", bad).isEmpty();
      assertThat(store().list("", Optional.empty(), 10)).as("retain-until %s", bad).isEmpty();
    }
  }

  @Test
  default void extendRetentionRefusesARetainUntilTheStoresCannotHoldAndChangesNothing() {
    Retention original = fiveYears();
    store().put(request(K, "hello", "text/plain", original));
    StoredObject before = store().get(K).orElseThrow();
    for (Instant bad : unstorableRetainUntils()) {
      assertThatThrownBy(() -> store().extendRetention(K, bad))
          .as("retain-until %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(store().get(K)).as("retain-until %s", bad).contains(before);
    }
  }

  @Test
  default void theLastMicrosecondOfYear9999IsARetainUntilEveryStoreHolds() {
    Instant last = Instant.parse("9999-12-31T23:59:59.999999Z");
    store()
        .put(request(K, "hello", "text/plain", new Retention(new RetentionClass("landing"), last)));
    assertThat(store().get(K).orElseThrow().retention().retainUntil()).isEqualTo(last);
    EvidenceKey other = EvidenceKey.of("landing", "k2");
    store().put(request(other, "world", "text/plain", fiveYears()));
    store().extendRetention(other, last);
    assertThat(store().get(other).orElseThrow().retention().retainUntil()).isEqualTo(last);
    // one microsecond of precision is kept as it is
    Instant micro = clock().now().plus(Duration.ofDays(2000)).plus(Duration.ofNanos(1_000));
    EvidenceKey third = EvidenceKey.of("landing", "k3");
    store()
        .put(
            request(
                third, "again", "text/plain", new Retention(new RetentionClass("landing"), micro)));
    assertThat(store().get(third).orElseThrow().retention().retainUntil()).isEqualTo(micro);
  }

  @Test
  default void legalHoldIsSeparateFromRetention() {
    Retention original = fiveYears();
    store().put(request(K, "hello", "text/plain", original));
    store().setLegalHold(K, true);
    StoredObject got = store().get(K).orElseThrow();
    assertThat(got.legalHold()).isTrue();
    assertThat(got.retention()).isEqualTo(original);
    // Extending retention keeps the hold and the bytes.
    Instant later = original.retainUntil().plus(Duration.ofDays(365));
    store().extendRetention(K, later);
    StoredObject extended = store().get(K).orElseThrow();
    assertThat(extended.legalHold()).isTrue();
    assertThat(extended.bytes()).isEqualTo(utf8("hello"));
    assertThat(extended.mediaType()).isEqualTo("text/plain");
    assertThat(extended.retention().retainUntil()).isEqualTo(later);
    // Setting the hold keeps retention and bytes, and false clears the flag.
    store().setLegalHold(K, false);
    StoredObject released = store().get(K).orElseThrow();
    assertThat(released.legalHold()).isFalse();
    assertThat(released.retention().retainUntil()).isEqualTo(later);
    assertThat(released.bytes()).isEqualTo(utf8("hello"));
    store().setLegalHold(K, true);
    assertThat(store().get(K).orElseThrow().legalHold()).isTrue();
  }

  @Test
  default void outageFailsClosedAndStoresNothing() {
    simulateOutage(true);
    assertThatThrownBy(() -> store().put(request(K, "hello", "text/plain", fiveYears())))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store().get(K)).isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store().list("landing/", Optional.empty(), 10))
        .isInstanceOf(EvidenceUnavailableException.class);
    // The port rule is that every method throws during an outage.
    assertThatThrownBy(() -> store().extendRetention(K, clock().now().plus(Duration.ofDays(1))))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store().setLegalHold(K, true))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store().grade()).isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store().durability()).isInstanceOf(EvidenceUnavailableException.class);
    simulateOutage(false);
    assertThat(store().get(K)).isEmpty();
  }

  @Test
  default void gradeAndDurabilityAreDeclared() {
    assertThat(store().grade().profile()).isNotBlank();
    assertThat(store().durability()).isNotNull();
    assertThat(store().durability().independentFailureDomains()).isGreaterThanOrEqualTo(0);
  }

  @Test
  default void returnedBytesAreCopies() {
    byte[] input = utf8("hello");
    store().put(new PutRequest(K, input, "text/plain", fiveYears()));
    input[0] = 'X';
    byte[] first = store().get(K).orElseThrow().bytes();
    assertThat(first).isEqualTo(utf8("hello"));
    first[0] = 'Y';
    assertThat(store().get(K).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  @Test
  default void acknowledgedPutIsReadableAfterReopen() {
    Retention retention = fiveYears();
    store().put(request(K, "hello", "text/plain", retention));
    StoredObject before = store().get(K).orElseThrow();
    StoredObject after = reopen().get(K).orElseThrow();
    assertThat(after.bytes()).isEqualTo(before.bytes());
    assertThat(after.sha256()).isEqualTo(before.sha256());
    assertThat(after.mediaType()).isEqualTo(before.mediaType());
    assertThat(after.writtenAt()).isEqualTo(before.writtenAt());
    assertThat(after.retention()).isEqualTo(before.retention());
  }

  /**
   * Meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec
   * 14.1).
   */
  @Test
  default void concurrentPutsOfTheSameKeyWithDifferentBytesHaveExactlyOneWinner() throws Exception {
    int threads = 8;
    Retention retention = fiveYears();
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<PutOutcome>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        String text = "writer-" + i;
        Callable<PutOutcome> task =
            () -> {
              release.await();
              return store().put(request(K, text, "text/plain", retention));
            };
        futures.add(pool.submit(task));
      }
      release.countDown();
      int created = 0;
      int integrity = 0;
      String winner = "";
      for (int i = 0; i < threads; i++) {
        try {
          if (futures.get(i).get() == PutOutcome.CREATED) {
            created++;
            winner = "writer-" + i;
          }
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(EvidenceIntegrityException.class);
          integrity++;
        }
      }
      assertThat(created).isEqualTo(1);
      assertThat(integrity).isEqualTo(threads - 1);
      assertThat(store().get(K).orElseThrow().bytes()).isEqualTo(utf8(winner));
    } finally {
      pool.shutdownNow();
    }
  }

  // M28, M33 (D-E2): a never-put key is a caller error, and an outage comes first.

  @Test
  default void extendRetentionOfAMissingKeyIsRejected() {
    Instant later = clock().now().plus(Duration.ofDays(1));
    simulateOutage(true);
    assertThatThrownBy(() -> store().extendRetention(K, later))
        .isInstanceOf(EvidenceUnavailableException.class);
    simulateOutage(false);
    assertThatThrownBy(() -> store().extendRetention(K, later))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(K.path());
    assertThat(store().get(K)).isEmpty();
    assertThat(store().list("landing/", Optional.empty(), 10)).isEmpty();
  }

  @Test
  default void setLegalHoldOfAMissingKeyIsRejected() {
    simulateOutage(true);
    assertThatThrownBy(() -> store().setLegalHold(K, true))
        .isInstanceOf(EvidenceUnavailableException.class);
    simulateOutage(false);
    assertThatThrownBy(() -> store().setLegalHold(K, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(K.path());
    assertThatThrownBy(() -> store().setLegalHold(K, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store().get(K)).isEmpty();
    assertThat(store().list("landing/", Optional.empty(), 10)).isEmpty();
  }

  @Test
  default void aKeyMayBeAPrefixOfAnotherKey() {
    EvidenceKey shorter = EvidenceKey.of("a", "b");
    EvidenceKey longer = EvidenceKey.of("a", "b", "c");
    store().put(request(longer, "longer", "text/plain", fiveYears()));
    store().put(request(shorter, "shorter", "text/plain", fiveYears()));
    assertThat(store().get(shorter).orElseThrow().bytes()).isEqualTo(utf8("shorter"));
    assertThat(store().get(longer).orElseThrow().bytes()).isEqualTo(utf8("longer"));
    assertThat(store().list("a/", Optional.empty(), 10)).containsExactly(shorter, longer);
    assertThat(store().list("a/", Optional.of(shorter), 10)).containsExactly(longer);
  }

  @Test
  default void listPrefixNeedNotEndAtASegmentBoundary() {
    EvidenceKey shorter = EvidenceKey.of("a", "b");
    EvidenceKey longer = EvidenceKey.of("a", "b", "c");
    EvidenceKey other = EvidenceKey.of("a", "c");
    for (EvidenceKey key : List.of(other, longer, shorter)) {
      store().put(request(key, "v", "text/plain", fiveYears()));
    }
    assertThat(store().list("a/b", Optional.empty(), 10)).containsExactly(shorter, longer);
    assertThat(store().list("a/b/", Optional.empty(), 10)).containsExactly(longer);
    assertThat(store().list("a", Optional.empty(), 10)).containsExactly(shorter, longer, other);
  }

  @Test
  default void aPrefixMatchesTheStartOfAKeyOnly() {
    // "b" occurs inside ab and xab and at the start of b only; "q/b" has it as a later segment
    EvidenceKey ab = EvidenceKey.of("ab");
    EvidenceKey b = EvidenceKey.of("b");
    EvidenceKey xab = EvidenceKey.of("xab");
    EvidenceKey qb = EvidenceKey.of("q", "b");
    for (EvidenceKey key : List.of(ab, b, xab, qb)) {
      store().put(request(key, "v", "text/plain", fiveYears()));
    }
    assertThat(store().list("b", Optional.empty(), 10)).containsExactly(b);
    assertThat(store().list("a", Optional.empty(), 10)).containsExactly(ab);
    assertThat(store().list("q/", Optional.empty(), 10)).containsExactly(qb);
    assertThat(store().list("ab", Optional.empty(), 10)).containsExactly(ab);
  }

  @Test
  default void listPagesWalkAllKeysOnce() {
    List<EvidenceKey> expected = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      EvidenceKey key = EvidenceKey.of("page", "k" + (1000 + i));
      expected.add(key);
      store().put(request(key, "v" + i, "text/plain", fiveYears()));
    }
    List<EvidenceKey> full = store().list("page/", Optional.empty(), 1000);
    assertThat(full).containsExactlyElementsOf(expected);
    List<EvidenceKey> walked = new ArrayList<>();
    Optional<EvidenceKey> after = Optional.empty();
    int pages = 0;
    while (pages < 5) {
      List<EvidenceKey> page = store().list("page/", after, 100);
      assertThat(page).hasSizeLessThanOrEqualTo(100);
      pages++;
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(page);
      after = Optional.of(page.get(page.size() - 1));
    }
    assertThat(pages).isEqualTo(4);
    assertThat(walked).containsExactlyElementsOf(full);
    assertThat(new HashSet<>(walked)).hasSize(250);
  }

  @Test
  default void emptyAndLargeObjectsRoundTrip() {
    EvidenceKey empty = EvidenceKey.of("size", "empty");
    EvidenceKey large = EvidenceKey.of("size", "large");
    byte[] largeBytes = new byte[3 * 1024 * 1024];
    for (int i = 0; i < largeBytes.length; i++) {
      largeBytes[i] = (byte) (i * 31 + (i >>> 8) + 7);
    }
    assertThat(store().put(new PutRequest(empty, new byte[0], "text/plain", fiveYears())))
        .isEqualTo(PutOutcome.CREATED);
    assertThat(
            store().put(new PutRequest(large, largeBytes, "application/octet-stream", fiveYears())))
        .isEqualTo(PutOutcome.CREATED);
    StoredObject gotEmpty = store().get(empty).orElseThrow();
    assertThat(gotEmpty.bytes()).isEmpty();
    assertThat(gotEmpty.sha256())
        .isEqualTo(new Sha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    StoredObject gotLarge = store().get(large).orElseThrow();
    assertThat(gotLarge.bytes()).isEqualTo(largeBytes);
    assertThat(gotLarge.sha256()).isEqualTo(Sha256.of(largeBytes));
    // The same bytes again are the same object, also at this size.
    assertThat(
            store().put(new PutRequest(large, largeBytes, "application/octet-stream", fiveYears())))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
  }

  @Test
  default void keysDifferingOnlyInCaseAreDistinct() {
    EvidenceKey upper = EvidenceKey.of("a", "KeyX");
    EvidenceKey lower = EvidenceKey.of("a", "keyx");
    assertThat(store().put(request(upper, "upper", "text/plain", fiveYears())))
        .isEqualTo(PutOutcome.CREATED);
    assertThat(store().put(request(lower, "lower", "text/plain", fiveYears())))
        .isEqualTo(PutOutcome.CREATED);
    assertThat(store().get(upper).orElseThrow().bytes()).isEqualTo(utf8("upper"));
    assertThat(store().get(lower).orElseThrow().bytes()).isEqualTo(utf8("lower"));
    assertThat(store().get(upper).orElseThrow().key()).isEqualTo(upper);
    assertThat(store().get(lower).orElseThrow().key()).isEqualTo(lower);
    assertThat(store().list("a/", Optional.empty(), 10)).containsExactly(upper, lower);
  }

  @Test
  default void aMaximumLengthKeyRoundTrips() {
    String segment = "m".repeat(100);
    EvidenceKey key = EvidenceKey.of(segment, segment, segment, segment, segment, "n".repeat(7));
    assertThat(key.path()).hasSize(512);
    assertThat(store().put(request(key, "max", "text/plain", fiveYears())))
        .isEqualTo(PutOutcome.CREATED);
    StoredObject got = store().get(key).orElseThrow();
    assertThat(got.key()).isEqualTo(key);
    assertThat(got.bytes()).isEqualTo(utf8("max"));
    assertThat(store().list(segment + "/", Optional.empty(), 10)).containsExactly(key);
    assertThat(store().put(request(key, "max", "text/plain", fiveYears())))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
  }

  @Test
  default void mediaTypeIsKeptVerbatim() {
    List<String> mediaTypes =
        List.of(
            "application/vnd.regpack.journal+json",
            "Text/Plain;Charset=UTF-8",
            "application/x-odd; a=\"b c\"; d=e",
            "application/octet-stream");
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 0; i < mediaTypes.size(); i++) {
      EvidenceKey key = EvidenceKey.of("media", "m" + i);
      keys.add(key);
      store().put(request(key, "v", mediaTypes.get(i), fiveYears()));
    }
    for (int i = 0; i < mediaTypes.size(); i++) {
      assertThat(store().get(keys.get(i)).orElseThrow().mediaType()).isEqualTo(mediaTypes.get(i));
    }
  }

  @Test
  default void aStoreOfAnEvidenceProfileDeclaresItsDurabilityConsistently() {
    String profile = store().grade().profile();
    Durability durability = store().durability();
    assertThat(profile).isIn("P-SEALED", "P-WORM", "P-MEMORY");
    if (profile.equals("P-MEMORY")) {
      assertThat(durability).isEqualTo(new Durability(false, 0));
    } else {
      assertThat(durability.synchronousAcknowledgement()).isTrue();
      assertThat(durability.independentFailureDomains()).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  default void retentionAndLegalHoldSurviveReopen() {
    Retention original = fiveYears();
    store().put(request(K, "hello", "text/plain", original));
    Instant later = original.retainUntil().plus(Duration.ofDays(365));
    store().extendRetention(K, later);
    store().setLegalHold(K, true);
    StoredObject before = store().get(K).orElseThrow();
    StoredObject after = reopen().get(K).orElseThrow();
    assertThat(after.retention().retainUntil()).isEqualTo(later);
    assertThat(after.retention().retentionClass()).isEqualTo(original.retentionClass());
    assertThat(after.legalHold()).isTrue();
    assertThat(after.bytes()).isEqualTo(before.bytes());
    assertThat(after.writtenAt()).isEqualTo(before.writtenAt());
    // A released hold also survives a reopen, and so does the extended retention.
    store().setLegalHold(K, false);
    assertThat(reopen().get(K).orElseThrow().legalHold()).isFalse();
    assertThat(reopen().get(K).orElseThrow().retention().retainUntil()).isEqualTo(later);
  }

  @Test
  default void concurrentPutsOfTheSameKeyWithTheSameBytesAllSucceedWithOneCreated()
      throws Exception {
    int threads = 8;
    Retention retention = fiveYears();
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<PutOutcome>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<PutOutcome> task =
            () -> {
              release.await();
              return store().put(request(K, "same bytes", "text/plain", retention));
            };
        futures.add(pool.submit(task));
      }
      release.countDown();
      int created = 0;
      int identical = 0;
      for (Future<PutOutcome> future : futures) {
        PutOutcome outcome = future.get();
        if (outcome == PutOutcome.CREATED) {
          created++;
        } else {
          assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
          identical++;
        }
      }
      assertThat(created).isEqualTo(1);
      assertThat(identical).isEqualTo(threads - 1);
      assertThat(store().get(K).orElseThrow().bytes()).isEqualTo(utf8("same bytes"));
      assertThat(store().list("landing/", Optional.empty(), 10)).containsExactly(K);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * P3b2 (D-W10): an answer shorter than the limit means that no key remains, so a caller may stop
   * at a short page. The answer holds the first {@code min(limit, n)} matching keys after {@code
   * after}.
   */
  @Test
  default void listReturnsFullPagesWhileKeysRemain() {
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 1; i <= 25; i++) {
      EvidenceKey key = EvidenceKey.of("full", String.format("k%02d", i));
      keys.add(key);
      store().put(request(key, "v" + i, "text/plain", fiveYears()));
    }
    assertThat(store().list("full/", Optional.empty(), 10))
        .containsExactlyElementsOf(keys.subList(0, 10));
    assertThat(store().list("full/", Optional.of(keys.get(9)), 10))
        .containsExactlyElementsOf(keys.subList(10, 20));
    assertThat(store().list("full/", Optional.of(keys.get(19)), 10))
        .containsExactlyElementsOf(keys.subList(20, 25));
    assertThat(store().list("full/", Optional.of(keys.get(24)), 10)).isEmpty();
    assertThat(store().list("full/", Optional.empty(), 7))
        .containsExactlyElementsOf(keys.subList(0, 7));
  }

  @Test
  default void keysWithPlusEqualsAndCommaAreDistinctAndListedInCodeUnitOrder() {
    List<String> shuffled = List.of("k=1", "ka1", "k+1", "k_1", "kA1", "k,1", "k.1", "k-1");
    for (String name : shuffled) {
      store().put(request(EvidenceKey.of("sym", name), name, "text/plain", fiveYears()));
    }
    List<String> expected = List.of("k+1", "k,1", "k-1", "k.1", "k=1", "kA1", "k_1", "ka1");
    List<EvidenceKey> listed = store().list("sym/", Optional.empty(), 100);
    assertThat(listed.stream().map(EvidenceKey::path).toList())
        .containsExactlyElementsOf(expected.stream().map(name -> "sym/" + name).toList());
    for (String name : expected) {
      EvidenceKey key = EvidenceKey.of("sym", name);
      assertThat(store().get(key).orElseThrow().bytes()).isEqualTo(utf8(name));
    }
  }

  @Test
  default void aMediaTypeOf255CharactersAndARetentionClassNameOf100CharactersRoundTrip() {
    // three-byte BMP characters, so that the worst case is exercised on every adapter
    String mediaType = "€".repeat(255);
    String className = "€".repeat(100);
    Retention retention = new Retention(new RetentionClass(className), fiveYears().retainUntil());
    store().put(request(K, "hello", mediaType, retention));
    StoredObject got = store().get(K).orElseThrow();
    assertThat(got.mediaType()).isEqualTo(mediaType);
    assertThat(got.retention().retentionClass().name()).isEqualTo(className);
    assertThat(got.retention()).isEqualTo(retention);
    assertThat(got.bytes()).isEqualTo(utf8("hello"));
  }
}
