package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): an outage of the evidence store is swallowed at
 * both puts, so the seal goes on to the SENDING commit as if the object were acknowledged. The one
 * wrong behaviour spans two template methods, because the two outage scenarios fail at different
 * puts. {@code SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class SwallowEvidenceOutageScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new SwallowEvidenceOutage(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class SwallowEvidenceOutage extends DurableSeal {
    SwallowEvidenceOutage(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected void putSealedBytes(SealContext c) {
      try {
        super.putSealedBytes(c);
      } catch (EvidenceUnavailableException e) {
        // swallowed
      }
    }

    @Override
    protected void putIntentObject(SealContext c) {
      try {
        super.putIntentObject(c);
      } catch (EvidenceUnavailableException e) {
        // swallowed
      }
    }
  }
}
