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
 * one except that it does not refuse an attempt whose {@code commit_issued} marker is already set.
 * The marker statement of the ledger is idempotent, so a second gate for the same attempt (a
 * restarted process) would let a second copy of the file go out. {@code SealingTeethTest} expects
 * the scenarios of a crash after the marker and after the first byte to fail.
 */
@Tag("mutant")
class IgnoreMarkerAlreadySetScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new IgnoreMarkerAlreadySet(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class IgnoreMarkerAlreadySet extends DurableSeal {
    private final LedgerStore ledgerView;

    IgnoreMarkerAlreadySet(
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
      Optional<LeaseLedger.LeaseGrant> lease = ledgerView.currentLease(endpoint);
      if (lease.isEmpty() || !lease.get().token().equals(token)) {
        return PreSendStatus.STALE_LEASE;
      }
      // the mutation: the row is only required to exist; a marker that is set is not looked at
      if (ledgerView.attempt(attempt).isEmpty()) {
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
