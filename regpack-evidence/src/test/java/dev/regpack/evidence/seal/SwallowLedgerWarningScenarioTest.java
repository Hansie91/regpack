package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): an exception of the ledger that says a commit was
 * not confirmed by the synchronous standby is turned into success: the SENDING unit is reported as
 * applied and the marker unit as set. (The shape of the review mutants DS-V and DS-W.) {@code
 * SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class SwallowLedgerWarningScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new SwallowLedgerWarning(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class SwallowLedgerWarning extends DurableSeal {
    SwallowLedgerWarning(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected DeliveryLedger.SendingResult commitSending(SealContext c) {
      try {
        return super.commitSending(c);
      } catch (LedgerException e) {
        return new DeliveryLedger.SendingResult(
            DeliveryLedger.SendingStatus.APPLIED, Optional.of(c.attempt()));
      }
    }

    @Override
    protected boolean setMarker(DeliveryLedger.AttemptId attempt, LeaseLedger.LeaseToken token) {
      try {
        return super.setMarker(attempt, token);
      } catch (LedgerException e) {
        return true;
      }
    }
  }
}
