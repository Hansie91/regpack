package dev.regpack.ports.ledger;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.KeyText;
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
      // a blank source id (control whitespace included) is the adapters' IAE of M39
      if (sourceId != null && !sourceId.isBlank()) {
        KeyText.require("sourceId", sourceId);
      }
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
   * writes nothing and returns the stored result with {@code redelivery = true}.
   *
   * <p>Throws {@link IllegalArgumentException} before anything is written (nothing is stored, not
   * even the batch id) for: duplicate keys inside one commit; an entry whose source differs from
   * {@code sourceId} (M35); a blank {@code sourceId} (M39).
   *
   * <p>Every text that becomes a key (the source id, the record id, the event identity, the batch
   * id, the stream) is a key text ({@link KeyText}): no control character (U+0000 included), no
   * unpaired surrogate, a bounded length. A value that is not is refused with {@link
   * IllegalArgumentException} by the constructor of its type, in every adapter, before anything is
   * written: the real store cannot hold such a value (it would fail the batch on every redelivery
   * or store a changed key), so the pipeline quarantines the record instead of committing it.
   *
   * <p>An entry whose (event identity, source, source version) exists under another record id is
   * the same version of the event and is decided by the content hash (spec 10 {@code
   * ingest_event_version_key}): an equal hash is {@code NOOP_SAME_VERSION_SAME_HASH}, a different
   * hash is {@code CONFLICT_SAME_VERSION_DIFFERENT_HASH}, and nothing is stored for it.
   */
  Result commitIngest(Commit commit);

  Optional<Result> ingestResult(BatchId batchId);

  Optional<StoredIngest> findIngest(IngestKey key);

  /**
   * The latest checkpoint of the stream of a source. Both texts must be key texts ({@link
   * KeyText}), else {@link IllegalArgumentException}.
   */
  Optional<Checkpoint> latestCheckpoint(String sourceId, String stream);
}
