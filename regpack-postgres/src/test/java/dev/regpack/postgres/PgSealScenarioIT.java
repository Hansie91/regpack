package dev.regpack.postgres;

import static dev.regpack.testkit.contract.SealScenarioContract.reportOf;
import static dev.regpack.testkit.contract.SealScenarioContract.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.evidence.retention.RetentionEngine;
import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.evidence.verify.EvidenceVerifier;
import dev.regpack.evidence.verify.EvidenceVerifier.Report;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
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
import dev.regpack.ports.ledger.ManifestConflictException;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T14b: the I1 scenarios of {@link SealScenarioContract} (the crash-point harness of T14a) on a
 * real PostgreSQL ledger and P-SEALED over two directories of a {@code @TempDir}: the ledger half
 * of "durable before effect" on the server, with the real evidence adapter. A real-store test: it
 * is skipped without {@code REGPACK_PG_URL} (see {@link PgTestDatabase}) and runs in the job {@code
 * ledger-pg}.
 *
 * <p><b>A fresh ledger per test.</b> The scenarios look for the attempts of a delivery among the
 * attempt ids 1 to {@code MAX_ATTEMPT_ID} and assume that the first id of a test is 1. {@link
 * AbstractPgStoreTest} gives every test its own schema, migrated from the scripts (the attempt
 * sequence is created inside that schema), and drops it afterwards, so each test starts at 1.
 *
 * <p><b>The evidence store.</b> One {@link SealedEvidenceStore} per test over {@code vol-a} and
 * {@code vol-b} below the temporary directory, opened after the fresh schema (the {@code
 * BeforeEach} method of the base class runs first), on the same {@code ManualClock} as the ledger.
 * Both directories are on one file system, so the store claims one failure domain; the scenarios
 * that need the production gate to be closed do not depend on that (see the next paragraph).
 *
 * <p>The closed-gate scenario of the shared suite asks for a binding whose production gate is
 * closed. In the job {@code ledger-pg} there is no standby, and since T17 of this plan the
 * durability query of the adapter ({@link PgLedgerDurability}) answers: the ledger is not
 * synchronous, so the gate is closed for the real reason, that the ledger commit is not
 * acknowledged by a synchronous standby (and, with both directories on one file system, that the
 * evidence store has one failure domain). Before T17 the query was a placeholder that threw, and
 * the gate closed on the exception, which is the same verdict for a worse reason. {@code
 * PgLedgerDurabilityTest} shows without a server that a server without a standby is read as "not
 * synchronous" and not as an error, and {@code PgLedgerStoreContractIT} reads the status on the
 * server. No crash point of the suite is placed on a durability or list read ({@code CrashPoints}).
 */
class PgSealScenarioIT extends AbstractPgStoreTest implements SealScenarioContract {
  /** The threads of the concurrency test, and the deliveries each of them seals. */
  private static final int THREADS = 4;

  private static final int DELIVERIES_PER_THREAD = 25;

  /**
   * How long the concurrency test may take: 100 seals, each of which forces four files on two
   * volumes, on a shared runner. A lock wait is not what this limit is for: the units of the ledger
   * give up after their own lock timeout of five seconds.
   */
  private static final long CONCURRENT_WAIT_SECONDS = 240;

  @TempDir Path root;

  private SealedEvidenceStore evidence;

