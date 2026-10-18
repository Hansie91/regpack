package dev.regpack.postgres.capacity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The read-out of one run of the capacity probe (T21). It is capacity information for the owner's
 * L-4 decision and never a verdict (review focus LF21): the text says so, lists how the fixture
 * differs from SP-L and states what the probe cannot separate. No criterion is evaluated and the
 * words of a verdict are not used.
 *
 * <p>The first five lines are the headline figures, each short enough for one workflow annotation;
 * GitHub shows only about ten annotations of a level per step, so the workflow emits exactly those
 * lines and keeps the rest in the summary file.
 */
final class ProbeSummary {
  /** SP-L's table-to-memory ratio for variant B (50,000,000 records of 969 bytes over 16 GiB). */
  static final double SP_L_RATIO = 2.82;

  /** A result is comparable to SP-L's only within this fraction of {@link #SP_L_RATIO}. */
  static final double RATIO_TOLERANCE = 0.10;

  /** The records of the reference day that the ratio and the node memory are scaled to. */
  static final long DAY_RECORDS = 50_000_000L;

  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final long WINDOW_NANOS = 60 * NANOS_PER_SECOND;

  /** The difference from SP-L in the server: one server, no standby, no replica, no archiving. */
  static final String SERVER_DIFFERENCE =
      "fixture differences from SP-L: one PostgreSQL server in a service container on a"
          + " GitHub-hosted runner, with no standby and no replica and no WAL archiving; a commit"
          + " waits for the local WAL flush only (SP-L: primary, quorum standby and replica on one VM,"
          + " archiving on)";

  /** The difference from SP-L in the load: the adapter and the layout. */
  static final String LOAD_DIFFERENCE =
      "load differences from SP-L: the production adapter and the P3a layout (decision body E2,"
          + " daily partitions, subjects, versions and deliveries in PREPARED) instead of the SP-L"
          + " spike layout; micro-batches with one source per writer; the generator shares the"
          + " runner with the server, as in SP-L";

  /** What the probe cannot do. */
  static final String LIMIT =
      "the probe drives the production adapter, so it cannot separate adapter cost from server"
          + " limit";

  /**
   * One micro-batch. Times are nanoseconds after the start of the run. {@code startNanos} is -1 for
   * a batch that was never started and {@code endNanos} is -1 for one that did not complete.
   */
  record Batch(
      long index,
      int source,
      long arrivalNanos,
      long startNanos,
      long endNanos,
      long generateNanos,
      long ingestNanos,
      long reportableNanos,
      int records,
      long executions,
      long batchedRows) {
    static final long NOT_DONE = -1L;

    /** A batch that arrived and was never taken by a writer. */
    static Batch notStarted(long index, int source, long arrivalNanos, int records) {
      return new Batch(index, source, arrivalNanos, NOT_DONE, NOT_DONE, 0, 0, 0, records, 0, 0);
    }

    boolean started() {
      return startNanos >= 0;
    }

    boolean completed() {
      return endNanos >= 0;
    }

    /** The time of the two commits of the batch: the ingest and the reportable unit. */
    long commitNanos() {
      return ingestNanos + reportableNanos;
    }
  }

  /**
   * What the database says about the size of the tables after the run.
   *
   * @param decisionRows the rows of the decision table
   * @param decisionBytes {@code pg_total_relation_size} of its partitions
   * @param ledgerBytes {@code pg_total_relation_size} of all tables of the ledger schema
   */
  record Sizes(long decisionRows, long decisionBytes, long ledgerBytes) {}

  /**
   * The memory that the ratio is taken against.
   *
   * @param bytes the memory of the server: the container limit, or the runner's memory, or empty
   * @param basis what {@code bytes} is, in words
   * @param containerLimit true only when {@code bytes} is a limit set on the server container (the
   *     variants B and C); only then is the ratio comparable to SP-L's
   */
  record Memory(OptionalLong bytes, String basis, boolean containerLimit) {
    Memory {
      Objects.requireNonNull(bytes, "bytes");
      Objects.requireNonNull(basis, "basis");
    }
  }

