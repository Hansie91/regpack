package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.Transport;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.Attempt;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerDurability.Status;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.postgres.DurabilityHarness.Waited;
import dev.regpack.testkit.contract.FencedSender;
import dev.regpack.testkit.contract.LedgerDurabilityContract;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import dev.regpack.testkit.sim.DestinationSimulator;
import dev.regpack.testkit.sim.Scenarios;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * I1 on the primary with its standby stopped (P3b1 T17, review focus EF1, EF14, EF15): the part of
 * "durable before effect" that no in-memory run and no run without a standby can show. The job
 * {@code ledger-durability} starts a primary whose {@code synchronous_standby_names} is {@code ANY
 * 1 (standby1)} and a streaming standby, runs {@link PgDurabilityUpIT}, stops the standby and runs
 * this class. A real-store test: skipped without {@code REGPACK_PG_URL} (see {@link
 * PgTestDatabase}); tagged {@code durability}, so {@code verify} does not run it; the job selects
 * it with {@code -Dgroups=durability -DexcludedGroups=none -Dit.test=PgDurabilityDownIT}. It does
 * not extend {@link AbstractPgStoreTest}: the fixtures of a primary without its standby must not
 * wait for it, see {@link DurabilityHarness}.
 *
 * <p>Four methods, four verdicts of the discriminating kind: the status says "not synchronous"; the
 * production gate refuses and a seal puts nothing; a commit whose wait for the standby is cancelled
 * is not reported as durable, once for the SENDING commit and once for the marker commit. The last
 * two are what the commit-warning rule of {@link Unit} is for: without it the cancelled commit
 * would return as an ordinary success and the file would go.
 *
 * <p>A second seal of the same sealed key behind a commit that is stuck in its wait would itself
 * wait for the unique key and be cut off by {@code lock_timeout} (SQLSTATE 55P03) after five
 * seconds. No method here runs a second seal while a commit waits: the call under test is the only
 * unit that writes, and the harness cancels its wait before anything else touches the ledger.
 *
 * <p>Every assertion carries the observed value in its description, and the cancel tests print
 * their notice line before they assert: the failure annotation of the job keeps the first line of a
 * failure message only. The class is bounded by a timeout in a thread of its own, because a commit
 * that waits for a standby blocks in a socket read, which a timeout in the thread of the test
 * cannot end (a normal test takes under 15 seconds, a cancel test about 7).
 */
