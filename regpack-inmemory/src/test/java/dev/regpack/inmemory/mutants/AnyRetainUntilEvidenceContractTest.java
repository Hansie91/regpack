package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractEvidenceContractTest;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code ACCEPT_ANY_RETAIN_UNTIL}); ContractSuiteTeethTest expects
 * named tests to fail.
 */
@Tag("mutant")
public class AnyRetainUntilEvidenceContractTest extends AbstractEvidenceContractTest {
  @Override
  public EvidenceStore create(ManualClock clock) {
    return new InMemoryEvidenceStore(clock, InMemoryEvidenceStore.Flaw.ACCEPT_ANY_RETAIN_UNTIL);
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