  /**
   * The kept sampler file.
   *
   * @param name its file name
   * @param rows the rows the sampler wrote
   * @param failures the samples that could not be taken
   */
  record SamplerFile(String name, long rows, long failures) {
    SamplerFile {
      Objects.requireNonNull(name, "name");
    }
  }

  /**
   * What the run and the database say besides the batches. There is one source id for each writer
   * (the adapter serialises the commits of one source), so the sources are the writers.
   *
   * @param variant the workflow variant (A, B, C) or a local name
   * @param batchSize the records of a micro-batch
   * @param writers the writer threads, one source each
   * @param offeredRecordsPerSecond the arrival rate
   * @param scheduledBatches the batches the schedule offered
   * @param coveredNanos the time the slice covers (the arrival of its last batch)
   * @param sizes the table sizes after the run
   * @param memory the memory of the server
   * @param synchronousCommitForced whether the adapter set {@code synchronous_commit = on}
   * @param sampler the kept sampler file
   * @param stopped the cause (see {@link #describe}) when the run stopped on an error
   */
  record Inputs(
      String variant,
      int batchSize,
      int writers,
      long offeredRecordsPerSecond,
      long scheduledBatches,
      long coveredNanos,
      Sizes sizes,
      Memory memory,
      boolean synchronousCommitForced,
      SamplerFile sampler,
      Optional<String> stopped) {
    Inputs {
      Objects.requireNonNull(variant, "variant");
      Objects.requireNonNull(sizes, "sizes");
      Objects.requireNonNull(memory, "memory");
      Objects.requireNonNull(sampler, "sampler");
      Objects.requireNonNull(stopped, "stopped");
    }

    int sources() {
      return writers;
    }
  }

  /** The longest text of a failure message that is carried into a message or a line. */
  private static final int CAUSE_LIMIT = 200;

  /**
   * A failure in one line for the places a reader of annotations sees: the simple class name, then
   * for a {@code SQLException} its SQLState (its message is not carried: server text can hold
   * values), otherwise the first line of its message cut at 200 characters. The adapter's own
   * {@code LedgerException} texts are already free of server text (Convention 12).
   */
  static String describe(Throwable failure) {
    String name = failure.getClass().getSimpleName();
    if (failure instanceof java.sql.SQLException sql) {
      String state = sql.getSQLState();
      return name + ": sqlstate=" + (state == null || state.isBlank() ? "none" : state);
    }
    String message = failure.getMessage();
    String firstLine = message == null ? "" : message.lines().findFirst().orElse("").strip();
    if (firstLine.isEmpty()) {
      return name;
    }
    return name
        + ": "
        + (firstLine.length() <= CAUSE_LIMIT ? firstLine : firstLine.substring(0, CAUSE_LIMIT));
  }

  /** The message of the error of one run of the load: it carries the cause (first line only). */
  static String loadStoppedMessage(String tag, Throwable cause) {
    return "the load stopped on an error (see summary-" + tag + ".txt): " + describe(cause);
  }

  /**
   * The message of the error of the whole probe, which JUnit and the failure annotation show: the
   * count of failed runs and the cause of the first one (the cause of an assertion that wraps one).
   */
  static String probeStoppedMessage(int failedRuns, Throwable first) {
    Throwable root =
        first instanceof AssertionError && first.getCause() != null ? first.getCause() : first;
    return "the capacity probe stopped on an error in " + failedRuns + " run(s): " + describe(root);
  }

  private final Inputs inputs;
  private final double[] windows;
  private final long[] commitNanosSorted;
  private final long completedBatches;
  private final long completedRecords;
  private final long finishedAfterTheSlice;
  private final long neverStarted;
  private final double backlogSeconds;
  private final double statementsPerBatch;
  private final double batchedRowsPerBatch;
  private final double generateMillisMedian;

