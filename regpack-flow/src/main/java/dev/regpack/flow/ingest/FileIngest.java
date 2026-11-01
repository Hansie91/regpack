package dev.regpack.flow.ingest;

import dev.regpack.flow.evidence.EvidenceWriter;
import dev.regpack.flow.ingest.FileSourceConnector.Reason;
import dev.regpack.flow.ingest.FileSourceConnector.Waiting;
import dev.regpack.model.BatchId;
import dev.regpack.model.IngestKey;
import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.json.Json;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.Manifest;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalSchemas;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.ledger.IngestLedger;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntSupplier;

/**
 * The ingest stage (plan T7, D-W2, spec 7.2): the files of a source become batches in the ledger.
 *
 * <p>Per batch, in this order, which is a never-cut control (SF10): the manifest hash is checked
 * and the marker must say {@code data_class} {@code SYNTHETIC}; the file is <b>landed</b> and the
 * {@code BatchAdmitted} object is <b>put</b>; the file is read and mapped; the data-quality items
 * that come from the bytes are written; one <b>{@code commitIngest}</b>; the items that depend on
 * the answer of the ledger are written; the source is <b>acknowledged</b>, and only then does the
 * connector <b>move</b> the file. Nothing is acknowledged or moved before the ledger commit, and
 * every record that is not committed (a quarantined record, a held or refused batch) has its
 * data-quality item written before that. A record that the ledger refuses (a stale version, a
 * conflict of versions, a conflict of sources) and a redelivery have their items written after the
 * commit and <b>before</b> the acknowledge, which is the last act of the batch: the answer of the
 * ledger is known by then, so nothing is gained by moving the file first, and a refused record
 * would be left without an item if the move came first and the write then failed. (The text of plan
 * task T7 puts the acknowledge before these items.) When writing one of these items fails, the call
 * throws and the file stays in the inbox; the next call takes the redelivery path below, which
 * writes the same items on the same keys from the stored result of the commit, so exactly one item
 * per record stands after the recovery. An accepted side effect: the recovered replay also writes
 * one {@code FILE_REDELIVERED} warning, as every redelivery does.
 *
 * <p>A bad value is a quarantined record, not a failed call (SF4): the mapper never throws for one.
 * A bad file is refused as a whole with its coordinates, and the next file is still read (SF9). A
 * {@link LedgerException} out of {@code commitIngest} means that the outcome is unknown (SF2): the
 * identical commit is issued again at most three more times in the call (a replay is a no-op), then
 * the exception propagates and nothing is acknowledged or moved. {@code
 * EvidenceUnavailableException}, {@code KeyUnavailableException} and an alarm of the evidence tier
 * propagate and move nothing; a landing object that holds other bytes than the file under the
 * digest key of the file is such an alarm (an {@code EvidenceIntegrityException} with a fixed text,
 * never a verdict), found when the landing put does not create the object. Any other {@link
 * RuntimeException} out of reading or mapping is a defect of this code and is not retried: the
 * batch is refused with the code {@code INGEST_DEFECT} and the report names the class of the
 * exception, never its message or its cause.
 *
 * <p>Every refusal that is final for the bytes carries the note {@code RELEASE_NEEDS_NEW_BYTES}:
 * the batch id is the digest of the bytes, so the same bytes are the same batch. The one exception
 * is a file refused before anything was landed (a marker that does not say {@code SYNTHETIC}): it
 * is committed under an id that also covers the marker (its bytes, its record count and its
 * totals), so the same bytes with a corrected marker are a new batch and are not taken for a
 * redelivery.
 *
 * <p>The same bytes again with a marker that says {@code SYNTHETIC}, whatever else the marker now
 * says (another record count, another sum), are a redelivery and a recorded no-op. The ledger is
 * asked for the stored result as soon as the batch id is known, and when it holds one nothing is
 * landed, admitted or committed, and nothing that comes from the parse or the marker is written
 * again: the items of the first delivery stand byte for byte (a second marker with other totals
 * would otherwise put other bytes under the key of an item that exists, and the file would never
 * move). What is written is the items that the stored result calls for (the same items on the same
 * keys, so a gap left by a crash is closed) and one {@code FILE_REDELIVERED} note, and then the
 * source is acknowledged. The report shows what the first delivery committed and not what the new
 * parse says: {@code PROCESSED} when records were committed; when none were, the verdict of the new
 * reading, except that a reading that would commit records the first delivery did not (a defect
 * that was fixed, a marker that was corrected) is {@code REJECTED} with nothing ingested, because
 * the batch is final for these bytes. <b>A defect that was fixed, or a marker that was corrected,
 * needs new bytes</b> ({@code RELEASE_NEEDS_NEW_BYTES}, the note of the redelivery item too). A
 * batch that was landed and admitted but never committed (a crash) has no stored result and takes
 * the normal path.
 *
 * <p>The stage sees the evidence tier through the {@link EvidenceWriter} only. Messages and items
 * carry fixed texts, codes, rule ids and coordinates, never a value of a record. Not thread-safe
 * across calls that share a source; the stage itself keeps no state.
 */
