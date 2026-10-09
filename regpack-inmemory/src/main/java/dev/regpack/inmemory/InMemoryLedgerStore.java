package dev.regpack.inmemory;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
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

  private record ChainKey(Regime regime, EventIdentity eventIdentity) {}

  private record DecisionKey(
      EventIdentity eventIdentity, Sha256 decisionContextId, Sha256 inputDigest) {}

  private final Clock clock;
  private final Object lock = new Object();
  private final Map<IngestKey, StoredIngest> ingests = new HashMap<>();
  private final Map<SourceEvent, Long> highestVersion = new HashMap<>();
  private final Map<EventIdentity, String> owningSource = new HashMap<>();
  private final Map<BatchId, Result> results = new HashMap<>();
  private final Map<StreamKey, Checkpoint> checkpoints = new HashMap<>();
  private final Map<OutboxId, OutboxItem> outbox = new LinkedHashMap<>();
  private long nextOutboxId = 1;
  private final Map<ChainKey, List<DecisionRecord>> chains = new HashMap<>();
  private final Map<DecisionKey, DecisionRecord> decisionsByKey = new HashMap<>();
  private long nextDecisionId = 1;

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

  @Override
  public List<Committed> commitDecisions(BatchCommit commit) {
    Objects.requireNonNull(commit, "commit");
    synchronized (lock) {
      validate(commit);
      // Work on copies; real state is touched only after every decision has been accepted.
      Map<ChainKey, List<DecisionRecord>> appended = new LinkedHashMap<>();
      Map<DecisionKey, DecisionRecord> inserted = new LinkedHashMap<>();
      long nextId = nextDecisionId;
      List<Committed> committed = new ArrayList<>();
      for (NewDecision decision : commit.decisions()) {
        DecisionBody body = decision.body();
        DecisionKey decisionKey =
            new DecisionKey(body.eventIdentity(), body.decisionContextId(), body.inputDigest());
        DecisionRecord known = inserted.get(decisionKey);
        if (known == null) {
          known = decisionsByKey.get(decisionKey);
        }
        if (known != null) {
          committed.add(new Committed(CommitStatus.NOOP_REDELIVERY, known));
          continue;
        }
        ChainKey chainKey = new ChainKey(body.regime(), body.eventIdentity());
        List<DecisionRecord> chain = chains.getOrDefault(chainKey, List.of());
        List<DecisionRecord> added = appended.computeIfAbsent(chainKey, k -> new ArrayList<>());
        long headSeq = chain.size() + added.size();
        if (decision.expectedHeadSeq() != headSeq) {
          throw new DecisionHeadConflictException(body.eventIdentity(), headSeq);
        }
        Optional<DecisionId> supersedes = Optional.empty();
        if (!added.isEmpty()) {
          supersedes = Optional.of(added.get(added.size() - 1).decisionId());
        } else if (!chain.isEmpty()) {
          supersedes = Optional.of(chain.get(chain.size() - 1).decisionId());
        }
        DecisionRecord record =
            new DecisionRecord(new DecisionId(nextId++), headSeq + 1, supersedes, body);
        added.add(record);
        inserted.put(decisionKey, record);
        committed.add(new Committed(CommitStatus.INSERTED, record));
      }
      appended.forEach(
          (chainKey, added) ->
              chains.computeIfAbsent(chainKey, k -> new ArrayList<>()).addAll(added));
      decisionsByKey.putAll(inserted);
      nextDecisionId = nextId;
      commit
          .completesOutbox()
          .ifPresent(
              id -> {
                OutboxItem item = outbox.get(id);
                outbox.put(id, new OutboxItem(id, item.kind(), item.subjectId(), OutboxState.DONE));
              });
      return List.copyOf(committed);
    }
  }

  private void validate(BatchCommit commit) {
    for (NewDecision decision : commit.decisions()) {
      if (decision.expectedHeadSeq() < 0) {
        throw new IllegalArgumentException("expectedHeadSeq must be >= 0");
      }
    }
    commit
        .completesOutbox()
        .ifPresent(
            id -> {
              if (!outbox.containsKey(id)) {
                throw new IllegalArgumentException("unknown outbox item " + id.value());
              }
            });
  }

  @Override
  public Optional<DecisionRecord> currentDecision(Regime regime, EventIdentity eventIdentity) {
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    synchronized (lock) {
      List<DecisionRecord> chain =
          chains.getOrDefault(new ChainKey(regime, eventIdentity), List.of());
      return chain.isEmpty() ? Optional.empty() : Optional.of(chain.get(chain.size() - 1));
    }
  }

  @Override
  public List<DecisionRecord> decisionChain(Regime regime, EventIdentity eventIdentity) {
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    synchronized (lock) {
      return List.copyOf(chains.getOrDefault(new ChainKey(regime, eventIdentity), List.of()));
    }
  }
}
