package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerDurability.Status;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.contract.LedgerDurabilityContract;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * I1 on the primary with a streaming quorum standby (P3b1 T17, review focus EF1, EF14): the open
 * side of the durability gate. The job {@code ledger-durability} starts a primary whose {@code
 * synchronous_standby_names} is {@code ANY 1 (standby1)} and a hot standby on {@code
 * 127.0.0.1:5433}, and runs this class first and {@link PgDurabilityDownIT} after it has stopped
 * the standby. A real-store test: skipped without {@code REGPACK_PG_URL}; tagged {@code
 * durability}, so {@code verify} does not run it (the job selects it with {@code
 * -Dgroups=durability -DexcludedGroups=none -Dit.test=PgDurabilityUpIT}). Like every store test it
 * works in a schema of its own ({@link AbstractPgStoreTest}).
 *
 * <p>What this phase shows and what it does not: that the status reads a synchronous quorum standby
 * from the server, that a SENDING commit under {@code remote_apply} is visible on the standby when
 * the call returns, and that the production gate is open and a full seal is clear before the first
 * byte. It cannot show that {@code on} waits for the standby; that is the Down phase, where a
 * commit that cannot get its acknowledgement is the evidence.
 *
 * <p>This class is also the proof, on a server, of the synchronous branch of {@code
 * LedgerDurabilityContract#aSynchronousStatusNamesAtLeastOneStandby}: in the job {@code ledger-pg}
 * the ledger has no standby and that contract method has nothing to check.
 */
@Tag("durability")
class PgDurabilityUpIT extends AbstractPgStoreTest {
  @TempDir Path root;

  private SealedEvidenceStore evidence() throws IOException {
    return DurabilityHarness.twoDomainEvidence(root, clock());
  }

  @Test
  void statusReportsASynchronousQuorumStandby() {
    Status status = store().durability();

    assertThat(status.synchronous()).as(status.detail()).isTrue();
    assertThat(status.synchronousStandbys()).isEqualTo(1);
    assertThat(status.detail()).contains("ANY 1 (standby1)");
    // the synchronous branch of the contract method, on the status of a real server
    LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(status);
  }

  /**
   * The store under test takes {@code remote_apply} from its connection options and does not force
   * the setting, so the commit of {@code beginSending} returns only when the standby has replayed
   * it: the hot standby then shows the delivery in SENDING and the attempt row at once, with no
   * polling. A fixture of the primary (the lease, the delivery) commits with {@code on}, which
   * waits for the standby's flush; the WAL is replayed in order, so everything before the SENDING
   * commit, the schema included, is visible on the standby too.
   */
  @Test
  void aSendingCommitIsVisibleOnTheStandbyTheMomentTheCallReturns() throws SQLException {
    DataSource remoteApply =
        PgTestDatabase.dataSourceWithOptions("-c synchronous_commit=remote_apply");
    PgLedgerStore underTest =
        new PgLedgerStore(
            remoteApply, clock(), Config.forSchema(schema()).withForceSynchronousCommit(false));
    DataSource standby = PgTestDatabase.standbyDataSource();
    EndpointKey endpoint = LedgerFixtures.endpoint("VISIBLE");
    LeaseToken token = SealScenarioContract.lease(store(), endpoint, "A");
    DeliveryId delivery = LedgerFixtures.queuedDelivery(store(), endpoint, "BK-VISIBLE", 1);
    AttemptId attempt = store().allocateAttemptId();

    SendingResult result =
        underTest.beginSending(delivery, attempt, token, LedgerFixtures.attemptIntent());

    assertThat(result.status()).isEqualTo(SendingStatus.APPLIED);
    try (Connection connection = standby.getConnection()) {
      assertThat(
              longOf(
                  connection,
                  "SELECT state FROM " + schema() + ".delivery WHERE delivery_id = ?",
                  delivery.value()))
          .as("the delivery on the standby, immediately")
          .isEqualTo(Codes.of(DeliveryState.SENDING));
      assertThat(
              longOf(
                  connection,
                  "SELECT count(*) FROM " + schema() + ".attempt WHERE attempt_id = ?",
                  attempt.value()))
          .as("the attempt row on the standby, immediately")
          .isEqualTo(1);
    }
    printLsns();
    // the store reads the setting it starts with, not the one the prologue forces: remote_apply
    Status status = underTest.durability();
    assertThat(status.synchronous()).as(status.detail()).isTrue();
  }

  @Test
  void theProductionGateIsOpenForAPSealedStoreOnTwoDomainsAndTheQuorumStandby() throws IOException {
    SendGate production = new SendGate(SendGate.Requirement.production());

    SendGate.Result open = production.check(store(), evidence());

    assertThat(open.reasons()).isEmpty();
    assertThat(open.open()).isTrue();
    // the same ledger with an evidence store of one failure domain: closed, for that reason only
    SendGate.Result oneDomain =
        production.check(store(), DurabilityHarness.oneDomainEvidence(root, clock()));
    assertThat(oneDomain.open()).isFalse();
    assertThat(oneDomain.reasons())
        .doesNotContain(SendGate.LEDGER_NOT_SYNCHRONOUS, SendGate.LEDGER_UNREADABLE)
        .hasSize(1);
  }

  @Test
  void aFullSealWithTheProductionGateIsClearBeforeTheFirstByte() throws IOException {
    DurableSeal sealing =
        new DurableSeal(
            store(),
            evidence(),
            clock(),
            RetentionPolicy.fixedDays(1826),
            new SendGate(SendGate.Requirement.production()));
    EndpointKey endpoint = LedgerFixtures.endpoint("FULLSEAL");
    LeaseToken token = SealScenarioContract.lease(store(), endpoint, "A");
    DeliveryId delivery = LedgerFixtures.queuedDelivery(store(), endpoint, "BK-FULL", 1);

    SealOutcome outcome =
        sealing.seal(
            SealScenarioContract.request(
                endpoint, token, delivery, "BK-FULL", SealScenarioContract.reportOf("BK-FULL")));

    assertThat(outcome.status()).as(outcome.detail()).isEqualTo(SealStatus.SEALED);
    AttemptId attempt = outcome.attempt().orElseThrow();
    assertThat(sealing.beforeFirstByte(endpoint, attempt, token)).isEqualTo(PreSendStatus.CLEAR);
    assertThat(store().attempt(attempt).orElseThrow().commitIssued()).isTrue();
    assertThat(store().delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.SENDING);
    assertThat(store().manifestRows("", Optional.empty(), 1_000))
        .as("the sealed file and the intent object")
        .hasSize(2);
  }

  // ---------------------------------------------------------------------------------------------

  private static long longOf(Connection connection, String sql, long parameter)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).as("a row for %s", sql).isTrue();
        return rows.getLong(1);
      }
    }
  }

  /**
   * The log position of the primary and what the standby has acknowledged, printed for the reader
   * of the log; a diagnostic only, nothing compares them.
   */
  private void printLsns() throws SQLException {
    try (Connection connection = dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT pg_current_wal_lsn()::text, flush_lsn::text, replay_lsn::text,"
                    + " sync_state FROM pg_stat_replication WHERE application_name = 'standby1'")) {
      if (rows.next()) {
        DurabilityHarness.notice(
            "standby-lsn",
            "primary="
                + rows.getString(1)
                + " standby-flush="
                + rows.getString(2)
                + " standby-replay="
                + rows.getString(3)
                + " sync_state="
                + rows.getString(4));
      }
    }
  }
}
