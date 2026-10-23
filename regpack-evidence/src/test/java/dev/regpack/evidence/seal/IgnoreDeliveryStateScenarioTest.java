package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the gate does not look at the state of the
 * delivery of the attempt and leaves the judgement to the marker statement of the ledger, which is
 * then asked about attempts that the gate could have refused. {@code SealingTeethTest} expects the
 * named scenarios to fail.
 */
@Tag("mutant")
class IgnoreDeliveryStateScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new IgnoreDeliveryState(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class IgnoreDeliveryState extends DurableSeal {
    IgnoreDeliveryState(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected boolean deliveryIsSending(DeliveryLedger.Delivery delivery) {
      return true;
    }
  }
}
