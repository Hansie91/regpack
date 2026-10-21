package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LedgerStore;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the gate in front of the first byte is the real
 * one except that its lease check only asks whether the endpoint has a lease, not whether the lease
 * is the sender's token. The fence of the marker statement still refuses, but with another status:
 * the gate must say {@code STALE_LEASE} itself. {@code SealingTeethTest} expects the scenario of a
 * takeover between the seal and the marker to fail.
 */
@Tag("mutant")
class LeaseCheckWithoutTokenScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new LeaseCheckWithoutToken(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class LeaseCheckWithoutToken extends DurableSeal {
    private final LedgerStore ledgerView;

    LeaseCheckWithoutToken(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
      this.ledgerView = ledger;
    }

    @Override
    public PreSendStatus beforeFirstByte(
        LeaseLedger.EndpointKey endpoint,
        DeliveryLedger.AttemptId attempt,
        LeaseLedger.LeaseToken token) {
      if (!gateResult().open()) {
        return PreSendStatus.GATE_CLOSED;
      }
      // the mutation: a lease of anybody satisfies the check
      Optional<LeaseLedger.LeaseGrant> lease = ledgerView.currentLease(endpoint);
      if (lease.isEmpty()) {
        return PreSendStatus.STALE_LEASE;
      }
      Optional<DeliveryLedger.Attempt> row = ledgerView.attempt(attempt);
      if (row.isEmpty() || row.get().commitIssued()) {
        return PreSendStatus.MARKER_REFUSED;
      }
      long epoch;
      try {
        epoch = readEpoch();
      } catch (RuntimeException e) {
        return PreSendStatus.EPOCH_MARKER_UNREADABLE;
      }
      if (epoch != token.restoreEpoch()) {
        return PreSendStatus.EPOCH_MARKER_DIFFERS;
      }
      return ledgerView.markCommitIssued(attempt, token)
          ? PreSendStatus.CLEAR
          : PreSendStatus.MARKER_REFUSED;
    }
  }
}