@Tag("durability")
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PgDurabilityDownIT {
  private static final String UNCONFIRMED = "commit not confirmed by the synchronous standby";

  @TempDir Path root;

  private static Transport.OutboundFile fileOf(String businessKey) {
    byte[] bytes = SealScenarioContract.reportOf(businessKey);
    return new Transport.OutboundFile(
        "file-" + businessKey,
        "file-" + businessKey + ".xml",
        bytes,
        Sha256.of(bytes),
        "application/xml");
  }

  private static String sqlStateOf(Throwable thrown) {
    return thrown.getCause() instanceof SQLException sql && sql.getSQLState() != null
        ? sql.getSQLState()
        : "none";
  }

  /**
   * One notice line for the log: what the call ended with (null-safe: a call that returned has no
   * exception), the SQLSTATE of its cause, and who ended the wait.
   */
  private static void printCancelNotice(String title, String method, Waited waited) {
    Throwable thrown = waited.thrown();
    DurabilityHarness.notice(
        title,
        method
            + ": "
            + (thrown == null
                ? "returned normally"
                : thrown + " cause-sqlstate=" + sqlStateOf(thrown))
            + " ended-by="
            + (waited.cancelledByTheHarness()
                ? "pg_cancel_backend"
                : "the server (the statement timeout)"));
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void statusReportsNotSynchronousWithTheStandbyDown() {
    try (DurabilityHarness harness = DurabilityHarness.open()) {
      Status status = harness.underTest().durability();

      assertThat(status.synchronous()).as(status.detail()).isFalse();
      assertThat(status.synchronousStandbys())
          .as("synchronous standbys streaming: %s", status.detail())
          .isZero();
      assertThat(status.detail())
          .as("one standby is required, none streams: %s", status.detail())
          .contains("0 of 1");
      LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(status);
    }
  }

  @Test
  void theProductionGateIsClosedAndSealPutsNothing() throws IOException {
    try (DurabilityHarness harness = DurabilityHarness.open()) {
      // the evidence store is in order (two failure domains), so the ledger is the only reason
      SealedEvidenceStore evidence = DurabilityHarness.twoDomainEvidence(root, harness.clock());
      EndpointKey endpoint = LedgerFixtures.endpoint("DOWNGATE");
      LeaseToken token = SealScenarioContract.lease(harness.setup(), endpoint, "A");
      DeliveryId delivery = LedgerFixtures.queuedDelivery(harness.setup(), endpoint, "BK-GATE", 1);
      SealRequest request =
          SealScenarioContract.request(
              endpoint, token, delivery, "BK-GATE", SealScenarioContract.reportOf("BK-GATE"));
      DurableSeal sealing =
          new DurableSeal(
              harness.underTest(),
              evidence,
              harness.clock(),
              RetentionPolicy.fixedDays(1826),
              new SendGate(SendGate.Requirement.production()));

      SealOutcome outcome = sealing.seal(request);

      assertThat(outcome.status()).as(outcome.detail()).isEqualTo(SealStatus.GATE_CLOSED);
      assertThat(outcome.attempt())
          .as("the attempt of a closed gate: %s (%s)", outcome.attempt(), outcome.detail())
          .isEmpty();
      assertThat(outcome.detail())
          .as(
              "closed because the ledger is not acknowledged by a standby, not because it failed:"
                  + " %s",
              outcome.detail())
          .contains(SendGate.LEDGER_NOT_SYNCHRONOUS)
          .doesNotContain(SendGate.LEDGER_UNREADABLE)
          .doesNotContain("failure domains");
      var put = evidence.list("", Optional.empty(), 1_000);
      assertThat(put).as("nothing was put: %s", put).isEmpty();
      var manifest = harness.setup().manifestRows("", Optional.empty(), 1_000);
      assertThat(manifest).as("manifest rows: %s", manifest).isEmpty();
      DeliveryState state = harness.setup().delivery(delivery).orElseThrow().state();
      assertThat(state).as("the delivery state: %s", state).isEqualTo(DeliveryState.QUEUED);
      AttemptId taken = harness.setup().allocateAttemptId();
      assertThat(taken)
          .as("no attempt id was taken, the next one is %s", taken)
          .isEqualTo(new AttemptId(1));
      // and the same gate stands in front of the first byte
      PreSendStatus beforeFirstByte = sealing.beforeFirstByte(endpoint, new AttemptId(1), token);
      assertThat(beforeFirstByte)
          .as("the status before the first byte: %s", beforeFirstByte)
          .isEqualTo(PreSendStatus.GATE_CLOSED);
    }
  }

  /**
   * The SENDING commit waits for the standby, which is not there, until its backend is cancelled.
   * The cancel ends the wait with a WARNING and the commit returns; the unit reports that as an
   * exception, so the sender stops before the transport: no first byte leaves on the strength of a
   * commit that no standby has. The delivery is SENDING all the same (it committed locally); that
   * row, without a marker, is what recovery requeues (spec 11.5 A.3 a).
   *
   * <p>The name keeps the plan's wording. The statement timeout of 2 seconds is configured on the
   * store under test, but the server does not apply it to the COMMIT (see {@link
   * DurabilityHarness}), so the harness cancels the backend after six seconds; the notice line says
   * which of the two ended the wait.
   */
  @Test
  void aCommitWaitCancelledByTheStatementTimeoutIsNotReportedAsDurable() {
    try (DurabilityHarness harness = DurabilityHarness.open()) {
      EndpointKey endpoint = LedgerFixtures.endpoint("DOWNCANCEL");
      DeliveryId delivery =
          LedgerFixtures.queuedDelivery(harness.setup(), endpoint, "BK-CANCEL", 1);
      DestinationSimulator destination =
          DestinationSimulator.withScenario(harness.clock(), Scenarios.acpt(Duration.ofSeconds(1)));
      // the lease and the attempt id come from the store that commits locally; only the SENDING
      // commit is the store under test's
      LedgerStore ledger =
          DurabilityHarness.routed(harness.setup(), harness.underTest(), "beginSending");
      FencedSender sender =
          new FencedSender(ledger, destination, endpoint, "A", Duration.ofMinutes(10));
      assertThat(sender.acquire()).isPresent();

      Waited waited =
          harness.callWhileTheCommitWaits(
              () -> sender.send(delivery, fileOf("BK-CANCEL"), () -> {}));

      Throwable thrown = waited.thrown();
      // before the assertions: the annotation of a failure keeps one line, this notice keeps the
      // rest
      printCancelNotice("cancelled-commit-wait", "beginSending", waited);
      assertThat(thrown)
          .as(
              "a commit that waited for a standby that is not there must not return a result;"
                  + " was: %s",
              thrown)
          .isInstanceOf(LedgerException.class);
      assertThat(thrown.getMessage()).as("message of %s", thrown).startsWith(UNCONFIRMED);
      List<?> received = destination.receivedFiles();
      assertThat(received).as("no transport call, no first byte: %s", received).isEmpty();
      DeliveryState state = harness.setup().delivery(delivery).orElseThrow().state();
      assertThat(state)
          .as("committed locally, the delivery state: %s", state)
          .isEqualTo(DeliveryState.SENDING);
      Attempt attempt = harness.setup().attempt(new AttemptId(1)).orElseThrow();
      assertThat(attempt.delivery())
          .as("the delivery of the attempt: %s", attempt)
          .isEqualTo(delivery);
      assertThat(attempt.commitIssued())
          .as("no marker yet, commit_issued: %s", attempt.commitIssued())
          .isFalse();
    }
  }

  /**
   * The marker commit, the last thing before the first byte: the SENDING row is there (committed
   * with the local setting), the store under test sets {@code commit_issued} and its commit waits
   * for the standby. Cancelled, it must not let the transport send.
   */
  @Test
  void aMarkerCommitWhoseStandbyWaitIsCancelledIsNotReportedAsDurable() {
    try (DurabilityHarness harness = DurabilityHarness.open()) {
      EndpointKey endpoint = LedgerFixtures.endpoint("DOWNMARKER");
      DeliveryId delivery =
          LedgerFixtures.queuedDelivery(harness.setup(), endpoint, "BK-MARKER", 1);
      DestinationSimulator destination =
          DestinationSimulator.withScenario(harness.clock(), Scenarios.acpt(Duration.ofSeconds(1)));
      // begin (SENDING) and the attempt id are the setup store's; only the marker is the store
      // under test's, and the sender calls it from the commit gate of the transport
      LedgerStore ledger =
          DurabilityHarness.routed(harness.setup(), harness.underTest(), "markCommitIssued");
      FencedSender sender =
          new FencedSender(ledger, destination, endpoint, "A", Duration.ofMinutes(10));
      assertThat(sender.acquire()).isPresent();

      Waited waited =
          harness.callWhileTheCommitWaits(
              () -> sender.send(delivery, fileOf("BK-MARKER"), () -> {}));

      Throwable thrown = waited.thrown();
      printCancelNotice("cancelled-marker-wait", "markCommitIssued", waited);
      assertThat(thrown)
          .as(
              "a marker commit that waited for a standby that is not there must not succeed;"
                  + " was: %s",
              thrown)
          .isInstanceOf(LedgerException.class);
      assertThat(thrown.getMessage()).as("message of %s", thrown).startsWith(UNCONFIRMED);
      List<?> received = destination.receivedFiles();
      assertThat(received).as("the gate threw: no first byte: %s", received).isEmpty();
      AttemptId attemptId = sender.lastAttempt().orElseThrow();
      Attempt attempt = harness.setup().attempt(attemptId).orElseThrow();
      assertThat(attempt.commitIssued())
          .as("committed locally, commit_issued: %s", attempt.commitIssued())
          .isTrue();
      DeliveryState state = harness.setup().delivery(delivery).orElseThrow().state();
      assertThat(state).as("the delivery state: %s", state).isEqualTo(DeliveryState.SENDING);
    }
  }
}
