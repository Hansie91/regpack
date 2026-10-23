package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the gate does not compare the endpoint of the
 * delivery of the attempt with the endpoint the caller names, so the holder of one endpoint passes
 * the attempt of another endpoint whose token has the same numbers. {@code SealingTeethTest}
 * expects the named scenarios to fail.
 */
@Tag("mutant")
class IgnoreDeliveryEndpointScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new IgnoreDeliveryEndpoint(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class IgnoreDeliveryEndpoint extends DurableSeal {
    IgnoreDeliveryEndpoint(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected boolean deliveryIsOfEndpoint(
        DeliveryLedger.Delivery delivery, LeaseLedger.EndpointKey endpoint) {
      return true;
    }
  }
}
