package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the gate does not look at the manifest rows that
 * the SENDING unit records, so an attempt that was begun through the ledger alone (no sealed file,
 * no intent object) passes. {@code SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class IgnoreAttemptManifestScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new IgnoreAttemptManifest(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class IgnoreAttemptManifest extends DurableSeal {
    IgnoreAttemptManifest(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected boolean manifestHoldsTheSeal(
        DeliveryLedger.Delivery delivery, DeliveryLedger.Attempt row) {
      return true;
    }
  }
}