  private ProbeSummary(Inputs inputs, List<Batch> batches) {
    this.inputs = inputs;
    long covered = inputs.coveredNanos();

    int windowCount = (int) (covered / WINDOW_NANOS);
    long[] windowRecords = new long[windowCount];
    long done = 0;
    long records = 0;
    long late = 0;
    long never = 0;
    long maxQueuedNanos = 0;
    long executions = 0;
    long batchedRows = 0;
    List<Long> commits = new ArrayList<>();
    List<Long> generates = new ArrayList<>();
    for (Batch batch : batches) {
      if (!batch.started()) {
        never++;
        maxQueuedNanos = Math.max(maxQueuedNanos, Math.max(0, covered - batch.arrivalNanos()));
        continue;
      }
      maxQueuedNanos =
          Math.max(maxQueuedNanos, Math.max(0, batch.startNanos() - batch.arrivalNanos()));
      if (!batch.completed()) {
        continue;
      }
      done++;
      records += batch.records();
      executions += batch.executions();
      batchedRows += batch.batchedRows();
      commits.add(batch.commitNanos());
      generates.add(batch.generateNanos());
      if (batch.endNanos() > covered) {
        late++;
      } else {
        int window = (int) (batch.endNanos() / WINDOW_NANOS);
        if (window < windowCount) {
          windowRecords[window] += batch.records();
        }
      }
    }
    this.completedBatches = done;
    this.completedRecords = records;
    this.finishedAfterTheSlice = late;
    this.neverStarted = never;
    this.backlogSeconds = maxQueuedNanos / (double) NANOS_PER_SECOND;
    this.statementsPerBatch = done == 0 ? 0.0 : executions / (double) done;
    this.batchedRowsPerBatch = done == 0 ? 0.0 : batchedRows / (double) done;
    this.windows = new double[windowCount];
    for (int i = 0; i < windowCount; i++) {
      windows[i] = windowRecords[i] / 60.0;
    }
    this.commitNanosSorted = commits.stream().mapToLong(Long::longValue).sorted().toArray();
    long[] generateSorted = generates.stream().mapToLong(Long::longValue).sorted().toArray();
    this.generateMillisMedian = nearestRank(generateSorted, 0.50) / (double) NANOS_PER_MILLI;
  }

  static ProbeSummary of(Inputs inputs, List<Batch> batches) {
    return new ProbeSummary(Objects.requireNonNull(inputs, "inputs"), batches);
  }

  /** The nearest-rank percentile: the ceil(q * n)-th smallest value; 0 for no values. */
  private static long nearestRank(long[] sorted, double quantile) {
    if (sorted.length == 0) {
      return 0;
    }
    int rank = (int) Math.ceil(quantile * sorted.length);
    return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
  }

  /** Full 60-second windows of the slice. */
  int windowCount() {
    return windows.length;
  }

  /** Records a second in the window with the median rate (the mean of the middle two if even). */
  double windowMedian() {
    if (windows.length == 0) {
      return 0.0;
    }
    double[] sorted = windows.clone();
    Arrays.sort(sorted);
    int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
  }

  double windowMinimum() {
    return windows.length == 0 ? 0.0 : Arrays.stream(windows).min().orElse(0.0);
  }

  double windowMaximum() {
    return windows.length == 0 ? 0.0 : Arrays.stream(windows).max().orElse(0.0);
  }

  long commitP50Millis() {
    return Math.round(nearestRank(commitNanosSorted, 0.50) / (double) NANOS_PER_MILLI);
  }

  long commitP99Millis() {
    return Math.round(nearestRank(commitNanosSorted, 0.99) / (double) NANOS_PER_MILLI);
  }

  long commitMaxMillis() {
    return Math.round(nearestRank(commitNanosSorted, 1.0) / (double) NANOS_PER_MILLI);
  }

  /**
   * The largest age of a queued arrival, in seconds: for a batch that was taken, the time from its
   * due time to its start; for one that never started, the time from its due time to the end of the
   * slice.
   */
  double backlogSeconds() {
    return backlogSeconds;
  }

  long completedBatches() {
    return completedBatches;
  }

  long neverStarted() {
    return neverStarted;
  }

  long finishedAfterTheSlice() {
    return finishedAfterTheSlice;
  }

