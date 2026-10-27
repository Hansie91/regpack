package dev.regpack.evidence.seal;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.seal.Sealing;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong sealer (SEAL-1, KF12): the hook {@code sealingWithRetention} returns a {@link
 * DurableSeal} with the five-argument constructor, which registers nothing. A second delivery of
 * the same bytes then shares the sealed object and leaves it with the retention of the first put,
 * and no {@code retention} row exists. {@code SealingTeethTest} expects the scenario {@code
 * theSameBytesSealedLaterExtendTheSharedObjectAndKeepOneRow} to fail, with an assertion.
 */
@Tag("mutant")
class ForgetsSharedRetentionScenarioTest extends SealScenariosInMemoryTest {
  @Override
  public Sealing sealingWithRetention(LedgerStore ledger, EvidenceStore evidence, Regime regime) {
    return new DurableSeal(ledger, evidence, clock(), RETENTION, gate(false));
  }
}
