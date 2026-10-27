package dev.regpack.evidence.s3;

import static dev.regpack.testkit.contract.SealScenarioContract.reportOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.crypto.EncryptingEvidenceStore;
import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.EpochMarker;
import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.verify.EvidenceVerifier;
import dev.regpack.evidence.verify.EvidenceVerifier.Finding;
import dev.regpack.evidence.verify.EvidenceVerifier.Kind;
import dev.regpack.evidence.verify.EvidenceVerifier.Report;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.KekSelector;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T13: {@code verify} over the encrypted tier on P-WORM, and the AT-14 half of I10 on it: a
 * real {@link WormEvidenceStore} on a fresh object-lock bucket under the {@link
 * EncryptingEvidenceStore} (every key encrypted), an in-memory ledger, deliveries sealed by {@link
 * DurableSeal} over the decorator, and the verifier over the decorator too. Two decorators with two
 * KEKs (one KEK each, {@link KekSelector#single}) stand for two cohorts, so that the destruction of
 * one KEK leaves objects of the other to be judged. The key service is the in-memory KMS, a fake:
 * what is shown is the effect of destroying a key on it (the objects cannot be opened and {@code
 * verify} counts them and judges nothing about them), not the destruction in a real KMS.
 *
 * <p>The backend comes from the seam of T8a (four abstract methods): a {@code *Test} subclass runs
 * over the fake, a {@code *IT} subclass over the real service. Nothing is deleted from the bucket:
 * a shredded object stays in it, locked and unreadable.
 */
abstract class AbstractWormEncryptedVerifyTest {
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final RetentionPolicy RETENTION = RetentionPolicy.fixedDays(1826);
  private static final String FIRST_KEK = "kek-2026";
  private static final String SECOND_KEK = "kek-2027";

  /** The raw client of the service, with the test credentials. */
  abstract S3Client rawClient();

  /** A fresh bucket with object lock on. */
  abstract String newBucket();

  /** The clock of {@code X-Amz-Date}: real time against a real service, manual on the fake. */
  abstract Clock signingClock();

  /** The endpoint, region and credentials of the service. */
  abstract S3Settings settings();

  /** What a seal left: the request and the keys of its two objects. */
  private record Sealed(SealRequest request, EvidenceKey sealedKey, SealOutcome outcome) {}

  /**
   * One tier: P-WORM on a fresh bucket, an in-memory ledger, a key service with the KEKs of the
   * fixture and two decorated views of the store, one that wraps under {@value #FIRST_KEK} and one
   * under {@value #SECOND_KEK}. Both open every envelope (the KEK is in its header), so the
   * verifier takes either.
   */
  private final class Tier {
    final ManualClock clock = new ManualClock(AbstractWormContractTest.START);
    final InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    final EncryptedFixtures fixtures = EncryptedFixtures.create();
    final WormEvidenceStore raw;
    final EvidenceStore first;
    final EvidenceStore second;
    final EvidenceVerifier verifier;

    Tier() {
      S3Settings settings = settings();
      WormConfig config =
          WormConfig.governance(
              settings.endpoint(),
              settings.region(),
              newBucket(),
              settings.credentials(),
              new Durability(true, 1));
      raw =
          WormEvidenceStore.open(
              config, clock, signingClock(), new OutageTransport(new JdkTransport(TTL)));
      first = underKek(FIRST_KEK);
      second = underKek(SECOND_KEK);
      verifier = new EvidenceVerifier(second, ledger);
    }

    private EvidenceStore underKek(String kekId) {
      return new EncryptingEvidenceStore(
          raw,
          fixtures.cipher(fixtures.kms(), KekSelector.single(EncryptedFixtures.kek(kekId)), clock),
          EncryptedFixtures.everyKey());
    }

    LeaseToken lease(EndpointKey endpoint) {
      return SealScenarioContract.lease(ledger, endpoint, "A");
    }

    /** Seals one delivery with the sealer over {@code view}: a sealed file and an intent object. */
    Sealed seal(EvidenceStore view, EndpointKey endpoint, LeaseToken token, String businessKey) {
      DeliveryId delivery = SealScenarioContract.queued(ledger, endpoint, businessKey);
      SealRequest request =
          SealScenarioContract.request(
              endpoint, token, delivery, businessKey, reportOf(businessKey));
      SealOutcome outcome =
          new DurableSeal(ledger, view, clock, RETENTION, new SendGate(SendGate.Requirement.none()))
              .seal(request);
      assertThat(outcome.status()).as("the seal: " + outcome.detail()).isEqualTo(SealStatus.SEALED);
      return new Sealed(request, SealedKeys.of(request.intent().artifactSha256()), outcome);
    }

    /**
     * Puts {@code text} through {@code view} as a sealed file, with a manifest row of {@code row}.
     */
    EvidenceKey putWithRow(EvidenceStore view, String text, Sha256 row) {
      byte[] bytes = text.getBytes(UTF_8);
      EvidenceKey key = SealedKeys.of(Sha256.of(bytes));
      view.put(
          new EvidenceStore.PutRequest(
              key,
              bytes,
              "application/xml",
              RETENTION.retentionFor(DurableSeal.SEALED_RETENTION_CLASS, clock.now())));
      ledger.recordObjects(
          List.of(new ObjectEntry(key, ObjectClass.SEALED_FILE, row, bytes.length)),
          Provenance.WRITTEN,
          Optional.empty());
      return key;
    }
  }

  @Test
  void aCleanEncryptedTierHasNoFinding() {
    Tier tier = new Tier();
    assertThat(tier.verifier.verify("")).isEqualTo(new Report(List.of(), 0, 0));

    EndpointKey endpoint = LedgerFixtures.endpoint("EVER1");
    LeaseToken token = tier.lease(endpoint);
    Sealed a = tier.seal(tier.first, endpoint, token, "BK-A");
    tier.seal(tier.second, endpoint, token, "BK-B");
    Report afterSealing = tier.verifier.verify("");

    assertThat(afterSealing.findings()).isEmpty();
    assertThat(afterSealing.clean()).isTrue();
    assertThat(afterSealing.objectsChecked())
        .as("two sealed files and two intent objects")
        .isEqualTo(4);
    assertThat(afterSealing.manifestRowsChecked()).isEqualTo(4);
    assertThat(afterSealing.objectsShredded()).isZero();
    // the check is not vacuous: P-WORM holds envelopes, and the row names the plaintext
    byte[] stored = tier.raw.get(a.sealedKey()).orElseThrow().bytes();
    assertThat(Envelope.isEnvelope(stored)).isTrue();
    assertThat(Sha256.of(stored)).isNotEqualTo(Sha256.of(reportOf("BK-A")));
    assertThat(tier.ledger.manifestRow(a.sealedKey()).orElseThrow().entry().sha256())
        .isEqualTo(Sha256.of(reportOf("BK-A")));
    // an epoch marker, written through the decorator before the ledger knows the epoch, is no
    // orphan
    new EpochMarker(tier.first, RETENTION, tier.clock).declare(1);
    Report withMarker = tier.verifier.verify("");
    assertThat(withMarker.findings()).isEmpty();
    assertThat(withMarker.objectsChecked()).isEqualTo(5);
    assertThat(withMarker.manifestRowsChecked()).isEqualTo(4);
    // the prefix narrows both sides alike
    Report journal = tier.verifier.verify("journal/");
    assertThat(journal).isEqualTo(new Report(List.of(), 2, 2));
    Report sealedFiles = tier.verifier.verify("sealed/");
    assertThat(sealedFiles).isEqualTo(new Report(List.of(), 2, 2));
  }

  @Test
  void verifyStaysCleanAfterTheKekOfAnObjectIsDestroyed() {
    Tier tier = new Tier();
    EndpointKey endpoint = LedgerFixtures.endpoint("EVER2");
    LeaseToken token = tier.lease(endpoint);
    Sealed shreddedLater = tier.seal(tier.first, endpoint, token, "BK-A");
    Sealed kept = tier.seal(tier.second, endpoint, token, "BK-B");
    assertThat(tier.verifier.verify("")).isEqualTo(new Report(List.of(), 4, 4, 0));
    assertThat(tier.second.get(shreddedLater.sealedKey()).orElseThrow().bytes())
        .isEqualTo(reportOf("BK-A"));
    byte[] envelope = tier.raw.get(shreddedLater.sealedKey()).orElseThrow().bytes();

    tier.fixtures.destroy(FIRST_KEK);
    Report report = tier.verifier.verify("");

    // the objects of the destroyed KEK cannot be opened, whichever decorated view is asked
    assertThatThrownBy(() -> tier.second.get(shreddedLater.sealedKey()))
        .isInstanceOf(DataShreddedException.class);
    assertThatThrownBy(() -> tier.first.get(shreddedLater.sealedKey()))
        .isInstanceOf(DataShreddedException.class);
    // verify still passes: the two shredded objects are counted and not judged, the two of the
    // other
    // KEK are judged and clean, and every manifest row is still there
    assertThat(report.findings()).isEmpty();
    assertThat(report.clean()).isTrue();
    assertThat(report.objectsShredded())
        .as("the sealed file and the intent object of the first delivery")
        .isEqualTo(2);
    assertThat(report.objectsChecked()).isEqualTo(4);
    assertThat(report.manifestRowsChecked()).isEqualTo(4);
    // the tier is not rebuilt and nothing is deleted: the envelope is in the bucket as it was,
    // locked and unreadable, and the object of the other KEK still opens
    assertThat(tier.raw.get(shreddedLater.sealedKey()).orElseThrow().bytes()).isEqualTo(envelope);
    assertThat(tier.second.get(kept.sealedKey()).orElseThrow().bytes()).isEqualTo(reportOf("BK-B"));
    assertThat(tier.verifier.verify("journal/")).isEqualTo(new Report(List.of(), 2, 2, 1));

    // the objects of the other KEK are still judged against their rows: a wrong row is reported,
    // and the count of the shredded stays
    EvidenceKey wrong =
        tier.putWithRow(tier.second, "another of the second KEK", Sha256.ofUtf8("no"));
    Report again = tier.verifier.verify("");
    assertThat(again.findings())
        .containsExactly(
            new Finding(Kind.HASH_MISMATCH, wrong.path(), EvidenceVerifier.MANIFEST_HASH));
    assertThat(again.objectsShredded()).isEqualTo(2);
    assertThat(again.objectsChecked()).isEqualTo(5);
    assertThat(again.manifestRowsChecked()).isEqualTo(5);
  }
}
