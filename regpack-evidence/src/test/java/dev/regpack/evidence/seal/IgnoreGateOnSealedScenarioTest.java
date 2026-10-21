package dev.regpack.evidence.seal;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * The wrong {@link DurableSeal} of {@code IgnoreGateScenarioTest} over P-SEALED on real
 * directories: it is the tooth of the scenario in which a lost volume closes a gate that asks the
 * evidence store for a durable acknowledgement. {@code SealingTeethTest} expects the named
 * scenarios to fail.
 */
@Tag("mutant")
class IgnoreGateOnSealedScenarioTest extends SealScenariosOnSealedTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new IgnoreGateScenarioTest.IgnoreGate(ledger, evidence, clock(), RETENTION, gate);
  }
}
