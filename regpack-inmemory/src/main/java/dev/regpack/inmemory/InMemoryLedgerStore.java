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
import dev.regpack.ports.ledger.DuplicateKeyException;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.LedgerStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
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

  /** States in which at most one delivery per (endpoint, business key) may be (spec 11.2). */
  private static final Set<DeliveryState> IN_FLIGHT =
      EnumSet.of(DeliveryState.SENDING, DeliveryState.AWAITING_ACK, DeliveryState.IN_DOUBT);

  /** Deliberate faults for ContractSuiteTeethTest; {@code NONE} is the correct adapter. */
  public enum Flaw {
    NONE,
    IGNORE_LEASE_TOKEN,
    NO_IN_FLIGHT_GUARD,
    NON_ATOMIC_DECISIONS,
    REINGEST_ON_REDELIVERY,
    NO_TAKEOVER_IN_DOUBT,
    ABA_AS_NOOP,
    IGNORE_RESTORE_EPOCH,
    IDEMPOTENCY_KEY_IGNORES_CONTEXT
  }

  /** Constant stand-in for the decision context id under IDEMPOTENCY_KEY_IGNORES_CONTEXT. */
  private static final Sha256 NO_CONTEXT = new Sha256("0".repeat(64));

  private final Clock clock;
  private final Flaw flaw;
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
  private final Map<EndpointKey, LeaseGrant> leases = new HashMap<>();
  private final Map<EndpointKey, Long> leaseCounters = new HashMap<>();
  private long restoreEpoch = 0;
  private final Map<DeliveryId, Delivery> deliveries = new LinkedHashMap<>();
  private final Set<NewDelivery> deliveryKeys = new HashSet<>();
  private long nextDeliveryId = 1;
  private final Map<AttemptId, Attempt> attempts = new HashMap<>();
  private long nextAttemptId = 1;

  /** The clock is the "database clock" for leases (T11). */
  public InMemoryLedgerStore(Clock clock) {
    this(clock, Flaw.NONE);
  }

  /** Test double only: an adapter with a deliberate {@link Flaw}. */
  public InMemoryLedgerStore(Clock clock, Flaw flaw) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.flaw = Objects.requireNonNull(flaw, "flaw");
  }

  @Override
  public Result commitIngest(Commit commit) {
    Objects.requireNonNull(commit, "commit");
    synchronized (lock) {
      Result stored = results.get(commit.batchId());
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (stored != null && flaw != Flaw.REINGEST_ON_REDELIVERY) {
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
    // test double only: deliberate flaw for ContractSuiteTeethTest
    if (flaw == Flaw.ABA_AS_NOOP && equalsEarlierVersion(commit, entry)) {
      return Disposition.NOOP_SAME_VERSION_SAME_HASH;
    }
    ingests.put(
        entry.key(),
        new StoredIngest(
            entry.key(), entry.eventIdentity(), entry.contentHash(), commit.batchId()));
    highestVersion.put(sourceEvent, entry.key().sourceVersion());
    owningSource.put(entry.eventIdentity(), commit.sourceId());
    return Disposition.NEW_VERSION;
  }

  private boolean equalsEarlierVersion(Commit commit, Entry entry) {
    for (StoredIngest earlier : ingests.values()) {
      if (earlier.eventIdentity().equals(entry.eventIdentity())
          && earlier.key().sourceId().equals(commit.sourceId())
          && earlier.contentHash().equals(entry.contentHash())) {
        return true;
      }
    }
    return false;
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
        DecisionKey decisionKey = decisionKey(body);
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
          // test double only: deliberate flaw for ContractSuiteTeethTest (no rollback)
          if (flaw == Flaw.NON_ATOMIC_DECISIONS) {
            publish(appended, inserted, nextId);
          }
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
      publish(appended, inserted, nextId);
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

  private void publish(
      Map<ChainKey, List<DecisionRecord>> appended,
      Map<DecisionKey, DecisionRecord> inserted,
      long nextId) {
    appended.forEach(
        (chainKey, added) ->
            chains.computeIfAbsent(chainKey, k -> new ArrayList<>()).addAll(added));
    decisionsByKey.putAll(inserted);
    nextDecisionId = nextId;
  }

  private DecisionKey decisionKey(DecisionBody body) {
    // test double only: deliberate flaw for ContractSuiteTeethTest
    Sha256 context =
        flaw == Flaw.IDEMPOTENCY_KEY_IGNORES_CONTEXT ? NO_CONTEXT : body.decisionContextId();
    return new DecisionKey(body.eventIdentity(), context, body.inputDigest());
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

  @Override
  public LeaseResult acquire(EndpointKey endpoint, String holder, Duration ttl) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(holder, "holder");
    requirePositiveWholeMicros(ttl);
    if (holder.isBlank()) {
      throw new IllegalArgumentException("holder must not be blank");
    }
    synchronized (lock) {
      Instant now = clock.now();
      LeaseGrant current = leases.get(endpoint);
      if (current != null && now.isBefore(current.expiresAt())) {
        return new LeaseResult.Held(current.holder(), current.expiresAt());
      }
      long counter = leaseCounters.merge(endpoint, 1L, Long::sum);
      LeaseGrant grant =
          new LeaseGrant(new LeaseToken(restoreEpoch, counter), holder, now.plus(ttl));
      leases.put(endpoint, grant);
      // Takeover rule: whatever the previous holder had in SENDING is now in doubt.
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (flaw != Flaw.NO_TAKEOVER_IN_DOUBT) {
        deliveries.replaceAll(
            (id, delivery) ->
                delivery.key().endpoint().equals(endpoint)
                        && delivery.state() == DeliveryState.SENDING
                    ? new Delivery(id, delivery.key(), DeliveryState.IN_DOUBT)
                    : delivery);
      }
      return new LeaseResult.Granted(grant);
    }
  }

  @Override
  public boolean renew(EndpointKey endpoint, LeaseToken token, Duration ttl) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(token, "token");
    requirePositiveWholeMicros(ttl);
    synchronized (lock) {
      Instant now = clock.now();
      LeaseGrant current = leases.get(endpoint);
      if (current == null || !current.token().equals(token) || !now.isBefore(current.expiresAt())) {
        return false;
      }
      leases.put(endpoint, new LeaseGrant(token, current.holder(), now.plus(ttl)));
      return true;
    }
  }

  @Override
  public void release(EndpointKey endpoint, LeaseToken token) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(token, "token");
    synchronized (lock) {
      LeaseGrant current = leases.get(endpoint);
      if (current != null && current.token().equals(token)) {
        leases.remove(endpoint);
      }
    }
  }

  @Override
  public Optional<LeaseGrant> currentLease(EndpointKey endpoint) {
    Objects.requireNonNull(endpoint, "endpoint");
    synchronized (lock) {
      return Optional.ofNullable(leases.get(endpoint));
    }
  }

  @Override
  public long restoreEpoch() {
    synchronized (lock) {
      return restoreEpoch;
    }
  }

  @Override
  public void declareRestoreEpoch(long epoch) {
    synchronized (lock) {
      if (epoch <= restoreEpoch) {
        throw new IllegalArgumentException(
            "restore epoch " + epoch + " must exceed the current epoch " + restoreEpoch);
      }
      restoreEpoch = epoch;
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (flaw != Flaw.IGNORE_RESTORE_EPOCH) {
        leases.clear();
        leaseCounters.clear();
      }
    }
  }

  private static void requirePositiveWholeMicros(Duration ttl) {
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive: " + ttl);
    }
    if (ttl.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("ttl is not whole microseconds: " + ttl);
    }
  }

  @Override
  public DeliveryId createDelivery(NewDelivery delivery) {
    Objects.requireNonNull(delivery, "delivery");
    Objects.requireNonNull(delivery.endpoint(), "endpoint");
    if (delivery.businessKey().isBlank()) {
      throw new IllegalArgumentException("businessKey must not be blank");
    }
    synchronized (lock) {
      if (!deliveryKeys.add(delivery)) {
        throw new DuplicateKeyException("delivery already exists: " + delivery);
      }
      DeliveryId id = new DeliveryId(nextDeliveryId++);
      deliveries.put(id, new Delivery(id, delivery, DeliveryState.PREPARED));
      return id;
    }
  }

  @Override
  public Optional<Delivery> delivery(DeliveryId id) {
    Objects.requireNonNull(id, "id");
    synchronized (lock) {
      return Optional.ofNullable(deliveries.get(id));
    }
  }

  @Override
  public TransitionStatus transition(DeliveryId id, DeliveryState expectedFrom, DeliveryState to) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(expectedFrom, "expectedFrom");
    Objects.requireNonNull(to, "to");
    if (to == DeliveryState.SENDING) {
      throw new IllegalArgumentException("a delivery moves into SENDING only through beginSending");
    }
    synchronized (lock) {
      Delivery delivery = knownDelivery(id);
      if (delivery.state() != expectedFrom) {
        return TransitionStatus.STATE_MISMATCH;
      }
      if (IN_FLIGHT.contains(to)
          && !IN_FLIGHT.contains(expectedFrom)
          && anotherInFlight(delivery)) {
        return TransitionStatus.IN_FLIGHT_CONFLICT;
      }
      deliveries.put(id, new Delivery(id, delivery.key(), to));
      return TransitionStatus.APPLIED;
    }
  }

  @Override
  public AttemptId allocateAttemptId() {
    synchronized (lock) {
      return new AttemptId(nextAttemptId++);
    }
  }

  @Override
  public SendingResult beginSending(
      DeliveryId id, AttemptId attemptId, LeaseToken token, AttemptIntent intent) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(attemptId, "attemptId");
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(intent, "intent");
    synchronized (lock) {
      Delivery delivery = knownDelivery(id);
      // The id is checked before the fence.
      if (attemptId.value() < 1 || attemptId.value() >= nextAttemptId) {
        throw new IllegalArgumentException("attempt id was not allocated: " + attemptId.value());
      }
      if (attempts.containsKey(attemptId)) {
        throw new DuplicateKeyException("attempt id already used: " + attemptId.value());
      }
      if (!isCurrent(delivery.key().endpoint(), token)) {
        return new SendingResult(SendingStatus.STALE_LEASE, Optional.empty());
      }
      if (delivery.state() != DeliveryState.QUEUED) {
        return new SendingResult(SendingStatus.STATE_MISMATCH, Optional.empty());
      }
      if (anotherInFlight(delivery)) {
        return new SendingResult(SendingStatus.IN_FLIGHT_CONFLICT, Optional.empty());
      }
      deliveries.put(id, new Delivery(id, delivery.key(), DeliveryState.SENDING));
      attempts.put(
          attemptId,
          new Attempt(attemptId, id, token, intent.artifactSha256(), intent.fileName(), false));
      return new SendingResult(SendingStatus.APPLIED, Optional.of(attemptId));
    }
  }

  @Override
  public boolean markCommitIssued(AttemptId attempt, LeaseToken token) {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(token, "token");
    synchronized (lock) {
      Attempt stored = attempts.get(attempt);
      if (stored == null) {
        return false;
      }
      Delivery delivery = deliveries.get(stored.delivery());
      if (delivery.state() != DeliveryState.SENDING
          || !isCurrent(delivery.key().endpoint(), token)) {
        return false;
      }
      attempts.put(
          attempt,
          new Attempt(
              stored.id(),
              stored.delivery(),
              stored.token(),
              stored.artifactSha256(),
              stored.fileName(),
              true));
      return true;
    }
  }

  @Override
  public Optional<Attempt> attempt(AttemptId id) {
    Objects.requireNonNull(id, "id");
    synchronized (lock) {
      return Optional.ofNullable(attempts.get(id));
    }
  }

  private Delivery knownDelivery(DeliveryId id) {
    Delivery delivery = deliveries.get(id);
    if (delivery == null) {
      throw new IllegalArgumentException("unknown delivery " + id.value());
    }
    return delivery;
  }

  /** True when {@code token} is the latest unreleased, unvoided token of the endpoint. */
  private boolean isCurrent(EndpointKey endpoint, LeaseToken token) {
    // test double only: deliberate flaw for ContractSuiteTeethTest
    if (flaw == Flaw.IGNORE_LEASE_TOKEN) {
      return true;
    }
    LeaseGrant current = leases.get(endpoint);
    return current != null && current.token().equals(token);
  }

  /** The in-flight guard: another delivery of the same endpoint and business key is in flight. */
  private boolean anotherInFlight(Delivery delivery) {
    // test double only: deliberate flaw for ContractSuiteTeethTest
    if (flaw == Flaw.NO_IN_FLIGHT_GUARD) {
      return false;
    }
    for (Delivery other : deliveries.values()) {
      if (!other.id().equals(delivery.id())
          && other.key().endpoint().equals(delivery.key().endpoint())
          && other.key().businessKey().equals(delivery.key().businessKey())
          && IN_FLIGHT.contains(other.state())) {
        return true;
      }
    }
    return false;
  }
}
