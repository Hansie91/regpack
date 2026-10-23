package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the restore epoch is taken from the ledger and
 * the marker of the evidence tier is never read, so the comparison of the gate always agrees.
 * {@code SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class SkipEpochCheckScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new SkipEpochCheck(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class SkipEpochCheck extends DurableSeal {
    private final LedgerStore ledgerView;

    SkipEpochCheck(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
      this.ledgerView = ledger;
    }

    @Override
    protected long readEpoch() {
      return ledgerView.restoreEpoch();
    }
  }
}
