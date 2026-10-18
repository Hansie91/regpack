package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.batchId;
import static dev.regpack.testkit.contract.LedgerFixtures.checkpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.commit;
import static dev.regpack.testkit.contract.LedgerFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.IngestLedger.Commit;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import dev.regpack.ports.ledger.IngestLedger.Result;
import dev.regpack.ports.ledger.IngestLedger.StoredIngest;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.OutboxLedger;
import dev.regpack.ports.ledger.OutboxLedger.OutboxItem;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.testkit.clock.ManualClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** Contract every {@link LedgerStore} adapter must satisfy for ingest and outbox. */
public interface IngestLedgerContract {
  String SRC_A = "srcA";
  String SRC_B = "srcB";

  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  ManualClock clock();

  private static Disposition only(Result result) {
    assertThat(result.entries()).hasSize(1);
    return result.entries().get(0).disposition();
  }

  @Test
  default void newBatchIsCommittedWithDispositionsCheckpointAndOutbox() {
    Commit c =
        commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A"), entry(SRC_A, "r2", 1, "A"));
    Result result = ledger().commitIngest(c);
    assertThat(result.redelivery()).isFalse();
    assertThat(result.batchId()).isEqualTo(c.batchId());
    assertThat(result.entries())
        .extracting(EntryResult::disposition)
        .containsExactly(Disposition.NEW_VERSION, Disposition.NEW_VERSION);
    assertThat(result.outboxId()).isPresent();
    List<OutboxItem> pending = ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10);
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).subjectId()).isEqualTo(c.batchId().value());
    assertThat(pending.get(0).state()).isEqualTo(OutboxState.PENDING);
    assertThat(pending.get(0).id()).isEqualTo(result.outboxId().orElseThrow());
    assertThat(ledger().latestCheckpoint(SRC_A, "trades")).contains(checkpoint(1));
    StoredIngest stored = ledger().findIngest(new IngestKey(SRC_A, "r1", 1)).orElseThrow();
    assertThat(stored.contentHash()).isEqualTo(Sha256.ofUtf8("A"));
    assertThat(stored.batchId()).isEqualTo(c.batchId());
  }

  @Test
  default void checkpointsAreIndependentPerSourceAndStream() {
    Checkpoint quotes = new Checkpoint("quotes", new byte[] {9});
    ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A")));
    ledger().commitIngest(commit("B2", SRC_B, checkpoint(2), entry(SRC_B, "r2", 1, "A")));
    ledger().commitIngest(commit("B3", SRC_A, quotes, entry(SRC_A, "r3", 1, "A")));
    // Same stream name, two sources: each keeps its own latest checkpoint.
    assertThat(ledger().latestCheckpoint(SRC_A, "trades")).contains(checkpoint(1));
    assertThat(ledger().latestCheckpoint(SRC_B, "trades")).contains(checkpoint(2));
    // Same source, two streams: the second stream does not replace the first.
    assertThat(ledger().latestCheckpoint(SRC_A, "quotes")).contains(quotes);
    assertThat(ledger().latestCheckpoint(SRC_B, "quotes")).isEmpty();
    // A later commit of one source moves only that source's checkpoint.
    ledger().commitIngest(commit("B4", SRC_A, checkpoint(3), entry(SRC_A, "r4", 1, "A")));
    assertThat(ledger().latestCheckpoint(SRC_A, "trades")).contains(checkpoint(3));
    assertThat(ledger().latestCheckpoint(SRC_B, "trades")).contains(checkpoint(2));
    assertThat(ledger().latestCheckpoint(SRC_A, "quotes")).contains(quotes);
  }

  @Test
  default void pendingOutboxIsOldestFirstTruncatedByMaxWithIncreasingDistinctIds() {
    Result b1 =
        ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A")));
    Result b2 =
        ledger().commitIngest(commit("B2", SRC_A, checkpoint(2), entry(SRC_A, "r2", 1, "A")));
    Result b3 =
        ledger().commitIngest(commit("B3", SRC_A, checkpoint(3), entry(SRC_A, "r3", 1, "A")));
    List<OutboxItem> all = ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10);
    assertThat(all)
        .extracting(OutboxItem::subjectId)
        .containsExactly(b1.batchId().value(), b2.batchId().value(), b3.batchId().value());
    assertThat(all)
        .extracting(OutboxItem::id)
        .containsExactly(
            b1.outboxId().orElseThrow(), b2.outboxId().orElseThrow(), b3.outboxId().orElseThrow());
    long previous = Long.MIN_VALUE;
    for (OutboxItem item : all) {
      assertThat(item.id().value()).isGreaterThan(previous);
      previous = item.id().value();
    }
    // max truncates from the old end and keeps the order.
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 1))
        .extracting(OutboxItem::subjectId)
        .containsExactly(b1.batchId().value());
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 2))
        .extracting(OutboxItem::subjectId)
        .containsExactly(b1.batchId().value(), b2.batchId().value());
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 0)).isEmpty();
  }

  @Test
  default void redeliveredBatchIsNoopAndReturnsOriginalResult() {
    Result first =
        ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A")));
    Result second =
        ledger().commitIngest(commit("B1", SRC_A, checkpoint(2), entry(SRC_A, "r1", 1, "A")));
    assertThat(first.redelivery()).isFalse();
    assertThat(second.redelivery()).isTrue();
    assertThat(second.entries()).isEqualTo(first.entries());
    assertThat(second.outboxId()).isEqualTo(first.outboxId());
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).hasSize(1);
    assertThat(ledger().latestCheckpoint(SRC_A, "trades")).contains(checkpoint(1));
  }

  @Test
  default void sameVersionSameHashIsNoop() {
    ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A")));
    Result again =
        ledger().commitIngest(commit("B2", SRC_A, checkpoint(2), entry(SRC_A, "r1", 1, "A")));
    assertThat(again.redelivery()).isFalse();
    assertThat(only(again)).isEqualTo(Disposition.NOOP_SAME_VERSION_SAME_HASH);
  }

  @Test
  default void sameVersionDifferentHashIsConflictAndKeepsOriginal() {
    ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A")));
    Result conflict =
        ledger().commitIngest(commit("B2", SRC_A, checkpoint(2), entry(SRC_A, "r1", 1, "B")));
    assertThat(only(conflict)).isEqualTo(Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);
    StoredIngest stored = ledger().findIngest(new IngestKey(SRC_A, "r1", 1)).orElseThrow();
    assertThat(stored.contentHash()).isEqualTo(Sha256.ofUtf8("A"));
    assertThat(stored.batchId()).isEqualTo(batchId("B1"));
  }

  @Test
  default void lowerVersionAfterHigherIsStale() {
    Result first =
        ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r9", 2, "A")));
    assertThat(only(first)).isEqualTo(Disposition.NEW_VERSION);
    Result stale =
        ledger().commitIngest(commit("B2", SRC_A, checkpoint(2), entry(SRC_A, "r9", 1, "A")));
    assertThat(only(stale)).isEqualTo(Disposition.STALE);
    assertThat(ledger().findIngest(new IngestKey(SRC_A, "r9", 1))).isEmpty();
  }

  @Test
  default void abaProducesThreeVersions() {
    Result v1 =
        ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r5", 1, "A")));
    Result v2 =
        ledger().commitIngest(commit("B2", SRC_A, checkpoint(2), entry(SRC_A, "r5", 2, "B")));
    Result v3 =
        ledger().commitIngest(commit("B3", SRC_A, checkpoint(3), entry(SRC_A, "r5", 3, "A")));
    assertThat(only(v1)).isEqualTo(Disposition.NEW_VERSION);
    assertThat(only(v2)).isEqualTo(Disposition.NEW_VERSION);
    assertThat(only(v3)).isEqualTo(Disposition.NEW_VERSION);
    assertThat(ledger().findIngest(new IngestKey(SRC_A, "r5", 3))).isPresent();
  }

  @Test
  default void otherSourceForSameEventIsSourceConflict() {
    ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A")));
    Result conflict =
        ledger().commitIngest(commit("B2", SRC_B, checkpoint(2), entry(SRC_B, "r1", 1, "A")));
    assertThat(only(conflict)).isEqualTo(Disposition.SOURCE_CONFLICT);
    assertThat(ledger().findIngest(new IngestKey(SRC_B, "r1", 1))).isEmpty();
  }

  @Test
  default void duplicateKeysInsideOneCommitAreRejectedAtomically() {
    Commit bad =
        commit(
            "B1",
            SRC_A,
            checkpoint(1),
            entry(SRC_A, "r1", 1, "A"),
            entry(SRC_A, "r2", 1, "A"),
            entry(SRC_A, "r1", 1, "B"));
    assertThatThrownBy(() -> ledger().commitIngest(bad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger().latestCheckpoint(SRC_A, "trades")).isEmpty();
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
    assertThat(ledger().ingestResult(bad.batchId())).isEmpty();
    assertThat(ledger().findIngest(new IngestKey(SRC_A, "r2", 1))).isEmpty();
  }

  /** M35: an entry of another source than the commit's is an error, and nothing is written. */
  @Test
  default void anEntryOfAnotherSourceThanTheCommitIsRejectedBeforeAnyWrite() {
    Commit bad =
        commit(
            "B1",
            SRC_A,
            checkpoint(1),
            entry(SRC_A, "r1", 1, "A"),
            entry(SRC_B, "r2", 1, "A"),
            entry(SRC_A, "r3", 1, "A"));
    assertThatThrownBy(() -> ledger().commitIngest(bad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger().ingestResult(bad.batchId())).isEmpty();
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
    assertThat(ledger().latestCheckpoint(SRC_A, "trades")).isEmpty();
    assertThat(ledger().findIngest(new IngestKey(SRC_A, "r1", 1))).isEmpty();
    assertThat(ledger().findIngest(new IngestKey(SRC_B, "r2", 1))).isEmpty();
    assertThat(ledger().findIngest(new IngestKey(SRC_A, "r3", 1))).isEmpty();
    // the rejected batch id is not burnt: the corrected commit under it is applied
    Result good =
        ledger().commitIngest(commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A")));
    assertThat(good.redelivery()).isFalse();
  }

  /** M39: a blank commit source id is an error, and nothing is written. */
  @Test
  default void aBlankCommitSourceIdIsRejected() {
    for (String blank : new String[] {"", " ", "\t"}) {
      Commit bad = new Commit(batchId("B1"), blank, List.of(), Optional.of(checkpoint(1)));
      assertThatThrownBy(() -> ledger().commitIngest(bad))
          .as("source id '" + blank + "'")
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(ledger().ingestResult(bad.batchId())).isEmpty();
      assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
    }
  }

  /**
   * Spec 10 {@code ingest_event_version_key}: the same event identity, source and source version
   * under another record id is one version, decided by the content hash. Nothing is stored for the
   * second record id.
   */
  @Test
  default void sameEventSourceAndVersionUnderAnotherRecordIdIsDecidedByHash() {
    EventIdentity event = EventIdentity.of("LEI1", "XPAR", "e1");
    IngestKey first = new IngestKey(SRC_A, "rec-1", 1);
    IngestKey second = new IngestKey(SRC_A, "rec-2", 1);
    Result created =
        ledger()
            .commitIngest(
                commit("B1", SRC_A, checkpoint(1), new Entry(first, event, Sha256.ofUtf8("A"))));
    assertThat(only(created)).isEqualTo(Disposition.NEW_VERSION);

    Result same =
        ledger()
            .commitIngest(
                commit("B2", SRC_A, checkpoint(2), new Entry(second, event, Sha256.ofUtf8("A"))));
    assertThat(only(same)).isEqualTo(Disposition.NOOP_SAME_VERSION_SAME_HASH);

    Result different =
        ledger()
            .commitIngest(
                commit("B3", SRC_A, checkpoint(3), new Entry(second, event, Sha256.ofUtf8("B"))));
    assertThat(only(different)).isEqualTo(Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);

    assertThat(ledger().findIngest(second)).isEmpty();
    StoredIngest stored = ledger().findIngest(first).orElseThrow();
    assertThat(stored.contentHash()).isEqualTo(Sha256.ofUtf8("A"));
    assertThat(stored.batchId()).isEqualTo(batchId("B1"));
    // another version of the same event under the other record id is a different key
    Result next =
        ledger()
            .commitIngest(
                commit(
                    "B4",
                    SRC_A,
                    checkpoint(4),
                    new Entry(new IngestKey(SRC_A, "rec-2", 2), event, Sha256.ofUtf8("B"))));
    assertThat(only(next)).isEqualTo(Disposition.NEW_VERSION);
  }

  /** M115/M116: one call returns at most {@link OutboxLedger#PAGE_LIMIT} items, oldest first. */
  @Test
  default void pendingOutboxReturnsAtMostPageLimitItems() {
    assertThat(OutboxLedger.PAGE_LIMIT).isEqualTo(1_000);
    int batches = OutboxLedger.PAGE_LIMIT + 1;
    List<BatchId> ids = new ArrayList<>();
    for (int i = 0; i < batches; i++) {
      Result result =
          ledger()
              .commitIngest(commit("P" + i, SRC_A, checkpoint(i), entry(SRC_A, "p" + i, 1, "A")));
      ids.add(result.batchId());
    }
    for (int max : new int[] {OutboxLedger.PAGE_LIMIT, batches, 5_000, Integer.MAX_VALUE}) {
      List<OutboxItem> page = ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, max);
      assertThat(page).as("max " + max).hasSize(OutboxLedger.PAGE_LIMIT);
      assertThat(page.get(0).subjectId()).isEqualTo(ids.get(0).value());
      assertThat(page.get(page.size() - 1).subjectId())
          .isEqualTo(ids.get(OutboxLedger.PAGE_LIMIT - 1).value());
    }
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 3))
        .extracting(OutboxItem::subjectId)
        .containsExactly(ids.get(0).value(), ids.get(1).value(), ids.get(2).value());
    assertThatThrownBy(() -> ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  default void ingestResultIsRetrievableByBatchId() {
    Commit c = commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A"));
    assertThat(ledger().ingestResult(c.batchId())).isEmpty();
    Result result = ledger().commitIngest(c);
    assertThat(ledger().ingestResult(c.batchId())).contains(result);
  }

  /**
   * Meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec
   * 14.1).
   */
  @Test
  default void concurrentCommitsOfTheSameBatchIdApplyExactlyOnce() throws Exception {
    int threads = 8;
    Commit c = commit("B1", SRC_A, checkpoint(1), entry(SRC_A, "r1", 1, "A"));
    List<Result> results = runConcurrently(threads, i -> c);
    assertThat(results.stream().filter(r -> !r.redelivery()).count()).isEqualTo(1);
    assertThat(results.stream().filter(Result::redelivery).count()).isEqualTo(threads - 1);
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).hasSize(1);
  }

  /**
   * Meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec
   * 14.1).
   */
  @Test
  default void concurrentBatchesWithTheSameIngestKeyCreateExactlyOneVersion() throws Exception {
    int threads = 8;
    List<Result> results =
        runConcurrently(
            threads, i -> commit("B" + i, SRC_A, checkpoint(i), entry(SRC_A, "r1", 1, "A")));
    long created = results.stream().filter(r -> only(r) == Disposition.NEW_VERSION).count();
    long noops =
        results.stream().filter(r -> only(r) == Disposition.NOOP_SAME_VERSION_SAME_HASH).count();
    assertThat(created).isEqualTo(1);
    assertThat(noops).isEqualTo(threads - 1);
    BatchId winner =
        results.stream()
            .filter(r -> only(r) == Disposition.NEW_VERSION)
            .findFirst()
            .orElseThrow()
            .batchId();
    assertThat(ledger().findIngest(new IngestKey(SRC_A, "r1", 1)).orElseThrow().batchId())
        .isEqualTo(winner);
  }

  private List<Result> runConcurrently(int threads, IntFunction<Commit> commits) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Result>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Commit c = commits.apply(i);
        Callable<Result> task =
            () -> {
              release.await();
              return ledger().commitIngest(c);
            };
        futures.add(pool.submit(task));
      }
      release.countDown();
      List<Result> results = new ArrayList<>();
      for (Future<Result> f : futures) {
        results.add(f.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
