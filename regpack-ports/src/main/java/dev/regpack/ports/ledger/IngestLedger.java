package dev.regpack.ports.ledger;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import java.util.List;
import java.util.Optional;

/** Ingest side of the ledger (spec 7.2 step 2, 10 rule 6). */
public interface IngestLedger {
  enum Disposition {
    NEW_VERSION,
    NOOP_SAME_VERSION_SAME_HASH,
    STALE,
    CONFLICT_SAME_VERSION_DIFFERENT_HASH,
    SOURCE_CONFLICT
  }

  record Entry(IngestKey key, EventIdentity eventIdentity, Sha256 contentHash) {}

  record Commit(
      BatchId batchId, String sourceId, List<Entry> entries, Optional<Checkpoint> checkpoint) {
    public Commit {
      entries = List.copyOf(entries);
    }
  }

  record EntryResult(IngestKey key, Disposition disposition) {}

  record Result(
      BatchId batchId,
      boolean redelivery,
      List<EntryResult> entries,
      Optional<OutboxLedger.OutboxId> outboxId) {
    public Result {
      entries = List.copyOf(entries);
    }
  }

  record StoredIngest(
      IngestKey key, EventIdentity eventIdentity, Sha256 contentHash, BatchId batchId) {}

  /**
   * Atomic and idempotent by batch id (spec 7.2 step 2): a second commit of the same batch id
   * writes nothing and returns the stored result with {@code redelivery = true}. Duplicate keys
   * inside one commit throw {@link IllegalArgumentException} before anything is written.
   */
  Result commitIngest(Commit commit);

  Optional<Result> ingestResult(BatchId batchId);

  Optional<StoredIngest> findIngest(IngestKey key);

  Optional<Checkpoint> latestCheckpoint(String sourceId, String stream);
}
