package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LedgerStore;
import java.util.Map;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the intent object always says {@code action}
 * NEWT, whatever the delivery is. {@code SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class HardCodedActionScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new HardCodedAction(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class HardCodedAction extends DurableSeal {
    HardCodedAction(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected Map<String, Object> intentPayload(
        SealRequest request, DeliveryLedger.AttemptId attempt) {
      Map<String, Object> payload = super.intentPayload(request, attempt);
      payload.put("action", "NEWT");
      return payload;
    }
  }
}
