package dev.regpack.evidence.seal;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;

/**
 * T14a: the crash-point harness and the I1 scenarios of {@code SealScenarioContract} on an
 * in-memory ledger and the in-memory evidence store. Every method comes from the shared suite. The
 * mutant bindings of {@code SealingTeethTest} extend this class.
 */
class SealScenariosInMemoryTest extends AbstractSealScenarioTest {
  @Override
  EvidenceStore createEvidence(ManualClock clock) {
    return new InMemoryEvidenceStore(clock);
  }
}