  @BeforeEach
  void openTheEvidenceStore() throws IOException {
    Path base = root.toRealPath();
    evidence =
        SealedEvidenceStore.open(
            new SealedEvidenceStore.Config(
                base.resolve("vol-a"), base.resolve("vol-b"), "vol-a", "vol-b", clock()));
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
   * The sealer with the retention registrar (SEAL-1) over the PostgreSQL ledger and P-SEALED: the
   * policy of 1,826 days, a gate that asks nothing and a {@link RetentionEngine} for {@code
   * regime}. Until {@code LedgerStore} extends {@code RetentionLedger} (P3c1 T11) the ledger is
   * cast. NOT RUN LOCALLY: the class skips without {@code REGPACK_PG_URL}; the first run is CP-1.
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
  // what the shared suite does not say: several deliveries at once, a wrong manifest row, verify

  /**
   * The assumption of the shared suite, pinned for this binding: the ledger of a test is empty and
   * its attempt ids start at 1 (the scenarios look for attempts among the ids 1 to {@code
   * MAX_ATTEMPT_ID}, so a ledger that carried ids over from an earlier test would hide attempts).
   */
  @Test
  void theLedgerOfEachTestIsFreshSoTheAttemptIdsStartAtOne() {
    assertThat(ledger().manifestRows("", Optional.empty(), 1_000)).isEmpty();
    assertThat(evidence().list("", Optional.empty(), 1_000)).isEmpty();
    assertThat(ledger().allocateAttemptId()).isEqualTo(new AttemptId(1));
    assertThat(ledger().allocateAttemptId()).isEqualTo(new AttemptId(2));
  }

  private static void awaitStart(CyclicBarrier barrier) {
    try {
      barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the other threads", e);
    } catch (BrokenBarrierException | TimeoutException e) {
      throw new AssertionError(
          "the threads did not all start within " + WAIT_SECONDS + " seconds", e);
    }
  }

  /**
   * Four threads seal 25 deliveries each, all of one endpoint under one lease, each delivery with
   * bytes of its own (the business key is in the file), so that no two units record the same
   * manifest key and no two threads put the same sealed object. The units take no row lock in
   * common except the share lock of the lease row, which does not conflict with itself: nothing
   * waits for a lock. A unit that waited longer than its lock timeout (five seconds, the default of
   * the adapter) would fail with SQLSTATE 55P03, and the test collects every exception of a seal
   * instead of stopping at the first.
   *
   * <p>What this shows, and what it does not (TESTS-7): no seal deadlocks, none waits for a lock
   * longer than the lock timeout, and every seal is sealed with an attempt id of its own. It does
   * not show that no unit waits for another for a shorter time: a unit that takes a lock in common
   * (a shared row, a dictionary row created inside the unit) and holds it only for milliseconds
   * would pass. That claim (EF21) is held by the shape of the schema and of the unit (no dictionary
   * row in V002, the manifest rows inserted last), which a reviewer reads; a check with {@code
   * pg_blocking_pids} on a unit held open between the manifest insert and its commit is a hand-off
   * and is not part of this test.
   */
  @Test
  void concurrentSealsOfDifferentDeliveriesNeitherDeadlockNorHitTheLockTimeout() throws Exception {
    EndpointKey endpoint = LedgerFixtures.endpoint("CONC");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    List<List<SealRequest>> perThread = new ArrayList<>();
    for (int thread = 0; thread < THREADS; thread++) {
      List<SealRequest> mine = new ArrayList<>();
      for (int i = 0; i < DELIVERIES_PER_THREAD; i++) {
        String businessKey = "BK-CONC-" + thread + "-" + i;
        DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, businessKey);
        mine.add(request(endpoint, token, delivery, businessKey, reportOf(businessKey)));
      }
      perThread.add(mine);
    }
    int total = THREADS * DELIVERIES_PER_THREAD;
    Sealing sealing = sealing(ledger(), evidence(), false);
    CyclicBarrier start = new CyclicBarrier(THREADS);
    List<SealOutcome> outcomes = new CopyOnWriteArrayList<>();
    List<RuntimeException> failures = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      List<Future<?>> running = new ArrayList<>();
      for (List<SealRequest> mine : perThread) {
        running.add(
            pool.submit(
                () -> {
                  awaitStart(start);
                  for (SealRequest request : mine) {
                    try {
                      outcomes.add(sealing.seal(request));
                    } catch (RuntimeException e) {
                      failures.add(e);
                    }
                  }
                }));
      }
      for (Future<?> thread : running) {
        thread.get(CONCURRENT_WAIT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures)
        .as("seals that threw (a lock wait over the lock timeout is sqlstate 55P03)")
        .isEmpty();
    assertThat(outcomes).hasSize(total);
    assertThat(outcomes).extracting(SealOutcome::status).containsOnly(SealStatus.SEALED);
    assertThat(outcomes)
        .extracting(outcome -> outcome.attempt().orElseThrow())
        .as("an attempt id for each seal")
        .doesNotHaveDuplicates();
    for (List<SealRequest> mine : perThread) {
      for (SealRequest request : mine) {
        assertThat(ledger().delivery(request.delivery()).orElseThrow().state())
            .isEqualTo(DeliveryState.SENDING);
      }
    }
    List<ManifestRow> rows = ledger().manifestRows("", Optional.empty(), 1_000);
    assertThat(rows).as("a sealed file and an intent object for each delivery").hasSize(2 * total);
    assertThat(rows.stream().filter(row -> row.entry().objectClass() == ObjectClass.SEALED_FILE))
        .as("one sealed file for each delivery: no two deliveries share bytes")
        .hasSize(total);
    assertThat(rows.stream().filter(row -> row.entry().objectClass() == ObjectClass.JOURNAL_A))
        .hasSize(total);
    assertThat(evidence().list("", Optional.empty(), 1_000)).hasSize(2 * total);
  }

  /**
   * The manifest already holds the key of the sealed bytes with another hash (a row that was
   * recorded wrongly). The seal puts both objects, and then the SENDING unit finds the conflict and
   * rolls back: the exception is not caught (the seal fails closed), the delivery is still queued,
   * there is no attempt row, and the only manifest row is the wrong one. The system stays usable:
   * another delivery seals afterwards.
   */
  @Test
  void aManifestConflictFailsTheSealClosed() {
    EndpointKey endpoint = LedgerFixtures.endpoint("CONFLICT");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, "BK-CONFLICT");
    byte[] bytes = reportOf("BK-CONFLICT");
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(bytes));
    Sha256 wrongHash = Sha256.ofUtf8("another object under the same key");
    ledger()
        .recordObjects(
            List.of(new ObjectEntry(sealedKey, ObjectClass.SEALED_FILE, wrongHash, 3)),
            Provenance.WRITTEN,
            Optional.empty());
    Sealing sealing = sealing(ledger(), evidence(), false);
    SealRequest request = request(endpoint, token, delivery, "BK-CONFLICT", bytes);

