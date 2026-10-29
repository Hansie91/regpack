package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The artifact lock of {@code PgRetention} on a real server (decision D28), shown without a race:
 * the deterministic evidence beside the two probabilistic race tests of {@code
 * RetentionLedgerContract}, which can pass by luck. The test takes the lock itself, from outside,
 * and keeps it: the claim, the hold and the release of that artifact then block on it and end by
 * the lock timeout with SQLSTATE 55P03 and nothing written, while another artifact and the reads do
 * not wait; when the lock is let go, the same calls succeed. The lock is taken here with a literal
 * statement, the purpose 4 and the text schema ':' artifact, and not with {@code
 * PgRetention.ARTIFACT_LOCK_SQL}, so that the test pins the purpose and the key text independently
 * of the production constant. The class also holds the evidence for the premise of KF22 (a call
 * that changes nothing still writes a row version, so there is no durability barrier).
 *
 * <p>Real-store test: skipped without {@code REGPACK_PG_URL}, a failure with {@code
 * REGPACK_PG_REQUIRED=true}. It ran green on a local PostgreSQL 18.6 before CP-1 (record AP-8); the
 * CI run stays the authoritative one.
 */
class PgRetentionIT extends AbstractPgStoreTest {
  private static final Instant UNTIL = Instant.parse("2030-01-01T00:00:00Z");
  private static final String A = "sealed/lock/a";
  private static final String B = "sealed/lock/b";

  private static RetentionClaim claim(String artifact, String regime) {
    return new RetentionClaim(
        new EvidenceKey(artifact),
        new Regime(regime),
        AnchorEvent.OBJECT_WRITTEN,
        Optional.of(UNTIL),
        "sealed");
  }

  /**
   * The premise of KF22 (D-K8): {@code PgRetention} takes no durability barrier, because a call
   * that records a claim or sets a hold writes a row version even when it changes nothing, and so
   * waits for the standby like any write. The system column {@code xmin}, the transaction that
   * wrote the row version, shows the write: the same claim recorded again (the same date, so the
   * {@code GREATEST} keeps the value) and a hold set to the value the flag has already each give
   * the row a new {@code xmin}. The job {@code ledger-pg} has no standby, so the wait itself is not
   * shown here, only the write that causes it.
   */
  @Test
  void aClaimOrAHoldThatChangesNothingStillWritesARowVersion() throws SQLException {
    store().recordRetention(List.of(claim(A, "emir.eu")));
    String born = xmin(A);

    store().recordRetention(List.of(claim(A, "emir.eu")));
    String afterTheSameClaim = xmin(A);
    assertThat(afterTheSameClaim)
        .as("the claim of a stored row, with the same date, wrote a row version")
        .isNotEqualTo(born);

    store().setRetentionHold(new EvidenceKey(A), false);
    assertThat(xmin(A))
        .as("the release of a row that is not held wrote a row version")
        .isNotEqualTo(afterTheSameClaim);
  }

  /** The {@code xmin} of the one row of the artifact, read on a plain connection. */
  private String xmin(String artifact) throws SQLException {
    String sql =
        "SELECT xmin::text FROM "
            + SqlIdentifier.require(schema())
            + ".retention"
            + " WHERE object_key = ?";
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, artifact);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).as("the artifact has a row").isTrue();
        String xmin = rows.getString(1);
        assertThat(rows.next()).as("the artifact has one row only").isFalse();
        return xmin;
      }
    }
  }

  @Test
  void aHeldArtifactLockBlocksTheWritersOfThatArtifactOnlyAndNothingIsWritten()
      throws SQLException {
    store().recordRetention(List.of(claim(A, "emir.eu"), claim(B, "emir.eu")));
    // the same store with a short lock timeout: a blocked call fails in a fraction of a second
    PgLedgerStore quick =
        new PgLedgerStore(
            dataSource(),
            clock(),
            Config.forSchema(schema()).withLockTimeout(Duration.ofMillis(300)));

    try (Connection holder = dataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
        lock.setInt(1, 4);
        lock.setString(2, schema() + ":" + A);
        lock.execute();
      }

      // the claim, the hold and the release of the locked artifact wait, and fail by the timeout
      assertThatThrownBy(() -> quick.recordRetention(List.of(claim(A, "mifir.eu.rts22"))))
          .as("a claim of the locked artifact")
          .isInstanceOf(LedgerException.class)
          .hasMessageContaining("sqlstate=55P03");
      assertThatThrownBy(() -> quick.setRetentionHold(new EvidenceKey(A), true))
          .as("a hold of the locked artifact")
          .isInstanceOf(LedgerException.class)
          .hasMessageContaining("sqlstate=55P03");
      assertThatThrownBy(() -> quick.setRetentionHold(new EvidenceKey(A), false))
          .as("a release of the locked artifact")
          .isInstanceOf(LedgerException.class)
          .hasMessageContaining("sqlstate=55P03");
      assertThat(count("SELECT count(*) FROM retention WHERE object_key = '" + A + "'"))
          .as("nothing was written for the locked artifact")
          .isEqualTo(1);
      assertThat(count("SELECT count(*) FROM retention WHERE legal_hold"))
          .as("no hold was set")
          .isZero();

      // another artifact does not wait, and neither do the reads
      quick.setRetentionHold(new EvidenceKey(B), true);
      assertThat(quick.retentionRows("sealed/lock/", Optional.empty(), 10))
          .as("the read of the rows does not wait")
          .hasSize(2);
      assertThat(
              quick.expiredRetention(
                  Instant.parse("9999-12-31T23:59:59.999999Z"), Optional.empty(), 10))
          .as("the read of the expiry list does not wait: the artifact B is held")
          .hasSize(1);

      holder.rollback();

      // the lock is gone with the transaction that held it: the same calls go through
      quick.recordRetention(List.of(claim(A, "mifir.eu.rts22")));
      assertThat(count("SELECT count(*) FROM retention"))
          .as("the claim of A is stored")
          .isEqualTo(3);
      assertThat(count("SELECT count(*) FROM retention WHERE object_key = '" + A + "'"))
          .isEqualTo(2);
      assertThat(count("SELECT count(*) FROM retention WHERE legal_hold"))
          .as("only B is held")
          .isEqualTo(1);

      quick.setRetentionHold(new EvidenceKey(A), true);
      assertThat(count("SELECT count(*) FROM retention WHERE legal_hold"))
          .as("the hold reaches both rows of A")
          .isEqualTo(3);
      quick.setRetentionHold(new EvidenceKey(A), false);
      assertThat(count("SELECT count(*) FROM retention WHERE legal_hold"))
          .as("the release leaves B held")
          .isEqualTo(1);
    }
  }
}
