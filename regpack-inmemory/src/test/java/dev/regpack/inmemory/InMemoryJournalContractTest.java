package dev.regpack.inmemory;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.JournalContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/** Binds the journal-format contract to the in-memory evidence store. */
class InMemoryJournalContractTest implements JournalContract {
  private ManualClock clock;
  private InMemoryEvidenceStore store;

  @BeforeEach
  void setUpStore() {
    clock = new ManualClock(Instant.parse("2026-10-19T12:00:00Z"));
    store = new InMemoryEvidenceStore(clock);
  }

  @Override
  public EvidenceStore store() {
    return store;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  @Override
  public void simulateOutage(boolean down) {
    store.simulateOutage(down);
  }
}
