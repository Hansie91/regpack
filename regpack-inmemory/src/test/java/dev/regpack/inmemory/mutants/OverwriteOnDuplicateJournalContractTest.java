package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.JournalContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code OVERWRITE_ON_DUPLICATE}) under the journal-format contract;
 * ContractSuiteTeethTest expects the named tests to fail.
 */
@Tag("mutant")
public class OverwriteOnDuplicateJournalContractTest implements JournalContract {
  private ManualClock clock;
  private InMemoryEvidenceStore store;

  @BeforeEach
  void setUpStore() {
    clock = new ManualClock(Instant.parse("2026-10-19T12:00:00Z"));
    store = new InMemoryEvidenceStore(clock, InMemoryEvidenceStore.Flaw.OVERWRITE_ON_DUPLICATE);
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