public final class FileIngest {
  /** A record that mapped, with its key in the ledger and the hash of its content. */
  public record Accepted(MappedRecord record, IngestKey key, Sha256 contentHash) {
    public Accepted {
      Objects.requireNonNull(record, "record");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(contentHash, "contentHash");
    }

    @Override
    public String toString() {
      return "Accepted[sourceVersion=" + key.sourceVersion() + "]";
    }
  }

  /** A file that cannot be read as a table of this mapping: a code and where. */
  public record Refusal(String code, int line, String column) {
    public Refusal {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(column, "column");
    }
  }

  /** A row that is not handed to the ledger: where, and why. */
  public record Quarantine(int line, String column, String code) {
    public Quarantine {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(code, "code");
    }
  }

  /**
   * What reading the landed bytes found, with no ledger involved: the same bytes always give the
   * same result.
   *
   * @param refusal why the file as a whole cannot be read, when it cannot
   * @param rows the number of data rows
   * @param sumQuantity the sum of the quantity cell of every row whose quantity parses, whatever
   *     else is wrong with the row
   * @param accepted the rows that mapped, in file order, the later row of a repeated key left out
   * @param lines the 1-based line of each accepted row
   * @param quarantined the rows that did not map or repeat a key, in file order
   */
  public record ParsedBatch(
      Optional<Refusal> refusal,
      int rows,
      BigDecimal sumQuantity,
      List<Accepted> accepted,
      Map<IngestKey, Integer> lines,
      List<Quarantine> quarantined) {
    public ParsedBatch {
      Objects.requireNonNull(refusal, "refusal");
      Objects.requireNonNull(sumQuantity, "sumQuantity");
      accepted = List.copyOf(accepted);
      lines = Collections.unmodifiableMap(new LinkedHashMap<>(lines));
      quarantined = List.copyOf(quarantined);
    }

    /** The line of an accepted row. */
    public int lineOf(IngestKey key) {
      Integer line = lines.get(key);
      if (line == null) {
        throw new IllegalArgumentException("the key is not of an accepted row");
      }
      return line;
    }

    @Override
    public String toString() {
      return "ParsedBatch[rows="
          + rows
          + ", accepted="
          + accepted.size()
          + ", quarantined="
          + quarantined.size()
          + ", refused="
          + refusal.isPresent()
          + "]";
    }
  }

  /**
   * The outcome of one call.
   *
   * @param batches the batches taken, in file order
   * @param defects the class names of the exceptions that were taken for a defect of this code
   */
  public record IngestReport(List<BatchReport> batches, List<String> defects) {
    public IngestReport {
      batches = List.copyOf(batches);
      defects = List.copyOf(defects);
    }
  }

  /** Maps one row; a seam so that a test can make the mapper fail. */
  @FunctionalInterface
  interface RowMapper {
    Mapper.Result map(int line, List<String> header, List<String> cells);
  }

  /** Deliberate flaws of the stage for {@code FileIngestMutantsTest}; production uses NONE. */
  enum Flaw {
    NONE,
    /** The source is acknowledged before the ledger commit. */
    ACKNOWLEDGE_BEFORE_COMMIT,
    /** The verdict that fixes the folder of the move is set after the acknowledge. */
    VERDICT_AFTER_ACKNOWLEDGE,
    /** The {@code BatchAdmitted} object is not put. */
    SKIP_ADMITTED_PUT,
    /** A repeated key in one file is handed to the ledger. */
    NO_DUPLICATE_CHECK,
    /** A defect of reading or mapping propagates instead of refusing the batch. */
    NO_DEFECT_HANDLER,
    /**
     * The ledger is not asked for a stored result before the landing: the same bytes again are
     * landed, admitted and read once more, and write the items of the parse and the marker again.
     */
    SKIP_REDELIVERY_CHECK,
    /**
     * A redelivery takes its verdict, and so the folder of the move, and its counts from the new
     * parse and not from what the first delivery committed.
     */
    VERDICT_FROM_NEW_PARSE,
    /**
     * The source is acknowledged right after the ledger commit and before the items that the answer
     * of the ledger calls for (a stale version, a conflict, a redelivery) are written.
     */
    ACKNOWLEDGE_BEFORE_DISPOSITIONS
  }

