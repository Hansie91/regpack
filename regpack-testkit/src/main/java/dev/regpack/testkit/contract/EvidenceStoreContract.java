package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
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
import dev.regpack.testkit.clock.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
  }

  @Test
  default void legalHoldIsSeparateFromRetention() {
    Retention original = fiveYears();
    store().put(request(K, "hello", "text/plain", original));
    store().setLegalHold(K, true);
    StoredObject got = store().get(K).orElseThrow();
    assertThat(got.legalHold()).isTrue();
    assertThat(got.retention()).isEqualTo(original);
  }

  @Test
  default void outageFailsClosedAndStoresNothing() {
    simulateOutage(true);
    assertThatThrownBy(() -> store().put(request(K, "hello", "text/plain", fiveYears())))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store().get(K)).isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store().list("landing/", Optional.empty(), 10))
        .isInstanceOf(EvidenceUnavailableException.class);
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
}
