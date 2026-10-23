package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LedgerStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the SENDING commit is made before the sealed
 * bytes and the intent object are put. {@code SealingTeethTest} expects the named scenarios to
 * fail. Everything else behaves as the correct class: the result of the early commit is handed back
 * when {@code seal} asks for the commit, so that the scenarios that fail do so on what they assert
 * and not on a stray exception.
 */
@Tag("mutant")
class CommitBeforePutsScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new CommitBeforePuts(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class CommitBeforePuts extends DurableSeal {
    private final Map<Long, DeliveryLedger.SendingResult> early = new ConcurrentHashMap<>();

    CommitBeforePuts(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
    }

    @Override
    protected void putSealedBytes(SealContext c) {
      early.put(c.attempt().value(), super.commitSending(c));
      super.putSealedBytes(c);
    }

    @Override
    protected DeliveryLedger.SendingResult commitSending(SealContext c) {
      DeliveryLedger.SendingResult done = early.remove(c.attempt().value());
      return done != null ? done : super.commitSending(c);
    }
  }
}
