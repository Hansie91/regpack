package dev.regpack.postgres.capacity;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.PathStep;
import dev.regpack.ports.ledger.DecisionLedger;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.IngestLedger;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ReportLedger;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import dev.regpack.postgres.capacity.ProbeSummary.Batch;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The load of the capacity probe (T21): the SP-L shape driven through the production adapter.
 * Micro-batches of N records arrive open-loop at a fixed rate ({@link OpenLoopSchedule}); each
 * writer thread has a source id of its own (the ingest commits of one source are serialised by the
 * adapter, so at least four sources are needed to use four writers) and takes the batches {@code
 * writer, writer + writers, ...} in order, waiting until a batch is due and starting it at once
 * when it is late. A batch is one {@code commitIngest} and one {@code commitReportable} (half of
 * its decisions REPORT with one version each, the other half DO_NOT_REPORT).
 *
 * <p>The keys come from {@link SplitMix64} seeded with the global record number, which is a
 * bijection, so no two records of a run share a key and the index inserts are random, as in SP-L.
 * The decision body is the mean-path body of the schema document (21 evaluated steps, 3 rule ids, 2
 * citations), so the row size is comparable to the estimate there. Nothing here reads the clock for
 * a decision: the only time is the monotonic clock, for the schedule and the timings.
 *
 * <p>A writer starts no batch later than one second after the end of the slice; a batch in flight
 * is allowed to finish. The first error stops every writer and is returned with the batches done so
 * far.
 */
final class BurstLoad {
  /** The load: {@code runTag} separates the batch ids of two runs. */
  record Settings(int batchSize, int writers, long ratePerSecond, long seconds, String runTag) {
    Settings {
      Objects.requireNonNull(runTag, "runTag");
    }
  }

  /**
   * What a run produced.
   *
   * @param observations one entry per scheduled batch, in batch order; a batch that was never
   *     started has {@link Batch#started()} false
   * @param coveredNanos the time the slice covers
   * @param scheduledBatches the batches the schedule offered
   * @param failure the first error of a writer, if any
   */
  record Outcome(
      List<Batch> observations,
      long coveredNanos,
      long scheduledBatches,
      Optional<Throwable> failure) {
    Outcome {
      observations = List.copyOf(observations);
      Objects.requireNonNull(failure, "failure");
    }
  }

  /** The evaluated steps of every decision body (the mean path of the schema document, 7.2). */
  static final int PATH_STEPS = 21;

  private static final long GRACE_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final long MAX_SLEEP_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
  private static final int REPORT_BODY_BYTES = 400;

  private static final Regime REGIME = new Regime("mifir.eu.rts22");
  private static final String EXECUTING_LEI = "CAPACITYPROBE0000001";
  private static final EndpointKey ENDPOINT =
      new EndpointKey("SIMULATOR", "ARM", "LEI-CAPACITY", "NCA-CAPACITY");
  private static final Instant AS_AT_BASE = Instant.parse("2026-10-09T00:00:00Z");
  private static final Instant EVALUATED_AT = Instant.parse("2026-10-09T10:00:00Z");
  private static final ReasonCode REASON_IN = new ReasonCode("MIFIR.N08.IN_SCOPE_26_2_A");
  private static final ReasonCode REASON_OUT = new ReasonCode("MIFIR.N08.OUT_OF_SCOPE");
  private static final DecisionContext CONTEXT =
      new DecisionContext(
          Sha256.ofUtf8("capacity-pack"),
          Optional.empty(),
          List.of("pin-1"),
          List.of("ref-1"),
          List.of(),
          "fp-1",
          "map-1",
          "capacity-probe",
          "interpreter");

  /** The id is a canonical-JSON digest: computed once, not once per decision. */
  private static final Sha256 CONTEXT_ID = CONTEXT.id();

  private static final List<String> RULE_IDS =
      List.of("MIFIR.CAP.R001", "MIFIR.CAP.R008", "MIFIR.CAP.R021");
  private static final List<PathStep> PATH = path();
  private static final List<Citation> CITATIONS =
      List.of(
          new Citation("MIFIR.RTS22", "Art.26(2)(a)", CitationRole.BASIS, Optional.empty()),
          new Citation(
              "MIFIR.RTS22",
              "Annex I Table 2",
              CitationRole.INTERPRETATION,
              Optional.of(Sha256.ofUtf8("capacity-quote"))));

  private final LedgerStore store;
  private final Settings settings;
  private final Supplier<CountingDataSource.Counts> counts;
  private final OpenLoopSchedule schedule;
  private final AtomicLong recordsCompleted = new AtomicLong();
  private final AtomicLong batchesCompleted = new AtomicLong();
  private final AtomicLong batchesInFlight = new AtomicLong();
  private final AtomicBoolean abort = new AtomicBoolean();
  private final Object failureLock = new Object();
  private Optional<Throwable> failure = Optional.empty();

