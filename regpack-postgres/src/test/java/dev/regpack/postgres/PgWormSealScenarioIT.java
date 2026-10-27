package dev.regpack.postgres;

import static dev.regpack.testkit.contract.SealScenarioContract.reportOf;
import static dev.regpack.testkit.contract.SealScenarioContract.request;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.retention.RetentionEngine;
import dev.regpack.evidence.s3.WormEvidenceStore;
import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.verify.EvidenceVerifier;
import dev.regpack.evidence.verify.EvidenceVerifier.Report;
import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.postgres.PgWormSupport.S3Settings;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P3b2 T11: the I1 scenarios of {@link SealScenarioContract} (the crash-point harness, 24 inherited
 * scenarios, and since P3c1 T9 the SEAL-1 scenario) on a real PostgreSQL ledger and P-WORM on a
 * real S3-compatible object-lock service: the ledger half and the P-WORM half of "durable before
 * effect" together. A real-store test of the job {@code evidence-worm} (group {@code worm}); it is
 * skipped without {@code REGPACK_PG_URL} and {@code REGPACK_S3_ENDPOINT} (see {@link
 * PgTestDatabase} and {@link PgWormSupport}) and excluded from the module's own run by the group.
 * Its twin with P-SEALED is {@link PgSealScenarioIT}; the one with an in-memory ledger is {@code
 * WormSealScenariosIT} of the module {@code regpack-evidence-s3}. The source-file limb of I1 is
 * P7a.
 *
 * <p><b>Clock.</b> The shared {@code ManualClock} is set to {@value #START_TEXT} before the store
 * is opened (D-W13), so that every retain-until date (1,826 days later, in 2045) lies in the
 * server's future. The ledger reads only this clock (no decision is written, so no day partition is
 * needed), so the later date changes nothing on the PostgreSQL side.
 *
 * <p><b>A fresh ledger and a fresh bucket per test.</b> {@link AbstractPgStoreTest} gives every
 * test its own migrated schema, so the attempt ids start at 1, and this class opens P-WORM on a new
 * object-lock bucket. The store declares one failure domain, so the production gate (two) stays
 * closed for the reason the test names.
 */
@Tag("worm")
class PgWormSealScenarioIT extends AbstractPgStoreTest implements SealScenarioContract {
  private static final String START_TEXT = "2040-03-01T00:00:00Z";
  private static final Instant START = Instant.parse(START_TEXT);

  private WormEvidenceStore evidence;

  @BeforeEach
  void openTheEvidenceStore() {
    clock().set(START);
    S3Settings settings = PgWormSupport.settings();
    String bucket = PgWormSupport.newBucket(PgWormSupport.rawClient(settings));
    evidence = PgWormSupport.openStore(settings, bucket, clock());
  }

  @Override
  public LedgerStore ledger() {
    return store();
  }

  @Override
  public EvidenceStore evidence() {
    return evidence;
  }

  @Override
  public Sealing sealing(LedgerStore ledger, EvidenceStore evidence, boolean productionGate) {
    return new DurableSeal(
        ledger,
        evidence,
        clock(),
        RetentionPolicy.fixedDays(1826),
        new SendGate(
            productionGate ? SendGate.Requirement.production() : SendGate.Requirement.none()));
  }

  /**
   * The sealer with the retention registrar (SEAL-1) over the PostgreSQL ledger and P-WORM: the
   * policy of 1,826 days, a gate that asks nothing and a {@link RetentionEngine} for {@code
   * regime}. Until {@code LedgerStore} extends {@code RetentionLedger} (P3c1 T11) the ledger is
   * cast. NOT RUN LOCALLY: the class skips without {@code REGPACK_PG_URL} and {@code
   * REGPACK_S3_ENDPOINT}; the first run is CP-1.
   */
  @Override
  public Sealing sealingWithRetention(LedgerStore ledger, EvidenceStore evidence, Regime regime) {
    return new DurableSeal(
        ledger,
        evidence,
        clock(),
        RetentionPolicy.fixedDays(1826),
        new SendGate(SendGate.Requirement.none()),
        new RetentionEngine((RetentionLedger) ledger, evidence, regime));
  }

