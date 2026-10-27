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
 * of the production constant.
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