  /** The records of the completed batches over the records that the schedule offered. */
  double completedFraction() {
    long scheduled = inputs.scheduledBatches() * inputs.batchSize();
    return scheduled == 0 ? 0.0 : completedRecords / (double) scheduled;
  }

  /** Bytes a row of the decision table takes, with its indexes and its out-of-line storage. */
  double decisionRowBytes() {
    return inputs.sizes().decisionRows() == 0
        ? 0.0
        : inputs.sizes().decisionBytes() / (double) inputs.sizes().decisionRows();
  }

  /** JDBC executions per completed batch (both commits). */
  double statementsPerBatch() {
    return statementsPerBatch;
  }

  /** Rows added with {@code addBatch} per completed batch. */
  double batchedRowsPerBatch() {
    return batchedRowsPerBatch;
  }

  /** Bytes of all ledger tables per completed record. */
  double ledgerBytesPerRecord() {
    return completedRecords == 0 ? 0.0 : inputs.sizes().ledgerBytes() / (double) completedRecords;
  }

  /**
   * The table-to-memory ratio of the slice: the bytes per completed record times the records that
   * the schedule offered, over the memory of the server. For a limit scaled the way SP-L scaled its
   * own (a 16 GiB node for 50,000,000 records), it equals the ratio of the reference day.
   */
  static double achievedRatio(
      long tableBytes, long completedRecords, long scheduledRecords, long memoryBytes) {
    if (completedRecords <= 0 || memoryBytes <= 0) {
      return 0.0;
    }
    return (tableBytes / (double) completedRecords) * scheduledRecords / memoryBytes;
  }

  /** The node memory that gives this ratio for a day of 50,000,000 records. */
  static long equivalentNodeMemoryBytes(long memoryBytes, long scheduledRecords) {
    if (scheduledRecords <= 0) {
      return 0;
    }
    return Math.multiplyExact(memoryBytes, DAY_RECORDS) / scheduledRecords;
  }

  /** True when the ratio is within the tolerance of SP-L's. */
  static boolean comparableToSpL(double ratio) {
    return Math.abs(ratio - SP_L_RATIO) <= SP_L_RATIO * RATIO_TOLERANCE;
  }

  /** The ratio of this run, or 0 when the memory of the server is not known. */
  double ratio() {
    if (inputs.memory().bytes().isEmpty()) {
      return 0.0;
    }
    return achievedRatio(
        inputs.sizes().ledgerBytes(),
        completedRecords,
        inputs.scheduledBatches() * inputs.batchSize(),
        inputs.memory().bytes().getAsLong());
  }

  /** The whole read-out: five headline lines first, then the detail. */
  List<String> lines() {
    List<String> out = new ArrayList<>(headline());
    out.addAll(detail());
    return List.copyOf(out);
  }

  private List<String> headline() {
    long scheduledRecords = inputs.scheduledBatches() * inputs.batchSize();
    String prefix = "variant " + inputs.variant() + " batches of " + inputs.batchSize() + ": ";
    List<String> out = new ArrayList<>();
    out.add(
        prefix
            + "information, not a verdict; records/s per 60 s window median "
            + one(windowMedian())
            + ", minimum "
            + one(windowMinimum())
            + ", maximum "
            + one(windowMaximum())
            + " over "
            + windowCount()
            + " windows (offered "
            + inputs.offeredRecordsPerSecond()
            + "); completed "
            + percent(completedFraction())
            + " of "
            + scheduledRecords
            + " scheduled records");
    out.add(
        prefix
            + "batch commit ms (ingest and reportable) p50 "
            + commitP50Millis()
            + ", p99 "
            + commitP99Millis()
            + ", max "
            + commitMaxMillis()
            + "; largest backlog "
            + one(backlogSeconds())
            + " s; "
            + neverStarted
            + " batches never started, "
            + finishedAfterTheSlice
            + " finished after the slice");
    out.add(ratioLine(prefix));
    out.add(
        prefix
            + "decision row "
            + Math.round(decisionRowBytes())
            + " bytes (rows "
            + inputs.sizes().decisionRows()
            + "; band of spec 5.6 is 300 to 500 bytes); "
            + inputs.writers()
            + " writers, "
            + inputs.sources()
            + " sources; "
            + one(statementsPerBatch())
            + " JDBC executions and "
            + one(batchedRowsPerBatch())
            + " batched rows per batch");
    if (inputs.stopped().isPresent()) {
      // the cause of a run that stopped comes before the fixture sentence, which stays in the file
      out.add(
          prefix
              + "the run stopped on an error: "
              + inputs.stopped().get()
              + " (information, not a verdict)");
    } else {
      out.add(
          prefix
              + "information, not a verdict; the fixture differs from SP-L (one server, no standby"
              + " and no replica, no WAL archiving; the production adapter and the P3a layout); the"
              + " probe cannot separate adapter cost from server limit");
    }
    return out;
  }

