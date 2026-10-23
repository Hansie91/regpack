package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the restore-epoch marker of the evidence tier is
 * never read and the epoch is taken as 0, which is what "no marker" means. {@code SealingTeethTest}
 * expects the named scenarios to fail; unlike {@code SkipEpochCheck} this one also breaks the send
 * of a ledger that is in a later epoch.
 */
@Tag("mutant")
class MarkerAlwaysAbsentScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new MarkerAlwaysAbsent(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class MarkerAlwaysAbsent extends DurableSeal {
    MarkerAlwaysAbsent(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected long readEpoch() {
      return 0;
    }
  }
}
