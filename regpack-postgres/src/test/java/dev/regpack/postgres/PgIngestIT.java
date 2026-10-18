package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.batchId;
import static dev.regpack.testkit.contract.LedgerFixtures.checkpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.commit;
import static dev.regpack.testkit.contract.LedgerFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.BatchId;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.IngestLedger.Commit;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import dev.regpack.ports.ledger.IngestLedger.Result;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.testkit.contract.IngestLedgerContract;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Real-store tests of the ingest path (T15, Convention 4): the properties that the in-memory
 * adapter passes trivially because it has one lock, and the parts of the stored result that no
 * contract method reads. Skipped without {@code REGPACK_PG_URL}.
 */
class PgIngestIT extends AbstractPgStoreTest {
  private static final String SRC_A = IngestLedgerContract.SRC_A;
  private static final String SRC_B = IngestLedgerContract.SRC_B;

  private static Disposition[] dispositions(Result result) {
    return result.entries().stream().map(EntryResult::disposition).toArray(Disposition[]::new);
  }

  /** Eight batches of one source over the same hundred events, one version each, all at once. */
  @Test
  void concurrentBatchesOfOneSourceSerialise() throws Exception {
    int threads = 8;
    int events = 100;
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    long newVersions = 0;
    try {
      List<Future<Result>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        List<Entry> entries = new ArrayList<>();
        for (int k = 0; k < events; k++) {
          entries.add(entry(SRC_A, "r" + k, t + 1, "content-" + t));
        }
        Commit commit = new Commit(batchId("S" + t), SRC_A, entries, Optional.of(checkpoint(t)));
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return store().commitIngest(commit);
                }));
      }
      go.countDown();
      for (Future<Result> future : futures) {
        Result result = future.get(60, TimeUnit.SECONDS);
        assertThat(result.redelivery()).isFalse();
        newVersions +=
            result.entries().stream()
                .filter(e -> e.disposition() == Disposition.NEW_VERSION)
                .count();
      }
    } finally {
      pool.shutdownNow();
    }
    // no unit failed (a unique violation would have surfaced above), the highest version of every
    // event is stored, and the rows that exist are exactly the ones reported as new
    assertThat(count("SELECT count(*) FROM ingest WHERE source_version = " + threads))
        .isEqualTo(events);
    assertThat(count("SELECT count(*) FROM ingest")).isEqualTo(newVersions);
    // batch numbers are taken inside the source lock, so they follow the order of the commits:
    // within an event the stored versions rise with the batch number, never fall
    Map<String, Long> previous = new HashMap<>();
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT event_identity, source_version FROM ingest ORDER BY event_identity,"
                    + " batch_no")) {
      while (rows.next()) {
        String event = rows.getString(1);
        long version = rows.getLong(2);
        Long before = previous.put(event, version);
        assertThat(before == null || before < version)
            .as("event %s: version %d after %s", event, version, before)
            .isTrue();
      }
    }
  }

  /**
   * More entries than the shared lock table can hold if each entry took a lock of its own: with the
   * default {@code max_locks_per_transaction} of 64 and {@code max_connections} of 100 that table
   * has room for about 6,400 locks, so 12,000 entries fail with "out of shared memory" unless the
   * commit takes one lock per source.
   */
  @Test
  void aThousandEntryBatchDoesNotExhaustTheLockTable() throws SQLException {
    int entries = 12_000;
    List<Entry> batch = new ArrayList<>();
    for (int i = 0; i < entries; i++) {
      batch.add(entry(SRC_A, "big-" + i, 1, "content"));
    }
    Result result =
        store().commitIngest(new Commit(batchId("BIG"), SRC_A, batch, Optional.of(checkpoint(1))));
    assertThat(result.entries()).hasSize(entries);
    assertThat(result.entries()).allMatch(e -> e.disposition() == Disposition.NEW_VERSION);
    assertThat(count("SELECT count(*) FROM ingest")).isEqualTo(entries);
  }

  @Test
  void ingestRowsKeepTheMicrosecondInstantOfTheBatch() throws SQLException {
    Instant first = Instant.parse("2026-10-09T23:59:59.999999Z");
    Instant second = Instant.parse("2026-10-10T00:00:00.000001Z");
    clock().set(first);
    store()
        .commitIngest(
            commit(
                "M1",
                SRC_A,
                checkpoint(1),
                entry(SRC_A, "r1", 1, "A"),
                entry(SRC_A, "r2", 1, "A")));
    clock().set(second);
    store().commitIngest(commit("M2", SRC_A, checkpoint(2), entry(SRC_A, "r3", 1, "A")));
    Map<String, Instant> landed = new HashMap<>();
    Map<String, String> text = new HashMap<>();
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT source_record_id, landed_on, to_char(landed_on AT TIME ZONE 'UTC',"
                    + " 'YYYY-MM-DD HH24:MI:SS.US') FROM ingest")) {
      while (rows.next()) {
        landed.put(rows.getString(1), rows.getObject(2, OffsetDateTime.class).toInstant());
        text.put(rows.getString(1), rows.getString(3));
      }
    }
    assertThat(landed)
        .containsEntry("r1", first)
        .containsEntry("r2", first)
        .containsEntry("r3", second);
    assertThat(text)
        .containsEntry("r1", "2026-10-09 23:59:59.999999")
        .containsEntry("r3", "2026-10-10 00:00:00.000001");
    // the batch row carries the same instant
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT created_at FROM ingest_batch ORDER BY batch_no")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getObject(1, OffsetDateTime.class).toInstant()).isEqualTo(first);
      assertThat(rows.next()).isTrue();
      assertThat(rows.getObject(1, OffsetDateTime.class).toInstant()).isEqualTo(second);
    }
  }

  @Test
  void aStaleEntryIsNeverStored() throws SQLException {
    // inside one commit: v3 first makes v1 and v2 stale
    Result first =
        store()
            .commitIngest(
                commit(
                    "T1",
                    SRC_A,
                    checkpoint(1),
                    entry(SRC_A, "r1", 3, "A"),
                    entry(SRC_A, "r1", 1, "B"),
                    entry(SRC_A, "r1", 2, "C")));
    assertThat(dispositions(first))
        .containsExactly(Disposition.NEW_VERSION, Disposition.STALE, Disposition.STALE);
    // across commits: v1 after the stored v3
    Result later =
        store().commitIngest(commit("T2", SRC_A, checkpoint(2), entry(SRC_A, "r1", 1, "B")));
    assertThat(dispositions(later)).containsExactly(Disposition.STALE);
    // in order, v1 then v2 then v3 are three versions
    Result ascending =
        store()
            .commitIngest(
                commit(
                    "T3",
                    SRC_A,
                    checkpoint(3),
                    entry(SRC_A, "r2", 1, "A"),
                    entry(SRC_A, "r2", 2, "B"),
                    entry(SRC_A, "r2", 3, "A")));
    assertThat(dispositions(ascending))
        .containsExactly(Disposition.NEW_VERSION, Disposition.NEW_VERSION, Disposition.NEW_VERSION);
    assertThat(store().findIngest(new IngestKey(SRC_A, "r1", 1))).isEmpty();
    assertThat(store().findIngest(new IngestKey(SRC_A, "r1", 2))).isEmpty();
    assertThat(store().findIngest(new IngestKey(SRC_A, "r1", 3))).isPresent();
    assertThat(count("SELECT count(*) FROM ingest WHERE source_record_id = 'r1'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM ingest WHERE source_record_id = 'r2'")).isEqualTo(3);
  }

  /** The decision of the T4 note: the entries of one commit are decided against each other. */
  @Test
  void entriesOfOneCommitWithTheSameEventSourceAndVersionAreDecidedAgainstEachOther()
      throws SQLException {
    EventIdentity event = EventIdentity.of("LEI1", "XPAR", "e1");
    Result result =
        store()
            .commitIngest(
                commit(
                    "E1",
                    SRC_A,
                    checkpoint(1),
                    new Entry(new IngestKey(SRC_A, "rec-1", 1), event, Sha256.ofUtf8("A")),
                    new Entry(new IngestKey(SRC_A, "rec-2", 1), event, Sha256.ofUtf8("A")),
                    new Entry(new IngestKey(SRC_A, "rec-3", 1), event, Sha256.ofUtf8("B"))));
    assertThat(dispositions(result))
        .containsExactly(
            Disposition.NEW_VERSION,
            Disposition.NOOP_SAME_VERSION_SAME_HASH,
            Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);
    assertThat(count("SELECT count(*) FROM ingest")).isEqualTo(1);
    assertThat(store().findIngest(new IngestKey(SRC_A, "rec-1", 1))).isPresent();
    assertThat(store().findIngest(new IngestKey(SRC_A, "rec-2", 1))).isEmpty();
  }

  /**
   * The stored result keeps the key and the disposition of every entry, not only the new ones, in
   * the order of the commit: a recovery that reads {@code ingestResult} decides the new entries in
   * that order, and a redelivery returns it unchanged.
   */
  @Test
  void aRedeliveredBatchReturnsEveryEntryInItsOriginalOrder() throws SQLException {
    store().commitIngest(commit("SEED-A", SRC_A, checkpoint(1), entry(SRC_A, "rA", 1, "A")));
    store().commitIngest(commit("SEED-B", SRC_B, checkpoint(2), entry(SRC_B, "rB", 1, "A")));
    store().commitIngest(commit("SEED-S", SRC_A, checkpoint(3), entry(SRC_A, "rS", 5, "S5")));
    Commit mixed =
        new Commit(
            batchId("MIXED"),
            SRC_A,
            List.of(
                entry(SRC_A, "rN2", 1, "N2"),
                entry(SRC_A, "rA", 1, "A"),
                entry(SRC_A, "rN1", 1, "N1"),
                new Entry(
                    new IngestKey(SRC_A, "alt", 1),
                    EventIdentity.of("LEI1", "XPAR", "rA"),
                    Sha256.ofUtf8("X")),
                entry(SRC_A, "rB", 1, "A"),
                entry(SRC_A, "rS", 4, "S4"),
                entry(SRC_A, "rS", 6, "S6")),
            Optional.of(checkpoint(4)));
    Result first = store().commitIngest(mixed);
    assertThat(first.redelivery()).isFalse();
    assertThat(dispositions(first))
        .containsExactly(
            Disposition.NEW_VERSION,
            Disposition.NOOP_SAME_VERSION_SAME_HASH,
            Disposition.NEW_VERSION,
            Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH,
            Disposition.SOURCE_CONFLICT,
            Disposition.STALE,
            Disposition.NEW_VERSION);
    assertThat(first.entries())
        .extracting(EntryResult::key)
        .containsExactly(
            new IngestKey(SRC_A, "rN2", 1),
            new IngestKey(SRC_A, "rA", 1),
            new IngestKey(SRC_A, "rN1", 1),
            new IngestKey(SRC_A, "alt", 1),
            new IngestKey(SRC_A, "rB", 1),
            new IngestKey(SRC_A, "rS", 4),
            new IngestKey(SRC_A, "rS", 6));

    Result again = store().commitIngest(mixed);
    assertThat(again.redelivery()).isTrue();
    assertThat(again.entries()).isEqualTo(first.entries());
    assertThat(again.outboxId()).isEqualTo(first.outboxId());
    assertThat(store().ingestResult(mixed.batchId())).contains(first);
    // only the new entries were stored: three seeds and three from the batch
    assertThat(count("SELECT count(*) FROM ingest")).isEqualTo(6);
    assertThat(store().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).hasSize(4);
  }

  @Test
  void aBatchWithoutEntriesStillHasItsOutboxItemItsCheckpointAndAStoredResult() {
    BatchId id = batchId("EMPTY");
    Commit empty = new Commit(id, SRC_A, List.of(), Optional.of(checkpoint(9)));
    Result first = store().commitIngest(empty);
    assertThat(first.entries()).isEmpty();
    assertThat(first.outboxId()).isPresent();
    assertThat(store().latestCheckpoint(SRC_A, "trades")).contains(checkpoint(9));
    Result again = store().commitIngest(empty);
    assertThat(again.redelivery()).isTrue();
    assertThat(again.entries()).isEmpty();
    assertThat(store().ingestResult(id)).contains(first);
  }
}
