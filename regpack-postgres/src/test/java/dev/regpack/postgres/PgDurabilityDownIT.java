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
 */
@Tag("durability")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
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

  // ---------------------------------------------------------------------------------------------

  @Test
  void statusReportsNotSynchronousWithTheStandbyDown() {
    try (DurabilityHarness harness = DurabilityHarness.open()) {
      Status status = harness.underTest().durability();

      assertThat(status.synchronous()).as(status.detail()).isFalse();
      assertThat(status.synchronousStandbys()).isZero();
      assertThat(status.detail()).as("one standby is required, none streams").contains("0 of 1");
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
      assertThat(outcome.attempt()).isEmpty();
      assertThat(outcome.detail())
          .as("closed because the ledger is not acknowledged by a standby, not because it failed")
          .contains(SendGate.LEDGER_NOT_SYNCHRONOUS)
          .doesNotContain(SendGate.LEDGER_UNREADABLE)
          .doesNotContain("failure domains");
      assertThat(evidence.list("", Optional.empty(), 1_000)).as("nothing was put").isEmpty();
      assertThat(harness.setup().manifestRows("", Optional.empty(), 1_000)).isEmpty();
      assertThat(harness.setup().delivery(delivery).orElseThrow().state())
          .isEqualTo(DeliveryState.QUEUED);
      assertThat(harness.setup().allocateAttemptId())
          .as("no attempt id was taken")
          .isEqualTo(new AttemptId(1));
      // and the same gate stands in front of the first byte
      assertThat(sealing.beforeFirstByte(endpoint, new AttemptId(1), token))
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
      assertThat(thrown)
          .as("a commit that waited for a standby that is not there must not return a result")
          .isInstanceOf(LedgerException.class);
      assertThat(thrown.getMessage()).startsWith(UNCONFIRMED);
      DurabilityHarness.notice(
          "cancelled-commit-wait",
          "beginSending: "
              + thrown.getMessage()
              + " cause-sqlstate="
              + sqlStateOf(thrown)
              + " ended-by="
              + (waited.cancelledByTheHarness()
                  ? "pg_cancel_backend"
                  : "the server (the statement timeout)"));
      assertThat(destination.receivedFiles()).as("no transport call, no first byte").isEmpty();
      assertThat(harness.setup().delivery(delivery).orElseThrow().state())
          .as("committed locally")
          .isEqualTo(DeliveryState.SENDING);
      Attempt attempt = harness.setup().attempt(new AttemptId(1)).orElseThrow();
      assertThat(attempt.delivery()).isEqualTo(delivery);
      assertThat(attempt.commitIssued()).as("no marker yet").isFalse();
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
      assertThat(thrown)
          .as("a marker commit that waited for a standby that is not there must not succeed")
          .isInstanceOf(LedgerException.class);
      assertThat(thrown.getMessage()).startsWith(UNCONFIRMED);
      DurabilityHarness.notice(
          "cancelled-marker-wait",
          "markCommitIssued: "
              + thrown.getMessage()
              + " cause-sqlstate="
              + sqlStateOf(thrown)
              + " ended-by="
              + (waited.cancelledByTheHarness()
                  ? "pg_cancel_backend"
                  : "the server (the statement timeout)"));
      assertThat(destination.receivedFiles()).as("the gate threw: no first byte").isEmpty();
      AttemptId attemptId = sender.lastAttempt().orElseThrow();
      Attempt attempt = harness.setup().attempt(attemptId).orElseThrow();
      assertThat(attempt.commitIssued()).as("committed locally").isTrue();
      assertThat(harness.setup().delivery(delivery).orElseThrow().state())
          .isEqualTo(DeliveryState.SENDING);
    }
  }
}
