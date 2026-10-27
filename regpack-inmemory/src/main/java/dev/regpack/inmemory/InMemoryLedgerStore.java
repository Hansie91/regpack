package dev.regpack.inmemory;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.KeyText;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.AsAtRange;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionCodec;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.model.json.Json;
import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DuplicateKeyException;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestConflictException;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.ports.ledger.SubjectIndexLedger;
import dev.regpack.ports.ledger.TrnConflictException;
import dev.regpack.ports.ledger.WithdrawalRequiredException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * In-memory {@link LedgerStore}. Every public method is {@code synchronized} on one lock and
 * validates its input before it mutates anything, so a rejected call leaves no trace.
 */
public final class InMemoryLedgerStore implements LedgerStore, RetentionLedger, SubjectIndexLedger {
  private record SourceEvent(EventIdentity eventIdentity, String sourceId) {}

  /** The spec 10 {@code ingest_event_version_key}: (event identity, source, source version). */
  private record EventVersion(EventIdentity eventIdentity, String sourceId, long sourceVersion) {}

  private record StreamKey(String sourceId, String stream) {}

  private record ChainKey(Regime regime, EventIdentity eventIdentity) {}

  /** The place of a retention row inside its artifact: ordered by regime id text, then anchor. */
  private record RetentionSlot(String regime, AnchorEvent anchor)
      implements Comparable<RetentionSlot> {
    @Override
    public int compareTo(RetentionSlot other) {
      int byRegime = regime.compareTo(other.regime);
      return byRegime != 0 ? byRegime : anchor.compareTo(other.anchor);
    }
  }

  /** The order of the references of one hash: the kind by its code, then the text by code unit. */
  private static final Comparator<SubjectRef> SUBJECT_REF_ORDER =
      Comparator.comparing(SubjectRef::kind).thenComparing(SubjectRef::ref);

  /** The order of the expiry list: (retain-until, artifact text, regime id text, anchor). */
  private static final Comparator<RetentionRow> EXPIRY_ORDER =
      Comparator.<RetentionRow, Instant>comparing(row -> row.claim().retainUntil().orElseThrow())
          .thenComparing(row -> row.claim().artifact().path())
          .thenComparing(row -> row.claim().regime().id())
          .thenComparing(row -> row.claim().anchor());

  /**
   * Spec 10 scopes the decision key by regime: (regime, event identity, context, input), and the
   * schema (D-S3, {@code decision_idem_key}) adds the UTC day of the decision's asAt.
   */
  private record DecisionKey(
      Regime regime,
      EventIdentity eventIdentity,
      Sha256 decisionContextId,
      Sha256 inputDigest,
      LocalDate asAtDay) {}

  /** (executing LEI, TRN): the spec 10 {@code UNIQUE (executing LEI, TRN)} of the assignment. */
  private record TrnKey(String executingLei, String trn) {}

  /** The TRN of a subject, assigned once with its first version and never changed. */
  private record Trn(String executingLei, String trn, String keyId, Instant assignedAt) {}

  /** At most one version of a subject per decision. */
  private record DecisionSubject(DecisionId decision, SubjectId subject) {}

  private static final class SubjectRow {
    private final SubjectId id;
    private final SubjectKey key;
    private Optional<Trn> trn = Optional.empty();
    private final List<VersionRow> versions = new ArrayList<>();

    private SubjectRow(SubjectId id, SubjectKey key) {
      this.id = id;
      this.key = key;
    }
  }

  private record VersionRow(
      SubjectId subject,
      long seq,
      DecisionId decision,
      String action,
      Sha256 logicalHash,
      byte[] body,
      VersionOrigin origin,
      DeliveryId delivery) {}