  /** The file being ingested: where it came from and what it is called. */
  private record Delivery(
      BatchStream stream, FileSourceConnector connector, Batch batch, String file) {}

  /** The value the marker must give for {@code data_class}. */
  static final String SYNTHETIC = "SYNTHETIC";

  private static final int SLICE = 0;
  private static final int MAX_ROWS = 10_000;
  private static final int MAX_COLUMNS = 64;
  private static final int MAX_REISSUES = 3;
  private static final String LANDING = "landing";
  private static final String LAYER = "INGEST";
  private static final String BLOCKER = "BLOCKER";
  private static final String WARNING = "WARNING";
  private static final String INFO = "INFO";
  private static final String FILE_PREFIX = "file:";
  private static final String LANDING_MISMATCH =
      "the landing object does not hold the bytes of the file";

  private final Clock clock;
  private final EvidenceWriter writer;
  private final LedgerStore ledger;
  private final MappingPack mapping;
  private final Sha256 packDigest;
  private final String sourceId;
  private final DqStore dq;
  private final Duration completionDeadline;
  private final Mapper mapper;
  private final RowMapper rowMapper;
  private final Flaw flaw;
  private final MappingPack.Column sumColumn;

  public FileIngest(
      Clock clock,
      EvidenceWriter writer,
      LedgerStore ledger,
      MappingPack mapping,
      Sha256 packDigest,
      String sourceId,
      DqStore dq,
      Duration completionDeadline) {
    this(
        clock,
        writer,
        ledger,
        mapping,
        packDigest,
        sourceId,
        dq,
        completionDeadline,
        new Mapper(mapping)::map,
        Flaw.NONE);
  }

