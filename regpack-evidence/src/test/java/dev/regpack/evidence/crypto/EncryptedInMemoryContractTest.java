package dev.regpack.evidence.crypto;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.EvidenceStoreContract;
import dev.regpack.testkit.contract.JournalContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/**
 * P3c1 T6: the {@code EvidenceStore} contract suite and the journal-format contract suite, both
 * unchanged, against the {@link EncryptingEvidenceStore} (every key encrypted) over the in-memory
 * store. Every method comes from the two shared interfaces; this class adds none. The store, the
 * cipher and the key service share one {@link ManualClock}.
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
}
