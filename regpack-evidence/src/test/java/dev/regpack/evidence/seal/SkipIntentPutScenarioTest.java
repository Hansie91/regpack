package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the {@code DeliveryIntent} journal object is
 * never put, although the manifest row for it is recorded. {@code SealingTeethTest} expects the
 * named scenarios to fail.
 */
@Tag("mutant")
class SkipIntentPutScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new SkipIntentPut(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class SkipIntentPut extends DurableSeal {
    SkipIntentPut(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected void putIntentObject(SealContext c) {
      // the intent object is not written
    }
  }
}