  /**
   * @param counts the JDBC counts of the calling thread (see {@link CountingDataSource}); read on
   *     the writer thread before and after a batch
   */
  BurstLoad(LedgerStore store, Settings settings, Supplier<CountingDataSource.Counts> counts) {
    this.store = Objects.requireNonNull(store, "store");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.counts = Objects.requireNonNull(counts, "counts");
    this.schedule =
        new OpenLoopSchedule(settings.ratePerSecond(), settings.batchSize(), settings.seconds());
  }

  private static List<PathStep> path() {
    List<PathStep> steps = new ArrayList<>(PATH_STEPS);
    for (int i = 1; i <= PATH_STEPS; i++) {
      steps.add(new PathStep("MIFIR.CAP.R" + (i < 10 ? "00" : "0") + i, i % 3 != 0, 3L + i));
    }
    return List.copyOf(steps);
  }

  /** Records whose batch has completed, so far. */
  long recordsCompleted() {
    return recordsCompleted.get();
  }

  /** Batches completed so far. */
  long batchesCompleted() {
    return batchesCompleted.get();
  }

  /** The progress columns for the sampler file. */
  Map<String, String> progressColumns() {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("probe.records_completed", Long.toString(recordsCompleted.get()));
    out.put("probe.batches_completed", Long.toString(batchesCompleted.get()));
    out.put("probe.batches_in_flight", Long.toString(batchesInFlight.get()));
    return out;
  }

  /**
   * Runs the schedule to its end and returns when every writer has stopped.
   *
   * @throws InterruptedException when the calling thread is interrupted; the writers are stopped
   */
  Outcome run() throws InterruptedException {
    int batchCount = Math.toIntExact(schedule.batchCount());
    Batch[] done = new Batch[batchCount];
    long start = System.nanoTime();
    List<Thread> threads = new ArrayList<>();
    for (int writer = 0; writer < settings.writers(); writer++) {
      int mine = writer;
      Thread thread = new Thread(() -> writerLoop(mine, start, done), "capacity-writer-" + mine);
      threads.add(thread);
      thread.start();
    }
    try {
      for (Thread thread : threads) {
        thread.join();
      }
    } catch (InterruptedException e) {
      abort.set(true);
      threads.forEach(Thread::interrupt);
      throw e;
    }
    List<Batch> observations = new ArrayList<>(batchCount);
    for (int k = 0; k < batchCount; k++) {
      observations.add(
          done[k] != null
              ? done[k]
              : Batch.notStarted(
                  k, k % settings.writers(), schedule.arrivalNanos(k), settings.batchSize()));
    }
    synchronized (failureLock) {
      return new Outcome(observations, schedule.coveredNanos(), batchCount, failure);
    }
  }

  private void writerLoop(int writer, long start, Batch[] done) {
    long covered = schedule.coveredNanos();
    for (long k = writer; k < schedule.batchCount(); k += settings.writers()) {
      long arrival = schedule.arrivalNanos(k);
      if (!awaitDue(start + arrival)) {
        return;
      }
      long begin = System.nanoTime();
      if (begin - start > covered + GRACE_NANOS) {
        return; // the slice is over: the rest of this writer's batches are never started
      }
      batchesInFlight.incrementAndGet();
      try {
        done[(int) k] = oneBatch(k, writer, arrival, start, begin);
        batchesCompleted.incrementAndGet();
        recordsCompleted.addAndGet(settings.batchSize());
      } catch (Throwable t) {
        done[(int) k] =
            new Batch(
                k,
                writer,
                arrival,
                begin - start,
                Batch.NOT_DONE,
                0,
                0,
                0,
                settings.batchSize(),
                0,
                0);
        fail(t);
        return;
      } finally {
        batchesInFlight.decrementAndGet();
      }
    }
  }

  /**
   * Waits until {@code dueNanos} (a {@code System.nanoTime} value); false when the run is aborted.
   */
  private boolean awaitDue(long dueNanos) {
    while (true) {
      if (abort.get()) {
        return false;
      }
      long wait = dueNanos - System.nanoTime();
      if (wait <= 0) {
        return true;
      }
      try {
        TimeUnit.NANOSECONDS.sleep(Math.min(wait, MAX_SLEEP_NANOS));
      } catch (InterruptedException e) {
        fail(e);
        return false;
      }
    }
  }

  private void fail(Throwable t) {
    synchronized (failureLock) {
      if (failure.isEmpty()) {
        failure = Optional.of(t);
      }
    }
    abort.set(true);
  }

