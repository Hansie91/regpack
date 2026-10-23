package dev.regpack.evidence.seal;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * The wrong {@link DurableSeal} of {@code SwallowEvidenceOutageScenarioTest} over P-SEALED on real
 * directories: it is the tooth of the scenario in which a volume is lost and the store can no
 * longer acknowledge. {@code SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class SwallowEvidenceOutageOnSealedScenarioTest extends SealScenariosOnSealedTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new SwallowEvidenceOutageScenarioTest.SwallowEvidenceOutage(
        ledger, evidence, clock(), RETENTION, gate);
  }
}
