package dev.regpack.evidence.s3;

import static dev.regpack.testkit.contract.SealScenarioContract.reportOf;
import static dev.regpack.testkit.contract.SealScenarioContract.request;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.retention.RetentionEngine;
import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.WormConfig.Verification;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Regime;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerDurability;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T9: binds the shared seal-and-send scenarios ({@link SealScenarioContract}, the 24 scenarios,
 * unchanged, and since P3c1 T9 the SEAL-1 scenario, through {@link #sealingWithRetention}) to a
 * {@link DurableSeal} over an in-memory ledger and {@link WormEvidenceStore} on a fresh object-lock
 * bucket, as {@code AbstractSealScenarioTest} does for the other evidence stores. The backend comes
 * from the seam of T8a (four abstract methods): a {@code *Test} subclass implements it over a fresh
 * {@code FakeS3Server}, a {@code *IT} subclass over the real service. The data clock is a {@link
 * ManualClock} at {@code 2040-03-01T00:00:00Z} (D-W13), so that every retain-until stays in the
 * server's future. Methods that need a fault switch of the fake live in the {@code *Test} subclass,
 * never here.
 *
 * <p>A subclass creates its backend lazily (the first call of a seam method), because this class's
 * {@code @BeforeEach} runs before the one of a subclass.
 */
abstract class AbstractWormSealTest implements SealScenarioContract {
  /** The retention policy the scenarios run with (OI-6: tests and tools only). */
  static final RetentionPolicy RETENTION = RetentionPolicy.fixedDays(1826);

  private ManualClock clock;
  private InMemoryLedgerStore ledger;
  private OutageTransport transport;
  private WormConfig config;
  private WormEvidenceStore evidence;

  /** The raw client of the service, with the test credentials. */
  abstract S3Client rawClient();

  /** A fresh bucket with object lock on. */
  abstract String newBucket();

  /** The clock of {@code X-Amz-Date}: real time against a real service, manual on the fake. */
  abstract Clock signingClock();

  /** The endpoint, region and credentials of the service. */
  abstract S3Settings settings();

  @BeforeEach
  void setUpStores() {
    clock = new ManualClock(AbstractWormContractTest.START);
    ledger = new InMemoryLedgerStore(clock);
    transport = new OutageTransport(new JdkTransport(Duration.ofSeconds(60)));
    S3Settings settings = settings();
    config =
        WormConfig.governance(
            settings.endpoint(),
            settings.region(),
            newBucket(),
            settings.credentials(),
            new Durability(true, 1));
    evidence = WormEvidenceStore.open(config, clock, signingClock(), transport);
  }

  @Override
  public LedgerStore ledger() {
    return ledger;
  }

  @Override
  public EvidenceStore evidence() {
    return evidence;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  /** The send gate of a binding: {@code production()} or {@code none()}. */
  static SendGate gate(boolean productionGate) {
    return new SendGate(
        productionGate ? SendGate.Requirement.production() : SendGate.Requirement.none());
  }

  /** The sealer under test, over any ledger, store and gate. */
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new DurableSeal(ledger, evidence, clock, RETENTION, gate);
  }

  @Override
  public Sealing sealing(LedgerStore ledger, EvidenceStore evidence, boolean productionGate) {
    return create(ledger, evidence, gate(productionGate));
  }

  /**
   * The sealer with the retention registrar (SEAL-1): the policy of {@link #RETENTION}, a gate that
   * asks nothing and a {@link RetentionEngine} for {@code regime} over the given ports.
   */
  @Override
  public Sealing sealingWithRetention(LedgerStore ledger, EvidenceStore evidence, Regime regime) {
    return new DurableSeal(
        ledger,
        evidence,
        clock,
        RETENTION,
        gate(false),
        new RetentionEngine(ledger, evidence, regime));
  }

  /** The configuration of the store under test (its bucket and endpoint). */
  WormConfig config() {
    return config;
  }

  /** Switches the transport of the store under test off or on (no HTTP answer while it is off). */
  void simulateOutage(boolean down) {
    transport.setDown(down);
  }

  /** A queued delivery under a held lease, and the request that seals it. */
  private record Fixture(
      EndpointKey endpoint, LeaseToken token, DeliveryId delivery, SealRequest request) {}

  private Fixture fixture(String name, String businessKey) {
    EndpointKey endpoint = LedgerFixtures.endpoint(name);
    LeaseToken token = SealScenarioContract.lease(ledger, endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger, endpoint, businessKey);
    return new Fixture(
        endpoint,
        token,
        delivery,
        request(endpoint, token, delivery, businessKey, reportOf(businessKey)));
  }

  @Test
  void theProductionGateIsClosedForOneDeclaredDomainAndOpensForThreeDeclaredByTheTest() {
    // the ledger side of the production gate is open (a test double that says so)
    ledger.setDurability(new LedgerDurability.Status(true, 1, "test"));
    Fixture f = fixture("GATE9", "BK-GATE9");

    // the CI service declares one failure domain: the gate (two) stays closed, nothing is written
    SealOutcome closed = sealing(ledger, evidence, true).seal(f.request());
    assertThat(closed.status()).isEqualTo(SealStatus.GATE_CLOSED);
    assertThat(closed.detail()).isEqualTo(SendGate.tooFewDomains(2));
    assertThat(evidence.list("", Optional.empty(), 100)).isEmpty();
    assertThat(ledger.delivery(f.delivery()).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.allocateAttemptId())
        .as("no attempt id was taken")
        .isEqualTo(new AttemptId(1));

    // a declaration in a test, not a verified store: three domains are declared, and that is
    // allowed only with Verification.SP_O_VERIFIED (D-W9). The store is not more durable for it.
    WormConfig declared =
        new WormConfig(
            config.endpoint(),
            config.region(),
            config.bucket(),
            config.credentials(),
            config.mode(),
            Verification.SP_O_VERIFIED,
            new Durability(true, 3),
            config.maxLockDate(),
            config.requestTimeout());
    WormEvidenceStore threeDomains =
        WormEvidenceStore.open(declared, clock, signingClock(), transport);
    SealOutcome open = sealing(ledger, threeDomains, true).seal(f.request());
    assertThat(open.status()).as(open.detail()).isEqualTo(SealStatus.SEALED);
    assertThat(ledger.delivery(f.delivery()).orElseThrow().state())
        .isEqualTo(DeliveryState.SENDING);
    assertThat(threeDomains.list("", Optional.empty(), 100)).hasSize(2);
  }

  @Test
  void aSealOnAnOutageOfTheServiceIsEvidenceUnavailableAndLeavesTheLedgerUntouched() {
    Fixture f = fixture("OUT9", "BK-OUT9");
    simulateOutage(true);

    SealOutcome outcome = sealing(ledger, evidence, false).seal(f.request());

    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail()).isNotBlank().doesNotContain(f.request().intent().businessKey());
    assertThat(ledger.delivery(f.delivery()).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.attempt(new AttemptId(1))).isEmpty();
    assertThat(ledger.manifestRows("", Optional.empty(), 100)).isEmpty();

    // the service is back: nothing was stored meanwhile, and the same request seals
    simulateOutage(false);
    assertThat(evidence.list("", Optional.empty(), 100)).isEmpty();
    assertThat(sealing(ledger, evidence, false).seal(f.request()).status())
        .isEqualTo(SealStatus.SEALED);
  }
}
