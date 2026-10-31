package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.model.Sha256;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T13: the 25 seal-and-send scenarios of {@link SealScenarioContract} (the 24 of P3b and the
 * SEAL-1 scenario of T9), unchanged, on the real PostgreSQL ledger and the {@link
 * dev.regpack.evidence.crypto.EncryptingEvidenceStore} (every key encrypted, one KEK) over P-SEALED
 * on two directories of a {@code @TempDir}: the encrypted tier with the ledger that holds the
 * manifest of the plaintext hashes. The binding overrides {@link #evidence()} only, as the class it
 * extends builds its sealer from the store it is given. The key service is the in-memory KMS, a
 * fake (the test-scope dependency of this module on {@code regpack-inmemory}, AP-5).
 *
 * <p>The four own methods of {@link PgSealScenarioIT} are inherited and also run over the decorator
 * (the concurrency test, the manifest-conflict test, the freshness test and {@code verify} after
 * sealing); they are counted in the tally of the job {@code ledger-pg} with the 25 scenarios and
 * are not claimed as part of the shared suite of the encrypted tier. A real-store test: it is
 * skipped without {@code REGPACK_PG_URL} (see {@link PgTestDatabase}) and runs in the job {@code
 * ledger-pg} (it is not tagged {@code worm}: it needs no S3 service).
 */
class PgEncryptedSealScenarioIT extends PgSealScenarioIT {
  private final EncryptedFixtures fixtures = EncryptedFixtures.create();
  private EvidenceStore encrypted;

  @BeforeEach
  void encryptTheEvidence() {
    encrypted = fixtures.encrypt(super.evidence(), clock());
  }

  @Override
  public EvidenceStore evidence() {
    return encrypted;
  }

  @Test
  void theBindingReallyEncryptsWhatTheScenariosSeal() {
    EndpointKey endpoint = LedgerFixtures.endpoint("ENCP1");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, "BK-ENCP1");
    byte[] report = SealScenarioContract.reportOf("BK-ENCP1");

    SealOutcome outcome =
        sealing(ledger(), evidence(), false)
            .seal(SealScenarioContract.request(endpoint, token, delivery, "BK-ENCP1", report));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(report));
    // P-SEALED holds an envelope, the decorated view the plaintext
    byte[] raw = super.evidence().get(sealedKey).orElseThrow().bytes();
    assertThat(Envelope.isEnvelope(raw)).isTrue();
    assertThat(raw).isNotEqualTo(report);
    assertThat(evidence().get(sealedKey).orElseThrow().bytes()).isEqualTo(report);
    // and the PostgreSQL manifest row names the hash of the plaintext
    assertThat(ledger().manifestRow(sealedKey).orElseThrow().entry().sha256())
        .isEqualTo(Sha256.of(report));
  }

  /**
   * After every method, the 25 scenarios and the four own methods of {@link PgSealScenarioIT}
   * alike: each object that the method left in P-SEALED is an envelope. Without this a policy that
   * skips a namespace, a class of keys or a key length would leave the scenarios green. It runs
   * before the base class drops the schema; without a server nothing was stored and it does
   * nothing.
   */
  @AfterEach
  void everyObjectTheMethodStoredIsAnEnvelope() {
    if (encrypted == null) {
      return;
    }
    List<EvidenceKey> keys = super.evidence().list("", Optional.empty(), 1000);
    assertThat(keys).as("the listing is one whole page").hasSizeLessThan(1000);
    for (EvidenceKey key : keys) {
      assertThat(Envelope.isEnvelope(super.evidence().get(key).orElseThrow().bytes()))
          .as("%s is stored as an envelope", key.path())
          .isTrue();
    }
  }
}
