package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T13: the {@code EvidenceStore} contract suite (31 methods) and the journal-format contract
 * suite (11), unchanged, against the {@link dev.regpack.evidence.crypto.EncryptingEvidenceStore}
 * (every key encrypted, one KEK) over P-WORM on the in-JVM fake S3 service. The shared methods come
 * from {@link WormEvidenceStoreContractTest} and so from the two shared interfaces; the one method
 * below shows that the store the suites run on encrypts. The decorator is made once per test, in a
 * {@code BeforeEach} method that runs after the one of the superclass (the racing methods of the
 * suite call {@code store()} from several threads); {@code reopen} opens P-WORM again on the same
 * bucket below a new decorator over the same key service, as a second process would. The outage is
 * the transport's, below the decorator, as in the raw binding. The service twin is {@link
 * WormEncryptedContractIT}.
 */
class EncryptedWormContractTest extends WormEvidenceStoreContractTest {
  private final EncryptedFixtures fixtures = EncryptedFixtures.create();
  private EvidenceStore encrypted;

  @BeforeEach
  void encryptTheStore() {
    encrypted = fixtures.encrypt(super.store(), clock());
  }

  @Override
  public EvidenceStore store() {
    return encrypted;
  }

  @Override
  public EvidenceStore reopen() {
    return fixtures.encrypt(super.reopen(), clock());
  }

  @Test
  void theBindingReallyEncryptsWhatTheSuitesStore() {
    EvidenceKey key = EvidenceKey.of("landing", "binding-check");
    byte[] plaintext = "not stored in the clear".getBytes(StandardCharsets.UTF_8);
    Retention retention =
        new Retention(new RetentionClass("landing"), clock().now().plus(Duration.ofDays(1826)));

    assertThat(store().put(new PutRequest(key, plaintext, "text/plain", retention)))
        .isEqualTo(PutOutcome.CREATED);

    byte[] stored = super.store().get(key).orElseThrow().bytes();
    assertThat(Envelope.isEnvelope(stored)).as("P-WORM holds an envelope").isTrue();
    assertThat(stored).as("and not the plaintext").isNotEqualTo(plaintext);
    assertThat(super.store().get(key).orElseThrow().sha256())
        .as("the hash the service recorded is that of the envelope")
        .isEqualTo(Sha256.of(stored))
        .isNotEqualTo(Sha256.of(plaintext));
    assertThat(store().get(key).orElseThrow().bytes()).isEqualTo(plaintext);
    assertThat(store().get(key).orElseThrow().sha256()).isEqualTo(Sha256.of(plaintext));
  }

  /**
   * The shared racing method accepts any {@link EvidenceIntegrityException} from a loser, and a
   * corruption alarm is one. A loser of a race on a decorated key must be told "other bytes" and
   * never that a stored copy is damaged: the decorator reads the key before it puts, outside the
   * stripe lock of the store, so on a real store it can meet a version that another thread's put is
   * still writing. This method accepts exactly {@link EvidenceIntegrityException}.
   */
  @Test
  void theLosersOfARaceOnADecoratedKeyAreToldOtherBytesAndNeverACorruptionAlarm() throws Exception {
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int round = 0; round < 5; round++) {
        EvidenceKey key = EvidenceKey.of("landing", "race-" + round);
        Retention retention =
            new Retention(new RetentionClass("landing"), clock().now().plus(Duration.ofDays(1826)));
        CountDownLatch release = new CountDownLatch(1);
        List<Future<PutOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          byte[] bytes = ("writer-" + i).getBytes(StandardCharsets.UTF_8);
          Callable<PutOutcome> task =
              () -> {
                release.await();
                return store().put(new PutRequest(key, bytes, "text/plain", retention));
              };
          futures.add(pool.submit(task));
        }
        release.countDown();
        int created = 0;
        for (Future<PutOutcome> future : futures) {
          try {
            if (future.get() == PutOutcome.CREATED) {
              created++;
            }
          } catch (ExecutionException e) {
            assertThat(e.getCause()).isExactlyInstanceOf(EvidenceIntegrityException.class);
          }
        }
        assertThat(created).as("round %d", round).isEqualTo(1);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * After every method, whichever method it is: each object that the method left in P-WORM is an
   * envelope. This is what makes the claim that the inherited methods run through the encryption
   * hold for every key they store and not for the one key of the binding check; a policy that skips
   * a namespace, a key length or a class of keys, or a method that writes behind the decorator, is
   * seen here.
   */
  @AfterEach
  void everyObjectTheMethodStoredIsAnEnvelope() {
    if (encrypted == null) {
      // the service is missing and the test did not start: nothing was stored
      return;
    }
    simulateOutage(false);
    List<EvidenceKey> keys = super.store().list("", Optional.empty(), 1000);
    assertThat(keys).as("the listing is one whole page").hasSizeLessThan(1000);
    for (EvidenceKey key : keys) {
      assertThat(Envelope.isEnvelope(super.store().get(key).orElseThrow().bytes()))
          .as("%s is stored as an envelope", key.path())
          .isTrue();
    }
  }
}
