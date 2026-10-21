package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the gate in front of the first byte always says
 * {@code CLEAR} and sets no marker. {@code SealingTeethTest} expects the named scenarios to fail.
 * This one overrides the public gate method, which is not a template method: it is the mutation
 * that the scenarios of the lease, the epoch marker and the marker itself have in common.
 */
@Tag("mutant")
class AlwaysClearScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new AlwaysClear(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class AlwaysClear extends DurableSeal {
    AlwaysClear(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    public PreSendStatus beforeFirstByte(
        LeaseLedger.EndpointKey endpoint,
        DeliveryLedger.AttemptId attempt,
        LeaseLedger.LeaseToken token) {
      return PreSendStatus.CLEAR;
    }
  }
}
