package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.batchId;
import static dev.regpack.testkit.contract.LedgerFixtures.checkpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.commit;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.ledger.IngestLedger.Commit;
import dev.regpack.ports.ledger.IngestLedger.Result;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * I1, acknowledgement limb, for the redelivery of a batch (P3b1 final review, POSTGRES-1 and
 * SEAL-3), without a server. A redelivery inserts nothing, so its transaction has no transaction id
 * and no WAL, and the server would let its commit return without waiting for the standby: after a
 * first commit that was reported as "not confirmed", the retry would be answered with the stored
 * result at once and the source acknowledged although the batch may exist on the primary only. The
 * redelivery therefore reads the stored batch row {@code FOR SHARE}, as the last statement before
 * the commit; the first delivery, which writes, does not. The server side (the retry really waits
 * and ends in "commit not confirmed") is {@code
 * PgDurabilityDownIT#aRedeliveryAfterAnUnconfirmedIngestCommitIsNotAcknowledgedEither}.
 */
class PgIngestReplayTest {
  private static final Config CONFIG = Config.forSchema("t42_7");
  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
  private static final String SOURCE = "SRC-REPLAY";

  private static final Commit EMPTY = commit("replay-batch", SOURCE, checkpoint(1));

  private static List<Object[]> row(Object... values) {
    return Collections.<Object[]>singletonList(values);
  }

  /** A server on which the batch is taken (a redelivery) or new, with the source known. */
  private static SqlSpy.Answers server(boolean batchTaken) {
    return (sql, events) -> {
      if (sql.equals(PgIngest.SOURCE_SELECT_SQL)) {
        return row((short) 1);
      }
      if (sql.equals(PgIngest.BATCH_INSERT_SQL)) {
        return batchTaken ? List.of() : row(5L);
      }
      if (sql.equals(PgIngest.BATCH_SELECT_SQL) || sql.equals(PgIngest.BATCH_SELECT_SHARE_SQL)) {
        return row(SOURCE, IngestResultCodec.encode(List.of()), 7L);
      }
      if (sql.equals(PgIngest.OUTBOX_INSERT_SQL)) {
        return row(7L);
      }
      return List.of();
    };
  }

  private static PgIngest ingest(SqlSpy spy) {
    return new PgIngest(spy.over(null), new ManualClock(NOW), CONFIG);
  }

  @Test
  void aRedeliveryReadsTheStoredBatchForShareAsTheLastStatementBeforeTheCommit() {
    SqlSpy spy = new SqlSpy().updatingRows(1).answering(server(true));

    Result result = ingest(spy).commitIngest(EMPTY);

    assertThat(result.redelivery()).isTrue();
    assertThat(result.batchId()).isEqualTo(batchId("replay-batch"));
    List<String> events = spy.events();
    int taken = events.indexOf("execute: " + PgIngest.BATCH_INSERT_SQL);
    int share = events.indexOf("execute: " + PgIngest.BATCH_SELECT_SHARE_SQL);
    int commit = events.indexOf("commit");
    assertThat(taken).as("the batch insert, in %s", events).isNotNegative();
    assertThat(share).as("the locking read, in %s", events).isGreaterThan(taken);
    assertThat(commit).as("the commit, in %s", events).isGreaterThan(share);
    assertThat(events.subList(share + 1, commit))
        .as("the statements between the locking read and the commit")
        .noneMatch(event -> event.startsWith("execute: "));
    assertThat(events).doesNotContain("execute: " + PgIngest.BATCH_SELECT_SQL);
  }

  @Test
  void theLockingReadIsAShareLockOnTheBatchRowAndNotOnTheSourceRow() {
    assertThat(PgIngest.BATCH_SELECT_SHARE_SQL)
        .as("the redelivery's read is the plain read plus a row lock on the batch")
        .isEqualTo(PgIngest.BATCH_SELECT_SQL + " FOR SHARE OF b");
  }

  @Test
  void theFirstDeliveryWritesAndAddsNoLockingRead() {
    SqlSpy spy = new SqlSpy().updatingRows(1).answering(server(false));

    Result result = ingest(spy).commitIngest(EMPTY);

    assertThat(result.redelivery()).isFalse();
    assertThat(spy.events())
        .contains("commit")
        .doesNotContain(
            "execute: " + PgIngest.BATCH_SELECT_SHARE_SQL,
            "execute: " + PgIngest.BATCH_SELECT_SQL,
            "execute: " + Unit.BARRIER_SQL);
  }

  @Test
  void theReadOfAStoredResultByItsBatchIdStaysAPlainRead() {
    SqlSpy spy = new SqlSpy().answering(server(true));

    assertThat(ingest(spy).ingestResult(batchId("replay-batch"))).isPresent();

    assertThat(spy.events())
        .contains("execute: " + PgIngest.BATCH_SELECT_SQL)
        .doesNotContain("execute: " + PgIngest.BATCH_SELECT_SHARE_SQL);
  }
}