    Throwable thrown = catchThrowable(() -> sealing.seal(request));

    assertThat(thrown)
        .isInstanceOf(ManifestConflictException.class)
        .hasMessageContaining(sealedKey.path());
    assertThat(ledger().delivery(delivery).orElseThrow().state())
        .as("the SENDING unit rolled back")
        .isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger().attempt(new AttemptId(1))).as("no attempt row").isEmpty();
    List<ManifestRow> rows = ledger().manifestRows("", Optional.empty(), 1_000);
    assertThat(rows).as("only the wrong row, nothing of the unit").hasSize(1);
    assertThat(rows.get(0).entry().key()).isEqualTo(sealedKey);
    assertThat(rows.get(0).entry().sha256()).isEqualTo(wrongHash);
    assertThat(evidence().list("", Optional.empty(), 1_000))
        .as("the sealed object and the intent object stay behind as orphans")
        .hasSize(2);
    // nothing can be sent: the attempt that was never recorded has no marker to set
    assertThat(sealing.beforeFirstByte(endpoint, new AttemptId(1), token))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);

    // the same request fails the same way, and a delivery of other bytes is not affected
    assertThat(catchThrowable(() -> sealing.seal(request)))
        .isInstanceOf(ManifestConflictException.class);
    DeliveryId other = SealScenarioContract.queued(ledger(), endpoint, "BK-OTHER");
    SealOutcome sealed =
        sealing.seal(request(endpoint, token, other, "BK-OTHER", reportOf("BK-OTHER")));
    assertThat(sealed.status()).as(sealed.detail()).isEqualTo(SealStatus.SEALED);
    assertThat(ledger().delivery(other).orElseThrow().state()).isEqualTo(DeliveryState.SENDING);
  }

  /**
   * I10, storage half, on the server: after successful seals the evidence tier and the manifest
   * that PostgreSQL holds agree, and {@code verify} has nothing to report. The fourth delivery
   * shares the bytes of the first, so the tier holds three sealed files and four intent objects.
   * The counts keep the verdict from being vacuous, and the prefixes narrow the range query of the
   * manifest and the listing alike.
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