  FileIngest(
      Clock clock,
      EvidenceWriter writer,
      LedgerStore ledger,
      MappingPack mapping,
      Sha256 packDigest,
      String sourceId,
      DqStore dq,
      Duration completionDeadline,
      RowMapper rowMapper,
      Flaw flaw) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.mapping = Objects.requireNonNull(mapping, "mapping");
    this.packDigest = Objects.requireNonNull(packDigest, "packDigest");
    Objects.requireNonNull(sourceId, "sourceId");
    if (sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    this.sourceId = KeyText.require("sourceId", sourceId);
    this.dq = Objects.requireNonNull(dq, "dq");
    Objects.requireNonNull(completionDeadline, "completionDeadline");
    if (completionDeadline.isNegative() || completionDeadline.isZero()) {
      throw new IllegalArgumentException("completionDeadline must be positive");
    }
    this.completionDeadline = completionDeadline;
    this.mapper = new Mapper(mapping);
    this.rowMapper = Objects.requireNonNull(rowMapper, "rowMapper");
    this.flaw = Objects.requireNonNull(flaw, "flaw");
    String sumName = mapping.controlTotals().sumColumn();
    this.sumColumn =
        mapping.columns().stream()
            .filter(c -> c.column().equals(Optional.of(sumName)))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("the mapping names no sum column"));
  }

  // ------------------------------------------------------------------ the call

  /**
   * Takes up to {@code credits} files from the stream and ingests each, in order. The files that
   * wait (no marker, an invalid marker, over the limit) are reported as items: an oversized file at
   * once, any other once it has waited past the completion deadline.
   *
   * <p>One stream per call. The acknowledge of a stream is cumulative (it moves every file the
   * stream delivered up to the checkpoint), so a stream that is used again after a failure could
   * move a file that was read from it and never committed. A call that ends in an exception
   * therefore closes the stream before it rethrows, and a later call with that stream fails with
   * {@code IllegalStateException} ("Stream is closed") at its first read. After an exception the
   * caller opens a new stream with no checkpoint: the files that were not moved are still in the
   * inbox.
   *
   * @param stream the stream of {@code connector} (the caller opens it with no checkpoint: the
   *     inbox is the cursor); closed by this call when it ends in an exception
   * @param connector the connector the stream came from; it is told the verdict of each file
   */
  public IngestReport ingest(BatchStream stream, FileSourceConnector connector, int credits) {
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(connector, "connector");
    try {
      List<Batch> batches = stream.next(credits);
      reportWaiting(connector);
      List<BatchReport> reports = new ArrayList<>();
      List<String> defects = new ArrayList<>();
      for (Batch batch : batches) {
        reports.add(process(stream, connector, batch, defects));
      }
      return new IngestReport(reports, defects);
    } catch (RuntimeException failure) {
      closeAfterFailure(stream, failure);
      throw failure;
    }
  }

  private static void closeAfterFailure(BatchStream stream, RuntimeException failure) {
    try {
      stream.close();
    } catch (RuntimeException closing) {
      failure.addSuppressed(closing);
    }
  }

  private void reportWaiting(FileSourceConnector connector) {
    for (Waiting waiting : connector.waiting()) {
      String scope = Sha256.ofUtf8(waiting.file()).hex();
      if (waiting.reason() == Reason.OVERSIZED) {
        // never read and never landed; it stays in the inbox until somebody replaces it
        dq.write(
            new Dq(
                Dq.Kind.BATCH_REJECTED,
                scope,
                0,
                "",
                "OVERSIZED",
                Dq.RELEASE_NEEDS_NEW_BYTES,
                LAYER,
                BLOCKER));
      } else if (clock.now().isAfter(waiting.firstSeen().plus(completionDeadline))) {
        dq.write(
            new Dq(
                Dq.Kind.COMPLETION_PENDING,
                scope,
                0,
                "",
                waiting.reason().name(),
                "",
                LAYER,
                WARNING));
      }
    }
  }

  // ------------------------------------------------------------------ one batch

  private BatchReport process(
      BatchStream stream, FileSourceConnector connector, Batch batch, List<String> defects) {
    Delivery delivery = new Delivery(stream, connector, batch, fileName(batch));
    byte[] bytes = batch.payload().bytes();
    Manifest manifest = batch.payload().manifest();
    Sha256 digest = Sha256.of(bytes);
    if (!digest.equals(manifest.sha256())) {
      return refuseBeforeLanding(delivery, digest, "MANIFEST_MISMATCH");
    }
    if (!SYNTHETIC.equals(manifest.controlTotals().get(FileMarker.DATA_CLASS))) {
      return refuseBeforeLanding(delivery, digest, "NOT_SYNTHETIC");
    }

    BatchId batchId = BatchId.of(digest, SLICE);
    String scope = digest.hex();
    if (flaw != Flaw.SKIP_REDELIVERY_CHECK) {
      // the same bytes are the same batch: when the ledger holds a result, this is a redelivery and
      // the first delivery wrote everything the bytes give (a crash after the landing and the
      // admission, before the commit, has no result and takes the normal path)
      Optional<IngestLedger.Result> stored = ledger.ingestResult(batchId);
      if (stored.isPresent()) {
        return redeliver(delivery, batchId, scope, stored.get(), defects);
      }
    }
    EvidenceKey landing = landingKey(digest);
    EvidenceStore.PutOutcome landed =
        writer.put(
            landing,
            bytes,
            batch.payload().mediaType(),
            LANDING,
            ManifestLedger.ObjectClass.LANDING_BATCH);
    if (landed != EvidenceStore.PutOutcome.CREATED) {
      // the writer keeps the stored bytes and says "identical" whatever they are (D-W11): the
      // landing object is the evidence for the whole batch, so it is compared here
      requireLandedBytes(landing, digest);
    }
    admit(batchId);

    Analysis analysis = analyze(bytes, manifest, scope, defects);
    return conclude(
        delivery,
        batchId,
        scope,
        analysis.verdict(),
        analysis.items(),
        analysis.toCommit(),
        analysis.ingested(),
        analysis.quarantined());
  }

  /**
   * The landing object that the key holds must be the file: its SHA-256 is the digest in the key.
   * Bit rot or tampering would otherwise be committed as a good batch whose evidence is other
   * bytes. An alarm of the evidence tier, with a fixed text (no key, no hash, no value), not a
   * verdict: the file stays in the inbox.
   *
   * @throws EvidenceIntegrityException the key holds no object, or other bytes than the file
   */
  private void requireLandedBytes(EvidenceKey landing, Sha256 digest) {
    boolean same =
        writer.get(landing).map(stored -> Sha256.of(stored.bytes()).equals(digest)).orElse(false);
    if (!same) {
      throw new EvidenceIntegrityException(LANDING_MISMATCH);
    }
  }

  /**
   * What the landed bytes and the marker say about a batch, with no ledger and no write.
   *
   * @param verdict what would become of the file
   * @param items the data-quality items that come from the bytes and the marker
   * @param read the bytes as read, when they could be read as a table of this mapping
   * @param toCommit the rows the ledger would be given; empty commits an empty batch
   * @param ingested the rows that would be handed to the ledger
   * @param quarantined the rows that would not be (all rows of a batch that is held back)
   */
  private record Analysis(
      Verdict verdict,
      List<Dq> items,
      Optional<ParsedBatch> read,
      Optional<ParsedBatch> toCommit,
      int ingested,
      int quarantined) {}

  private Analysis analyze(byte[] bytes, Manifest manifest, String scope, List<String> defects) {
    ParsedBatch parsed;
    try {
      parsed = parseLanded(bytes);
    } catch (RuntimeException e) {
      if (flaw == Flaw.NO_DEFECT_HANDLER) {
        throw e;
      }
      defects.add(e.getClass().getName());
      return new Analysis(
          Verdict.REJECTED,
          List.of(rejectedItem(scope, 0, "", "INGEST_DEFECT")),
          Optional.empty(),
          Optional.empty(),
          0,
          0);
    }

    if (parsed.refusal().isPresent()) {
      Refusal refusal = parsed.refusal().get();
      return new Analysis(
          Verdict.REJECTED,
          List.of(rejectedItem(scope, refusal.line(), refusal.column(), refusal.code())),
          Optional.empty(),
          Optional.empty(),
          0,
          0);
    }

    Optional<String> mismatch = controlTotalMismatch(parsed, manifest);
    List<Dq> items = new ArrayList<>();
    if (mismatch.isPresent()) {
      items.add(
          new Dq(
              Dq.Kind.BATCH_QUARANTINED,
              scope,
              0,
              mismatch.get(),
              "CONTROL_TOTAL",
              Dq.RELEASE_NEEDS_NEW_BYTES,
              LAYER,
              BLOCKER));
      items.add(summary(scope, manifest.recordCount(), 0, parsed.rows()));
      return new Analysis(
          Verdict.QUARANTINED, items, Optional.of(parsed), Optional.empty(), 0, parsed.rows());
    }
    for (Quarantine q : parsed.quarantined()) {
      items.add(
          new Dq(
              Dq.Kind.RECORD_QUARANTINED,
              scope,
              q.line(),
              q.column(),
              q.code(),
              "",
              LAYER,
              BLOCKER));
    }
    int ingested = parsed.accepted().size();
    int quarantined = parsed.rows() - ingested;
    items.add(summary(scope, manifest.recordCount(), ingested, quarantined));
    return new Analysis(
        Verdict.PROCESSED, items, Optional.of(parsed), Optional.of(parsed), ingested, quarantined);
  }

  /** The verdict and counts a redelivery is reported with. */
  private record Outcome(Verdict verdict, int ingested, int quarantined) {}

  /**
   * The same bytes again (plan D-W2: a redelivery is a recorded no-op). The bytes are read once
   * more, but only to learn the verdict and the lines of the entries of the stored result; nothing
   * that comes from the parse or the marker is written. The items that the stored result calls for
   * are written (the same items on the same keys as the first delivery, so the ones a crash left
   * unwritten are closed and the others are not written twice), then the note of the redelivery,
   * and only then is the source acknowledged.
   */
  private BatchReport redeliver(
      Delivery delivery,
      BatchId batchId,
      String scope,
      IngestLedger.Result stored,
      List<String> defects) {
    Batch batch = delivery.batch();
    Manifest manifest = batch.payload().manifest();
    Analysis again = analyze(batch.payload().bytes(), manifest, scope, defects);
    Outcome outcome = redeliveryOutcome(stored, again);
    for (IngestLedger.EntryResult entry : stored.entries()) {
      dispositionItem(
          entry,
          scope,
          () -> again.read().map(p -> p.lines().getOrDefault(entry.key(), 0)).orElse(0));
    }
    dq.write(redeliveredItem(delivery.file()));
    acknowledge(delivery, outcome.verdict());
    return new BatchReport(
        batchId,
        delivery.file(),
        outcome.verdict(),
        true,
        manifest.recordCount(),
        outcome.ingested(),
        outcome.quarantined(),
        List.of(),
        stored.outboxId());
  }

  /**
   * What the first delivery committed decides, not the new reading: when it committed records they
   * are in the ledger whatever this delivery's marker says (PROCESSED, the counts from the stored
   * entries). When it committed none, the new reading gives the verdict unless it would commit
   * records: those were never in the ledger and cannot be (the batch is final for these bytes), so
   * the file is REJECTED with nothing ingested. A batch that was held back stays held back when the
   * new reading holds it back too.
   */
  private Outcome redeliveryOutcome(IngestLedger.Result stored, Analysis again) {
    if (flaw == Flaw.VERDICT_FROM_NEW_PARSE) {
      return new Outcome(again.verdict(), again.ingested(), again.quarantined());
    }
    int rows = again.ingested() + again.quarantined();
    if (!stored.entries().isEmpty()) {
      int ingested = stored.entries().size();
      return new Outcome(Verdict.PROCESSED, ingested, Math.max(0, rows - ingested));
    }
    boolean wouldCommit = again.toCommit().map(p -> !p.accepted().isEmpty()).orElse(false);
    if (wouldCommit) {
      return new Outcome(Verdict.REJECTED, 0, 0);
    }
    return new Outcome(again.verdict(), again.ingested(), again.quarantined());
  }

  /** The note that a file came again: about the file name, never about a value. */
  private static Dq redeliveredItem(String file) {
    return new Dq(
        Dq.Kind.FILE_REDELIVERED,
        Sha256.ofUtf8(file).hex(),
        0,
        "",
        "REDELIVERED",
        Dq.RELEASE_NEEDS_NEW_BYTES,
        LAYER,
        WARNING);
  }

  /**
   * A file refused before anything is landed. Its id covers the marker as well as the bytes, so the
   * same bytes with a corrected marker are a new batch (a rejection is final for the bytes, but
   * here the cause is the marker).
   */
  private BatchReport refuseBeforeLanding(Delivery delivery, Sha256 digest, String code) {
    Manifest manifest = delivery.batch().payload().manifest();
    Map<String, Object> claim = new TreeMap<>();
    claim.put("code", code);
    claim.put("sha256", digest.hex());
    claim.put("records", (long) manifest.recordCount());
    claim.put("totals", new TreeMap<>(manifest.controlTotals()));
    BatchId id = BatchId.of(Sha256.ofUtf8(Json.canonical(claim)), SLICE);
    return conclude(
        delivery,
        id,
        digest.hex(),
        Verdict.REJECTED,
        List.of(rejectedItem(digest.hex(), 0, "", code)),
        Optional.empty(),
        0,
        0);
  }

  private static Dq rejectedItem(String scope, int line, String column, String code) {
    return new Dq(
        Dq.Kind.BATCH_REJECTED,
        scope,
        line,
        column,
        code,
        Dq.RELEASE_NEEDS_NEW_BYTES,
        LAYER,
        BLOCKER);
  }

  private static Dq summary(String scope, int declared, int ingested, int quarantined) {
    // the label says what the count is not: no expected count exists for this source (spec 7.7)
    return new Dq(
        Dq.Kind.BATCH_SUMMARY,
        scope,
        0,
        "",
        "UNPROVEN_COMPLETENESS",
        "control_total_records="
            + declared
            + ";ingested="
            + ingested
            + ";quarantined="
            + quarantined,
        LAYER,
        INFO);
  }

  /**
   * The rest of the order: the items that come from the bytes, the commit, the items that come from
   * the answer of the ledger, and last the acknowledge (which moves the file).
   *
   * @param parsed the accepted rows to commit; empty commits an empty batch with the checkpoint
   */
  private BatchReport conclude(
      Delivery delivery,
      BatchId batchId,
      String scope,
      Verdict verdict,
      List<Dq> items,
      Optional<ParsedBatch> parsed,
      int ingested,
      int quarantined) {
    for (Dq item : items) {
      dq.write(item);
    }
    List<Accepted> toCommit = parsed.map(ParsedBatch::accepted).orElse(List.of());
    List<IngestLedger.Entry> entries = new ArrayList<>();
    Map<IngestKey, Accepted> byKey = new HashMap<>();
    for (Accepted accepted : toCommit) {
      entries.add(
          new IngestLedger.Entry(
              accepted.key(),
              accepted.record().execution().eventIdentity(),
              accepted.contentHash()));
      byKey.put(accepted.key(), accepted);
    }
    IngestLedger.Commit commit =
        new IngestLedger.Commit(
            batchId, sourceId, entries, Optional.of(delivery.batch().checkpoint()));

    boolean acknowledged = false;
    if (flaw == Flaw.ACKNOWLEDGE_BEFORE_COMMIT) {
      acknowledge(delivery, verdict);
      acknowledged = true;
    }
    IngestLedger.Result result = commit(commit);
    if (flaw == Flaw.ACKNOWLEDGE_BEFORE_DISPOSITIONS) {
      acknowledge(delivery, verdict);
      acknowledged = true;
    }

    // the answer of the ledger is known: the items it calls for are written before the source is
    // acknowledged, so that a failure here leaves the file in the inbox and the redelivery path
    // writes the same items from the stored result
    // (a replay has no new versions: what still needs deciding is the state of the outbox item,
    // D-W2, not a list in the report)
    List<Accepted> newVersions = new ArrayList<>();
    for (IngestLedger.EntryResult entry : result.entries()) {
      Accepted accepted = byKey.get(entry.key());
      if (entry.disposition() == Disposition.NEW_VERSION
          && accepted != null
          && !result.redelivery()) {
        newVersions.add(accepted);
      }
      dispositionItem(entry, scope, () -> parsed.map(p -> p.lineOf(entry.key())).orElse(0));
    }
    if (result.redelivery()) {
      dq.write(redeliveredItem(delivery.file()));
    }
    if (!acknowledged) {
      acknowledge(delivery, verdict);
    }
    return new BatchReport(
        batchId,
        delivery.file(),
        verdict,
        result.redelivery(),
        delivery.batch().payload().manifest().recordCount(),
        ingested,
        quarantined,
        newVersions,
        result.outboxId());
  }

  /**
   * Writes the item that the ledger's answer for one entry calls for, if it calls for one: a stale
   * version, a conflict of versions, a conflict of sources. A new version and a repeat of a stored
   * version need none. The item is keyed by the line, which is asked only when there is an item.
   */
  private void dispositionItem(
      IngestLedger.EntryResult entry, String scope, IntSupplier lineOfTheEntry) {
    switch (entry.disposition()) {
      case NEW_VERSION, NOOP_SAME_VERSION_SAME_HASH -> {}
      case STALE ->
          dq.write(
              new Dq(
                  Dq.Kind.STALE_VERSION,
                  scope,
                  lineOfTheEntry.getAsInt(),
                  "",
                  "STALE",
                  "",
                  LAYER,
                  WARNING));
      case CONFLICT_SAME_VERSION_DIFFERENT_HASH ->
          dq.write(
              new Dq(
                  Dq.Kind.VERSION_CONFLICT,
                  scope,
                  lineOfTheEntry.getAsInt(),
                  "",
                  "CONFLICT_SAME_VERSION",
                  "",
                  LAYER,
                  WARNING));
      case SOURCE_CONFLICT ->
          dq.write(
              new Dq(
                  Dq.Kind.SOURCE_CONFLICT,
                  scope,
                  lineOfTheEntry.getAsInt(),
                  "",
                  "SOURCE_CONFLICT",
                  "",
                  LAYER,
                  WARNING));
    }
  }

  /** Tells the connector the verdict (the folder of the move), then acknowledges the source. */
  private void acknowledge(Delivery delivery, Verdict verdict) {
    Batch batch = delivery.batch();
    if (flaw == Flaw.VERDICT_AFTER_ACKNOWLEDGE) {
      delivery.stream().acknowledge(batch.checkpoint());
      delivery.connector().verdict(batch.seq(), verdict);
      return;
    }
    delivery.connector().verdict(batch.seq(), verdict);
    delivery.stream().acknowledge(batch.checkpoint());
  }

  /**
   * One {@code commitIngest}. A {@link LedgerException} means that the outcome is unknown: the
   * identical commit is issued again at most {@value #MAX_REISSUES} more times (a replay is a
   * no-op), then the exception propagates.
   */
  private IngestLedger.Result commit(IngestLedger.Commit commit) {
    for (int attempt = 0; attempt < MAX_REISSUES; attempt++) {
      try {
        return ledger.commitIngest(commit);
      } catch (LedgerException e) {
        // the outcome is unknown: the identical commit is issued again
      }
    }
    return ledger.commitIngest(commit); // the last attempt: its exception propagates
  }

  // ------------------------------------------------------------------ BatchAdmitted

  /**
   * Puts the {@code BatchAdmitted} object of the batch (control scope, epoch 0, ordinal 1) and
   * records the stored object in the manifest. The key is the batch id, the digest of the bytes.
   * When the key holds an object, that object stands as the admission of the batch and nothing is
   * put (the clock instant of the stored object stays), whatever its payload says: it is
   * write-once, and a payload that differs (the pack or the mapping changed between a crash and the
   * bytes coming again) is no reason to refuse the batch, because the decide stage stamps the pack
   * it runs with. The manifest row and the retention claim are completed from the stored object,
   * never from the newly built bytes. A stored object is put again before its row is completed
   * ({@code recordStored}), so that a put that stopped between the copies of a store that keeps
   * several is finished before the batch is committed and the file moved.
   */
  private void admit(BatchId batchId) {
    if (flaw == Flaw.SKIP_ADMITTED_PUT) {
      return;
    }
    JournalKey.ClassA key =
        new JournalKey.ClassA(
            JournalScope.control(), 0, JournalType.BATCH_ADMITTED, batchId.value(), 1);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("batch_id", batchId.value());
    payload.put("pack_digest", packDigest.hex());
    payload.put("context_digests", List.of(mapping.digest().hex()));
    JournalObject planned =
        new JournalObject(key, JournalType.BATCH_ADMITTED, JournalSchemas.V1, clock.now(), payload);
    if (writer.journal().read(key).isEmpty()) {
      // returns only after every copy of the object exists
      writer.journal().write(planned);
      writer.recordWritten(key.evidenceKey(), ManifestLedger.ObjectClass.JOURNAL_A);
    } else {
      // found, perhaps after a put that stopped between two copies: put again, then record
      writer.recordStored(key.evidenceKey(), ManifestLedger.ObjectClass.JOURNAL_A);
    }
  }

  // ------------------------------------------------------------------ reading the landed bytes

  /**
   * Reads landed bytes into records, with no ledger and no clock: the same bytes and mapping always
   * give the same result, so a recovery can rebuild the records of a batch from its landing object.
   *
   * <p>A file that is no CSV of this mapping is a {@link Refusal}. Otherwise every row is mapped; a
   * row that does not map, or whose ingest key (source, record id, version) occurred earlier in the
   * file, is quarantined (the later row only). The row count and the quantity sum are computed in a
   * separate pass over the rows, so that they do not depend on why a row was quarantined.
   *
   * @throws RuntimeException a defect of the reader or the mapper (not a bad value)
   */
  public ParsedBatch parseLanded(byte[] landed) {
    Objects.requireNonNull(landed, "landed");
    CsvReader.Table table;
    try {
      table = CsvReader.read(landed, KeyText.MAX_CHARS, MAX_ROWS, MAX_COLUMNS);
    } catch (CsvException e) {
      String column = e.column() > 0 ? Integer.toString(e.column()) : "";
      return refused(new Refusal(e.code(), e.line(), column));
    }
    List<String> missing = mapper.missingColumns(table.header());
    if (!missing.isEmpty()) {
      return refused(new Refusal("MISSING_COLUMN", 1, missing.getFirst()));
    }

    // the control totals, over the rows as they are
    int sumIndex = table.header().indexOf(mapping.controlTotals().sumColumn());
    BigDecimal sum = BigDecimal.ZERO;
    for (CsvReader.Row row : table.rows()) {
      String cell = row.cells().get(sumIndex);
      if (!cell.isEmpty() && Mapper.convert(sumColumn, cell) instanceof Mapper.Ok ok) {
        sum = sum.add((BigDecimal) ok.value());
      }
    }

    List<Accepted> accepted = new ArrayList<>();
    Map<IngestKey, Integer> lines = new LinkedHashMap<>();
    List<Quarantine> quarantined = new ArrayList<>();
    Set<IngestKey> seen = new HashSet<>();
    for (CsvReader.Row row : table.rows()) {
      switch (rowMapper.map(row.line(), table.header(), row.cells())) {
        case Mapper.Mapped mapped -> {
          MappedRecord record = mapped.record();
          IngestKey key = new IngestKey(sourceId, record.sourceRecordId(), record.sourceVersion());
          if (flaw != Flaw.NO_DUPLICATE_CHECK && !seen.add(key)) {
            quarantined.add(
                new Quarantine(row.line(), mapping.sourceRecordIdColumn(), "DUPLICATE_IN_FILE"));
          } else {
            accepted.add(new Accepted(record, key, contentHash(record)));
            lines.put(key, row.line());
          }
        }
        case Mapper.Quarantined q ->
            quarantined.add(new Quarantine(q.line(), q.column(), q.code()));
      }
    }
    return new ParsedBatch(
        Optional.empty(), table.rows().size(), sum, accepted, lines, quarantined);
  }

  private static ParsedBatch refused(Refusal refusal) {
    return new ParsedBatch(
        Optional.of(refusal), 0, BigDecimal.ZERO, List.of(), Map.of(), List.of());
  }

  /**
   * The hash of what the ledger compares for one version: the canonical event and the attributes.
   */
  private static Sha256 contentHash(MappedRecord record) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("execution", CanonicalExecutionCodec.toMap(record.execution()));
    content.put("attributes", record.attributes());
    return Sha256.ofUtf8(Json.canonical(content));
  }

  /** The key of the control total that does not hold, when one does not. */
  private Optional<String> controlTotalMismatch(ParsedBatch parsed, Manifest manifest) {
    MappingPack.ControlTotals totals = mapping.controlTotals();
    if (parsed.rows() != manifest.recordCount()) {
      return Optional.of(totals.recordsKey());
    }
    String declared = manifest.controlTotals().get(totals.sumKey());
    if (declared != null) {
      try {
        if (new BigDecimal(declared).compareTo(parsed.sumQuantity()) != 0) {
          return Optional.of(totals.sumKey());
        }
      } catch (NumberFormatException e) {
        return Optional.of(totals.sumKey());
      }
    }
    return Optional.empty();
  }

  // ------------------------------------------------------------------ names

  private static EvidenceKey landingKey(Sha256 digest) {
    return EvidenceKey.of(LANDING, digest.hex(), Integer.toString(SLICE));
  }

  private static String fileName(Batch batch) {
    String coordinates = batch.sourceCoordinates();
    return coordinates.startsWith(FILE_PREFIX)
        ? coordinates.substring(FILE_PREFIX.length())
        : coordinates;
  }
}