  private Batch oneBatch(long k, int writer, long arrival, long start, long begin) {
    int n = settings.batchSize();
    String sourceId = "CAP-SRC-" + writer;
    BatchId batchId = BatchId.of(Sha256.ofUtf8("capacity/" + settings.runTag() + "/" + k), 0);

    List<IngestLedger.Entry> entries = new ArrayList<>(n);
    List<ReportLedger.ReportableDecision> decisions = new ArrayList<>(n);
    for (int j = 0; j < n; j++) {
      long global = k * n + j;
      String recordId = HexFormat.of().toHexDigits(new SplitMix64(global).nextLong());
      EventIdentity event = EventIdentity.of("LEI" + writer, "XPAR", recordId);
      entries.add(
          new IngestLedger.Entry(
              new IngestKey(sourceId, recordId, 1), event, Sha256.ofUtf8("content-" + recordId)));
      decisions.add(decision(global, j % 2 == 0, event, recordId));
    }
    IngestLedger.Commit ingest =
        new IngestLedger.Commit(
            batchId,
            sourceId,
            entries,
            Optional.of(new Checkpoint("trades", ByteBuffer.allocate(8).putLong(k).array())));
    long generated = System.nanoTime();

    CountingDataSource.Counts before = counts.get();
    IngestLedger.Result ingested = store.commitIngest(ingest);
    long afterIngest = System.nanoTime();
    requireAllNew(ingested, n);
    List<ReportableCommitted> committed =
        store.commitReportable(
            new ReportableCommit(decisions, ingested.outboxId(), Optional.of(batchId)));
    long afterReportable = System.nanoTime();
    requireAllInserted(committed, decisions);
    CountingDataSource.Counts used = counts.get().minus(before);

    return new Batch(
        k,
        writer,
        arrival,
        begin - start,
        afterReportable - start,
        generated - begin,
        afterIngest - generated,
        afterReportable - afterIngest,
        n,
        used.executions(),
        used.batchedRows());
  }

  private static ReportLedger.ReportableDecision decision(
      long global, boolean report, EventIdentity event, String recordId) {
    DecisionBody body =
        new DecisionBody(
            event,
            REGIME,
            CONTEXT_ID,
            AS_AT_BASE.plusSeconds(34_200L + global % 3_600L),
            EVALUATED_AT,
            report ? dev.regpack.model.Outcome.REPORT : dev.regpack.model.Outcome.DO_NOT_REPORT,
            List.of(),
            report ? REASON_IN : REASON_OUT,
            "N08",
            RULE_IDS,
            PATH,
            CITATIONS,
            report ? List.of(Leg.MARKET) : List.of(),
            Sha256.ofUtf8("input-" + recordId),
            report ? Map.of("action", "NEWT") : Map.of());
    DecisionLedger.NewDecision decision =
        new DecisionLedger.NewDecision(body, 0, Optional.of(CONTEXT));
    if (!report) {
      return new ReportLedger.ReportableDecision(decision, List.of());
    }
    String trn = "T" + recordId;
    byte[] reportBody = reportBody(global, trn);
    ReportLedger.NewVersion version =
        new ReportLedger.NewVersion(
            new ReportLedger.SubjectKey(REGIME, event, Leg.MARKET),
            EXECUTING_LEI,
            Optional.of(new ReportLedger.TrnProposal(trn, "KEY-1")),
            "NEWT",
            Sha256.of(reportBody),
            reportBody,
            ReportLedger.VersionOrigin.SOURCE,
            ENDPOINT,
            1);
    return new ReportLedger.ReportableDecision(decision, List.of(version));
  }

  /** 400 bytes of ASCII: the TRN, then hexadecimal words from SplitMix64 as stand-in content. */
  private static byte[] reportBody(long global, String trn) {
    StringBuilder text = new StringBuilder(REPORT_BODY_BYTES + 16).append(trn);
    SplitMix64 words = new SplitMix64(global ^ 0x5DEECE66DL);
    while (text.length() < REPORT_BODY_BYTES) {
      text.append(HexFormat.of().toHexDigits(words.nextLong()));
    }
    return Arrays.copyOf(text.toString().getBytes(StandardCharsets.US_ASCII), REPORT_BODY_BYTES);
  }

  private static void requireAllNew(IngestLedger.Result result, int expected) {
    if (result.entries().size() != expected
        || result.entries().stream().anyMatch(e -> e.disposition() != Disposition.NEW_VERSION)) {
      throw new IllegalStateException(
          "the ingest did not store every record as a new version: the probe's keys clash");
    }
  }

  private static void requireAllInserted(
      List<ReportableCommitted> committed, List<ReportLedger.ReportableDecision> sent) {
    if (committed.size() != sent.size()) {
      throw new IllegalStateException(
          "the reportable unit answered for another number of decisions");
    }
    for (int i = 0; i < sent.size(); i++) {
      ReportableCommitted result = committed.get(i);
      if (result.decision().status() != CommitStatus.INSERTED
          || result.versions().size() != sent.get(i).versions().size()
          || result.versions().stream().anyMatch(v -> v.status() != VersionStatus.CREATED)) {
        throw new IllegalStateException(
            "the reportable unit did not insert a decision with its version: the probe's keys clash");
      }
    }
  }
}
