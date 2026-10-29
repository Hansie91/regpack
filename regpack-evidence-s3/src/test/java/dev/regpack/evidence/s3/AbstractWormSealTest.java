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
import dev.regpack.ports.evidence.EvidenceUnavailableException;
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
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Since P3c1 T15-2 the transport under the store is a {@link RecordingTransport}, which only
 * forwards unless a measurement marks a segment, and {@link #sealPathOfTheBinding} (OQ-1) measures
 * the requests of one seal in four cases at the end of one existing test of each binding. The
 * classes that run against the real service print the figures as a notice line and assert nothing;
 * the fake-backed twins assert the counts and print nothing.
 */
abstract class AbstractWormSealTest implements SealScenarioContract {
  /** The retention policy the scenarios run with (OI-6: tests and tools only). */
  static final RetentionPolicy RETENTION = RetentionPolicy.fixedDays(1826);

  private ManualClock clock;
  private InMemoryLedgerStore ledger;
  private OutageTransport transport;
  private RecordingTransport recording;
  private WormConfig config;
  private WormEvidenceStore evidence;

  /**
   * Why the last measurement of OQ-1 failed (a word of {@link SealPathReport#REASONS}), or none.
   */
  private String sealPathFailure = "none";

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
    // the recorder forwards and does nothing else outside a marked segment (OQ-1, T15-2)
    recording = new RecordingTransport(new JdkTransport(Duration.ofSeconds(60)));
    transport = new OutageTransport(recording);
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
    return fixture(name, businessKey, reportOf(businessKey));
  }

  private Fixture fixture(String name, String businessKey, byte[] bytes) {
    EndpointKey endpoint = LedgerFixtures.endpoint(name);
    LeaseToken token = SealScenarioContract.lease(ledger, endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger, endpoint, businessKey);
    return new Fixture(
        endpoint, token, delivery, request(endpoint, token, delivery, businessKey, bytes));
  }

  // ---------------------------------------------------------------------------------------------
  // OQ-1 (P3c1 T15-2): the request figures of one seal, taken from the tests themselves

  /**
   * Measures one {@code DurableSeal.seal} in four cases through {@link #evidence()} (the raw store
   * in the raw bindings, the decorator in the encrypted ones) and hands the figures to {@link
   * #takeSealPath}. The cases are new bytes and bytes that are already sealed (the shared object,
   * sealed for a second delivery after the data clock has moved on by one day, so that the
   * retention extension is real), each without the registrar ({@link #sealing}) and with the {@link
   * RetentionEngine} registrar ({@link #sealingWithRetention}), after a first seal in the bucket.
   * The requests are counted by kind and the elapsed time is taken from {@code System.nanoTime}, by
   * the {@link RecordingTransport} under the store.
   *
   * <p>Call it as the LAST statement of an existing test, after every assertion of that test: it
   * writes to the bucket and leaves the data clock one day later. The measurement never throws: a
   * seal that fails, a store that is unavailable, a recorder that lost a request or any other
   * failure of the measurement ends in no figures and a reason, and the test goes on with its own
   * result. Only {@link #takeSealPath} may fail the test, and only in the fake-backed twins.
   */
  final void sealPathOfTheBinding(String binding) {
    takeSealPath(binding, measureSealPath(evidence()));
  }

  /**
   * What a class does with the figures of one measurement; an empty list means that the measurement
   * failed (see {@link #sealPathFailure()}). The default prints the notice line of {@link
   * SealPathReport}, which is what the classes that run against the real service do: a figure of
   * the service is printed, never asserted. The fake-backed twins override it and assert the
   * counts, and print nothing. Printing cannot fail the test: a report that throws is replaced by
   * the fixed line of a failed measurement. That catch is defensive and not reachable by a test:
   * {@link SealPathReport#line} clamps a negative time, so the report of figures does not throw.
   */
  void takeSealPath(String binding, List<SealPathReport.Figure> figures) {
    String line;
    try {
      line =
          figures.isEmpty()
              ? SealPathReport.failedLine(binding, sealPathFailure)
              : SealPathReport.line(binding, figures);
    } catch (RuntimeException e) {
      line = SealPathReport.failedLine(binding, "error");
    }
    System.out.println(line);
  }

  /** Why the last measurement failed: a word of {@link SealPathReport#REASONS}, or "none". */
  final String sealPathFailure() {
    return sealPathFailure;
  }

  /**
   * The measurement of {@link #sealPathOfTheBinding}, over any store (package-private for {@code
   * WormSealScenariosTest}, which shows the guard with stores that fail). It returns the four
   * figures, or no figures and a reason in {@link #sealPathFailure()}: a seal that does not end
   * SEALED is {@code not-sealed} (an outage of the service ends here, because {@link DurableSeal}
   * turns an unavailable store into a status), a recorder that lost a request is {@code recorder},
   * a {@link RuntimeException} or an {@link AssertionError} is {@code error}; any other {@link
   * Error} passes through. The {@link EvidenceUnavailableException} catch is defensive and not
   * reachable by a test (the word {@code unavailable} stays in the vocabulary of {@link
   * SealPathReport#REASONS}, which the records pin). One call per bucket: a second call meets the
   * leases of the first.
   */
  final List<SealPathReport.Figure> measureSealPath(EvidenceStore store) {
    try {
      Regime regime = new Regime("mifir.eu.rts22");
      Sealing without = sealing(ledger, store, false);
      Sealing with = sealingWithRetention(ledger, store, regime);
      byte[] firstBytes = reportOf("OQ-1 the first seal in the bucket");
      byte[] bytesA = reportOf("OQ-1 bytes that a seal without the registrar writes");
      byte[] bytesB = reportOf("OQ-1 bytes that a seal with the registrar writes");

      // not measured: the figures are counted after a first seal in the bucket
      requireSealed(without.seal(fixture("OQ1W", "BK-OQ1-W1", firstBytes).request()));

      List<SealPathReport.Figure> figures = new ArrayList<>();
      figures.add(
          measuredSeal(SealPathReport.NEW_WITHOUT, without, fixture("OQ1A", "BK-OQ1-A1", bytesA)));
      figures.add(
          measuredSeal(SealPathReport.NEW_WITH, with, fixture("OQ1B", "BK-OQ1-B1", bytesB)));
      // a second delivery of the same bytes, a day later: the sealed key is shared, and the
      // registrar's extension of the retention is a real one (the lease is taken after the move)
      clock.advance(Duration.ofDays(1));
      figures.add(
          measuredSeal(
              SealPathReport.SHARED_WITHOUT, without, fixture("OQ1C", "BK-OQ1-C1", bytesA)));
      figures.add(
          measuredSeal(SealPathReport.SHARED_WITH, with, fixture("OQ1D", "BK-OQ1-D1", bytesB)));
      if (!recording.intact()) {
        return failedMeasurement("recorder");
      }
      sealPathFailure = "none";
      return List.copyOf(figures);
    } catch (EvidenceUnavailableException e) {
      return failedMeasurement("unavailable");
    } catch (NotSealedException e) {
      return failedMeasurement("not-sealed");
    } catch (RuntimeException | AssertionError e) {
      return failedMeasurement("error");
    } finally {
      recording.stop();
    }
  }

  private List<SealPathReport.Figure> failedMeasurement(String reason) {
    sealPathFailure = reason;
    return List.of();
  }

  /** Seals the fixture inside a segment of the recorder and returns the figure of the segment. */
  private SealPathReport.Figure measuredSeal(String caseName, Sealing sealer, Fixture f) {
    SealOutcome outcome;
    recording.mark(caseName);
    try {
      outcome = sealer.seal(f.request());
    } finally {
      recording.stop();
    }
    requireSealed(outcome);
    List<RecordingTransport.Segment> segments = recording.segments();
    RecordingTransport.Segment segment = segments.get(segments.size() - 1);
    long millis = (segment.elapsedNanos() + 500_000L) / 1_000_000L;
    return new SealPathReport.Figure(caseName, millis, segment.byLabel());
  }

  private static void requireSealed(SealOutcome outcome) {
    if (outcome.status() != SealStatus.SEALED) {
      // no detail: it is not part of any line
      throw new NotSealedException();
    }
  }

  /** A seal of the measurement ended in another status than SEALED. */
  private static final class NotSealedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotSealedException() {
      super("a seal of the measurement did not end SEALED");
    }
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

    // OQ-1 (P3c1 T15-2): the request figures of one seal on the raw store. This test ends with the
    // service back and a first seal in the bucket, and its subject is the seal over the transport.
    // The encrypted bindings inherit this test and run it over the raw store on purpose: they
    // measure through their own decorator in theBindingReallyEncryptsWhatTheScenariosSeal, so the
    // raw figure is taken only where evidence() is the raw store.
    if (evidence() == evidence) {
      sealPathOfTheBinding("raw");
    }
  }
}
