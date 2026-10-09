package dev.regpack.inmemory;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.LedgerStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link LedgerStore}. Every public method is {@code synchronized} on one lock and
 * validates its input before it mutates anything, so a rejected call leaves no trace.
 */
public final class InMemoryLedgerStore implements LedgerStore {
  private record SourceEvent(EventIdentity eventIdentity, String sourceId) {}

  private record StreamKey(String sourceId, String stream) {}

  private final Clock clock;
  private final Object lock = new Object();
  private final Map<IngestKey, StoredIngest> ingests = new HashMap<>();
  private final Map<SourceEvent, Long> highestVersion = new HashMap<>();
  private final Map<EventIdentity, String> owningSource = new HashMap<>();
  private final Map<BatchId, Result> results = new HashMap<>();
  private final Map<StreamKey, Checkpoint> checkpoints = new HashMap<>();
  private final Map<OutboxId, OutboxItem> outbox = new LinkedHashMap<>();
  private long nextOutboxId = 1;

  /** The clock is the "database clock" for leases (T11). */
  public InMemoryLedgerStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Result commitIngest(Commit commit) {
    Objects.requireNonNull(commit, "commit");
    synchronized (lock) {
      Result stored = results.get(commit.batchId());
      if (stored != null) {
        return new Result(stored.batchId(), true, stored.entries(), stored.outboxId());
      }
      validate(commit);
      List<EntryResult> entryResults = new ArrayList<>();
      for (Entry entry : commit.entries()) {
        entryResults.add(new EntryResult(entry.key(), apply(commit, entry)));
      }
      OutboxId outboxId = new OutboxId(nextOutboxId++);
      outbox.put(
          outboxId,
          new OutboxItem(
              outboxId, OutboxKind.DECIDE_BATCH, commit.batchId().value(), OutboxState.PENDING));
      commit
          .checkpoint()
          .ifPresent(cp -> checkpoints.put(new StreamKey(commit.sourceId(), cp.stream()), cp));
      Result result = new Result(commit.batchId(), false, entryResults, Optional.of(outboxId));
      results.put(commit.batchId(), result);
      return result;
    }
  }

  private static void validate(Commit commit) {
    if (commit.sourceId().isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    Set<IngestKey> seen = new HashSet<>();
    for (Entry entry : commit.entries()) {
      if (!entry.key().sourceId().equals(commit.sourceId())) {
        throw new IllegalArgumentException(
            "entry source " + entry.key().sourceId() + " differs from commit source");
      }
      if (!seen.add(entry.key())) {
        throw new IllegalArgumentException("duplicate ingest key in one commit: " + entry.key());
      }
    }
  }

  /** Decides and, when the entry is new, stores it. Called only after validation. */
  private Disposition apply(Commit commit, Entry entry) {
    StoredIngest existing = ingests.get(entry.key());
    if (existing != null) {
      return existing.contentHash().equals(entry.contentHash())
          ? Disposition.NOOP_SAME_VERSION_SAME_HASH
          : Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH;
    }
    String owner = owningSource.get(entry.eventIdentity());
    if (owner != null && !owner.equals(commit.sourceId())) {
      return Disposition.SOURCE_CONFLICT;
    }
    SourceEvent sourceEvent = new SourceEvent(entry.eventIdentity(), commit.sourceId());
    Long highest = highestVersion.get(sourceEvent);
    if (highest != null && entry.key().sourceVersion() < highest) {
      return Disposition.STALE;
    }
    ingests.put(
        entry.key(),
        new StoredIngest(
            entry.key(), entry.eventIdentity(), entry.contentHash(), commit.batchId()));
    highestVersion.put(sourceEvent, entry.key().sourceVersion());
    owningSource.put(entry.eventIdentity(), commit.sourceId());
    return Disposition.NEW_VERSION;
  }

  @Override
  public Optional<Result> ingestResult(BatchId batchId) {
    Objects.requireNonNull(batchId, "batchId");
    synchronized (lock) {
      return Optional.ofNullable(results.get(batchId));
    }
  }

  @Override
  public Optional<StoredIngest> findIngest(IngestKey key) {
    Objects.requireNonNull(key, "key");
    synchronized (lock) {
      return Optional.ofNullable(ingests.get(key));
    }
  }

  @Override
  public Optional<Checkpoint> latestCheckpoint(String sourceId, String stream) {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(stream, "stream");
    synchronized (lock) {
      return Optional.ofNullable(checkpoints.get(new StreamKey(sourceId, stream)));
    }
  }

  @Override
  public List<OutboxItem> pendingOutbox(OutboxKind kind, int max) {
    Objects.requireNonNull(kind, "kind");
    if (max < 0) {
      throw new IllegalArgumentException("max must be >= 0");
    }
    synchronized (lock) {
      List<OutboxItem> pending = new ArrayList<>();
      for (OutboxItem item : outbox.values()) {
        if (pending.size() >= max) {
          break;
        }
        if (item.kind() == kind && item.state() == OutboxState.PENDING) {
          pending.add(item);
        }
      }
      return List.copyOf(pending);
    }
  }

  @Override
  public Optional<OutboxItem> outboxItem(OutboxId id) {
    Objects.requireNonNull(id, "id");
    synchronized (lock) {
      return Optional.ofNullable(outbox.get(id));
    }
  }
}