  /** Deliveries whose version is not live: a later equal version is not suppressed against them. */
  private static final Set<DeliveryState> DEAD =
      EnumSet.of(
          DeliveryState.REJECTED,
          DeliveryState.IN_REPAIR,
          DeliveryState.WITHDRAWN,
          DeliveryState.WRITTEN_OFF);

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
    IDEMPOTENCY_KEY_IGNORES_CONTEXT,
    /** A second subject may take a (executing LEI, TRN) pair that is already assigned. */
    TRN_NOT_UNIQUE_PER_LEI,
    /** Every version with a proposal overwrites the key id of the stored TRN. */
    TRN_REASSIGNED_PER_VERSION,
    /** Suppression compares with any live version of the subject, not only the latest. */
    SUPPRESS_AGAINST_ANY_VERSION,
    /** A reportable unit that throws leaves the decisions and subjects staged so far. */
    NON_ATOMIC_REPORTABLE,
    /** The subject key ignores the regime. */
    SUBJECT_NOT_REGIME_SCOPED,
    /** A superseding non-REPORT decision is accepted while versions exist. */
    NO_WITHDRAWAL_GUARD,
    /** A takeover moves the SENDING deliveries of every endpoint, not only its own. */
    TAKEOVER_TOUCHES_OTHER_ENDPOINTS,
    /** The outbox page limit is not applied. */
    PAGE_UNBOUNDED,
    /** A decision with an asAt outside [2000-01-01, 2200-01-01) UTC is accepted. */
    AS_AT_NOT_CHECKED,
    /** The idempotency key of a decision leaves out the UTC day of its asAt. */
    IDEMPOTENCY_KEY_IGNORES_DAY,
    /** A lease ttl above {@link LeaseLedger#MAX_TTL} is accepted. */
    LEASE_TTL_UNBOUNDED,
    /** The withdrawal guard looks at the first leg only. */
    WITHDRAWAL_GUARD_FIRST_LEG_ONLY,
    /** The withdrawal guard ignores the outcome: only a dropped leg counts. */
    WITHDRAWAL_GUARD_IGNORES_OUTCOME,
    /** The withdrawal guard also runs on a redelivered (NOOP_REDELIVERY) decision. */
    WITHDRAWAL_GUARD_ON_REDELIVERY,
    /** A version is accepted on a decision whose outcome is not REPORT. */
    VERSION_ON_ANY_OUTCOME,
    /** The highest stored version of an event is kept as the lowest one. */
    HIGHEST_VERSION_IS_MIN,
    /** The exact ingest key is not looked up first, only (event, source, version). */
    EXACT_KEY_NOT_FIRST,
    /** The pending outbox lists the items of every kind. */
    OUTBOX_KIND_IGNORED,
    /** A key that is recorded again with another hash overwrites the row instead of conflicting. */
    MANIFEST_OVERWRITES_ON_CONFLICT,
    /** A beginSending that does not apply still records the objects of its intent. */
    MANIFEST_RECORDED_ON_FAILED_BEGIN,
    /** A manifest conflict in beginSending leaves the state change and the attempt row behind. */
    BEGIN_SENDING_NOT_ATOMIC_WITH_MANIFEST,
    /** A ledger that is built to require envelope bodies accepts a plain body. */
    ENVELOPE_REQUIREMENT_IGNORED,
    /** A claim with a value replaces the stored retain-until even when it is earlier. */
    RETENTION_SHORTENS,
    /** The expiry list also names the rows under the epoch-marker prefix. */
    RETENTION_EXPIRED_LISTS_CONTROL,
    /** The expiry list also names the rows that are held. */
    RETENTION_HOLD_IGNORED_IN_EXPIRED,
    /** A new row of a held artifact is stored unheld. */
    RETENTION_NEW_ROW_UNHELD,
    /** Removing a reference deletes every row of each hash that names it. */
    SUBJECT_REMOVE_REMOVES_ALL
  }

  /** Constant stand-in for the day under IDEMPOTENCY_KEY_IGNORES_DAY. */
  private static final LocalDate NO_DAY = LocalDate.of(2000, 1, 1);

  /** Constant stand-in for the decision context id under IDEMPOTENCY_KEY_IGNORES_CONTEXT. */
  private static final Sha256 NO_CONTEXT = new Sha256("0".repeat(64));

  /** Constant stand-in for the regime under SUBJECT_NOT_REGIME_SCOPED. */
  private static final Regime NO_REGIME = new Regime("noregime");

  private final Clock clock;
  private final Flaw flaw;
  private volatile boolean requireEnvelopeBodies;
  private final Object lock = new Object();
  private final Map<IngestKey, StoredIngest> ingests = new HashMap<>();
  private final Map<EventVersion, StoredIngest> ingestsByEventVersion = new HashMap<>();
  private final Map<SourceEvent, Long> highestVersion = new HashMap<>();
  private final Map<EventIdentity, String> owningSource = new HashMap<>();
  private final Map<BatchId, Result> results = new HashMap<>();
  private final Map<StreamKey, Checkpoint> checkpoints = new HashMap<>();
  private final Map<OutboxId, OutboxItem> outbox = new LinkedHashMap<>();
  private long nextOutboxId = 1;
  private final Map<ChainKey, List<DecisionRecord>> chains = new HashMap<>();
  private final Map<DecisionKey, DecisionRecord> decisionsByKey = new HashMap<>();
  private final Map<Sha256, DecisionContext> contexts = new HashMap<>();
  private long nextDecisionId = 1;
  private final Map<SubjectKey, SubjectRow> subjects = new HashMap<>();
  private final Map<SubjectId, SubjectRow> subjectsById = new HashMap<>();
  private final Map<TrnKey, SubjectRow> subjectsByTrn = new HashMap<>();
  private final Map<DecisionSubject, VersionRow> versionsByDecisionSubject = new HashMap<>();
  private long nextSubjectId = 1;
  private final Map<EndpointKey, LeaseGrant> leases = new HashMap<>();
  private final Map<EndpointKey, Long> leaseCounters = new HashMap<>();
  private long restoreEpoch = 0;
  private final Map<DeliveryId, Delivery> deliveries = new LinkedHashMap<>();
  private final Set<NewDelivery> deliveryKeys = new HashSet<>();
  private long nextDeliveryId = 1;
  private final Map<AttemptId, Attempt> attempts = new HashMap<>();
  private long nextAttemptId = 1;

  /** The object manifest by key path; String order is the UTF-16 code-unit order of the store. */
  private final TreeMap<String, ManifestRow> manifest = new TreeMap<>();

  /**
   * The retention rows by artifact text, then by (regime id text, anchor event): String order is
   * the UTF-16 code-unit order of the store, and never the order of a Regime object or of
   * insertion.
   */
  private final TreeMap<String, TreeMap<RetentionSlot, RetentionRow>> retention = new TreeMap<>();

  /**
   * The data-subject index: the references of each keyed hash, ordered by (kind code, reference
   * text); String order is the UTF-16 code-unit order and never a collation. The map is keyed by
   * the {@link SubjectHash} value, which compares its hash with {@code MessageDigest.isEqual}.
   */
  private final Map<SubjectHash, TreeSet<SubjectRef>> subjectIndex = new HashMap<>();

  private Status durability = new Status(false, 0, "in-memory ledger");

  /** The clock is the "database clock" for leases (T11). */
  public InMemoryLedgerStore(Clock clock) {
    this(clock, Flaw.NONE);
  }

  /** Test double only: an adapter with a deliberate {@link Flaw}. */
  public InMemoryLedgerStore(Clock clock, Flaw flaw) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.flaw = Objects.requireNonNull(flaw, "flaw");
  }

  /**
   * Builds the store to require envelope bodies (the {@code Config.production} of the PostgreSQL
   * adapter): a version whose body is not an envelope ({@code Envelope.isEnvelope}) is refused
   * before anything is written. The default is false. Returns this store.
   */
  public InMemoryLedgerStore requireEnvelopeBodies(boolean require) {
    this.requireEnvelopeBodies = require;
    return this;
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
    // test double only: deliberate flaw for ContractSuiteTeethTest
    StoredIngest existing = flaw == Flaw.EXACT_KEY_NOT_FIRST ? null : ingests.get(entry.key());
    if (existing != null) {
      return existing.contentHash().equals(entry.contentHash())
          ? Disposition.NOOP_SAME_VERSION_SAME_HASH
          : Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH;
    }
    // spec 10 ingest_event_version_key: the same event, source and version under another record
    // id is the same version, decided by the content hash; nothing is stored for it
    StoredIngest sameVersion =
        ingestsByEventVersion.get(
            new EventVersion(
                entry.eventIdentity(), commit.sourceId(), entry.key().sourceVersion()));
    if (sameVersion != null) {
      return sameVersion.contentHash().equals(entry.contentHash())
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
    StoredIngest stored =
        new StoredIngest(entry.key(), entry.eventIdentity(), entry.contentHash(), commit.batchId());
    ingests.put(entry.key(), stored);
    ingestsByEventVersion.put(
        new EventVersion(entry.eventIdentity(), commit.sourceId(), entry.key().sourceVersion()),
        stored);
    // test double only: deliberate flaw for ContractSuiteTeethTest
    highestVersion.put(
        sourceEvent,
        flaw == Flaw.HIGHEST_VERSION_IS_MIN && highest != null
            ? Math.min(highest, entry.key().sourceVersion())
            : entry.key().sourceVersion());
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
    KeyText.require("sourceId", sourceId);
    KeyText.require("stream", stream);
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
      // test double only: deliberate flaw for ContractSuiteTeethTest
      int limit = flaw == Flaw.PAGE_UNBOUNDED ? max : Math.min(max, PAGE_LIMIT);
      List<OutboxItem> pending = new ArrayList<>();
      for (OutboxItem item : outbox.values()) {
        if (pending.size() >= limit) {
          break;
        }
        if ((item.kind() == kind || flaw == Flaw.OUTBOX_KIND_IGNORED)
            && item.state() == OutboxState.PENDING) {
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
      // Work on staged copies; real state is touched only after every decision has been accepted.
      DecisionStage stage = new DecisionStage();
      List<Committed> committed = new ArrayList<>();
      for (NewDecision decision : commit.decisions()) {
        Committed c = stage.stage(decision);
        if (c.status() == CommitStatus.INSERTED || flaw == Flaw.WITHDRAWAL_GUARD_ON_REDELIVERY) {
          requireNoWithdrawal(decision.body());
        }
        committed.add(c);
      }
      stage.publish();
      completeOutbox(commit.completesOutbox());
      return List.copyOf(committed);
    }
  }

  /**
   * The decisions of one unit, staged: nothing is visible until {@link #publish()}, so a unit that
   * throws leaves no trace.
   */
  private final class DecisionStage {
    private final Map<ChainKey, List<DecisionRecord>> appended = new LinkedHashMap<>();
    private final Map<DecisionKey, DecisionRecord> inserted = new LinkedHashMap<>();
    private final Map<Sha256, DecisionContext> newContexts = new LinkedHashMap<>();
    private long nextId = nextDecisionId;

    Committed stage(NewDecision decision) {
      DecisionBody body = decision.body();
      // a context travels with a redelivery too: storing it is idempotent
      decision.context().ifPresent(c -> newContexts.put(c.id(), c));
      DecisionKey decisionKey = decisionKey(body);
      DecisionRecord known = inserted.get(decisionKey);
      if (known == null) {
        known = decisionsByKey.get(decisionKey);
      }
      if (known != null) {
        return new Committed(CommitStatus.NOOP_REDELIVERY, known);
      }
      ChainKey chainKey = new ChainKey(body.regime(), body.eventIdentity());
      List<DecisionRecord> chain = chains.getOrDefault(chainKey, List.of());
      List<DecisionRecord> added = appended.computeIfAbsent(chainKey, k -> new ArrayList<>());
      long headSeq = chain.size() + added.size();
      if (decision.expectedHeadSeq() != headSeq) {
        // test double only: deliberate flaw for ContractSuiteTeethTest (no rollback)
        if (flaw == Flaw.NON_ATOMIC_DECISIONS) {
          publishDecisions();
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
      return new Committed(CommitStatus.INSERTED, record);
    }

    /** The head sequence of a chain including the decisions staged so far. */
    long headSeq(ChainKey chainKey) {
      return chains.getOrDefault(chainKey, List.of()).size()
          + appended.getOrDefault(chainKey, List.of()).size();
    }

    void publish() {
      publishDecisions();
      contexts.putAll(newContexts);
    }

    private void publishDecisions() {
      appended.forEach(
          (chainKey, added) ->
              chains.computeIfAbsent(chainKey, k -> new ArrayList<>()).addAll(added));
      decisionsByKey.putAll(inserted);
      nextDecisionId = nextId;
    }
  }

  private void completeOutbox(Optional<OutboxId> completes) {
    completes.ifPresent(
        id -> {
          OutboxItem item = outbox.get(id);
          outbox.put(id, new OutboxItem(id, item.kind(), item.subjectId(), OutboxState.DONE));
        });
  }

  /**
   * Rule 8 (D-L4): an inserted decision that is not REPORT, or that omits a leg, fails closed while
   * that leg has report versions; the withdrawal transaction belongs to the delivery state machine.
   */
  private void requireNoWithdrawal(DecisionBody body) {
    // test double only: deliberate flaw for ContractSuiteTeethTest
    if (flaw == Flaw.NO_WITHDRAWAL_GUARD) {
      return;
    }
    for (Leg leg : Leg.values()) {
      // test double only: deliberate flaws for ContractSuiteTeethTest
      if (flaw == Flaw.WITHDRAWAL_GUARD_FIRST_LEG_ONLY && leg != Leg.values()[0]) {
        break;
      }
      boolean outcomeWithdraws =
          flaw != Flaw.WITHDRAWAL_GUARD_IGNORES_OUTCOME && body.outcome() != Outcome.REPORT;
      SubjectRow subject =
          subjects.get(indexKey(new SubjectKey(body.regime(), body.eventIdentity(), leg)));
      if (subject != null
          && !subject.versions.isEmpty()
          && (outcomeWithdraws || !body.legs().contains(leg))) {
        throw new WithdrawalRequiredException(
            "the "
                + leg
                + " leg of "
                + body.eventIdentity().value()
                + " has report versions and the superseding decision does not report it");
      }
    }
  }

  @Override
  public List<ReportableCommitted> commitReportable(ReportableCommit commit) {
    Objects.requireNonNull(commit, "commit");
    synchronized (lock) {
      List<NewDecision> decisions = new ArrayList<>();
      for (ReportableDecision reportable : commit.decisions()) {
        decisions.add(reportable.decision());
      }
      validate(new BatchCommit(decisions, commit.completesOutbox()));
      for (ReportableDecision reportable : commit.decisions()) {
        validateVersions(reportable);
      }
      UnitLog log = new UnitLog();
      DecisionStage stage = new DecisionStage();
      List<ReportableCommitted> results = new ArrayList<>();
      boolean anyCreated = false;
      try {
        for (ReportableDecision reportable : commit.decisions()) {
          Committed committed = stage.stage(reportable.decision());
          if (committed.status() == CommitStatus.INSERTED
              || flaw == Flaw.WITHDRAWAL_GUARD_ON_REDELIVERY) {
            requireNoWithdrawal(reportable.decision().body());
          }
          List<VersionResult> versionResults = new ArrayList<>();
          for (NewVersion version : reportable.versions()) {
            VersionResult result = applyVersion(stage, committed, version, log);
            anyCreated |= result.status() == VersionStatus.CREATED;
            versionResults.add(result);
          }
          results.add(new ReportableCommitted(committed, versionResults));
        }
      } catch (RuntimeException e) {
        // test double only: deliberate flaw for ContractSuiteTeethTest (no rollback)
        if (flaw == Flaw.NON_ATOMIC_REPORTABLE) {
          stage.publish();
        } else {
          log.rollBack();
        }
        throw e;
      }
      stage.publish();
      completeOutbox(commit.completesOutbox());
      if (anyCreated) {
        commit.sequenceBatch().ifPresent(this::ensureSequenceItem);
      }
      return List.copyOf(results);
    }
  }

  /** Rule 2: checked for the whole unit before anything is written. */
  private void validateVersions(ReportableDecision reportable) {
    DecisionBody body = reportable.decision().body();
    Set<Leg> seen = EnumSet.noneOf(Leg.class);
    for (NewVersion version : reportable.versions()) {
      SubjectKey subject = version.subject();
      // the single test of the ledger (D-K6); the message holds nothing of the body
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (requireEnvelopeBodies
          && flaw != Flaw.ENVELOPE_REQUIREMENT_IGNORED
          && !Envelope.isEnvelope(version.body())) {
        throw new IllegalArgumentException("a report body must be an envelope");
      }
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (body.outcome() != Outcome.REPORT && flaw != Flaw.VERSION_ON_ANY_OUTCOME) {
        throw new IllegalArgumentException("a version needs a REPORT decision: " + body.outcome());
      }
      if (!body.legs().contains(subject.leg())) {
        throw new IllegalArgumentException(
            "the leg " + subject.leg() + " is not a leg of the decision");
      }
      if (!seen.add(subject.leg())) {
        throw new IllegalArgumentException("two versions for the leg " + subject.leg());
      }
      if (!subject.regime().equals(body.regime())
          || !subject.eventIdentity().equals(body.eventIdentity())) {
        throw new IllegalArgumentException("the subject names another regime or event");
      }
    }
  }

  /**
   * Undo log of one reportable unit: the report state is changed in place and a unit that throws is
   * rolled back in reverse order, including the counters.
   */
  private final class UnitLog {
    private final List<Runnable> undo = new ArrayList<>();
    private final long subjectMark = nextSubjectId;
    private final long deliveryMark = nextDeliveryId;

    void onRollBack(Runnable action) {
      undo.add(action);
    }

    void rollBack() {
      for (int i = undo.size() - 1; i >= 0; i--) {
        undo.get(i).run();
      }
      nextSubjectId = subjectMark;
      nextDeliveryId = deliveryMark;
    }
  }

  private VersionResult applyVersion(
      DecisionStage stage, Committed committed, NewVersion version, UnitLog log) {
    DecisionRecord decision = committed.record();
    SubjectRow subject = subjects.get(indexKey(version.subject()));
    if (committed.status() == CommitStatus.NOOP_REDELIVERY) {
      // rule 3
      VersionRow existing =
          subject == null
              ? null
              : versionsByDecisionSubject.get(
                  new DecisionSubject(decision.decisionId(), subject.id));
      if (existing != null) {
        return new VersionResult(
            subject.id,
            subject.trn.orElseThrow().trn(),
            existing.seq(),
            VersionStatus.EXISTING,
            Optional.of(existing.delivery()));
      }
      long head =
          stage.headSeq(new ChainKey(decision.body().regime(), decision.body().eventIdentity()));
      if (decision.decisionSeq() != head) {
        throw new DecisionHeadConflictException(decision.body().eventIdentity(), head);
      }
    }
    if (subject == null) {
      SubjectRow created = new SubjectRow(new SubjectId(nextSubjectId++), version.subject());
      subjects.put(indexKey(created.key), created);
      subjectsById.put(created.id, created);
      log.onRollBack(
          () -> {
            subjects.remove(indexKey(created.key));
            subjectsById.remove(created.id);
          });
      subject = created;
    }
    Trn trn = assignTrn(subject, version, log);
    // rule 7
    for (int i = subject.versions.size() - 1; i >= 0; i--) {
      VersionRow latest = subject.versions.get(i);
      if (DEAD.contains(deliveries.get(latest.delivery()).state())) {
        continue;
      }
      if (latest.action().equals(version.action())
          && latest.logicalHash().equals(version.logicalHash())) {
        return new VersionResult(
            subject.id,
            trn.trn(),
            latest.seq(),
            VersionStatus.SUPPRESSED_UNCHANGED,
            Optional.empty());
      }
      // test double only: deliberate flaw for ContractSuiteTeethTest (looks at older versions too)
      if (flaw != Flaw.SUPPRESS_AGAINST_ANY_VERSION) {
        break;
      }
    }
    // rules 4 and 6
    long seq = subject.versions.size() + 1L;
    NewDelivery newDelivery =
        new NewDelivery(
            version.endpoint(), version.endpointEpoch(), trn.executingLei() + "|" + trn.trn(), seq);
    DeliveryId deliveryId = createDelivery(newDelivery);
    log.onRollBack(
        () -> {
          deliveries.remove(deliveryId);
          deliveryKeys.remove(newDelivery);
        });
    VersionRow row =
        new VersionRow(
            subject.id,
            seq,
            decision.decisionId(),
            version.action(),
            version.logicalHash(),
            version.body(),
            version.origin(),
            deliveryId);
    SubjectRow target = subject;
    DecisionSubject link = new DecisionSubject(decision.decisionId(), subject.id);
    target.versions.add(row);
    versionsByDecisionSubject.put(link, row);
    log.onRollBack(
        () -> {
          target.versions.remove(target.versions.size() - 1);
          versionsByDecisionSubject.remove(link);
        });
    return new VersionResult(
        subject.id, trn.trn(), seq, VersionStatus.CREATED, Optional.of(deliveryId));
  }

  /** Rule 5: the TRN of a subject is assigned once and then only read. */
  private Trn assignTrn(SubjectRow subject, NewVersion version, UnitLog log) {
    if (subject.trn.isPresent()) {
      Trn stored = subject.trn.get();
      if (!stored.executingLei().equals(version.executingLei())) {
        throw new IllegalArgumentException("the executing LEI of a subject cannot change");
      }
      if (version.trn().isPresent() && !version.trn().get().trn().equals(stored.trn())) {
        throw new IllegalArgumentException("a stored TRN cannot be replaced");
      }
      // test double only: deliberate flaw for ContractSuiteTeethTest (the TRN is assigned again)
      if (flaw == Flaw.TRN_REASSIGNED_PER_VERSION && version.trn().isPresent()) {
        Trn again =
            new Trn(stored.executingLei(), stored.trn(), version.trn().get().keyId(), clock.now());
        subject.trn = Optional.of(again);
        log.onRollBack(() -> subject.trn = Optional.of(stored));
        return again;
      }
      return stored;
    }
    TrnProposal proposal =
        version.trn().orElseThrow(() -> new IllegalArgumentException("a new subject needs a TRN"));
    TrnKey trnKey = new TrnKey(version.executingLei(), proposal.trn());
    // test double only: deliberate flaw for ContractSuiteTeethTest (no uniqueness)
    if (flaw != Flaw.TRN_NOT_UNIQUE_PER_LEI && subjectsByTrn.containsKey(trnKey)) {
      throw new TrnConflictException(version.executingLei(), proposal.trn());
    }
    Trn assigned = new Trn(version.executingLei(), proposal.trn(), proposal.keyId(), clock.now());
    subject.trn = Optional.of(assigned);
    subjectsByTrn.put(trnKey, subject);
    log.onRollBack(
        () -> {
          subject.trn = Optional.empty();
          subjectsByTrn.remove(trnKey);
        });
    return assigned;
  }

  /** Rule 9: one {@code SEQUENCE} item per batch, unique on kind and subject. */
  private void ensureSequenceItem(BatchId batch) {
    for (OutboxItem item : outbox.values()) {
      if (item.kind() == OutboxKind.SEQUENCE && item.subjectId().equals(batch.value())) {
        return;
      }
    }
    OutboxId id = new OutboxId(nextOutboxId++);
    outbox.put(id, new OutboxItem(id, OutboxKind.SEQUENCE, batch.value(), OutboxState.PENDING));
  }

  @Override
  public Optional<Subject> subject(SubjectKey key) {
    Objects.requireNonNull(key, "key");
    synchronized (lock) {
      return Optional.ofNullable(subjects.get(indexKey(key))).map(InMemoryLedgerStore::view);
    }
  }

  @Override
  public Optional<Subject> subjectByTrn(String executingLei, String trn) {
    KeyText.require("executingLei", executingLei);
    KeyText.require("trn", trn);
    synchronized (lock) {
      return Optional.ofNullable(subjectsByTrn.get(new TrnKey(executingLei, trn)))
          .map(InMemoryLedgerStore::view);
    }
  }

  private static Subject view(SubjectRow row) {
    return new Subject(
        row.id,
        row.key,
        row.trn.map(Trn::executingLei),
        row.trn.map(Trn::trn),
        row.trn.map(Trn::keyId),
        row.versions.size());
  }

  @Override
  public List<StoredVersion> versions(SubjectId subject) {
    Objects.requireNonNull(subject, "subject");
    synchronized (lock) {
      SubjectRow row = subjectsById.get(subject);
      if (row == null) {
        return List.of();
      }
      List<StoredVersion> stored = new ArrayList<>();
      for (VersionRow version : row.versions) {
        stored.add(
            new StoredVersion(
                version.subject(),
                version.seq(),
                version.decision(),
                version.action(),
                version.logicalHash(),
                version.body(),
                version.origin(),
                Optional.of(version.delivery())));
      }
      return List.copyOf(stored);
    }
  }

  /** The key under which a subject is indexed: the regime is dropped under the flaw. */
  private SubjectKey indexKey(SubjectKey key) {
    // test double only: deliberate flaw for ContractSuiteTeethTest
    return flaw == Flaw.SUBJECT_NOT_REGIME_SCOPED
        ? new SubjectKey(NO_REGIME, key.eventIdentity(), key.leg())
        : key;
  }

  private DecisionKey decisionKey(DecisionBody body) {
    // test double only: deliberate flaw for ContractSuiteTeethTest
    Sha256 context =
        flaw == Flaw.IDEMPOTENCY_KEY_IGNORES_CONTEXT ? NO_CONTEXT : body.decisionContextId();
    // test double only: deliberate flaw for ContractSuiteTeethTest
    LocalDate day =
        flaw == Flaw.IDEMPOTENCY_KEY_IGNORES_DAY
            ? NO_DAY
            : body.asAt().atOffset(ZoneOffset.UTC).toLocalDate();
    return new DecisionKey(body.regime(), body.eventIdentity(), context, body.inputDigest(), day);
  }

  private void validate(BatchCommit commit) {
    for (NewDecision decision : commit.decisions()) {
      if (decision.expectedHeadSeq() < 0) {
        throw new IllegalArgumentException("expectedHeadSeq must be >= 0");
      }
      decision
          .context()
          .ifPresent(
              c -> {
                if (!c.id().equals(decision.body().decisionContextId())) {
                  throw new IllegalArgumentException(
                      "the context supplied with a decision has id "
                          + c.id().hex()
                          + " but the body cites "
                          + decision.body().decisionContextId().hex());
                }
              });
      // what the real store refuses before it writes: an asAt outside the daily partitions and a
      // body without a canonical JSON form (a control character or a lone surrogate in a text)
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (flaw != Flaw.AS_AT_NOT_CHECKED) {
        AsAtRange.dayOf(decision.body().asAt());
      }
      Json.canonical(DecisionCodec.bodyToMap(decision.body(), true));
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
  public Optional<DecisionContext> decisionContext(Sha256 contextId) {
    Objects.requireNonNull(contextId, "contextId");
    synchronized (lock) {
      return Optional.ofNullable(contexts.get(contextId));
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
    KeyText.require("holder", holder);
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
                (delivery.key().endpoint().equals(endpoint)
                            || flaw == Flaw.TAKEOVER_TOUCHES_OTHER_ENDPOINTS)
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

  private void requirePositiveWholeMicros(Duration ttl) {
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive: " + ttl);
    }
    if (ttl.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("ttl is not whole microseconds: " + ttl);
    }
    // test double only: deliberate flaw for ContractSuiteTeethTest
    if (flaw != Flaw.LEASE_TTL_UNBOUNDED && ttl.compareTo(LeaseLedger.MAX_TTL) > 0) {
      throw new IllegalArgumentException("ttl is longer than " + LeaseLedger.MAX_TTL);
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
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (flaw == Flaw.MANIFEST_RECORDED_ON_FAILED_BEGIN) {
        recordLocked(intent.objects(), Provenance.WRITTEN, Optional.of(attemptId));
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
      try {
        // the manifest rows come last in the unit, as in the schema
        recordLocked(intent.objects(), Provenance.WRITTEN, Optional.of(attemptId));
      } catch (ManifestConflictException e) {
        // test double only: deliberate flaw for ContractSuiteTeethTest
        if (flaw != Flaw.BEGIN_SENDING_NOT_ATOMIC_WITH_MANIFEST) {
          deliveries.put(id, delivery);
          attempts.remove(attemptId);
        }
        throw e;
      }
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
  public void recordObjects(
      List<ObjectEntry> entries, Provenance provenance, Optional<AttemptId> attempt) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(attempt, "attempt");
    List<ObjectEntry> copy = List.copyOf(entries);
    Set<EvidenceKey> keys = new HashSet<>();
    for (ObjectEntry entry : copy) {
      if (!keys.add(entry.key())) {
        throw new IllegalArgumentException("two entries of one call have one key");
      }
    }
    synchronized (lock) {
      recordLocked(copy, provenance, attempt);
    }
  }

  /** All or nothing: every entry is checked before the first row is stored. */
  private void recordLocked(
      List<ObjectEntry> entries, Provenance provenance, Optional<AttemptId> attempt) {
    Instant now = clock.now();
    List<ManifestRow> toStore = new ArrayList<>();
    for (ObjectEntry entry : entries) {
      ManifestRow stored = manifest.get(entry.key().path());
      if (stored == null) {
        toStore.add(new ManifestRow(entry, provenance, attempt, now));
      } else if (!stored.entry().sha256().equals(entry.sha256())) {
        // test double only: deliberate flaw for ContractSuiteTeethTest
        if (flaw != Flaw.MANIFEST_OVERWRITES_ON_CONFLICT) {
          throw new ManifestConflictException(
              "object key is recorded with another hash: " + entry.key().path());
        }
        toStore.add(new ManifestRow(entry, provenance, attempt, now));
      }
    }
    for (ManifestRow row : toStore) {
      manifest.put(row.entry().key().path(), row);
    }
  }

  @Override
  public Optional<ManifestRow> manifestRow(EvidenceKey key) {
    Objects.requireNonNull(key, "key");
    synchronized (lock) {
      return Optional.ofNullable(manifest.get(key.path()));
    }
  }

  @Override
  public List<ManifestRow> manifestRows(String prefix, Optional<EvidenceKey> after, int limit) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    int max = Math.min(limit, MAX_ROWS_PER_CALL);
    List<ManifestRow> rows = new ArrayList<>();
    synchronized (lock) {
      // start at the larger of the prefix and the key after which to list, so that a page costs
      // its own size and not the number of keys before it; the keys of a prefix are contiguous
      boolean afterPrefix = after.isPresent() && after.get().path().compareTo(prefix) >= 0;
      NavigableMap<String, ManifestRow> candidates =
          afterPrefix
              ? manifest.tailMap(after.get().path(), false)
              : manifest.tailMap(prefix, true);
      for (ManifestRow row : candidates.values()) {
        if (rows.size() >= max || !row.entry().key().path().startsWith(prefix)) {
          break;
        }
        rows.add(row);
      }
    }
    return List.copyOf(rows);
  }

  @Override
  public void recordRetention(List<RetentionClaim> claims) {
    Objects.requireNonNull(claims, "claims");
    List<RetentionClaim> copy = List.copyOf(claims);
    Set<List<Object>> seen = new HashSet<>();
    for (RetentionClaim claim : copy) {
      if (!seen.add(List.of(claim.artifact().path(), claim.regime().id(), claim.anchor()))) {
        throw new IllegalArgumentException("two claims of one call have one artifact and anchor");
      }
    }
    synchronized (lock) {
      Instant now = clock.now();
      for (RetentionClaim claim : copy) {
        TreeMap<RetentionSlot, RetentionRow> slots =
            retention.computeIfAbsent(claim.artifact().path(), path -> new TreeMap<>());
        RetentionSlot slot = new RetentionSlot(claim.regime().id(), claim.anchor());
        RetentionRow stored = slots.get(slot);
        if (stored == null) {
          // test double only: deliberate flaw for ContractSuiteTeethTest
          boolean held =
              flaw != Flaw.RETENTION_NEW_ROW_UNHELD
                  && slots.values().stream().anyMatch(RetentionRow::legalHold);
          slots.put(slot, new RetentionRow(claim, held, now));
        } else {
          RetentionClaim kept = stored.claim();
          Optional<Instant> merged = mergedRetainUntil(kept.retainUntil(), claim.retainUntil());
          slots.put(
              slot,
              new RetentionRow(
                  new RetentionClaim(
                      kept.artifact(), kept.regime(), kept.anchor(), merged, kept.retentionClass()),
                  stored.legalHold(),
                  stored.recordedAt()));
        }
      }
    }
  }

  /**
   * The later of the two dates; a value replaces an empty one and an empty one replaces nothing.
   */
  private Optional<Instant> mergedRetainUntil(Optional<Instant> stored, Optional<Instant> given) {
    if (given.isEmpty()) {
      return stored;
    }
    // test double only: deliberate flaw for ContractSuiteTeethTest
    if (stored.isEmpty() || flaw == Flaw.RETENTION_SHORTENS) {
      return given;
    }
    return given.get().isAfter(stored.get()) ? given : stored;
  }

  @Override
  public void setRetentionHold(EvidenceKey artifact, boolean hold) {
    Objects.requireNonNull(artifact, "artifact");
    synchronized (lock) {
      TreeMap<RetentionSlot, RetentionRow> slots = retention.get(artifact.path());
      if (slots == null) {
        throw new IllegalArgumentException("the artifact has no retention row");
      }
      slots.replaceAll((slot, row) -> new RetentionRow(row.claim(), hold, row.recordedAt()));
    }
  }

  @Override
  public List<RetentionRow> retentionRows(String prefix, Optional<EvidenceKey> after, int limit) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    int maxArtifacts = Math.min(limit, MAX_RETENTION_ROWS_PER_CALL);
    List<RetentionRow> rows = new ArrayList<>();
    synchronized (lock) {
      // as in manifestRows: start at the larger of the prefix and the artifact after which to list
      boolean afterPrefix = after.isPresent() && after.get().path().compareTo(prefix) >= 0;
      NavigableMap<String, TreeMap<RetentionSlot, RetentionRow>> candidates =
          afterPrefix
              ? retention.tailMap(after.get().path(), false)
              : retention.tailMap(prefix, true);
      int artifacts = 0;
      for (var artifact : candidates.entrySet()) {
        if (artifacts >= maxArtifacts || !artifact.getKey().startsWith(prefix)) {
          break;
        }
        rows.addAll(artifact.getValue().values());
        artifacts++;
      }
    }
    return List.copyOf(rows);
  }

  @Override
  public List<RetentionRow> expiredRetention(
      Instant asOf, Optional<RetentionRow> after, int limit) {
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    if (after.isPresent() && after.get().claim().retainUntil().isEmpty()) {
      throw new IllegalArgumentException("a row without a retain-until is not in the expiry list");
    }
    int max = Math.min(limit, MAX_RETENTION_ROWS_PER_CALL);
    List<RetentionRow> due = new ArrayList<>();
    synchronized (lock) {
      for (var artifact : retention.entrySet()) {
        // test double only: deliberate flaw for ContractSuiteTeethTest
        if (flaw != Flaw.RETENTION_EXPIRED_LISTS_CONTROL
            && artifact.getKey().startsWith(EXEMPT_PREFIX)) {
          continue;
        }
        for (RetentionRow row : artifact.getValue().values()) {
          boolean isDue =
              row.claim().retainUntil().isPresent()
                  && !row.claim().retainUntil().get().isAfter(asOf)
                  // test double only: deliberate flaw for ContractSuiteTeethTest
                  && (flaw == Flaw.RETENTION_HOLD_IGNORED_IN_EXPIRED || !row.legalHold());
          if (isDue && (after.isEmpty() || EXPIRY_ORDER.compare(row, after.get()) > 0)) {
            due.add(row);
          }
        }
      }
    }
    due.sort(EXPIRY_ORDER);
    return List.copyOf(due.subList(0, Math.min(max, due.size())));
  }

  @Override
  public void indexSubjects(List<SubjectEntry> entries) {
    // List.copyOf refuses a null list or element before anything is stored
    List<SubjectEntry> copy = List.copyOf(Objects.requireNonNull(entries, "entries"));
    synchronized (lock) {
      for (SubjectEntry entry : copy) {
        subjectIndex
            .computeIfAbsent(entry.hash(), hash -> new TreeSet<>(SUBJECT_REF_ORDER))
            .add(entry.ref());
      }
    }
  }

  @Override
  public List<SubjectRef> subjectRefs(SubjectHash hash, Optional<SubjectRef> after, int limit) {
    Objects.requireNonNull(hash, "hash");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    int max = Math.min(limit, MAX_REFS_PER_CALL);
    List<SubjectRef> refs = new ArrayList<>();
    synchronized (lock) {
      TreeSet<SubjectRef> stored = subjectIndex.get(hash);
      if (stored != null && max > 0) {
        for (SubjectRef ref : after.isPresent() ? stored.tailSet(after.get(), false) : stored) {
          refs.add(ref);
          if (refs.size() == max) {
            break;
          }
        }
      }
    }
    return List.copyOf(refs);
  }

  @Override
  public int removeSubjectRefs(SubjectRef ref) {
    Objects.requireNonNull(ref, "ref");
    int removed = 0;
    synchronized (lock) {
      for (var each : subjectIndex.values()) {
        // test double only: deliberate flaw for ContractSuiteTeethTest
        if (flaw == Flaw.SUBJECT_REMOVE_REMOVES_ALL) {
          if (each.contains(ref)) {
            removed += each.size();
            each.clear();
          }
        } else if (each.remove(ref)) {
          removed++;
        }
      }
      subjectIndex.values().removeIf(TreeSet::isEmpty);
    }
    return removed;
  }

  @Override
  public Status durability() {
    synchronized (lock) {
      return durability;
    }
  }

  /** Test double only: what {@link #durability()} answers from now on. */
  public void setDurability(Status status) {
    Objects.requireNonNull(status, "status");
    synchronized (lock) {
      this.durability = status;
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
