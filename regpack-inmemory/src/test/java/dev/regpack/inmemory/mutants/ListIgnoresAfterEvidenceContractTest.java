package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractEvidenceContractTest;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code LIST_IGNORES_AFTER}); ContractSuiteTeethTest expects named
 * tests to fail.
 */
@Tag("mutant")
public class ListIgnoresAfterEvidenceContractTest extends AbstractEvidenceContractTest {
  @Override
  public EvidenceStore create(ManualClock clock) {
    return new InMemoryEvidenceStore(clock, InMemoryEvidenceStore.Flaw.LIST_IGNORES_AFTER);
  }

  @Override
  public void simulateOutage(boolean down) {
    ((InMemoryEvidenceStore) store()).simulateOutage(down);
  }

  @Override
  public EvidenceStore reopen() {
    return store();
  }
}
