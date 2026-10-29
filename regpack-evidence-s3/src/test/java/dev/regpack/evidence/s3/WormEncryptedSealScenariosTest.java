package dev.regpack.evidence.s3;

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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * P3c1 T13: the 25 seal-and-send scenarios of {@code SealScenarioContract} (the 24 of P3b and the
 * SEAL-1 scenario of T9), unchanged, with the {@link
 * dev.regpack.evidence.crypto.EncryptingEvidenceStore} (every key encrypted, one KEK) over P-WORM
 * on the in-JVM fake S3 service. The binding overrides {@link #evidence()} only: the scenarios and
 * the SEAL-1 hook take their store from it, so the sealer, the epoch marker and the journal write
 * through the decorator. The manifest rows hold the hashes of the plaintext handed in (the
 * decorator is below the sealer, D-K3), so the scenarios that compare a stored object with its row
 * run as they are.
 *
 * <p>The two own methods of {@link AbstractWormSealTest} use the raw store and are not claimed for
 * the encrypted tier; the own method of {@link WormSealScenariosTest} (a marker behind the
 * adapter's paging) reads {@code evidence()} and so also runs over the decorator, where the markers
 * are encrypted like every other key. The service twin is {@link WormEncryptedSealScenariosIT}.
 */
class WormEncryptedSealScenariosTest extends WormSealScenariosTest {
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
    EndpointKey endpoint = LedgerFixtures.endpoint("ENCW1");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, "BK-ENCW1");
    byte[] report = SealScenarioContract.reportOf("BK-ENCW1");

    SealOutcome outcome =
        sealing(ledger(), evidence(), false)
            .seal(SealScenarioContract.request(endpoint, token, delivery, "BK-ENCW1", report));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(report));
    // P-WORM holds an envelope, the decorated view the plaintext
    byte[] raw = super.evidence().get(sealedKey).orElseThrow().bytes();
    assertThat(Envelope.isEnvelope(raw)).isTrue();
    assertThat(raw).isNotEqualTo(report);
    assertThat(evidence().get(sealedKey).orElseThrow().bytes()).isEqualTo(report);
    // and the ledger row names the hash of the plaintext
    assertThat(ledger().manifestRow(sealedKey).orElseThrow().entry().sha256())
        .isEqualTo(Sha256.of(report));
  }

  /**
   * After every scenario: each object the scenario left in P-WORM is an envelope. The two own
   * methods of {@link AbstractWormSealTest} build their sealer over the raw store on purpose and
   * are left out; every other method, inherited or not, writes through {@link #evidence()}. Without
   * this a policy that skips a namespace, a class of keys or a key length would leave the 25
   * scenarios green.
   */
  @AfterEach
  void everyObjectTheMethodStoredIsAnEnvelope(TestInfo info) {
    if (encrypted == null) {
      // the service is missing and the test did not start: nothing was stored
      return;
    }
    Set<String> rawOnPurpose =
        Arrays.stream(AbstractWormSealTest.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Test.class))
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertThat(rawOnPurpose).as("the own test methods of AbstractWormSealTest").hasSize(2);
    if (info.getTestMethod().map(method -> rawOnPurpose.contains(method.getName())).orElse(false)) {
      return;
    }
    simulateOutage(false);
    List<EvidenceKey> keys = super.evidence().list("", Optional.empty(), 1000);
    assertThat(keys).as("the listing is one whole page").hasSizeLessThan(1000);
    for (EvidenceKey key : keys) {
      assertThat(Envelope.isEnvelope(super.evidence().get(key).orElseThrow().bytes()))
          .as("%s is stored as an envelope", key.path())
          .isTrue();
    }
  }
}
