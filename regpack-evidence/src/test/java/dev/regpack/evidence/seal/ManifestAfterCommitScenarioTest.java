package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the manifest rows are recorded in a second unit
 * after the SENDING commit instead of in the same unit. {@code SealingTeethTest} expects the named
 * scenarios to fail.
 */
@Tag("mutant")
class ManifestAfterCommitScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new ManifestAfterCommit(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class ManifestAfterCommit extends DurableSeal {
    private final LedgerStore ledgerView;

    ManifestAfterCommit(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
      this.ledgerView = ledger;
    }

    @Override
    protected DeliveryLedger.SendingResult commitSending(SealContext c) {
      DeliveryLedger.SendingResult result =
          ledgerView.beginSending(
              c.request().delivery(),
              c.attempt(),
              c.token(),
              new DeliveryLedger.AttemptIntent(
                  c.request().intent().artifactSha256(), c.request().fileName()));
      if (result.status() == DeliveryLedger.SendingStatus.APPLIED) {
        ledgerView.recordObjects(
            c.entries(), ManifestLedger.Provenance.WRITTEN, Optional.of(c.attempt()));
      }
      return result;
    }
  }
}