  // ---------------------------------------------------------------------------------------------
  // what the shared suite does not say

  /**
   * The production gate is closed on the PostgreSQL ledger of this job (no standby) and, for its
   * own reason, on the store: P-WORM declares one failure domain (the CI service), the gate asks
   * for two. The evidence side is isolated first (a requirement that asks nothing of the ledger),
   * then the whole gate is shown to refuse the seal and to write nothing.
   */
  @Test
  void theProductionGateIsClosedForOneDeclaredDomain() {
    // the store reads the configuration of the bucket (versioning and object lock) and reports
    // what it declares: synchronous, one domain
    assertThat(evidence().durability()).isEqualTo(new Durability(true, 1));
    SendGate.Result evidenceSide =
        new SendGate(new SendGate.Requirement(false, true, 2)).check(store(), evidence());
    assertThat(evidenceSide.open()).isFalse();
    assertThat(evidenceSide.reasons()).containsExactly(SendGate.tooFewDomains(2));

    EndpointKey endpoint = LedgerFixtures.endpoint("GATE11");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, "BK-GATE11");
    SealRequest request = request(endpoint, token, delivery, "BK-GATE11", reportOf("BK-GATE11"));

    SealOutcome closed = sealing(ledger(), evidence(), true).seal(request);

    assertThat(closed.status()).as(closed.detail()).isEqualTo(SealStatus.GATE_CLOSED);
    assertThat(closed.detail()).contains(SendGate.tooFewDomains(2));
    assertThat(closed.attempt()).isEmpty();
    assertThat(evidence().list("", Optional.empty(), 100)).as("nothing was written").isEmpty();
    assertThat(ledger().delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger().allocateAttemptId())
        .as("no attempt id was taken")
        .isEqualTo(new AttemptId(1));
  }

  /**
   * I10, storage half, on the server and on the service: after successful seals the evidence tier
   * and the manifest that PostgreSQL holds agree, and {@code verify} has nothing to report. The
   * fourth delivery shares the bytes of the first, so the tier holds three sealed files and four
   * intent objects. The counts keep the verdict from being vacuous, and the prefixes narrow the
   * range query of the manifest and the listing alike.
   */
  @Test
  void verifyIsCleanAfterSuccessfulSeals() {
    EndpointKey endpoint = LedgerFixtures.endpoint("VERIFY");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    Sealing sealing = sealing(ledger(), evidence(), false);
    List<AttemptId> attempts = new ArrayList<>();
    String[] businessKeys = {"BK-V1", "BK-V2", "BK-V3", "BK-V4"};
    for (String businessKey : businessKeys) {
      DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, businessKey);
      // the fourth delivery has the bytes of the first
      byte[] bytes = reportOf(businessKey.equals("BK-V4") ? "BK-V1" : businessKey);
      SealOutcome outcome = sealing.seal(request(endpoint, token, delivery, businessKey, bytes));
      assertThat(outcome.status()).as(outcome.detail()).isEqualTo(SealStatus.SEALED);
      attempts.add(outcome.attempt().orElseThrow());
    }
    // one of them has passed the gate of the first byte: its marker is set
    assertThat(sealing.beforeFirstByte(endpoint, attempts.get(0), token))
        .isEqualTo(PreSendStatus.CLEAR);
    EvidenceVerifier verifier = new EvidenceVerifier(evidence(), ledger());

    Report whole = verifier.verify("");

    assertThat(whole.findings()).isEmpty();
    assertThat(whole.clean()).isTrue();
    assertThat(whole.objectsChecked())
        .as("three sealed files and four intent objects")
        .isEqualTo(7);
    assertThat(whole.manifestRowsChecked()).isEqualTo(7);
    Report journal = verifier.verify("journal/");
    assertThat(journal.findings()).isEmpty();
    assertThat(journal.objectsChecked()).isEqualTo(4);
    assertThat(journal.manifestRowsChecked()).isEqualTo(4);
    Report sealedFiles = verifier.verify("sealed/");
    assertThat(sealedFiles.findings()).isEmpty();
    assertThat(sealedFiles.objectsChecked()).isEqualTo(3);
    assertThat(sealedFiles.manifestRowsChecked()).isEqualTo(3);
  }
}
