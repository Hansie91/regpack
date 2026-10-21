package dev.regpack.evidence.seal;

import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.ledger.LedgerStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong evidence store (D-E10, the decorator form): over P-SEALED on real directories,
 * a store that acknowledges the put of a journal object and does not keep it. The seal itself is
 * the correct {@link DurableSeal}; the scenarios must find the lost object. {@code
 * SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class LostIntentWriteOnSealedScenarioTest extends SealScenariosOnSealedTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return super.create(ledger, new LosesJournalObjects(evidence), gate);
  }

  /** Acknowledges every put under {@code journal/} and stores nothing for it. */
  static final class LosesJournalObjects implements EvidenceStore {
    private final EvidenceStore delegate;

    LosesJournalObjects(EvidenceStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Grade grade() {
      return delegate.grade();
    }

    @Override
    public Durability durability() {
      return delegate.durability();
    }

    @Override
    public PutOutcome put(PutRequest request) {
      if (request.key().path().startsWith("journal/")) {
        return PutOutcome.CREATED;
      }
      return delegate.put(request);
    }

    @Override
    public Optional<StoredObject> get(EvidenceKey key) {
      return delegate.get(key);
    }

    @Override
    public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
      return delegate.list(prefix, after, limit);
    }

    @Override
    public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
      delegate.extendRetention(key, newRetainUntil);
    }

    @Override
    public void setLegalHold(EvidenceKey key, boolean hold) {
      delegate.setLegalHold(key, hold);
    }
  }
}
