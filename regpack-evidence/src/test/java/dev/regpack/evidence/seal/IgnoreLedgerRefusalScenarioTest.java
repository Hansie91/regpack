package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LedgerStore;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): a refusal of the SENDING unit (stale lease,
 * delivery no longer queued, another delivery in flight) is reported as a success. {@code
 * SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class IgnoreLedgerRefusalScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new IgnoreLedgerRefusal(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class IgnoreLedgerRefusal extends DurableSeal {
    IgnoreLedgerRefusal(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected DeliveryLedger.SendingResult commitSending(SealContext c) {
      DeliveryLedger.SendingResult result = super.commitSending(c);
      return result.status() == DeliveryLedger.SendingStatus.APPLIED
          ? result
          : new DeliveryLedger.SendingResult(
              DeliveryLedger.SendingStatus.APPLIED, Optional.of(c.attempt()));
    }
  }
}
