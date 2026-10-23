package dev.regpack.evidence.seal;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong {@link DurableSeal} (D-E10): the sealed bytes are stored under a key of the
 * attempt instead of the content-addressed key, while the manifest names the content-addressed key.
 * {@code SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class AttemptScopedSealedKeyScenarioTest extends SealScenariosInMemoryTest {
  @Override
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new AttemptScopedSealedKey(ledger, evidence, clock(), RETENTION, gate);
  }

  static final class AttemptScopedSealedKey extends DurableSeal {
    private final EvidenceStore store;
    private final RetentionPolicy retentionPolicy;
    private final Clock clockView;

    AttemptScopedSealedKey(
        LedgerStore ledger,
        EvidenceStore evidence,
        Clock clock,
        RetentionPolicy retention,
        SendGate gate) {
      super(ledger, evidence, clock, retention, gate);
      this.store = evidence;
      this.retentionPolicy = retention;
      this.clockView = clock;
    }

    @Override
    protected void putSealedBytes(SealContext c) {
      store.put(
          new EvidenceStore.PutRequest(
              EvidenceKey.of("sealed", "attempt-" + c.attempt().value()),
              c.request().bytes(),
              c.request().mediaType(),
              retentionPolicy.retentionFor(SEALED_RETENTION_CLASS, clockView.now())));
    }
  }
}
