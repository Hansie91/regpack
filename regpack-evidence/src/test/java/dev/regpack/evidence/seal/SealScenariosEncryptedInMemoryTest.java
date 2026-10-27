package dev.regpack.evidence.seal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.crypto.EncryptedFixtures;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T6: the shared seal-and-send scenarios of {@code SealScenarioContract}, unchanged, with the
 * {@link dev.regpack.evidence.crypto.EncryptingEvidenceStore} (every key encrypted) over the
 * in-memory evidence store. The manifest rows hold the hashes of the plaintext handed in, so the
 * scenarios that compare a stored object with its row run as they are.
 */
class SealScenariosEncryptedInMemoryTest extends SealScenariosInMemoryTest {
  private final EncryptedFixtures fixtures = EncryptedFixtures.create();
  private InMemoryEvidenceStore inner;

  @Override
  EvidenceStore createEvidence(ManualClock clock) {
    inner = new InMemoryEvidenceStore(clock);
    return fixtures.encrypt(inner, clock);
  }

  @Test
  void theBindingReallyEncryptsWhatTheScenariosSeal() {
    EndpointKey endpoint = LedgerFixtures.endpoint("ENC1");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, "BK-ENC1");
    byte[] report = SealScenarioContract.reportOf("BK-ENC1");

    SealOutcome outcome =
        sealing(ledger(), evidence(), false)
            .seal(SealScenarioContract.request(endpoint, token, delivery, "BK-ENC1", report));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(report));
    // the raw store holds an envelope, the decorated view the plaintext
    byte[] raw = inner.get(sealedKey).orElseThrow().bytes();
    assertThat(Envelope.isEnvelope(raw)).isTrue();
    assertThat(raw).isNotEqualTo(report);
    assertThat(evidence().get(sealedKey).orElseThrow().bytes()).isEqualTo(report);
    // and the ledger row names the hash of the plaintext
    assertThat(ledger().manifestRow(sealedKey).orElseThrow().entry().sha256())
        .isEqualTo(Sha256.of(report));
  }
}