  private String ratioLine(String prefix) {
    String size = one(ledgerBytesPerRecord()) + " bytes a record in all ledger tables";
    if (inputs.memory().bytes().isEmpty()) {
      return prefix
          + size
          + "; memory of the server unknown ("
          + inputs.memory().basis()
          + "), table-to-memory ratio not computed; SP-L 2.82; comparable to SP-L: no";
    }
    double ratio = ratio();
    long limit = inputs.memory().bytes().getAsLong();
    if (!inputs.memory().containerLimit()) {
      // SP-L's 2.82 belongs to a container-limited server: against the memory of the runner the
      // figure is shown for the record only, and no comparison is made
      return prefix
          + size
          + "; table-to-memory ratio "
          + two(ratio)
          + " against the runner memory of "
          + (limit / (1024 * 1024))
          + " MiB ("
          + inputs.memory().basis()
          + "; SP-L 2.82); comparable to SP-L: not applicable (no container limit)";
    }
    long equivalent =
        equivalentNodeMemoryBytes(limit, inputs.scheduledBatches() * inputs.batchSize());
    return prefix
        + size
        + "; table-to-memory ratio "
        + two(ratio)
        + " over "
        + (limit / (1024 * 1024))
        + " MiB ("
        + inputs.memory().basis()
        + "; SP-L 2.82; equal to a node with "
        + one(equivalent / (1024.0 * 1024.0 * 1024.0))
        + " GiB for 50,000,000 records); comparable to SP-L: "
        + (comparableToSpL(ratio) ? "yes" : "no")
        + " (within 10 percent of 2.82)";
  }

  private List<String> detail() {
    List<String> out = new ArrayList<>();
    out.add(SERVER_DIFFERENCE);
    out.add(LOAD_DIFFERENCE);
    out.add(LIMIT);
    out.add(
        "commit mode: "
            + (inputs.synchronousCommitForced()
                ? "synchronous_commit forced on by the adapter"
                : "the adapter did not set synchronous_commit; the server setting applies"));
    out.add(
        "batches: "
            + completedBatches
            + " completed of "
            + inputs.scheduledBatches()
            + " scheduled; slice "
            + one(inputs.coveredNanos() / (double) NANOS_PER_SECOND)
            + " s; batch generation median "
            + one(generateMillisMedian)
            + " ms, outside the commit times and inside the writer's busy time");
    StringBuilder perWindow = new StringBuilder("records/s per window:");
    for (double window : windows) {
      perWindow.append(' ').append(one(window));
    }
    out.add(perWindow.toString());
    out.add(
        "sampler file "
            + inputs.sampler().name()
            + ": "
            + inputs.sampler().rows()
            + " rows, "
            + inputs.sampler().failures()
            + " samples missed; the rows are kept for the cause of the shortfall (H-4)");
    out.add(
        "the ratio uses the sum of pg_total_relation_size of the ledger tables over the completed"
            + " records; a result is comparable to SP-L's L-4 runs only if the ratio is within"
            + " 10 percent of 2.82");
    return out;
  }

  private static String one(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private static String two(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private static String percent(double fraction) {
    return one(fraction * 100.0) + " percent";
  }
}
