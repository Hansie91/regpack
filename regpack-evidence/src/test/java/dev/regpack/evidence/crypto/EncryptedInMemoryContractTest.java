package dev.regpack.evidence.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.EvidenceStoreContract;
import dev.regpack.testkit.contract.JournalContract;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T6: the {@code EvidenceStore} contract suite and the journal-format contract suite, both
 * unchanged, against the {@link EncryptingEvidenceStore} (every key encrypted) over the in-memory
 * store. Every method comes from the two shared interfaces but one, which shows that the store the
 * suites run on encrypts. The store, the cipher and the key service share one {@link ManualClock}.
 */
class EncryptedInMemoryContractTest implements EvidenceStoreContract, JournalContract {
  private ManualClock clock;
  private InMemoryEvidenceStore inner;
  private EvidenceStore store;

  @BeforeEach
  void openStore() {
    clock = new ManualClock(Instant.parse("2026-03-01T00:00:00Z"));
    inner = new InMemoryEvidenceStore(clock);
    store = EncryptedFixtures.create().encrypt(inner, clock);
  }

  @Override
  public EvidenceStore store() {
    return store;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  /** The outage is the inner store's: the decorator has no outage of its own. */
  @Override
  public void simulateOutage(boolean down) {
    inner.simulateOutage(down);
  }

  /** The in-memory store survives nothing, so "the same state" is the same decorated store. */
  @Override
  public EvidenceStore reopen() {
    return store;
  }

  /**
   * After every method, whichever it is: each object that the method left in the inner store is an
   * envelope (T15-1, as in {@code EncryptedWormContractTest}). Without it a policy that skips a
   * namespace, a key length or a class of keys leaves the inherited methods green.
   */
  @AfterEach
  void everyObjectTheMethodStoredIsAnEnvelope() {
    if (inner == null) {
      return;
    }
    inner.simulateOutage(false);
    List<EvidenceKey> keys = inner.list("", Optional.empty(), 1000);
    assertThat(keys).as("the listing is one whole page").hasSizeLessThan(1000);
    for (EvidenceKey key : keys) {
      assertThat(Envelope.isEnvelope(inner.get(key).orElseThrow().bytes()))
          .as("%s is stored as an envelope", key.path())
          .isTrue();
    }
  }

  @Test
  void theBindingReallyEncryptsWhatTheSuitesStore() {
    EvidenceKey key = EvidenceKey.of("landing", "binding-check");
    byte[] plaintext = "not stored in the clear".getBytes(StandardCharsets.UTF_8);
    Retention retention =
        new Retention(new RetentionClass("landing"), clock.now().plus(Duration.ofDays(1826)));

    store.put(new PutRequest(key, plaintext, "text/plain", retention));

    byte[] stored = inner.get(key).orElseThrow().bytes();
    assertThat(Envelope.isEnvelope(stored)).as("the inner store holds an envelope").isTrue();
    assertThat(stored).as("and not the plaintext").isNotEqualTo(plaintext);
    assertThat(store.get(key).orElseThrow().bytes()).isEqualTo(plaintext);
  }
}
