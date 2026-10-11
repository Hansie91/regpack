package dev.regpack.spikes.l;

import dev.regpack.bench.eval.Basis;
import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.MeasuredOnly;
import dev.regpack.bench.eval.MeasurementSet.Projection;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.eval.Num;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.stats.Stats;
import dev.regpack.spikes.l.load.BurstRunner;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.RunResult;
import dev.regpack.spikes.l.load.WindowRates;
import dev.regpack.spikes.l.matrix.CommitMatrix;
import dev.regpack.spikes.l.matrix.HotRowCounter;
import dev.regpack.spikes.l.partition.PartitionPlan.Granularity;
import dev.regpack.spikes.l.partition.PartitionStudy;
import dev.regpack.spikes.l.restore.RebuildRun;
import dev.regpack.spikes.l.restore.RestoreRun;
import dev.regpack.spikes.l.restore.RestoreRun.Quality;
import dev.regpack.spikes.l.restore.RtoModel;
import dev.regpack.spikes.l.schema.ConstraintChecks;
import dev.regpack.spikes.l.schema.ConstraintChecks.CheckResult;
import dev.regpack.spikes.l.volume.EvidenceVolumeModel;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns what the SP-L studies measured into the series and decisions of a {@link MeasurementSet}.
 * Pure: the same inputs give the same output, and nothing here reads a clock, a disk or a database.
 * The runner ({@link SpLRunner}) collects the study results and calls one method per part; the
 * verdicts come later, from {@code ResultsEvaluator}, never from here.
 *
 * <p>The rules that keep a result honest are here:
 *
 * <ul>
 *   <li>a run that the disk stopped is neither failed nor valid, so it counts for nothing (L-1,
 *       L-4); a run that failed counts as a failure and as a counted run;
 *   <li>run B (the memory-limited slice, L-4) is judged on its own, so a pass of the cache-hot run
 *       A never hides a failure under the day's table-to-memory ratio (RF14);
 *   <li>a restore point enters the projection of L-6 and L-7 only when the page cache was dropped
 *       and fsync was on (RF13), and fewer than three measured sizes give no series at all, so the
 *       criteria stay NOT_RUN instead of being extrapolated from two points;
 *   <li>a set that holds an EXTRAPOLATED criterion must carry the stated basis in {@code
 *       decisions.extrapolationNote} ({@link #requireExtrapolationNote}).
 * </ul>
 */
public final class LedgerEvaluator {
  /** The spike id. */
  public static final String SPIKE = "SP-L";

  /** The design length of a sustain run, in seconds (20 minutes). */
  public static final long DESIGN_SECONDS = 1_200;

  /** The records of the day that the sketch is sized for (spec 5.6). */
  public static final long DAY_RECORDS = 50_000_000L;

  /** The memory of the reference machine of a full day (spec 5.2): 16 GiB. */
  public static final long DAY_MEMORY_BYTES = 16L << 30;

  /** The least memory that run B gets: below it PostgreSQL cannot run a meaningful workload. */
  public static final long MIN_B_MEMORY_BYTES = 512L << 20;

  /** The stated basis of every extrapolated criterion (RF14), the same text in every part. */
  public static final String EXTRAPOLATION_NOTE =
      "L-1 and L-4 are counts over 20-minute open-loop slices (about 4.2M records, fewer when the"
          + " disk of the runner caps them; the report prints the covered seconds against the 1,200 s"
          + " design) at 3,470 records/s with a live quorum synchronous standby, not over a full"
          + " day of 50M records. Run A is cache-hot; run B repeats it with the primary limited to"
          + " the memory that gives the slice the table-to-memory ratio of a full 50M-record day on"
          + " 16 GiB (derived from the measured bytes per record), and must also sustain. Restore"
          + " RTO at 10M and 100M keys (L-6, L-7) is projected by a Theil-Sen line from at least"
          + " three measured cold-cache sizes with fsync on, raised to a physical floor, with the"
          + " replay of the gap added for the stated backup interval; the full rebuild (L-8) is"
          + " projected linearly from its measured seconds per million keys. These readings are"
          + " EXTRAPOLATED and need the owner's acknowledgement in the freeze record.";

  private static final long SECOND = 1_000_000_000L;
  private static final long WINDOW_NANOS = 60 * SECOND;
  private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600);
  private static final BigDecimal HOUR_SCALE =
      BigDecimal.ONE.divide(SECONDS_PER_HOUR, 15, RoundingMode.HALF_EVEN);
  private static final long TEN_MILLION = 10_000_000L;
  private static final long HUNDRED_MILLION = 100_000_000L;
  private static final double KNEE_FIXED_SHARE = 0.10;
  private static final int MIN_RESTORE_SIZES = 3;

  private LedgerEvaluator() {}

  // ------------------------------------------------------------------ records

  /** The series, decisions and open items that a part produced. */
  public record Output(List<Series> series, Map<String, Object> decisions, List<String> open) {
    public Output {
      series = List.copyOf(series);
      decisions = Collections.unmodifiableMap(new LinkedHashMap<>(decisions));
      open = List.copyOf(open);
    }

    /**
     * Both outputs in one: a criterion that both produced must be a count and its counts add; the
     * decisions of this output win and an open item is kept once.
     */
    public Output plus(Output other) {
      Map<String, Series> byId = new LinkedHashMap<>();
      for (Series s : series) {
        byId.put(s.criterionId(), s);
      }
      for (Series s : other.series) {
        if (!byId.containsKey(s.criterionId())) {
          byId.put(s.criterionId(), s);
        } else if (byId.get(s.criterionId()) instanceof CountValue a && s instanceof CountValue b) {
          byId.put(
              a.criterionId(),
              new CountValue(
                  a.criterionId(),
                  Math.addExact(a.observed(), b.observed()),
                  Math.addExact(a.validRuns(), b.validRuns())));
        } else {
          throw new IllegalArgumentException(
              "two outputs both produced " + s.criterionId() + " and it is not a count");
        }
      }
      Map<String, Object> decided = new LinkedHashMap<>(decisions);
      other.decisions.forEach(decided::putIfAbsent);
      Set<String> opened = new LinkedHashSet<>(open);
      opened.addAll(other.open);
      return new Output(new ArrayList<>(byId.values()), decided, new ArrayList<>(opened));
    }
  }

  /** A run with the name it has in the report: A1, A2, B1, B2 or C1. */
  public record NamedRun(String name, RunResult result) {}

  /**
   * The memory of run B: the primary is limited so that the slice of {@code sliceRecords} has the
   * table-and-index-to-memory ratio of a full day on 16 GiB. The ratio is {@code 50M x bytes per
   * candidate / 16 GiB}; the limit that gives it to the slice is {@code 16 GiB x sliceRecords /
   * 50M} (the bytes per candidate cancel), raised to {@link #MIN_B_MEMORY_BYTES}.
   *
   * @param sliceRecords the records that the slice writes
   * @param bytesPerCandidate the measured bytes of one candidate with its indexes
   * @param memoryBytes the limit of the container
   * @param sharedBuffersBytes the {@code shared_buffers} setting, a quarter of the limit
   * @param dayRatio the table-and-index-to-memory ratio of a full day, scale 3
   * @param floored the limit was raised to the floor, so the ratio of the slice is lower than the
   *     day's
   */
  public record MemoryPlan(
      long sliceRecords,
      long bytesPerCandidate,
      long memoryBytes,
      long sharedBuffersBytes,
      BigDecimal dayRatio,
      boolean floored) {
    /** The plan for a slice of {@code sliceRecords} records of {@code bytesPerCandidate} bytes. */
    public static MemoryPlan of(long sliceRecords, long bytesPerCandidate) {
      if (sliceRecords <= 0 || bytesPerCandidate <= 0) {
        throw new IllegalArgumentException("the slice and the bytes per candidate are positive");
      }
      BigInteger exact =
          BigInteger.valueOf(DAY_MEMORY_BYTES)
              .multiply(BigInteger.valueOf(sliceRecords))
              .add(BigInteger.valueOf(DAY_RECORDS - 1))
              .divide(BigInteger.valueOf(DAY_RECORDS));
      long wanted = exact.min(BigInteger.valueOf(DAY_MEMORY_BYTES)).longValueExact();
      boolean floored = wanted < MIN_B_MEMORY_BYTES;
      long memory = Math.max(wanted, MIN_B_MEMORY_BYTES);
      BigDecimal ratio =
          new BigDecimal(
                  BigInteger.valueOf(DAY_RECORDS).multiply(BigInteger.valueOf(bytesPerCandidate)))
              .divide(BigDecimal.valueOf(DAY_MEMORY_BYTES), 3, RoundingMode.HALF_EVEN);
      return new MemoryPlan(sliceRecords, bytesPerCandidate, memory, memory / 4, ratio, floored);
    }

    /** The value of {@code --memory=} of {@code pg-restart.sh}, whole mebibytes. */
    public String dockerMemory() {
      return memoryBytes / (1L << 20) + "m";
    }

    /** The value of {@code --shared-buffers=} of {@code pg-restart.sh}, whole mebibytes. */
    public String sharedBuffers() {
      return sharedBuffersBytes / (1L << 20) + "MB";
    }

    /** The sentence that the L-4 reason carries. */
    public String describe() {
      return "table-to-memory ratio "
          + dayRatio.setScale(2, RoundingMode.HALF_EVEN).toPlainString()
          + " ("
          + DAY_RECORDS
          + " records x "
          + bytesPerCandidate
          + " bytes over 16 GiB), the primary limited to "
          + memoryBytes / (1L << 20)
          + " MiB for a slice of "
          + sliceRecords
          + " records"
          + (floored
              ? ", raised to the "
                  + MIN_B_MEMORY_BYTES / (1L << 20)
                  + " MiB floor: the slice's"
                  + " ratio is below the day's"
              : "");
    }
  }

  /**
   * The runs of the ingest part.
   *
   * @param sustainA the open-loop runs A1 and A2 (cache-hot slice)
   * @param sustainB the open-loop runs B1 and B2 (memory-limited slice)
   * @param closedLoop run C1, the closed-loop capacity
   * @param violatingSamples the samples of all sampled runs that had no live synchronous standby
   * @param memory the memory of run B
   */
  public record IngestRuns(
      List<NamedRun> sustainA,
      List<NamedRun> sustainB,
      Optional<NamedRun> closedLoop,
      long violatingSamples,
      MemoryPlan memory) {
    public IngestRuns {
      sustainA = List.copyOf(sustainA);
      sustainB = List.copyOf(sustainB);
    }
  }

  /** A rebuild with the comparison of its decisions with the direct run. */
  public record RebuildEntry(RebuildRun.Result result, boolean contentEqual) {}

  /**
   * The restore part.
   *
   * @param points every restore point that ran, valid or not, with its gap
   * @param bytesPerKey the measured bytes of a key with its indexes (for the physical floor)
   * @param rebuilds the rebuilds that ran
   * @param recordsPerDay the records of a day (for the replay term)
   * @param backupIntervalHours the assumed backup interval of the replay term
   */
  public record RestoreData(
      List<RestoreRun.Outcome> points,
      long bytesPerKey,
      List<RebuildEntry> rebuilds,
      long recordsPerDay,
      BigDecimal backupIntervalHours) {
    public RestoreData {
      points = List.copyOf(points);
      rebuilds = List.copyOf(rebuilds);
    }
  }

  /**
   * The size of the decision rows of one encoding.
   *
   * @param variant E1_COMPACT or E3_VERBOSE
   * @param rows decision rows (one per candidate)
   * @param heapBytes heap of the decision partitions
   * @param indexBytes indexes of the decision partitions
   * @param headRows rows of {@code decision_head}
   * @param tableBytes {@code pg_total_relation_size} per table (partitions folded into the parent)
   */
  public record RowSizeData(
      String variant,
      long rows,
      long heapBytes,
      long indexBytes,
      long headRows,
      Map<String, Long> tableBytes) {
    public RowSizeData {
      tableBytes = Map.copyOf(tableBytes);
    }
  }

  /**
   * The primary-key fetch under the burst.
   *
   * @param p99NanosPerBlock the p99 of each block of fetches
   * @param burstRanThroughout the open-loop burst was still running when the last block ended
   * @param burstRecords the records of the burst
   */
  public record FetchData(
      double[] p99NanosPerBlock, boolean burstRanThroughout, long burstRecords) {}

  /**
   * Replay lag and cancelled queries under console load.
   *
   * @param samples the readings of the lag
   * @param maxReplayLagSeconds the largest replay lag seen on the standby or the replica
   * @param cancelledQueries the console queries that the server cancelled
   */
  public record ConsoleData(long samples, double maxReplayLagSeconds, long cancelledQueries) {}

  /** What the misc part measured; an empty part was not run. */
  public record MiscData(
      List<CheckResult> checks,
      Optional<RowSizeData> rowSizeE1,
      Optional<RowSizeData> rowSizeE3,
      List<PartitionStudy.Point> partitions,
      Optional<FetchData> fetch,
      Optional<HotRowCounter.Result> hotRow,
      List<CommitMatrix.Cell> cells,
      Optional<EvidenceVolumeModel.Volume> volume,
      long violatingSamples,
      Optional<ConsoleData> console) {
    public MiscData {
      checks = List.copyOf(checks);
      partitions = List.copyOf(partitions);
      cells = List.copyOf(cells);
    }
  }

  // ------------------------------------------------------------------ small helpers

  private static BigDecimal ms(double nanos) {
    return Num.dec(nanos / 1e6, 3);
  }

  private static BigDecimal dec3(double value) {
    return Num.dec(value, 3);
  }

  private static ForkStat stat(int index, double value) {
    return new ForkStat(index, Stats.decimal(value), false, "", false);
  }

  private static Series forks(String id, String unit, List<Double> values) {
    List<ForkStat> stats = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      stats.add(stat(i, values.get(i)));
    }
    return new ForkValues(id, unit, BigDecimal.ONE, stats);
  }

  private static List<Double> boxed(double[] values, double divisor) {
    List<Double> out = new ArrayList<>(values.length);
    for (double v : values) {
      out.add(v / divisor);
    }
    return out;
  }

  private static double median(double[] values) {
    return Stats.median(values);
  }

  private static double p99(long[] nanos) {
    double[] values = new double[nanos.length];
    for (int i = 0; i < values.length; i++) {
      values[i] = nanos[i];
    }
    return Stats.percentile(values, 99);
  }

  private static long ceilDiv(long a, long b) {
    return Math.floorDiv(Math.addExact(a, b - 1), b);
  }

  /** A stop that left a usable set of commits: the load was covered or a record cap ended it. */
  private static boolean measuredCommits(RunResult r) {
    return !r.environmentFault()
        && r.stoppedBecause() != BurstRunner.Stop.SYNC_PRECONDITION
        && r.stoppedBecause() != BurstRunner.Stop.ERROR;
  }

  private static String describe(NamedRun run) {
    RunResult r = run.result();
    String state = r.valid() ? "valid" : r.environmentFault() ? "environment fault" : "FAILED";
    StringBuilder out =
        new StringBuilder(run.name())
            .append(": ")
            .append(state)
            .append(", stopped by ")
            .append(r.stoppedBecause())
            .append(", covered ")
            .append(r.coveredNanos() / SECOND)
            .append(" s of the ")
            .append(DESIGN_SECONDS)
            .append(" s design, ")
            .append(r.records())
            .append(" of ")
            .append(r.scheduledRecords())
            .append(" scheduled records completed");
    if (!r.reasons().isEmpty()) {
      out.append("; ").append(String.join("; ", r.reasons()));
    }
    return out.toString();
  }

  // ------------------------------------------------------------------ the ingest part

  /**
   * L-1 and L-4: the failed runs among the counted ones. A disk stop (an environment fault) is
   * neither failed nor counted.
   */
  static CountValue runsFailed(String id, List<RunResult> runs) {
    long failed = runs.stream().filter(RunResult::failed).count();
    int counted = (int) runs.stream().filter(r -> !r.environmentFault()).count();
    return new CountValue(id, failed, counted);
  }

  /** The p99 of the synchronous commit time of each full 60 s window of a run, in ms. */
  static List<Double> windowCommitP99Ms(RunResult r) {
    int windows = WindowRates.fullWindows(r.coveredNanos(), WINDOW_NANOS);
    long[] commits = r.commitNanosSync();
    long[] ends = r.batchEndOffsetNanos();
    int n = Math.min(commits.length, ends.length);
    List<List<Double>> buckets = new ArrayList<>();
    for (int w = 0; w < windows; w++) {
      buckets.add(new ArrayList<>());
    }
    for (int i = 0; i < n; i++) {
      long w = ends[i] / WINDOW_NANOS;
      if (ends[i] >= 0 && w < windows) {
        buckets.get((int) w).add((double) commits[i]);
      }
    }
    List<Double> out = new ArrayList<>();
    for (List<Double> bucket : buckets) {
      if (!bucket.isEmpty()) {
        double[] values = bucket.stream().mapToDouble(Double::doubleValue).toArray();
        out.add(Stats.percentile(values, 99) / 1e6);
      }
    }
    return out;
  }

  /** The series, decisions and open items of the ingest part. */
  public static Output ingest(IngestRuns in) {
    List<Series> series = new ArrayList<>();
    Map<String, Object> decisions = new TreeMap<>();
    List<String> open = new ArrayList<>();
    decisions.put("extrapolationNote", EXTRAPOLATION_NOTE);

    List<RunResult> a = in.sustainA().stream().map(NamedRun::result).toList();
    List<RunResult> b = in.sustainB().stream().map(NamedRun::result).toList();
    for (NamedRun run : in.sustainA()) {
      open.add(describe(run));
    }
    if (!a.isEmpty()) {
      series.add(runsFailed("L-1", a));
      List<Double> windows = new ArrayList<>();
      for (RunResult r : a) {
        if (measuredCommits(r)) {
          windows.addAll(windowCommitP99Ms(r));
        }
      }
      if (!windows.isEmpty()) {
        series.add(forks("L-2", "ms", windows));
      } else {
        open.add(
            "L-2 not recorded: no run of A covered a full 60 s window with synchronous commits");
      }
      sustainDecisions(a, decisions);
    }
    if (in.closedLoop().isPresent()) {
      NamedRun c = in.closedLoop().get();
      open.add(describe(c));
      RunResult r = c.result();
      if ((r.valid() || r.onlyCappedByRecords()) && r.windowRates().length > 0) {
        series.add(forks("L-3", "records/s", boxed(r.windowRates(), 1.0)));
        decisions.put("capacity.records", r.records());
        decisions.put("capacity.windows", (long) r.windowRates().length);
      } else {
        open.add(
            "L-3 not recorded: "
                + (r.windowRates().length == 0
                    ? "the closed-loop run covered no full 60 s window"
                    : "the closed-loop run is not usable"));
      }
    }
    MemoryPlan memory = in.memory();
    if (!b.isEmpty()) {
      CountValue l4 = runsFailed("L-4", b);
      series.add(l4);
      for (NamedRun run : in.sustainB()) {
        open.add(describe(run));
      }
      open.add(
          "L-4: run B at "
              + memory.describe()
              + "; "
              + l4.observed()
              + " of "
              + l4.validRuns()
              + " counted runs failed"
              + (l4.observed() > 0
                  ? " (FAILED: the slice that sustains when cache-hot does not sustain under the"
                      + " day's memory pressure)"
                  : ""));
    }
    decisions.put("memory.ratioDay", memory.dayRatio());
    decisions.put("memory.limitMiB", memory.memoryBytes() / (1L << 20));
    decisions.put("memory.sharedBuffersMiB", memory.sharedBuffersBytes() / (1L << 20));
    decisions.put("memory.sliceRecords", memory.sliceRecords());
    decisions.put("memory.bytesPerCandidate", memory.bytesPerCandidate());
    decisions.put("memory.floored", memory.floored());
    boolean sampled = !a.isEmpty() || !b.isEmpty() || in.closedLoop().isPresent();
    if (sampled) {
      series.add(new CountValue("L-13", in.violatingSamples(), 1));
    }
    return new Output(series, decisions, open);
  }

  private static void sustainDecisions(List<RunResult> a, Map<String, Object> decisions) {
    double peak = -1;
    List<Long> latency = new ArrayList<>();
    List<Long> commits = new ArrayList<>();
    for (RunResult r : a) {
      if (!measuredCommits(r)) {
        continue;
      }
      peak = Math.max(peak, r.peakBacklogSeconds());
      for (long l : r.openLoopLatencyNanos()) {
        latency.add(l);
      }
      for (long c : r.commitNanosSync()) {
        commits.add(c);
      }
    }
    if (peak >= 0) {
      decisions.put("ledger.peakBacklogSeconds", dec3(peak));
    }
    if (!latency.isEmpty()) {
      decisions.put(
          "ledger.openLoopP99Ms", ms(p99(latency.stream().mapToLong(Long::longValue).toArray())));
    }
    if (!commits.isEmpty()) {
      BigDecimal commit = ms(p99(commits.stream().mapToLong(Long::longValue).toArray()));
      decisions.put("ledger.commitP99Ms", commit);
      // the figure of spec 7.8's T: the two synchronous transactions of a micro-batch together
      decisions.put("ledger.ingestCommitP99Ms", commit);
    }
  }

  // ------------------------------------------------------------------ L-5

  /**
   * L-5: the bytes of one decision row with its indexes, rounded up (never under-reported).
   *
   * @throws IllegalArgumentException for no rows
   */
  public static Series rowSize(long rows, long heapBytes, long indexBytes) {
    if (rows <= 0) {
      throw new IllegalArgumentException("rows must be positive: " + rows);
    }
    return new CountValue("L-5", ceilDiv(Math.addExact(heapBytes, indexBytes), rows), 1);
  }

  // ------------------------------------------------------------------ the restore part

  private static BigDecimal seconds(long nanos) {
    return BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(SECOND), 3, RoundingMode.HALF_EVEN);
  }

  /** The series, decisions and open items of the restore part. */
  public static Output restore(RestoreData in) {
    List<Series> series = new ArrayList<>();
    Map<String, Object> decisions = new TreeMap<>();
    List<String> open = new ArrayList<>();
    decisions.put("extrapolationNote", EXTRAPOLATION_NOTE);

    List<RestoreRun.Point> points = in.points().stream().map(RestoreRun.Outcome::point).toList();
    for (RestoreRun.Outcome o : in.points()) {
      RestoreRun.Point p = o.point();
      String key = "restore." + p.keys() + ".";
      decisions.put(key + "rtoSeconds", seconds(p.rtoNanos()));
      decisions.put(key + "copySeconds", seconds(p.copyNanos()));
      decisions.put(key + "syncSeconds", seconds(p.syncNanos()));
      decisions.put(key + "replaySeconds", seconds(p.replayNanos()));
      decisions.put(key + "gapObjectsSeconds", seconds(p.gapObjectsNanos()));
      decisions.put(key + "analyzeSeconds", seconds(p.analyzeNanos()));
      decisions.put(key + "verifySeconds", seconds(p.verifyNanos()));
      decisions.put(key + "quality", p.quality().name());
      decisions.put(key + "gapRecords", o.gapRecords());
      if (p.quality() != Quality.VALID) {
        open.add("restore point at " + p.keys() + " keys is " + p.quality() + ": " + p.reason());
      }
    }
    decisions.put("restore.bytesPerKey", in.bytesPerKey());
    decisions.put("restore.backupIntervalHours", in.backupIntervalHours());

    if (points.size() < MIN_RESTORE_SIZES) {
      open.add(
          "L-6 and L-7 not run: fewer than three restore sizes were measured ("
              + points.size()
              + "); a projection is never made from two points");
    } else {
      restoreProjections(in, series, decisions, open);
    }
    rebuilds(in, series, decisions, open);
    return new Output(series, decisions, open);
  }

  private static void restoreProjections(
      RestoreData in, List<Series> series, Map<String, Object> decisions, List<String> open) {
    List<RestoreRun.Outcome> valid =
        in.points().stream().filter(o -> o.point().quality() == Quality.VALID).toList();
    BigDecimal replaySeconds = BigDecimal.ZERO;
    BigDecimal floorSeconds10 = BigDecimal.ZERO;
    BigDecimal floorSeconds100 = BigDecimal.ZERO;
    List<BigDecimal> xs = new ArrayList<>();
    List<BigDecimal> ys = new ArrayList<>();
    if (!valid.isEmpty()) {
      RestoreRun.Outcome largest =
          valid.stream().max(Comparator.comparingLong(o -> o.point().keys())).orElseThrow();
      replaySeconds =
          RtoModel.replaySeconds(
              largest.point().replayNanos(),
              Math.max(1, largest.gapRecords()),
              in.recordsPerDay(),
              in.backupIntervalHours());
      for (RestoreRun.Outcome o : valid) {
        RestoreRun.Point p = o.point();
        BigDecimal grows =
            seconds(p.rtoNanos() - p.replayNanos())
                .add(replaySeconds)
                .setScale(3, RoundingMode.HALF_EVEN);
        xs.add(BigDecimal.valueOf(p.keys()));
        ys.add(grows);
      }
      // the physical floor: the fastest observed copy (it reads and writes the cluster once each)
      // sets the rate that the disk is known to sustain, in bytes per second for each direction
      long rate = 1;
      for (RestoreRun.Outcome o : valid) {
        RestoreRun.Point p = o.point();
        double copySeconds = Math.max(1e-9, p.copyNanos() / 1e9);
        rate = Math.max(rate, (long) (2.0 * p.keys() * in.bytesPerKey() / copySeconds));
      }
      floorSeconds10 = RtoModel.floorSeconds(TEN_MILLION * in.bytesPerKey(), rate, rate);
      floorSeconds100 = RtoModel.floorSeconds(HUNDRED_MILLION * in.bytesPerKey(), rate, rate);
      decisions.put("restore.floorRateBytesPerSecond", rate);
    }
    decisions.put("restore.replaySeconds", replaySeconds.setScale(3, RoundingMode.HALF_EVEN));
    decisions.put("restore.floorSeconds.10M", floorSeconds10.setScale(3, RoundingMode.HALF_EVEN));
    decisions.put("restore.floorSeconds.100M", floorSeconds100.setScale(3, RoundingMode.HALF_EVEN));
    if (valid.size() < MIN_RESTORE_SIZES) {
      open.add(
          "L-6 and L-7 INCONCLUSIVE: only "
              + valid.size()
              + " of "
              + in.points().size()
              + " restore points are valid (cold cache and fsync on); the others never feed the"
              + " projection");
    }
    series.add(
        new Projection(
            "L-6",
            "hours",
            HOUR_SCALE,
            xs,
            ys,
            BigDecimal.valueOf(TEN_MILLION),
            hours(floorSeconds10.add(replaySeconds))));
    series.add(
        new Projection(
            "L-7",
            "hours",
            HOUR_SCALE,
            xs,
            ys,
            BigDecimal.valueOf(HUNDRED_MILLION),
            hours(floorSeconds100.add(replaySeconds))));
    open.add(
        "L-6 and L-7 assume a backup every "
            + in.backupIntervalHours().toPlainString()
            + " hours: the replay of the gap is the measured replay rate scaled to that interval of "
            + in.recordsPerDay()
            + " records a day");
  }

  private static BigDecimal hours(BigDecimal seconds) {
    return seconds.divide(SECONDS_PER_HOUR, 9, RoundingMode.HALF_EVEN);
  }

  private static void rebuilds(
      RestoreData in, List<Series> series, Map<String, Object> decisions, List<String> open) {
    if (in.rebuilds().isEmpty()) {
      return;
    }
    boolean invariants = true;
    int cold = 0;
    BigDecimal slowest = BigDecimal.ZERO;
    for (RebuildEntry e : in.rebuilds()) {
      RebuildRun.Result r = e.result();
      String key = "rebuild." + r.keys() + ".";
      decisions.put(key + "secondsPerMillionKeys", r.secondsPerMillionKeys());
      decisions.put(key + "wallSeconds", seconds(r.wallNanos()));
      decisions.put(key + "readSeconds", seconds(r.readNanos()));
      decisions.put(key + "decideSeconds", seconds(r.decideNanos()));
      decisions.put(key + "copySeconds", seconds(r.copyNanos()));
      decisions.put(key + "coldCache", r.cacheDropped());
      decisions.put(key + "contentEqual", e.contentEqual());
      if (!r.cacheDropped()) {
        open.add(
            "rebuild at "
                + r.keys()
                + " keys: the page cache was not dropped, so it is not a cold-cache reading and is"
                + " not counted");
        continue;
      }
      cold++;
      if (!e.contentEqual()) {
        invariants = false;
        open.add(
            "rebuild at "
                + r.keys()
                + " keys decided differently from the direct run: the rebuild is wrong, not slow");
      }
      slowest = slowest.max(r.secondsPerMillionKeys());
    }
    series.add(
        new MeasuredOnly(
            "L-8",
            invariants,
            cold,
            slowest.setScale(3, RoundingMode.HALF_EVEN),
            "seconds per million keys (the slowest cold rebuild)"));
    if (cold > 0) {
      decisions.put("rebuild.projectedHours.10M", rebuildHours(slowest, TEN_MILLION));
      decisions.put("rebuild.projectedHours.100M", rebuildHours(slowest, HUNDRED_MILLION));
    }
  }

  private static BigDecimal rebuildHours(BigDecimal secondsPerMillion, long keys) {
    return secondsPerMillion
        .multiply(BigDecimal.valueOf(keys))
        .divide(
            BigDecimal.valueOf(1_000_000L).multiply(SECONDS_PER_HOUR), 3, RoundingMode.HALF_EVEN);
  }

  // ------------------------------------------------------------------ the misc part

  /** The series, decisions and open items of the misc part. */
  public static Output misc(MiscData in) {
    List<Series> series = new ArrayList<>();
    Map<String, Object> decisions = new TreeMap<>();
    List<String> open = new ArrayList<>();
    decisions.put("extrapolationNote", EXTRAPOLATION_NOTE);
    int l14Components = 0;
    BigDecimal bytesPerDay = BigDecimal.ZERO;

    constraints(in, series, open);

    if (in.rowSizeE1().isPresent()) {
      RowSizeData e1 = in.rowSizeE1().get();
      series.add(rowSize(e1.rows(), e1.heapBytes(), e1.indexBytes()));
      rowSizeDecisions(e1, decisions);
      l14Components++;
      bytesPerDay = bytesPerDay(e1, decisions);
      l14Components++;
    }
    in.rowSizeE3().ifPresent(e3 -> rowSizeDecisions(e3, decisions));

    partitions(in, series, decisions);
    in.fetch().ifPresent(f -> fetch(f, series, open));
    long hotViolations = hotRow(in, series, decisions, open);

    int knee = matrix(in, decisions, open);
    if (knee > 0) {
      l14Components++;
    }
    if (in.volume().isPresent()) {
      volume(in.volume().get(), decisions);
      l14Components++;
    }
    if (l14Components > 0) {
      boolean bytesMeasured = in.rowSizeE1().isPresent();
      series.add(
          new MeasuredOnly(
              "L-14",
              true,
              l14Components,
              bytesPerDay.setScale(0, RoundingMode.CEILING),
              bytesMeasured
                  ? "bytes per day (ledger tables of a 50M-record day)"
                  : "bytes per day: NOT MEASURED (the row size did not run, so 0 is a placeholder;"
                      + " the other components of L-14 are in the decisions)"));
      if (!bytesMeasured) {
        open.add(
            "L-14: the bytes per day were not measured because the row size step did not run; the"
                + " value 0 is a placeholder, not a size");
      }
    }
    in.console()
        .ifPresent(
            c -> {
              series.add(
                  new MeasuredOnly(
                      "L-15",
                      true,
                      (int) Math.min(Integer.MAX_VALUE, c.samples()),
                      dec3(c.maxReplayLagSeconds()),
                      "seconds (largest replay lag under console load)"));
              decisions.put("console.cancelledQueries", c.cancelledQueries());
              decisions.put("console.maxReplayLagSeconds", dec3(c.maxReplayLagSeconds()));
            });

    long invalidCells =
        in.cells().stream()
            .filter(c -> !c.valid() && c.stoppedBecause() == BurstRunner.Stop.SYNC_PRECONDITION)
            .count();
    boolean sampled = in.hotRow().isPresent() || !in.cells().isEmpty() || in.fetch().isPresent();
    if (sampled) {
      series.add(new CountValue("L-13", in.violatingSamples() + hotViolations + invalidCells, 1));
    }
    return new Output(series, decisions, open);
  }

  private static void constraints(MiscData in, List<Series> series, List<String> open) {
    if (in.checks().isEmpty()) {
      return;
    }
    Set<String> present = new LinkedHashSet<>();
    long failed = 0;
    for (CheckResult check : in.checks()) {
      present.add(check.name());
      if (!check.passed()) {
        failed++;
        open.add(
            "L-12: constraint check "
                + check.name()
                + " FAILED: "
                + check.detail()
                + " (a finding for the P3 schema design: name what PostgreSQL 18 cannot express and"
                + " how it can be re-shaped)");
      }
    }
    for (String name : ConstraintChecks.NAMES) {
      if (!present.contains(name)) {
        failed++;
        open.add("L-12: constraint check " + name + " did not run and counts as failed");
      }
    }
    series.add(new CountValue("L-12", failed, 1));
  }

  private static void rowSizeDecisions(RowSizeData row, Map<String, Object> decisions) {
    String key = "rowSize." + (row.variant().startsWith("E1") ? "E1" : "E3") + ".";
    decisions.put(key + "rows", row.rows());
    decisions.put(key + "heapBytes", row.heapBytes());
    decisions.put(key + "indexBytes", row.indexBytes());
    decisions.put(
        key + "bytesPerRow",
        ceilDiv(Math.addExact(row.heapBytes(), row.indexBytes()), Math.max(1, row.rows())));
  }

  /** The bytes per day by table (L-14) and the growth of {@code decision_head}; the total. */
  private static BigDecimal bytesPerDay(RowSizeData e1, Map<String, Object> decisions) {
    BigDecimal total = BigDecimal.ZERO;
    BigInteger day = BigInteger.valueOf(DAY_RECORDS);
    for (Map.Entry<String, Long> e : new TreeMap<>(e1.tableBytes()).entrySet()) {
      BigInteger perDay =
          BigInteger.valueOf(e.getValue())
              .multiply(day)
              .add(BigInteger.valueOf(Math.max(1, e1.rows()) - 1))
              .divide(BigInteger.valueOf(Math.max(1, e1.rows())));
      decisions.put("bytes.perDay." + e.getKey(), perDay.longValueExact());
      decisions.put(
          "bytes.perCandidate." + e.getKey(),
          BigDecimal.valueOf(e.getValue())
              .divide(BigDecimal.valueOf(Math.max(1, e1.rows())), 3, RoundingMode.HALF_EVEN));
      total = total.add(new BigDecimal(perDay));
    }
    decisions.put("bytes.perDay.total", total.setScale(0, RoundingMode.CEILING).longValueExact());
    // decision_head and ingest_registry are not partitioned and never purged by the sketch
    long headPerDay =
        BigInteger.valueOf(e1.headRows())
            .multiply(day)
            .divide(BigInteger.valueOf(Math.max(1, e1.rows())))
            .longValueExact();
    decisions.put("decisionHead.rowsPerDay", headPerDay);
    Optional.ofNullable(e1.tableBytes().get("decision_head"))
        .ifPresent(
            headBytes ->
                decisions.put(
                    "decisionHead.bytesPerDay",
                    BigInteger.valueOf(headBytes)
                        .multiply(day)
                        .divide(BigInteger.valueOf(Math.max(1, e1.rows())))
                        .longValueExact()));
    return total;
  }

  private static void partitions(MiscData in, List<Series> series, Map<String, Object> decisions) {
    for (PartitionStudy.Point p : in.partitions()) {
      String key =
          "partition."
              + (p.granularity() == Granularity.DAILY ? "daily" : "monthly")
              + p.partitions()
              + ".";
      decisions.put(key + "lookupP99Ms", ms(median(p.lookupP99NanosPerBlock())));
      decisions.put(
          key + "headThenFetchP99Ms", ms(median(p.headThenPrunedFetchP99NanosPerBlock())));
      decisions.put(key + "detachMs", ms(p.detachNanos()));
      decisions.put(key + "dropMs", ms(p.dropNanos()));
    }
    in.partitions().stream()
        .filter(p -> p.granularity() == Granularity.DAILY)
        .max(Comparator.comparingInt(PartitionStudy.Point::partitions))
        .ifPresent(
            largestDaily -> {
              series.add(forks("L-10", "ms", boxed(largestDaily.lookupP99NanosPerBlock(), 1e6)));
              decisions.put("partition.l10Partitions", (long) largestDaily.partitions());
            });
  }

  private static void fetch(FetchData f, List<Series> series, List<String> open) {
    if (!f.burstRanThroughout()) {
      open.add(
          "L-9 not recorded: the 3,470 records/s burst ended before the last block of fetches, so"
              + " they were not measured under the burst");
      return;
    }
    if (f.p99NanosPerBlock().length == 0) {
      open.add("L-9 not recorded: no block of fetches was measured");
      return;
    }
    series.add(forks("L-9", "ms", boxed(f.p99NanosPerBlock(), 1e6)));
  }

  /** The L-11 series; returns the standby violations that the counter's watch saw. */
  private static long hotRow(
      MiscData in, List<Series> series, Map<String, Object> decisions, List<String> open) {
    if (in.hotRow().isEmpty()) {
      return 0;
    }
    HotRowCounter.Result hot = in.hotRow().get();
    for (HotRowCounter.Point p : hot.points()) {
      decisions.put("hotrow.w" + p.writers() + ".eventsPerSecond", dec3(p.eventsPerSecond()));
    }
    decisions.put("hotrow.bestWriters", (long) hot.bestWriters());
    decisions.put(
        "hotrow.requiredEventsPerSecond",
        dec3(HotRowCounter.requiredEventsPerSecond(3_470.0, 500.0, 3.0)));
    if (!hot.violations().isEmpty()) {
      open.add(
          "L-11 not recorded: the synchronous standby precondition was violated while the counter"
              + " ran: "
              + hot.violations());
    } else if (hot.windowRates().length > 0) {
      series.add(forks("L-11", "events/s", boxed(hot.windowRates(), 1.0)));
    }
    return hot.violations().size();
  }

  /** The knee and the matrix decisions; returns the knee N of COPY with four writers, or 0. */
  private static int matrix(MiscData in, Map<String, Object> decisions, List<String> open) {
    if (in.cells().isEmpty()) {
      return 0;
    }
    for (CommitMatrix.Cell c : in.cells()) {
      String key = "ledger.matrix." + c.method() + ".w" + c.writers() + ".n" + c.batchSize() + ".";
      decisions.put(key + "commitP99Ms", ms(c.p99CommitNanos()));
      decisions.put(key + "recordsPerSecond", c.recordsPerSecond());
      decisions.put(key + "valid", c.valid());
    }
    int knee = 0;
    for (Method method : Method.values()) {
      for (int writers : CommitMatrix.WRITERS) {
        List<CommitMatrix.Cell> group =
            in.cells().stream()
                .filter(c -> c.method() == method && c.writers() == writers)
                .toList();
        OptionalInt k = CommitMatrix.knee(group, KNEE_FIXED_SHARE);
        if (k.isPresent()) {
          decisions.put("ledger.matrix." + method + ".w" + writers + ".kneeN", (long) k.getAsInt());
          if (method == Method.COPY && writers == 4) {
            knee = k.getAsInt();
            int at = knee;
            group.stream()
                .filter(c -> c.batchSize() == at)
                .findFirst()
                .ifPresent(c -> decisions.put("ledger.kneeCommitP99Ms", ms(c.p99CommitNanos())));
          }
        }
      }
    }
    if (knee > 0) {
      decisions.put("ledger.kneeN", (long) knee);
    } else {
      open.add(
          "no knee found for COPY with four writers: the fixed part of the commit never fell to 10%"
              + " of the batch time at the sizes measured");
    }
    return knee;
  }

  private static void volume(EvidenceVolumeModel.Volume v, Map<String, Object> decisions) {
    decisions.put("volume.landingObjectsPerDay", v.landingObjectsPerDay());
    v.journalObjectsPerDay()
        .forEach(
            (type, count) ->
                decisions.put("volume.journal." + type, count.setScale(3, RoundingMode.HALF_EVEN)));
    decisions.put("volume.sealedFilesPerDay", v.sealedFilesPerDay());
    decisions.put("volume.enrichmentObjectsPerDay", v.enrichmentObjectsPerDay());
    decisions.put("volume.rawBytesPerDay", v.rawBytesPerDay());
    decisions.put("volume.objectsPerDay", v.objectsPerDay().setScale(3, RoundingMode.HALF_EVEN));
    decisions.put(
        "volume.averagePutPerSecond", v.averagePutPerSecond().setScale(3, RoundingMode.HALF_EVEN));
    decisions.put(
        "volume.burstPutPerSecond", v.burstPutPerSecond().setScale(3, RoundingMode.HALF_EVEN));
    decisions.put("volume.notes", v.notes());
  }

  // ------------------------------------------------------------------ the set

  /**
   * The measurement set of a part (or of the whole run), checked: a set with an extrapolated
   * criterion and no stated basis is refused.
   *
   * @param scope FULL or PARTIAL
   */
  public static MeasurementSet set(
      String configDigest,
      String scope,
      Output out,
      Map<Resource, List<BigDecimal>> canary,
      List<RunInfo> runs,
      Map<String, Object> source,
      Map<String, Object> env) {
    MeasurementSet set =
        new MeasurementSet(
            SPIKE,
            configDigest,
            scope,
            out.series(),
            canary,
            runs,
            source,
            env,
            out.decisions(),
            out.open());
    requireExtrapolationNote(set, Criteria.load());
    return set;
  }

  /**
   * The self-check of the runner (RF14): every set that holds a series of an EXTRAPOLATED criterion
   * states its basis in {@code decisions.extrapolationNote}.
   *
   * @throws IllegalStateException when the note is missing or blank
   */
  public static void requireExtrapolationNote(MeasurementSet set, Criteria criteria) {
    List<String> extrapolated = new ArrayList<>();
    for (Series s : set.series()) {
      boolean is =
          criteria.byId(s.criterionId()).map(c -> c.basis() == Basis.EXTRAPOLATED).orElse(false);
      if (is) {
        extrapolated.add(s.criterionId());
      }
    }
    if (extrapolated.isEmpty()) {
      return;
    }
    Object note = set.decisions().get("extrapolationNote");
    if (!(note instanceof String text) || text.isBlank()) {
      throw new IllegalStateException(
          "the criteria "
              + extrapolated
              + " are EXTRAPOLATED and the set states no basis: decisions.extrapolationNote is"
              + " missing (RF14)");
    }
  }
}
