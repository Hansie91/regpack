package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.LedgerStore;
import java.util.List;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the send gate is computed and then ignored, so a
 * closed gate stops neither the seal nor the first byte. {@code SealingTeethTest} expects the named
 * scenarios to fail.
 */
@Tag("mutant")
class IgnoreGateScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new IgnoreGate(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class IgnoreGate extends DurableSeal {
    IgnoreGate(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected SendGate.Result gateResult() {
      return new SendGate.Result(true, List.of());
    }
  }
}
