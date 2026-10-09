package dev.regpack.engine.pipeline;

import dev.regpack.model.BatchId;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.model.json.Json;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.IngestLedger;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.OutboxLedger.OutboxItem;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.ports.regime.RegimeProvider;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The micro-batch pipeline of spec 7.2: land the raw batch (idempotent PUT), commit the ingest and
 * its outbox item in one ledger unit, acknowledge the source, then decide and persist. Every step
 * is a port call, so a crash at any call boundary leaves a state that {@link #recoverPending()} or
 * a redelivery of the batch completes without loss or duplicate.
 */
public final class MicroBatchPipeline {
  private static final String LANDING = "landing";
  private static final String RECORD_ID_FIELD = "sourceRecordId";
  private static final String VERSION_FIELD = "sourceVersion";
  private static final int SLICE = 0;

  /** One parsed NDJSON line of a landed batch. */
  private record Line(
      String sourceRecordId, long sourceVersion, CanonicalExecution execution, Sha256 contentHash) {

    IngestKey key(String sourceId) {
      return new IngestKey(sourceId, sourceRecordId, sourceVersion);
    }
  }

  private record Ref(String sourceRecordId, long sourceVersion) {}

  private final Clock clock;
  private final EvidenceStore evidence;
  private final LedgerStore ledger;
  private final RegimeProvider regime;
  private final DecisionContext context;
  private final EvidenceStore.Retention landingRetention;
  private final CostBudget budget;

  public MicroBatchPipeline(
      Clock clock,
      EvidenceStore evidence,
      LedgerStore ledger,
      RegimeProvider regime,
      DecisionContext context,
      EvidenceStore.Retention landingRetention,
      CostBudget budget) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.regime = Objects.requireNonNull(regime, "regime");
    this.context = Objects.requireNonNull(context, "context");
    this.landingRetention = Objects.requireNonNull(landingRetention, "landingRetention");
    this.budget = Objects.requireNonNull(budget, "budget");
  }

  /**
   * Takes up to {@code credits} batches from the stream and processes each in the spec 7.2 order:
   * land (idempotent PUT), commitIngest, acknowledge the source, then decide and persist. Returns
   * the batches taken.
   */
  public int pump(BatchStream stream, String sourceId, int credits) {
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(sourceId, "sourceId");
    List<Batch> batches = stream.next(credits);
    for (Batch batch : batches) {
      process(stream, sourceId, batch);
    }
    return batches.size();
  }

  /**
   * Crash recovery: decides every DECIDE_BATCH outbox item still PENDING, reading the bytes back
   * from the landing zone. Returns the number of items decided.
   */
  public int recoverPending() {
    List<OutboxItem> pending = ledger.pendingOutbox(OutboxKind.DECIDE_BATCH, Integer.MAX_VALUE);
    for (OutboxItem item : pending) {
      BatchId batchId = new BatchId(item.subjectId());
      EvidenceStore.StoredObject landed =
          evidence
              .get(landingKey(batchId))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "landed bytes missing for pending batch " + batchId.value()));
      decide(batchId, item.id(), landed.bytes());
    }
    return pending.size();
  }

  private void process(BatchStream stream, String sourceId, Batch batch) {
    byte[] bytes = batch.payload().bytes();
    Sha256 digest = batch.payload().manifest().sha256();
    if (!Sha256.of(bytes).equals(digest)) {
      throw new IllegalArgumentException(
          "manifest sha256 does not match the payload of batch seq " + batch.seq());
    }
    BatchId batchId = BatchId.of(digest, SLICE);

    evidence.put(
        new EvidenceStore.PutRequest(
            landingKey(batchId), bytes, batch.payload().mediaType(), landingRetention));

    List<Line> lines = parse(bytes);
    List<Entry> entries = new ArrayList<>(lines.size());
    for (Line line : lines) {
      entries.add(
          new Entry(line.key(sourceId), line.execution().eventIdentity(), line.contentHash()));
    }
    IngestLedger.Result ingested =
        ledger.commitIngest(
            new IngestLedger.Commit(batchId, sourceId, entries, Optional.of(batch.checkpoint())));

    stream.acknowledge(batch.checkpoint());

    if (ingested.outboxId().isPresent()) {
      OutboxId outboxId = ingested.outboxId().get();
      boolean done =
          ledger.outboxItem(outboxId).map(item -> item.state() == OutboxState.DONE).orElse(false);
      if (!done) {
        decide(batchId, outboxId, bytes);
      }
    }
  }

  /** Decides the NEW_VERSION entries of the stored ingest result and completes the outbox item. */
  private void decide(BatchId batchId, OutboxId outboxId, byte[] bytes) {
    IngestLedger.Result ingested =
        ledger
            .ingestResult(batchId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no ingest result for pending batch " + batchId.value()));
    Map<Ref, Line> byRef = new HashMap<>();
    for (Line line : parse(bytes)) {
      byRef.put(new Ref(line.sourceRecordId(), line.sourceVersion()), line);
    }

    Regime regimeId = regime.describe().regime();
    Map<EventIdentity, Long> heads = new HashMap<>();
    List<NewDecision> decisions = new ArrayList<>();
    for (EntryResult entry : ingested.entries()) {
      if (entry.disposition() != Disposition.NEW_VERSION) {
        continue;
      }
      Line line = byRef.get(new Ref(entry.key().sourceRecordId(), entry.key().sourceVersion()));
      if (line == null) {
        throw new IllegalStateException(
            "ingest entry " + entry.key() + " is missing from the landed bytes");
      }
      EventIdentity identity = line.execution().eventIdentity();
      long head =
          heads.computeIfAbsent(
              identity,
              id ->
                  ledger.currentDecision(regimeId, id).map(DecisionRecord::decisionSeq).orElse(0L));
      DecisionBody body =
          regime
              .evaluate(
                  line.execution(),
                  new EvaluationContext(context, entry.key(), clock.now(), budget))
              .decision();
      decisions.add(new NewDecision(body, head));
      heads.put(identity, head + 1);
    }
    ledger.commitDecisions(new BatchCommit(decisions, Optional.of(outboxId)));
  }

  private static List<Line> parse(byte[] bytes) {
    List<Line> lines = new ArrayList<>();
    for (Object value : Json.parseNdjson(new String(bytes, StandardCharsets.UTF_8))) {
      if (!(value instanceof Map<?, ?> raw)) {
        throw new IllegalArgumentException("each NDJSON line must be an object");
      }
      Map<String, Object> map = new LinkedHashMap<>();
      raw.forEach((k, v) -> map.put((String) k, v));
      Object recordId = map.remove(RECORD_ID_FIELD);
      Object version = map.remove(VERSION_FIELD);
      if (!(recordId instanceof String id)) {
        throw new IllegalArgumentException("sourceRecordId must be a string");
      }
      if (!(version instanceof Long v)) {
        throw new IllegalArgumentException("sourceVersion must be an integer");
      }
      CanonicalExecution execution = CanonicalExecutionCodec.fromMap(map);
      Sha256 contentHash = Sha256.ofUtf8(Json.canonical(CanonicalExecutionCodec.toMap(execution)));
      lines.add(new Line(id, v, execution, contentHash));
    }
    return List.copyOf(lines);
  }

  private static EvidenceKey landingKey(BatchId batchId) {
    String value = batchId.value();
    int colon = value.lastIndexOf(':');
    if (colon <= 0 || colon == value.length() - 1) {
      throw new IllegalArgumentException("not a landing batch id: " + value);
    }
    return EvidenceKey.of(LANDING, value.substring(0, colon), value.substring(colon + 1));
  }
}
