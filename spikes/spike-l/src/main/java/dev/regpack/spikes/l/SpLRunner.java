package dev.regpack.spikes.l;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.proto.Canary;
import dev.regpack.bench.proto.EnvCapture;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.proto.RunDeadline;
import dev.regpack.bench.report.Git;
import dev.regpack.bench.stats.Stats;
import dev.regpack.spikes.l.LedgerEvaluator.ConsoleData;
import dev.regpack.spikes.l.LedgerEvaluator.FetchData;
import dev.regpack.spikes.l.LedgerEvaluator.IngestRuns;
import dev.regpack.spikes.l.LedgerEvaluator.MemoryPlan;
import dev.regpack.spikes.l.LedgerEvaluator.MiscData;
import dev.regpack.spikes.l.LedgerEvaluator.NamedRun;
import dev.regpack.spikes.l.LedgerEvaluator.Output;
import dev.regpack.spikes.l.LedgerEvaluator.RebuildEntry;
import dev.regpack.spikes.l.LedgerEvaluator.RestoreData;
import dev.regpack.spikes.l.LedgerEvaluator.RowSizeData;
import dev.regpack.spikes.l.load.BurstRunner;
import dev.regpack.spikes.l.load.BurstSampler;
import dev.regpack.spikes.l.load.LedgerLoadModel;
import dev.regpack.spikes.l.load.LedgerLoadModel.Candidate;
import dev.regpack.spikes.l.load.LedgerLoadModel.Params;
import dev.regpack.spikes.l.load.MicroBatchWriter;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.RunResult;
import dev.regpack.spikes.l.matrix.CommitMatrix;
import dev.regpack.spikes.l.matrix.HotRowCounter;
import dev.regpack.spikes.l.partition.PartitionPlan.Granularity;
import dev.regpack.spikes.l.partition.PartitionStudy;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.restore.DiskPlan;
import dev.regpack.spikes.l.restore.RebuildRun;
import dev.regpack.spikes.l.restore.RestoreRun;
import dev.regpack.spikes.l.restore.Shell;
import dev.regpack.spikes.l.schema.ConstraintChecks;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import dev.regpack.spikes.l.volume.EvidenceVolumeModel;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs SP-L against the fixture that {@code pg-up.sh} started on the runner: the plan of the part
 * (or of the three parts) one step after another, and after each finished step an atomic rewrite of
 * {@code measurements.json}, so that whatever exists when the run ends (the deadline, a failed
 * step) can be sealed by {@code bench publish}. The run never decides a verdict: it measures, and
 * {@link LedgerEvaluator} turns the studies into series.
 *
 * <p>The parts (one runner each, in parallel, one {@code MeasurementSet} each, the one {@code
 * configDigest} of the whole plan):
 *
 * <ul>
 *   <li><b>ingest</b>: the fixture baseline; A1 and A2 (20-minute open loop at 3,470 records/s);
 *       the closed-loop capacity run C1; B1 and B2, the runs of A again with the primary limited to
 *       the memory that gives the slice the day's table-to-memory ratio (L-1 to L-4, L-13);
 *   <li><b>restore</b>: cold-cache restore points at the sizes the disk holds, then the rebuild
 *       from evidence at the two smallest (L-6 to L-8);
 *   <li><b>misc</b>: the constraint checks, row size, partitions, the primary-key fetch under the
 *       burst, the hot-row counter, the commit matrix and its knee, the evidence volume and, with
 *       the stretch flag, the console load (L-5, L-9 to L-15).
 * </ul>
 *
 * <p>Every run of a study starts on a freshly wiped cluster ({@code pg-restart.sh}), except the
 * first, which starts on the cluster of {@code pg-up.sh}. The three canary probes (CPU, memory and
 * disk, the disk probe on the volume of the clusters) run before and after each measured run and
 * never during one; each part ends with at least five readings per resource.
 */
public final class SpLRunner {
  /** The three parts of a dispatch. */
  public enum Part {
    INGEST("ingest"),
    RESTORE("restore"),
    MISC("misc");

    private final String label;

    Part(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** The part with this label, if there is one. */
    public static Optional<Part> of(String label) {
      for (Part p : values()) {
        if (p.label.equals(label)) {
          return Optional.of(p);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * The sizes of a profile.
   *
   * @param sustainSeconds scheduled seconds of the open-loop runs A and B (1,200 by design)
   * @param closedSeconds seconds of the closed-loop run C1
   * @param batchSize the reference micro-batch N
   * @param rowSizeBatches batches of {@code batchSize} candidates loaded for the row size
   * @param daily the daily partition counts of the study
   * @param monthly the monthly partition count
   * @param populated populated days of the study
   * @param rowsPerDay rows per populated day
   * @param blocks blocks of lookups per series (L-9, L-10)
   * @param lookups lookups per block
   * @param fetchSeconds seconds of the burst under which the fetches are measured
   * @param hotWriters the writer counts of the hot row
   * @param hotSeconds seconds per writer count
   * @param hotWindows windows of 10 s of the L-11 series
   * @param matrixSizes the N of the commit matrix
   * @param matrixSeconds seconds per cell
   * @param consoleSeconds seconds of the console load (stretch)
   * @param restoreKeys the candidate sizes of the restore points
   * @param gapSeconds seconds of the gap ingest of a restore point
   * @param canary the length of one canary probe
   */
  public record Sizes(
      int sustainSeconds,
      int closedSeconds,
      int batchSize,
      int rowSizeBatches,
      List<Integer> daily,
      int monthly,
      int populated,
      int rowsPerDay,
      int blocks,
      int lookups,
      int fetchSeconds,
      List<Integer> hotWriters,
      int hotSeconds,
      int hotWindows,
      List<Integer> matrixSizes,
      int matrixSeconds,
      int consoleSeconds,
      List<Long> restoreKeys,
      int gapSeconds,
      Duration canary) {
    public Sizes {
      daily = List.copyOf(daily);
      hotWriters = List.copyOf(hotWriters);
      matrixSizes = List.copyOf(matrixSizes);
      restoreKeys = List.copyOf(restoreKeys);
    }
  }

  /** The profile: how large a run is. */
  public enum Profile {
    /** A wiring check of every part in minutes. It can only be INCONCLUSIVE. */
    SMOKE(
        "smoke",
        new Sizes(
            30,
            20,
            5_000,
            4,
            List.of(10, 30),
            3,
            10,
            1_000,
            5,
            50,
            20,
            List.of(1, 4),
            3,
            1,
            List.of(500, 2_000, 5_000),
            3,
            15,
            List.of(20_000L, 50_000L, 100_000L, 200_000L),
            20,
            Duration.ofMillis(50))),
    /** The measurement run of the plan. */
    FULL(
        "full",
        new Sizes(
            1_200,
            600,
            5_000,
            40,
            List.of(30, 365, 1_826),
            60,
            30,
            300_000,
            15,
            200,
            90,
            List.of(1, 2, 4, 8, 16),
            20,
            15,
            Arrays.stream(CommitMatrix.SIZES).boxed().toList(),
            30,
            120,
            Arrays.stream(DiskPlan.CANDIDATE_KEYS).boxed().toList(),
            300,
            Duration.ofSeconds(1)));

    private final String label;
    private final Sizes sizes;

    Profile(String label, Sizes sizes) {
      this.label = label;
      this.sizes = sizes;
    }

    public String label() {
      return label;
    }

    public Sizes sizes() {
      return sizes;
    }

    /** The profile with this label. */
    public static Profile of(String label) {
      for (Profile p : values()) {
        if (p.label.equals(label)) {
          return p;
        }
      }
      throw new IllegalArgumentException("unknown profile " + label + " (smoke or full)");
    }
  }

  /** What a step does. */
  public enum Role {
    BASELINE,
    SUSTAIN_A,
    CLOSED_LOOP,
    SUSTAIN_B,
    RESTORE_POINT,
    REBUILD,
    CONSTRAINTS,
    ROW_SIZE,
    PARTITIONS,
    FETCH,
    HOT_ROW,
    MATRIX,
    VOLUME,
    CONSOLE
  }

  /**
   * One step of the plan.
   *
   * @param work the size of the step in its own unit (seconds of load, keys, records), for the
   *     projection of a full run from a smoke run
   * @param canary the three probes run before and after the step
   * @param restart the step starts on a freshly wiped cluster, which costs a restart
   */
  public record Step(
      String id,
      Part part,
      Role role,
      List<String> criteria,
      long work,
      boolean canary,
      boolean restart) {
    public Step {
      criteria = List.copyOf(criteria);
    }
  }

  /** The steps of a run, in the order they are run. */
  public record Plan(Profile profile, boolean stretch, List<Step> steps) {
    public Plan {
      steps = List.copyOf(steps);
    }

    /** The steps of one part, in order. */
    public List<Step> steps(Part part) {
      return steps.stream().filter(s -> s.part() == part).toList();
    }

    /** The plan of one part only. */
    public Plan only(Part part) {
      return new Plan(profile, stretch, steps(part));
    }

    /** Every criterion that some step produces. */
    public List<String> criteria() {
      return steps.stream().flatMap(s -> s.criteria().stream()).distinct().sorted().toList();
    }

    /** Every criterion that the steps of a part produce. */
    public List<String> criteria(Part part) {
      return steps(part).stream().flatMap(s -> s.criteria().stream()).distinct().sorted().toList();
    }

    /** The canary readings per resource that the steps of a part take: before and after each. */
    public int canarySamplesPerResource(Part part) {
      return 2 * (int) steps(part).stream().filter(Step::canary).count();
    }
  }

  /**
   * The inputs of a run.
   *
   * @param part the part to run, empty for all three one after another
   * @param deadlineMinutes no step starts after this many minutes
   * @param out the result directory; {@code measurements.json} is written into it
   * @param env the environment (run id, commit)
   * @param repo the repository whose HEAD is recorded when not on a runner
   */
  public record Options(
      Profile profile,
      boolean stretch,
      Optional<Part> part,
      long deadlineMinutes,
      Path out,
      Map<String, String> env,
      Path repo) {}

  // the load of the plan
  private static final double RATE = 3_470.0;
  private static final int WRITERS = 4;
  private static final long LOAD_SEED = 20_261_020L;
  private static final long ROW_SIZE_SEED = 5L;

  /** Until the smoke dispatch has measured it: the bytes of a candidate with its indexes. */
  static final long BYTES_PER_CANDIDATE = 1_500L;

  private static final int LIVE_COPIES = 3;
  private static final int MIN_CANARY_SAMPLES = 5;
  private static final double WORST_FACTOR = 1.25;
  private static final double ASSUMED_SECONDS_PER_WORK = 0.01;
  static final long PROJECTION_LIMIT_PERCENT = 85;
  private static final int RESTORE_THREADS = 4;
  private static final String SCRATCH = "/mnt/spike-pg/scratch";
  // the image runs under en_US.utf8, so a value of 1,000 or more can carry a thousands separator
  private static final Pattern FSYNC_LINE =
      Pattern.compile("^\\s*([a-z_]+)\\s+[0-9][0-9.,]* ops/sec\\s+([0-9][0-9,]*) usecs/op\\s*$");
  private static final long BACKUP_INTERVAL_FALLBACK_HOURS = 24;

  /** The bytes of a row of the partition study until the row size has been measured. */
  static final long STUDY_BYTES_PER_ROW_FALLBACK = 500L;

  private static final long TWO_GIB = 2L << 30;
  private static final long SECOND_NANOS = 1_000_000_000L;
  private static final long CONSOLE_GRACE_NANOS = 30 * SECOND_NANOS;

  private final Options options;
  private final Plan plan;
  private final Criteria criteria;
  private final PgFixture pg;
  private final Shell shell;
  private final PrintStream out;
  private final RunDeadline deadline;
  private final NanoClock clock = NanoClock.system();
  private final List<Part> parts;

  // what has been measured so far
  private final Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
  private final Map<String, Object> env = new LinkedHashMap<>();
  private final List<String> notes = new ArrayList<>();
  private final Map<String, Long> stepSeconds = new LinkedHashMap<>();
  private final List<Long> restartSeconds = new ArrayList<>();
  private final Map<String, Object> extraDecisions = new LinkedHashMap<>();
  private boolean complete = true;
  private boolean fresh = true;
  private String currentOptions = "";

  private final List<NamedRun> sustainA = new ArrayList<>();
  private final List<NamedRun> sustainB = new ArrayList<>();
  private Optional<NamedRun> closed = Optional.empty();
  private long ingestViolations;
  // the bytes of a candidate as A1 measured them: they size the memory of run B and are reported;
  // the record caps use the constant, so that A1 and A2 are capped alike
  private long bytesPerCandidate = BYTES_PER_CANDIDATE;
  private boolean bytesPerCandidateMeasured;
  private Optional<MemoryPlan> memory = Optional.empty();

  private final List<RestoreRun.Outcome> restorePoints = new ArrayList<>();
  private final List<RebuildEntry> rebuilds = new ArrayList<>();
  private long bytesPerKey;

  private List<ConstraintChecks.CheckResult> checks = List.of();
  private Optional<RowSizeData> rowSizeE1 = Optional.empty();
  private Optional<RowSizeData> rowSizeE3 = Optional.empty();
  private List<PartitionStudy.Point> partitionPoints = List.of();
  private Optional<FetchData> fetch = Optional.empty();
  private Optional<HotRowCounter.Result> hotRow = Optional.empty();
  private List<CommitMatrix.Cell> cells = List.of();
  private Optional<EvidenceVolumeModel.Volume> volume = Optional.empty();
  private long miscViolations;
  private Optional<ConsoleData> console = Optional.empty();

  SpLRunner(Options options, PgFixture pg, Shell shell, PrintStream out) {
    this.options = options;
    this.plan = plan(options.profile(), options.stretch());
    this.criteria = Criteria.load();
    this.pg = pg;
    this.shell = shell;
    this.out = out;
    this.deadline = RunDeadline.afterMinutes(clock, options.deadlineMinutes());
    this.parts = options.part().map(List::of).orElse(List.of(Part.values()));
  }

  // ---------------------------------------------------------------- the plan

  /** The plan of a run; the same plan is run and, in a test, inspected. */
  public static Plan plan(Profile profile, boolean stretch) {
    Sizes z = profile.sizes();
    List<Step> steps = new ArrayList<>();

    // ingest: baseline, the cache-hot slice twice, the closed-loop capacity, the memory-limited
    // slice twice
    steps.add(new Step("baseline", Part.INGEST, Role.BASELINE, List.of(), 60, false, false));
    List<String> a = List.of("L-1", "L-2", "L-13");
    List<String> b = List.of("L-4", "L-13");
    steps.add(new Step("A1", Part.INGEST, Role.SUSTAIN_A, a, z.sustainSeconds(), true, false));
    steps.add(new Step("A2", Part.INGEST, Role.SUSTAIN_A, a, z.sustainSeconds(), true, true));
    steps.add(
        new Step(
            "C1",
            Part.INGEST,
            Role.CLOSED_LOOP,
            List.of("L-3", "L-13"),
            z.closedSeconds(),
            true,
            true));
    steps.add(new Step("B1", Part.INGEST, Role.SUSTAIN_B, b, z.sustainSeconds(), true, true));
    steps.add(new Step("B2", Part.INGEST, Role.SUSTAIN_B, b, z.sustainSeconds(), true, true));

    // restore: one point per candidate size that the disk holds, then the rebuild at the two
    // smallest
    long gapRecords = (long) (RATE * z.gapSeconds());
    boolean first = true;
    for (long keys : z.restoreKeys()) {
      steps.add(
          new Step(
              "restore-" + keys,
              Part.RESTORE,
              Role.RESTORE_POINT,
              List.of("L-6", "L-7"),
              keys + gapRecords,
              true,
              !first));
      first = false;
    }
    List<Long> smallest = z.restoreKeys().stream().sorted().limit(2).toList();
    first = true;
    for (long keys : smallest) {
      steps.add(
          new Step(
              "rebuild-" + keys, Part.RESTORE, Role.REBUILD, List.of("L-8"), keys, true, first));
      first = false;
    }

    // misc
    steps.add(
        new Step("constraints", Part.MISC, Role.CONSTRAINTS, List.of("L-12"), 1, false, false));
    steps.add(
        new Step(
            "rowsize",
            Part.MISC,
            Role.ROW_SIZE,
            List.of("L-5", "L-14"),
            2L * z.rowSizeBatches() * z.batchSize(),
            true,
            false));
    long partitionRows = (long) (z.daily().size() + 1) * z.populated() * z.rowsPerDay();
    steps.add(
        new Step(
            "partitions",
            Part.MISC,
            Role.PARTITIONS,
            List.of("L-10", "L-14"),
            partitionRows,
            true,
            true));
    steps.add(
        new Step(
            "fetch", Part.MISC, Role.FETCH, List.of("L-9", "L-13"), z.fetchSeconds(), true, true));
    steps.add(
        new Step(
            "hotrow",
            Part.MISC,
            Role.HOT_ROW,
            List.of("L-11", "L-13"),
            (long) z.hotWriters().size() * z.hotSeconds() + 10L * z.hotWindows(),
            true,
            true));
    steps.add(
        new Step(
            "matrix",
            Part.MISC,
            Role.MATRIX,
            List.of("L-13", "L-14"),
            2L * CommitMatrix.WRITERS.length * z.matrixSizes().size() * z.matrixSeconds(),
            true,
            true));
    steps.add(new Step("volume", Part.MISC, Role.VOLUME, List.of("L-14"), 1, false, false));
    if (stretch) {
      steps.add(
          new Step(
              "console", Part.MISC, Role.CONSOLE, List.of("L-15"), z.consoleSeconds(), true, true));
    }
    return new Plan(profile, stretch, steps);
  }

  /**
   * The digest of the configuration: the profile, the stretch flag and the plan of all three parts
   * (never the part that this run executes), so that the parts of one dispatch pool.
   */
  public static String configDigest(Plan plan) {
    Sizes z = plan.profile().sizes();
    Map<String, Object> planMap = new LinkedHashMap<>();
    planMap.put("seed", LOAD_SEED);
    planMap.put("rate", (long) RATE);
    planMap.put("writers", (long) WRITERS);
    planMap.put("bytesPerCandidate", BYTES_PER_CANDIDATE);
    planMap.put("liveCopies", (long) LIVE_COPIES);
    planMap.put("sustainSeconds", (long) z.sustainSeconds());
    planMap.put("closedSeconds", (long) z.closedSeconds());
    planMap.put("batchSize", (long) z.batchSize());
    planMap.put("rowSizeBatches", (long) z.rowSizeBatches());
    planMap.put("daily", z.daily().stream().map(Integer::longValue).toList());
    planMap.put("monthly", (long) z.monthly());
    planMap.put("populated", (long) z.populated());
    planMap.put("rowsPerDay", (long) z.rowsPerDay());
    planMap.put("blocks", (long) z.blocks());
    planMap.put("lookups", (long) z.lookups());
    planMap.put("fetchSeconds", (long) z.fetchSeconds());
    planMap.put("hotWriters", z.hotWriters().stream().map(Integer::longValue).toList());
    planMap.put("hotSeconds", (long) z.hotSeconds());
    planMap.put("hotWindows", (long) z.hotWindows());
    planMap.put("matrixSizes", z.matrixSizes().stream().map(Integer::longValue).toList());
    planMap.put("matrixSeconds", (long) z.matrixSeconds());
    planMap.put("consoleSeconds", (long) z.consoleSeconds());
    planMap.put("restoreKeys", z.restoreKeys());
    planMap.put("gapSeconds", (long) z.gapSeconds());
    planMap.put("canaryNanos", z.canary().toNanos());
    List<Object> steps = new ArrayList<>();
    for (Step step : plan.steps()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", step.id());
      m.put("stepPart", step.part().label());
      m.put("role", step.role().name());
      m.put("criteria", step.criteria());
      m.put("work", step.work());
      m.put("canary", step.canary());
      m.put("restart", step.restart());
      steps.add(m);
    }
    planMap.put("steps", steps);
    return MeasurementSet.configDigest(
        LedgerEvaluator.SPIKE,
        plan.profile().label(),
        plan.stretch(),
        Criteria.load().digest().hex(),
        planMap);
  }

  // ---------------------------------------------------------------- the run

  /**
   * Runs the plan and writes {@code measurements.json} into the result directory.
   *
   * @return 0, or 1 when no step recorded a single measurement (an unreachable PostgreSQL, every
   *     script failing): the file then holds notes only and the job must not look green. A step
   *     that fails among others is recorded as an open item and its criteria stay NOT_RUN
   */
  public static int run(Options options, PgFixture pg, Shell shell, PrintStream out)
      throws IOException, InterruptedException {
    return new SpLRunner(options, pg, shell, out).execute();
  }

  private int execute() throws IOException, InterruptedException {
    Files.createDirectories(options.out());
    out.println(
        "sp-l: "
            + plan.profile().label()
            + " profile, parts "
            + parts.stream().map(Part::label).toList()
            + ", deadline "
            + options.deadlineMinutes()
            + " minutes");
    captureEnvironment();
    boolean measured = false;
    for (Part part : parts) {
      for (Step step : plan.steps(part)) {
        if (deadline.passed()) {
          complete = false;
          notes.add(
              "the deadline of "
                  + options.deadlineMinutes()
                  + " minutes passed before step "
                  + step.id());
          continue;
        }
        long started = System.nanoTime();
        int before = resultCount();
        try {
          runStep(step);
        } catch (InterruptedException e) {
          throw e;
        } catch (IOException | SQLException | RuntimeException | LinkageError e) {
          // LinkageError: a class that cannot be linked (a driver or checker-qual missing on the
          // runner) fails the step, not the run, so that the other steps still record what they can
          complete = false;
          String message = e.getClass().getSimpleName() + ": " + e.getMessage();
          notes.add("step " + step.id() + " failed: " + message);
          out.println("sp-l: step " + step.id() + " failed: " + message);
        }
        measured |= resultCount() > before;
        long seconds = (System.nanoTime() - started) / SECOND_NANOS;
        stepSeconds.put(step.id(), seconds);
        out.println("sp-l: step " + step.id() + " done in " + seconds + " s");
        write();
      }
      topUpCanary();
    }
    if (!measured) {
      complete = false;
      notes.add(
          "nothing could be measured: no step recorded a result, so this file holds notes only (see"
              + " the failed steps)");
      out.println("sp-l: nothing could be measured: no step recorded a result");
    }
    write();
    if (options.profile() == Profile.SMOKE) {
      Plan full = plan(Profile.FULL, options.stretch());
      long restart = averageRestartSeconds();
      for (Part part : parts) {
        out.println(
            "sp-l: "
                + part.label()
                + " "
                + projection(
                    full.only(part),
                    plan.only(part),
                    stepSeconds,
                    restart,
                    deadlineMinutesOf(part)));
      }
    }
    return measured ? 0 : 1;
  }

  /**
   * The results that the studies have recorded so far, to tell a step that measured something from
   * one that failed or was not run. The fsync baseline and the evidence volume model are not
   * counted: neither needs a working ledger.
   */
  private int resultCount() {
    return sustainA.size()
        + sustainB.size()
        + (closed.isPresent() ? 1 : 0)
        + restorePoints.size()
        + rebuilds.size()
        + checks.size()
        + (rowSizeE1.isPresent() ? 1 : 0)
        + partitionPoints.size()
        + (fetch.isPresent() ? 1 : 0)
        + (hotRow.isPresent() ? 1 : 0)
        + cells.size()
        + (console.isPresent() ? 1 : 0);
  }

  /** The deadline of a part on the workflow: 305 minutes for ingest and restore, 215 for misc. */
  private long deadlineMinutesOf(Part part) {
    return options.deadlineMinutes() > 0
        ? options.deadlineMinutes()
        : (part == Part.MISC ? 215 : 305);
  }

  private long averageRestartSeconds() {
    return restartSeconds.isEmpty()
        ? 0
        : restartSeconds.stream().mapToLong(Long::longValue).sum() / restartSeconds.size();
  }

  private void runStep(Step step) throws IOException, SQLException, InterruptedException {
    switch (step.role()) {
      case BASELINE -> baseline();
      case SUSTAIN_A -> sustain(step, false);
      case SUSTAIN_B -> sustain(step, true);
      case CLOSED_LOOP -> closedLoop(step);
      case RESTORE_POINT -> restorePoint(step);
      case REBUILD -> rebuild(step);
      case CONSTRAINTS -> constraints();
      case ROW_SIZE -> rowSize();
      case PARTITIONS -> partitions();
      case FETCH -> fetchStep();
      case HOT_ROW -> hotRowStep();
      case MATRIX -> matrixStep();
      case VOLUME -> volumeStep();
      case CONSOLE -> consoleStep();
    }
  }

  // ---------------------------------------------------------------- the environment and the canary

  private void captureEnvironment() {
    env.putAll(EnvCapture.capture());
    try {
      pg.settings(
              "server_version",
              "shared_buffers",
              "max_wal_size",
              "checkpoint_timeout",
              "fsync",
              "full_page_writes",
              "synchronous_commit",
              "synchronous_standby_names",
              "wal_level",
              "archive_mode",
              "wal_sync_method",
              "max_connections")
          .forEach((name, value) -> env.put("pg." + name, value));
    } catch (SQLException e) {
      notes.add("the PostgreSQL settings could not be read: " + e.getMessage());
    }
  }

  private static Path scratchDir() {
    Path scratch = Path.of(SCRATCH);
    return Files.isDirectory(scratch) ? scratch : Path.of(System.getProperty("java.io.tmpdir"));
  }

  /** One reading of each of the three probes, on the volume of the clusters for the disk. */
  private void probeCanary() {
    Duration d = plan.profile().sizes().canary();
    Path file = scratchDir().resolve("sp-l-canary.dat");
    try {
      add(Resource.CPU, Canary.cpuMbPerSec(clock, d));
      add(Resource.MEMORY, Canary.memoryMbPerSec(clock, d));
      add(Resource.DISK, Canary.diskSyncOpsPerSec(clock, d, file));
    } catch (UncheckedIOException e) {
      notes.add("a canary probe failed: " + e.getMessage());
    } finally {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        notes.add("the canary scratch file could not be removed: " + e.getMessage());
      }
    }
  }

  private void add(Resource resource, double reading) {
    canary.computeIfAbsent(resource, r -> new ArrayList<>()).add(Stats.decimal(reading));
  }

  /** Each part carries at least {@value #MIN_CANARY_SAMPLES} readings per resource. */
  private void topUpCanary() {
    for (int i = 0; i < MIN_CANARY_SAMPLES; i++) {
      boolean enough =
          Arrays.stream(Resource.values())
              .allMatch(r -> canary.getOrDefault(r, List.of()).size() >= MIN_CANARY_SAMPLES);
      if (enough) {
        return;
      }
      probeCanary();
    }
  }

  // ---------------------------------------------------------------- the fixture

  /**
   * The arguments of {@code pg-restart.sh}: the mode of the part, and for run B the memory limit
   * and {@code shared_buffers} of the day's table-to-memory ratio. A runner that forgot the flags
   * would run B like A and pass L-4 falsely (RF14).
   */
  static List<String> restartArgs(Part part, Optional<MemoryPlan> limited) {
    List<String> args = new ArrayList<>();
    args.add("sp-l-" + part.label());
    limited.ifPresent(
        m -> {
          args.add("--memory=" + m.dockerMemory());
          args.add("--shared-buffers=" + m.sharedBuffers());
        });
    return List.copyOf(args);
  }

  /**
   * Makes sure that the cluster is freshly initialised with the given options: a restart that wipes
   * the data directories ({@code pg-restart.sh}) unless the cluster is fresh already.
   */
  void freshCluster(Part part, Optional<MemoryPlan> limited)
      throws IOException, InterruptedException {
    List<String> args = restartArgs(part, limited);
    // the options after the mode: nothing for an unlimited cluster, which is what a fresh start has
    String wanted = String.join(" ", args.subList(1, args.size()));
    if (fresh && wanted.equals(currentOptions)) {
      return;
    }
    long started = System.nanoTime();
    shell.script("pg-restart.sh", args.toArray(new String[0]));
    restartSeconds.add((System.nanoTime() - started) / 1_000_000_000L);
    fresh = true;
    currentOptions = wanted;
    if (limited.isPresent()) {
      try {
        pg.settings("shared_buffers", "max_wal_size")
            .forEach((name, value) -> env.put("pg.runB." + name, value));
      } catch (SQLException e) {
        notes.add("the settings of the limited cluster could not be read: " + e.getMessage());
      }
    }
  }

  private static long usableBytes() {
    try {
      return Files.getFileStore(BurstSampler.dataDisk()).getUsableSpace();
    } catch (IOException e) {
      return Long.MAX_VALUE;
    }
  }

  /**
   * The largest record cap whose copies fit the disk: {@code DiskPlan}'s formula with three live
   * copies and the archived WAL (about the size of the data) as the reserve, tried at multiples of
   * twenty batches and at the scheduled load itself. Zero when not even the smallest fits.
   */
  static long maxRecords(long freeBytes, long bytesPerCandidate, long scheduledRecords, int batch) {
    long step = 20L * batch;
    List<Long> candidates = new ArrayList<>();
    for (long k = step; k < scheduledRecords; k += step) {
      candidates.add(k);
    }
    candidates.add(scheduledRecords);
    long best = 0;
    for (long k : candidates) {
      long reserve = Math.multiplyExact(k, bytesPerCandidate);
      if (DiskPlan.choose(freeBytes, bytesPerCandidate, LIVE_COPIES, reserve, new long[] {k}).length
          == 1) {
        best = k;
      }
    }
    return best;
  }

  private long scheduledRecords(int seconds) {
    long batches = (long) Math.floor(RATE * seconds / plan.profile().sizes().batchSize());
    return batches * plan.profile().sizes().batchSize();
  }

  // ---------------------------------------------------------------- the baseline

  /** The first block of {@code pg_test_fsync}: microseconds per operation by method. */
  static Map<String, Object> parseFsync(List<String> lines) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String line : lines) {
      Matcher m = FSYNC_LINE.matcher(line);
      if (m.matches()) {
        // the first block (one 8 kB write) counts; the later blocks repeat the methods
        out.putIfAbsent(
            "pgTestFsync." + m.group(1) + ".usecsPerOp",
            Long.parseLong(m.group(2).replace(",", "")));
      }
    }
    return out;
  }

  private void baseline() throws InterruptedException, SQLException {
    try {
      Map<String, Object> fsync = parseFsync(shell.script("pg-baseline.sh"));
      if (fsync.isEmpty()) {
        notes.add(
            "the pg_test_fsync baseline printed no line that could be read: the fsync figure that"
                + " the report prints beside L-2 and L-11 is missing");
      }
      env.putAll(fsync);
    } catch (IOException e) {
      notes.add("the pg_test_fsync baseline is unavailable: " + e.getMessage());
    }
    long[] nanos = new long[1_000];
    try (Connection c = pg.primary();
        Statement st = c.createStatement()) {
      c.setAutoCommit(true);
      for (int i = 0; i < nanos.length + 100; i++) {
        long t0 = System.nanoTime();
        try (ResultSet rs = st.executeQuery("SELECT 1")) {
          rs.next();
        }
        if (i >= 100) {
          nanos[i - 100] = System.nanoTime() - t0;
        }
      }
    }
    double[] values = Arrays.stream(nanos).asDoubleStream().toArray();
    env.put("pg.loopbackRoundTripMicros.p50", Math.round(Stats.median(values) / 1e3));
    env.put("pg.loopbackRoundTripMicros.p99", Math.round(Stats.percentile(values, 99) / 1e3));
  }

  // ---------------------------------------------------------------- ingest

  /** A burst run with a sampler of its own, so that the violating samples can be counted. */
  private record Sampled(RunResult result, long violatingSamples) {}

  private Sampled sampledRun(BurstRunner.Config config, TradeRecordGen gen) throws SQLException {
    LocalDate day = new TradeRecordGen(1).next().tradeDate();
    try (Connection c = pg.primary()) {
      SchemaInstaller.drop(c, BurstRunner.SCHEMA);
      SchemaInstaller.install(c, BurstRunner.SCHEMA, config.variant(), day, 1);
    }
    try (BurstSampler.Source source = BurstSampler.pgSource(pg, BurstSampler.dataDisk())) {
      BurstSampler sampler = new BurstSampler(source, clock);
      Thread sampling = Thread.ofPlatform().daemon().name("sp-l-sampler").start(sampler);
      try {
        RunResult result =
            BurstRunner.runInto(pg, BurstRunner.SCHEMA, day, config, gen, clock, sampler);
        return new Sampled(result, violatingSamples(sampler));
      } finally {
        sampler.stop();
        try {
          sampling.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static long violatingSamples(BurstSampler sampler) {
    long withViolation = sampler.rows().stream().filter(row -> !row.violations().isEmpty()).count();
    // counted by the sampler: its reasons are capped, so a long outage would be under-counted
    return withViolation + sampler.sampleFailures();
  }

  /**
   * The load of runs A and B: open loop at the design rate, N 5,000, four writers, COPY, the
   * compact encoding. B differs from A only in the memory limit of its cluster (RF14).
   */
  static BurstRunner.Config sustainConfig(Sizes z, long cap) {
    return openLoopConfig(z.sustainSeconds(), z.batchSize(), cap);
  }

  /** The load of C1: the same writers, as fast as they can go (closed loop). */
  static BurstRunner.Config closedLoopConfig(Sizes z, long cap) {
    return new BurstRunner.Config(
        Duration.ofSeconds(z.closedSeconds()),
        cap,
        0.0,
        z.batchSize(),
        WRITERS,
        Method.COPY,
        Variant.E1_COMPACT,
        true);
  }

  private static BurstRunner.Config openLoopConfig(int seconds, int batchSize, long cap) {
    return new BurstRunner.Config(
        Duration.ofSeconds(seconds),
        cap,
        RATE,
        batchSize,
        WRITERS,
        Method.COPY,
        Variant.E1_COMPACT,
        false);
  }

  private void sustain(Step step, boolean limited)
      throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    Optional<MemoryPlan> memoryLimit = Optional.empty();
    if (limited) {
      memoryLimit = Optional.of(memoryPlan());
      memory = memoryLimit;
    }
    freshCluster(Part.INGEST, memoryLimit);
    long scheduled = scheduledRecords(z.sustainSeconds());
    long cap = maxRecords(usableBytes(), BYTES_PER_CANDIDATE, scheduled, z.batchSize());
    if (cap == 0) {
      notes.add("step " + step.id() + " not run: the disk of the runner cannot hold the slice");
      return;
    }
    BurstRunner.Config config = sustainConfig(z, cap);
    probeCanary();
    fresh = false;
    Sampled sampled = sampledRun(config, new TradeRecordGen(LOAD_SEED));
    probeCanary();
    NamedRun run = new NamedRun(step.id(), sampled.result());
    (limited ? sustainB : sustainA).add(run);
    ingestViolations += sampled.violatingSamples();
    if (step.id().equals("A1") && sampled.result().records() > 0) {
      try (Connection c = pg.primary()) {
        bytesPerCandidate =
            Math.max(1, RestoreRun.bytesPerKey(c, BurstRunner.SCHEMA, sampled.result().records()));
        bytesPerCandidateMeasured = true;
      }
    }
  }

  /** The memory of run B: the slice of A1 (or the design) at the measured bytes per candidate. */
  private MemoryPlan memoryPlan() {
    long slice =
        sustainA.stream()
            .map(r -> r.result().scheduledRecords())
            .filter(n -> n > 0)
            .findFirst()
            .orElse(scheduledRecords(plan.profile().sizes().sustainSeconds()));
    return MemoryPlan.of(slice, bytesPerCandidate);
  }

  private void closedLoop(Step step) throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    freshCluster(Part.INGEST, Optional.empty());
    // the closed loop writes as fast as it can: the cap is what the disk holds of five slices
    long scheduled = 5 * scheduledRecords(z.closedSeconds());
    long cap = maxRecords(usableBytes(), BYTES_PER_CANDIDATE, scheduled, z.batchSize());
    if (cap == 0) {
      notes.add("step " + step.id() + " not run: the disk of the runner cannot hold the slice");
      return;
    }
    BurstRunner.Config config = closedLoopConfig(z, cap);
    probeCanary();
    fresh = false;
    Sampled sampled = sampledRun(config, new TradeRecordGen(LOAD_SEED));
    probeCanary();
    closed = Optional.of(new NamedRun(step.id(), sampled.result()));
    ingestViolations += sampled.violatingSamples();
  }

  // ---------------------------------------------------------------- restore and rebuild

  private void restorePoint(Step step) throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    long keys = Long.parseLong(step.id().substring("restore-".length()));
    freshCluster(Part.RESTORE, Optional.empty());
    if (bytesPerKey > 0) {
      long gapRecords = (long) (RestoreRun.GAP_RECORDS_PER_SECOND * z.gapSeconds());
      long reserve = gapRecords * BYTES_PER_CANDIDATE * 2 + (256L << 20);
      long[] fits =
          DiskPlan.choose(usableBytes(), bytesPerKey, LIVE_COPIES, reserve, new long[] {keys});
      if (fits.length == 0) {
        notes.add(
            "restore at "
                + keys
                + " keys is NOT_RUN: "
                + (LIVE_COPIES + 2)
                + " copies of "
                + bytesPerKey
                + " bytes a key do not fit the free disk");
        return;
      }
    }
    probeCanary();
    fresh = false;
    RestoreRun.Outcome outcome =
        RestoreRun.runWithGap(pg, keys, Duration.ofSeconds(z.gapSeconds()));
    probeCanary();
    recordRestore(outcome);
  }

  /**
   * Keeps a restore point and, from the first one, the bytes per key that size the later points.
   * The figure is the one measured on the loaded keys before the gap (see {@link
   * RestoreRun.Outcome#bytesPerKey}): measured afterwards, the gap ingest of 300 s at the burst
   * rate would inflate it about three times at 500,000 keys, so that a 14 GiB runner would select
   * only the smallest size and L-6 and L-7 would be NOT_RUN.
   */
  void recordRestore(RestoreRun.Outcome outcome) {
    restorePoints.add(outcome);
    if (bytesPerKey == 0 && outcome.bytesPerKey() > 0) {
      bytesPerKey = outcome.bytesPerKey();
    }
  }

  /** The bytes per key that size the restore points after the first; zero before it has run. */
  long bytesPerKey() {
    return bytesPerKey;
  }

  private void rebuild(Step step) throws IOException, SQLException, InterruptedException {
    long keys = Long.parseLong(step.id().substring("rebuild-".length()));
    boolean measured = restorePoints.stream().anyMatch(o -> o.point().keys() == keys);
    if (!measured) {
      notes.add("rebuild at " + keys + " keys is NOT_RUN: no restore point of that size ran");
      return;
    }
    // after a restore point the standby and the replica are gone: start fresh for a rebuild with
    // the synchronous standby of the design
    if (!restorePoints.isEmpty() && rebuilds.isEmpty()) {
      fresh = false;
      freshCluster(Part.RESTORE, Optional.empty());
    }
    Path landing = scratchDir().resolve("landing-" + keys);
    probeCanary();
    fresh = false;
    try {
      RebuildRun.Result result = RebuildRun.run(pg, landing, keys, RESTORE_THREADS);
      boolean equal = result.content().equals(RebuildRun.directContent(keys));
      rebuilds.add(new RebuildEntry(result, equal));
    } finally {
      deleteTree(landing);
      try (Connection c = pg.primary()) {
        SchemaInstaller.drop(c, RebuildRun.SCHEMA);
      } catch (SQLException e) {
        notes.add("the rebuild schema could not be dropped: " + e.getMessage());
      }
    }
    probeCanary();
  }

  private static void deleteTree(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException | UncheckedIOException e) {
      // a leftover under the scratch directory is removed by the next restart of the runner
    }
  }

  // ---------------------------------------------------------------- misc

  private void constraints() throws SQLException {
    checks = ConstraintChecks.runAll(pg);
    for (ConstraintChecks.CheckResult check : checks) {
      out.println(
          "sp-l: constraint check "
              + check.name()
              + (check.passed() ? " passed" : " FAILED: " + check.detail()));
    }
  }

  private void rowSize() throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    freshCluster(Part.MISC, Optional.empty());
    probeCanary();
    fresh = false;
    rowSizeE1 = Optional.of(measureRowSize(Variant.E1_COMPACT, "sp_rowsize_e1", z));
    rowSizeE3 = Optional.of(measureRowSize(Variant.E3_VERBOSE, "sp_rowsize_e3", z));
    probeCanary();
  }

  private RowSizeData measureRowSize(Variant variant, String schema, Sizes z) throws SQLException {
    LocalDate day = new TradeRecordGen(1).next().tradeDate();
    try (Connection c = pg.primary()) {
      SchemaInstaller.drop(c, schema);
      SchemaInstaller.install(c, schema, variant, day, 1);
    }
    Params params = new Params(z.batchSize(), 0.5, 1.5, 0.02, 99);
    TradeRecordGen gen = new TradeRecordGen(ROW_SIZE_SEED);
    SplitMix64 rng = new SplitMix64(6);
    List<String> existing = new ArrayList<>();
    try (Connection c = pg.primary()) {
      MicroBatchWriter writer = new MicroBatchWriter(c, schema, Method.COPY, variant);
      for (int b = 1; b <= z.rowSizeBatches(); b++) {
        List<Candidate> batch = LedgerLoadModel.nextBatch(params, gen, rng, existing);
        writer.write(b, batch);
        for (Candidate candidate : batch) {
          if (candidate.report() && !candidate.superseding()) {
            existing.add(candidate.eventIdentity());
          }
        }
      }
    }
    String qualified = "\"" + schema + "\".decision";
    try (Connection c = pg.primary();
        Statement st = c.createStatement()) {
      c.setAutoCommit(true);
      List<String> leaves = new ArrayList<>();
      try (ResultSet rs =
          st.executeQuery(
              "SELECT relid::regclass::text FROM pg_partition_tree('"
                  + qualified
                  + "'::regclass) WHERE isleaf")) {
        while (rs.next()) {
          leaves.add(rs.getString(1));
        }
      }
      for (String leaf : leaves) {
        st.execute("VACUUM (ANALYZE) " + leaf);
      }
      long rows;
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + qualified)) {
        rs.next();
        rows = rs.getLong(1);
      }
      long heap;
      long index;
      try (ResultSet rs =
          st.executeQuery(
              "SELECT COALESCE(sum(pg_table_size(relid)), 0),"
                  + " COALESCE(sum(pg_indexes_size(relid)), 0)"
                  + " FROM pg_partition_tree('"
                  + qualified
                  + "'::regclass) WHERE isleaf")) {
        rs.next();
        heap = rs.getLong(1);
        index = rs.getLong(2);
      }
      long headRows;
      try (ResultSet rs =
          st.executeQuery("SELECT count(*) FROM \"" + schema + "\".decision_head")) {
        rs.next();
        headRows = rs.getLong(1);
      }
      Map<String, Long> tables = new LinkedHashMap<>();
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT regexp_replace(c.relname, '_[0-9]{8}$', ''),"
                  + " sum(pg_total_relation_size(c.oid))::bigint"
                  + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                  + " WHERE n.nspname = ? AND c.relkind = 'r' GROUP BY 1 ORDER BY 1")) {
        ps.setString(1, schema);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            tables.put(rs.getString(1), rs.getLong(2));
          }
        }
      }
      RowSizeData data = new RowSizeData(variant.name(), rows, heap, index, headRows, tables);
      SchemaInstaller.drop(c, schema);
      return data;
    }
  }

  /**
   * The bytes of a row of the partition study, from the measured E1 row size: the decision rows and
   * the head rows with their indexes over the decision rows, rounded up. A conservative constant
   * until the row size has been measured.
   */
  static long studyBytesPerRow(Optional<RowSizeData> e1) {
    return e1.filter(d -> d.rows() > 0 && d.tableBytes().containsKey("decision"))
        .map(
            d ->
                ceilDiv(
                    d.tableBytes().get("decision")
                        + d.tableBytes().getOrDefault("decision_head", 0L),
                    d.rows()))
        .map(bytes -> Math.max(1, bytes))
        .orElse(STUDY_BYTES_PER_ROW_FALLBACK);
  }

  private static long ceilDiv(long a, long b) {
    return Math.floorDiv(a + b - 1, b);
  }

  /**
   * The largest rows per populated day, of the requested number and then three quarters, a half, a
   * quarter and an eighth of it, that one point of the study can write to the disk: {@code
   * DiskPlan}'s formula with the primary and the archived WAL as the two copies (the standby and
   * the replica are dropped for the study) and the live WAL at {@code max_wal_size} as the reserve.
   * Zero when not even an eighth fits.
   */
  static long fittingRowsPerDay(long freeBytes, long bytesPerRow, int populated, int rowsPerDay) {
    for (int eighths : new int[] {8, 6, 4, 2, 1}) {
      long rows = rowsPerDay * (long) eighths / 8;
      if (rows > 0
          && DiskPlan.choose(freeBytes, bytesPerRow, 0, TWO_GIB, new long[] {populated * rows})
                  .length
              == 1) {
        return rows;
      }
    }
    return 0;
  }

  /** The disk is full or the file system failed: an environment fault, not a result. */
  private static boolean diskFault(Throwable e) {
    if (e instanceof SQLException sql
        && ("53100".equals(sql.getSQLState()) || "58030".equals(sql.getSQLState()))) {
      return true;
    }
    return String.valueOf(e.getMessage()).contains("No space left on device")
        || Optional.ofNullable(e.getCause()).map(SpLRunner::diskFault).orElse(false);
  }

  /**
   * The partition study, one point at a time. Every point starts on a freshly wiped cluster (the
   * restart also empties the WAL archive, which would otherwise keep every point's WAL: about as
   * much as the data), without the standby and the replica, which the study does not need and which
   * would hold two more copies; a point whose rows do not fit the free disk is reduced and the
   * reduction is recorded, and a point that fails is a note, not the loss of the others (a full
   * disk would stop the primary and L-10, a required criterion, with it).
   */
  private void partitions() throws IOException, InterruptedException {
    Sizes z = plan.profile().sizes();
    long bytesPerRow = studyBytesPerRow(rowSizeE1);
    List<PartitionStudy.Point> points = new ArrayList<>();
    partitionPoints = points;
    extraDecisions.put("partitions.bytesPerRowAssumed", bytesPerRow);
    extraDecisions.put(
        "partitions.standby",
        "dropped for the study (pg-standby.sh drop): planner and probe overhead need no standby");
    List<Granularity> granularities = new ArrayList<>();
    List<Integer> counts = new ArrayList<>();
    for (int daily : z.daily()) {
      granularities.add(Granularity.DAILY);
      counts.add(daily);
    }
    granularities.add(Granularity.MONTHLY);
    counts.add(z.monthly());
    for (int i = 0; i < counts.size(); i++) {
      Granularity granularity = granularities.get(i);
      int count = counts.get(i);
      String name = granularity.name().toLowerCase(Locale.ROOT) + count;
      fresh = false;
      freshCluster(Part.MISC, Optional.empty());
      shell.script("pg-standby.sh", "drop");
      fresh = false;
      long rows = fittingRowsPerDay(usableBytes(), bytesPerRow, z.populated(), z.rowsPerDay());
      if (rows == 0) {
        notes.add(
            "DISK: partition point "
                + name
                + " not run: not even an eighth of "
                + z.populated()
                + " x "
                + z.rowsPerDay()
                + " rows of "
                + bytesPerRow
                + " bytes fits the free disk");
        continue;
      }
      if (rows < z.rowsPerDay()) {
        notes.add(
            "DISK: partition point "
                + name
                + " ran with "
                + rows
                + " rows per populated day instead of "
                + z.rowsPerDay()
                + ": the free disk did not hold more (primary and archived WAL at "
                + bytesPerRow
                + " bytes a row)");
      }
      extraDecisions.put("partitions." + name + ".rowsPerPopulatedDay", rows);
      probeCanary();
      try {
        points.add(
            PartitionStudy.runPoint(
                pg, granularity, count, z.populated(), (int) rows, z.blocks(), z.lookups()));
      } catch (SQLException | RuntimeException e) {
        notes.add(
            (diskFault(e) ? "DISK: " : "")
                + "partition point "
                + name
                + (diskFault(e) ? " stopped by the disk of the runner: " : " failed: ")
                + e.getClass().getSimpleName()
                + ": "
                + e.getMessage());
      }
      probeCanary();
    }
  }

  // the primary-key fetch under the burst (L-9): a head lookup and the fetch of the decision it
  // names, while an open-loop burst at 3,470 records/s runs in the background. The blocks are
  // spread over the life of the burst after a warm-up, each on a fresh sample of the heads written
  // so far, so that the last blocks see the tables, the WAL and the checkpoints of a burst that has
  // run for most of its length and not the cache-warm first seconds (RF1).
  private static final String HEAD_SQL =
      "SELECT decision_id FROM \""
          + BurstRunner.SCHEMA
          + "\".decision_head WHERE regime = '"
          + MicroBatchWriter.REGIME
          + "' AND event_identity = ?";
  private static final String FETCH_SQL =
      "SELECT decision_seq, outcome, body FROM \""
          + BurstRunner.SCHEMA
          + "\".decision WHERE decision_id = ? AND as_at = ?";
  // the salt changes the order, so every block draws a fresh sample
  private static final String HEAD_KEYS_SQL =
      "SELECT event_identity FROM \""
          + BurstRunner.SCHEMA
          + "\".decision_head WHERE regime = '"
          + MicroBatchWriter.REGIME
          + "' ORDER BY md5(event_identity || ?) LIMIT ?";
  private static final String ROWS_SQL =
      "SELECT count(*) FROM \"" + BurstRunner.SCHEMA + "\".decision";
  private static final long KEY_WAIT_SECONDS = 90;

  /** The background burst of a study: its result and the failure that ended it, if any. */
  private final class Background {
    private final AtomicReference<Optional<Sampled>> result =
        new AtomicReference<>(Optional.empty());
    private final AtomicReference<Optional<Throwable>> failure =
        new AtomicReference<>(Optional.empty());
    private final Thread thread;

    Background(BurstRunner.Config config) {
      this.thread =
          Thread.ofPlatform()
              .daemon()
              .name("sp-l-background-burst")
              .unstarted(
                  () -> {
                    try {
                      result.set(Optional.of(sampledRun(config, new TradeRecordGen(LOAD_SEED))));
                    } catch (SQLException | RuntimeException e) {
                      failure.set(Optional.of(e));
                    }
                  });
    }

    void start() {
      thread.start();
    }

    boolean running() {
      return thread.isAlive();
    }

    /** The records that the burst wrote; zero while it has no result. */
    long records() {
      return result.get().map(sampled -> sampled.result().records()).orElse(0L);
    }

    /** Waits for the burst to end; the violating samples are counted into the misc part. */
    void finish(long seconds) throws InterruptedException {
      thread.join(Duration.ofSeconds(seconds + 300));
      result.get().ifPresent(sampled -> miscViolations += sampled.violatingSamples());
      failure
          .get()
          .ifPresent(
              t ->
                  notes.add(
                      "the background burst failed: "
                          + t.getClass().getSimpleName()
                          + ": "
                          + t.getMessage()));
    }
  }

  private BurstRunner.Config backgroundBurst(int seconds) {
    Sizes z = plan.profile().sizes();
    long scheduled = scheduledRecords(seconds);
    long cap = maxRecords(usableBytes(), BYTES_PER_CANDIDATE, scheduled, z.batchSize());
    return openLoopConfig(seconds, z.batchSize(), Math.max(cap, z.batchSize()));
  }

  /**
   * When each block of the fetch study starts, in nanoseconds after the burst was started: a
   * warm-up (a tenth of the burst, at least 2 s) first, then equally spaced blocks over the rest,
   * leaving a tail (a fifteenth, at least 2 s) so that the last block ends before the burst does.
   * For the 90 s burst of the full profile the 15 blocks start at 9 s, 14 s, ... 79 s.
   *
   * @throws IllegalArgumentException when the burst is too short for the blocks
   */
  static long[] fetchBlockOffsets(int fetchSeconds, int blocks) {
    long warmup = Math.max(2, fetchSeconds / 10);
    long tail = Math.max(2, fetchSeconds / 15);
    long usable = fetchSeconds - warmup - tail;
    if (blocks <= 0 || usable < blocks) {
      throw new IllegalArgumentException(
          "a burst of " + fetchSeconds + " s is too short for " + blocks + " blocks of fetches");
    }
    long spacing = usable * SECOND_NANOS / blocks;
    long[] offsets = new long[blocks];
    for (int b = 0; b < blocks; b++) {
      offsets[b] = warmup * SECOND_NANOS + b * spacing;
    }
    return offsets;
  }

  /** The p99 of each block, and whether the burst was still running when the last one ended. */
  private record FetchBlocks(double[] p99NanosPerBlock, boolean throughout) {}

  private void fetchStep() throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    freshCluster(Part.MISC, Optional.empty());
    probeCanary();
    fresh = false;
    long[] offsets = fetchBlockOffsets(z.fetchSeconds(), z.blocks());
    Background burst = new Background(backgroundBurst(z.fetchSeconds()));
    LocalDate day = new TradeRecordGen(1).next().tradeDate();
    FetchBlocks measured = new FetchBlocks(new double[0], false);
    long started = System.nanoTime();
    burst.start();
    try (Connection c = pg.primary()) {
      c.setAutoCommit(true);
      measured = measureFetchBlocks(c, burst, z, offsets, started, day);
    } finally {
      burst.finish(z.fetchSeconds());
    }
    probeCanary();
    extraDecisions.put(
        "fetch.schedule",
        "warm-up "
            + offsets[0] / SECOND_NANOS
            + " s, then "
            + z.blocks()
            + " blocks of "
            + z.lookups()
            + " lookups every "
            + (offsets.length > 1 ? (offsets[1] - offsets[0]) / SECOND_NANOS : 0)
            + " s over a burst of "
            + z.fetchSeconds()
            + " s, each on a fresh sample of the heads written so far");
    fetch =
        Optional.of(
            new FetchData(measured.p99NanosPerBlock(), measured.throughout(), burst.records()));
  }

  private FetchBlocks measureFetchBlocks(
      Connection c, Background burst, Sizes z, long[] offsets, long started, LocalDate day)
      throws SQLException, InterruptedException {
    FetchBlocks failed = new FetchBlocks(new double[0], false);
    List<String> warmKeys = waitForKeys(c, z.lookups(), burst);
    if (warmKeys.size() < z.lookups()) {
      notes.add(
          "L-9 not measured: the burst wrote fewer than "
              + z.lookups()
              + " heads in "
              + KEY_WAIT_SECONDS
              + " s, or ended first");
      return failed;
    }
    long[] nanos = new long[z.blocks() * z.lookups()];
    try (PreparedStatement head = c.prepareStatement(HEAD_SQL);
        PreparedStatement fetch = c.prepareStatement(FETCH_SQL)) {
      // the warm-up: statements, plans and the first pages, not measured
      lookupBlock(head, fetch, warmKeys, day);
      for (int b = 0; b < z.blocks(); b++) {
        sleepUntil(started + offsets[b]);
        if (!burst.running()) {
          notes.add("L-9 not measured under the burst: the burst ended before block " + (b + 1));
          return failed;
        }
        List<String> keys = sampleKeys(c, z.lookups(), "block-" + b);
        if (keys.size() < z.lookups()) {
          notes.add("L-9 not measured: block " + (b + 1) + " found only " + keys.size() + " heads");
          return failed;
        }
        if (b == 0 || b == z.blocks() - 1) {
          // the table size that the block saw, for the report
          extraDecisions.put(
              b == 0 ? "fetch.decisionRows.firstBlock" : "fetch.decisionRows.lastBlock",
              decisionRows(c));
        }
        long[] took = lookupBlock(head, fetch, keys, day);
        System.arraycopy(took, 0, nanos, b * z.lookups(), z.lookups());
      }
    }
    return new FetchBlocks(blockP99(nanos, z.blocks(), z.lookups()), burst.running());
  }

  private static void sleepUntil(long deadlineNanos) throws InterruptedException {
    long wait = deadlineNanos - System.nanoTime();
    if (wait > 0) {
      Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
    }
  }

  private static long decisionRows(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(ROWS_SQL)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** The time of each head lookup and fetch of the decision it names. */
  private static long[] lookupBlock(
      PreparedStatement head, PreparedStatement fetch, List<String> keys, LocalDate day)
      throws SQLException {
    long[] nanos = new long[keys.size()];
    for (int i = 0; i < nanos.length; i++) {
      long t0 = System.nanoTime();
      head.setString(1, keys.get(i));
      long decisionId;
      try (ResultSet rs = head.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("no head for " + keys.get(i));
        }
        decisionId = rs.getLong(1);
      }
      fetch.setLong(1, decisionId);
      fetch.setObject(2, day);
      try (ResultSet rs = fetch.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("no decision " + decisionId);
        }
        rs.getLong(1);
        rs.getBytes(3);
      }
      nanos[i] = System.nanoTime() - t0;
    }
    return nanos;
  }

  /** {@code n} of the heads that the burst has written so far, in an order set by the salt. */
  private static List<String> sampleKeys(Connection c, int n, String salt) throws SQLException {
    List<String> keys = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(HEAD_KEYS_SQL)) {
      ps.setString(1, salt);
      ps.setInt(2, n);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          keys.add(rs.getString(1));
        }
      }
    }
    return keys;
  }

  /**
   * Waits until the burst has written {@code needed} heads; fewer when it ends or time runs out.
   */
  private List<String> waitForKeys(Connection c, int needed, Background burst)
      throws InterruptedException {
    long until = System.nanoTime() + KEY_WAIT_SECONDS * SECOND_NANOS;
    List<String> keys = List.of();
    while (System.nanoTime() < until && burst.running()) {
      try {
        keys = sampleKeys(c, needed, "warm-up");
      } catch (SQLException e) {
        keys = List.of(); // the schema of the burst is not installed yet
      }
      if (keys.size() >= needed) {
        return keys;
      }
      Thread.sleep(1_000);
    }
    return keys;
  }

  /** The nearest-rank p99 of each block of consecutive samples. */
  static double[] blockP99(long[] nanos, int blocks, int perBlock) {
    double[] out = new double[blocks];
    for (int b = 0; b < blocks; b++) {
      double[] block = new double[perBlock];
      for (int i = 0; i < perBlock; i++) {
        block[i] = nanos[b * perBlock + i];
      }
      out[b] = Stats.percentile(block, 99);
    }
    return out;
  }

  private void hotRowStep() throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    freshCluster(Part.MISC, Optional.empty());
    probeCanary();
    fresh = false;
    hotRow =
        Optional.of(
            HotRowCounter.run(
                pg,
                z.hotWriters().stream().mapToInt(Integer::intValue).toArray(),
                Duration.ofSeconds(z.hotSeconds()),
                z.hotWindows()));
    probeCanary();
  }

  private void matrixStep() throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    freshCluster(Part.MISC, Optional.empty());
    probeCanary();
    fresh = false;
    int cellCount = 2 * CommitMatrix.WRITERS.length * z.matrixSizes().size();
    long cap =
        Math.min(
            CommitMatrix.DEFAULT_MAX_RECORDS_PER_CELL,
            usableBytes() / (4L * cellCount * BYTES_PER_CANDIDATE));
    cells =
        CommitMatrix.run(
            pg,
            z.matrixSizes().stream().mapToInt(Integer::intValue).toArray(),
            CommitMatrix.WRITERS,
            Duration.ofSeconds(z.matrixSeconds()),
            Math.max(cap, 1));
    probeCanary();
  }

  private void volumeStep() {
    volume =
        Optional.of(
            EvidenceVolumeModel.compute(
                new EvidenceVolumeModel.Inputs(
                    LedgerEvaluator.DAY_RECORDS,
                    plan.profile().sizes().batchSize(),
                    criteria.param("availableMarginHours").orElse(BigDecimal.valueOf(4)),
                    400L,
                    500,
                    new BigDecimal("0.01"),
                    3,
                    20_000L)));
    extraDecisions.put(
        "volume.assumptions",
        "400 raw bytes a record, 500 reports a file, 1% of files sent again, 3 enrichment phases"
            + " of 20,000 bytes a micro-batch: planning assumptions, not measurements");
  }

  // ---------------------------------------------------------------- the console load (stretch)

  private static final String CONSOLE_SQL =
      "SELECT count(*), COALESCE(max(decision_id), 0) FROM \""
          + BurstRunner.SCHEMA
          + "\".decision WHERE outcome = ?";
  private static final String LAG_SQL =
      "SELECT application_name, COALESCE(EXTRACT(EPOCH FROM replay_lag), 0)::float8"
          + " FROM pg_stat_replication";

  private void consoleStep() throws IOException, SQLException, InterruptedException {
    Sizes z = plan.profile().sizes();
    freshCluster(Part.MISC, Optional.empty());
    probeCanary();
    fresh = false;
    Background burst = new Background(backgroundBurst(z.consoleSeconds()));
    AtomicBoolean stop = new AtomicBoolean();
    AtomicLong cancelled = new AtomicLong();
    AtomicLong errors = new AtomicLong();
    List<Thread> consoles = new ArrayList<>();
    burst.start();
    for (int i = 0; i < 2; i++) {
      boolean onStandby = i == 0;
      consoles.add(
          Thread.ofPlatform()
              .daemon()
              .name("sp-l-console-" + i)
              .start(() -> consoleQueries(onStandby, stop, cancelled, errors)));
    }
    long samples = 0;
    double maxLag = 0;
    try (Connection c = pg.primary();
        Statement st = c.createStatement()) {
      c.setAutoCommit(true);
      while (burst.running()) {
        try (ResultSet rs = st.executeQuery(LAG_SQL)) {
          while (rs.next()) {
            maxLag = Math.max(maxLag, rs.getDouble(2));
          }
        } catch (SQLException e) {
          errors.incrementAndGet();
        }
        samples++;
        Thread.sleep(1_000);
      }
    } finally {
      stop.set(true);
      for (Thread t : consoles) {
        t.join(Duration.ofSeconds(30));
      }
      burst.finish(z.consoleSeconds());
    }
    probeCanary();
    console = Optional.of(new ConsoleData(samples, maxLag, cancelled.get()));
    if (errors.get() > 0) {
      notes.add(
          "console load: "
              + errors.get()
              + " queries or lag readings failed for a reason other than a cancellation");
    }
  }

  /**
   * A missing schema or table in the first seconds of the console load is not a fault: the burst
   * has not installed its schema yet, or the standby has not replayed it. After the grace period
   * the same error is counted.
   */
  static boolean ignorableConsoleError(String sqlState, long elapsedNanos) {
    return ("42P01".equals(sqlState) || "3F000".equals(sqlState))
        && elapsedNanos < CONSOLE_GRACE_NANOS;
  }

  private void consoleQueries(
      boolean onStandby, AtomicBoolean stop, AtomicLong cancelled, AtomicLong errors) {
    long started = System.nanoTime();
    try (Connection c = onStandby ? pg.standby() : pg.replica()) {
      c.setAutoCommit(true);
      int outcome = 1;
      while (!stop.get()) {
        try (PreparedStatement ps = c.prepareStatement(CONSOLE_SQL)) {
          ps.setInt(1, outcome);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
          }
        } catch (SQLException e) {
          // a query that the server cancels on a recovery conflict, or on a timeout, is what the
          // criterion counts; any other failure is a fault of the study
          if ("40001".equals(e.getSQLState()) || "57014".equals(e.getSQLState())) {
            cancelled.incrementAndGet();
          } else {
            if (!ignorableConsoleError(e.getSQLState(), System.nanoTime() - started)) {
              errors.incrementAndGet();
            }
            Thread.sleep(1_000);
          }
        }
        outcome = outcome % 4 + 1;
      }
    } catch (SQLException e) {
      errors.incrementAndGet();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------- the file

  private Output partOutput(Part part) {
    return switch (part) {
      case INGEST -> {
        if (sustainA.isEmpty() && sustainB.isEmpty() && closed.isEmpty()) {
          yield empty();
        }
        MemoryPlan memoryPlan = memory.orElseGet(this::memoryPlan);
        Output o =
            LedgerEvaluator.ingest(
                new IngestRuns(sustainA, sustainB, closed, ingestViolations, memoryPlan));
        Map<String, Object> d = new LinkedHashMap<>(o.decisions());
        d.put("ingest.bytesPerCandidate", bytesPerCandidate);
        d.put("ingest.bytesPerCandidateMeasured", bytesPerCandidateMeasured);
        yield new Output(o.series(), d, o.open());
      }
      case RESTORE -> {
        if (restorePoints.isEmpty() && rebuilds.isEmpty()) {
          yield empty();
        }
        BigDecimal interval =
            criteria
                .param("backupIntervalHours")
                .orElse(BigDecimal.valueOf(BACKUP_INTERVAL_FALLBACK_HOURS));
        long perDay =
            criteria
                .param("recordsPerDay")
                .map(BigDecimal::longValueExact)
                .orElse(LedgerEvaluator.DAY_RECORDS);
        Output o =
            LedgerEvaluator.restore(
                new RestoreData(
                    restorePoints, Math.max(1, bytesPerKey), rebuilds, perDay, interval));
        Map<String, Object> d = new LinkedHashMap<>(o.decisions());
        d.put(
            "restore.bytesPerKeyBasis",
            "measured on the first restore point after the load of its keys and the checkpoint,"
                + " before the gap ingest and the base backup, so the gap and its objects are not"
                + " in the figure");
        yield new Output(o.series(), d, o.open());
      }
      case MISC -> {
        boolean any =
            !checks.isEmpty()
                || rowSizeE1.isPresent()
                || !partitionPoints.isEmpty()
                || fetch.isPresent()
                || hotRow.isPresent()
                || !cells.isEmpty()
                || volume.isPresent()
                || console.isPresent();
        if (!any) {
          yield empty();
        }
        Output o =
            LedgerEvaluator.misc(
                new MiscData(
                    checks,
                    rowSizeE1,
                    rowSizeE3,
                    partitionPoints,
                    fetch,
                    hotRow,
                    cells,
                    volume,
                    miscViolations,
                    console));
        Map<String, Object> d = new LinkedHashMap<>(o.decisions());
        extraDecisions.forEach(d::putIfAbsent);
        yield new Output(o.series(), d, o.open());
      }
    };
  }

  private static Output empty() {
    return new Output(List.of(), Map.of(), List.of());
  }

  private RunInfo runInfo() {
    Map<String, String> vars = options.env();
    String sha =
        Optional.ofNullable(vars.get("GITHUB_SHA"))
            .filter(s -> Git.SHA.matcher(s).matches())
            .orElseGet(() -> Git.head(options.repo()).orElse("unknown"));
    int attempt = 1;
    try {
      attempt = Integer.parseInt(vars.getOrDefault("GITHUB_RUN_ATTEMPT", "1"));
    } catch (NumberFormatException e) {
      // a runner always sets a number; anything else is a local run
    }
    return new RunInfo(
        vars.getOrDefault("GITHUB_RUN_ID", "local"),
        attempt,
        options.part().map(Part::label).orElse("all"),
        sha,
        String.valueOf(env.get("cpu.model")),
        vars.getOrDefault("ImageVersion", "local"));
  }

  /** Assembles what exists, checks it and rewrites the file. */
  private void write() throws IOException {
    Output all = new Output(List.of(), Map.of(), List.of());
    for (Part part : parts) {
      all = all.plus(partOutput(part));
    }
    List<String> open = new ArrayList<>(notes);
    open.addAll(all.open());
    Output output = new Output(all.series(), all.decisions(), open);
    String scope = complete && plan.profile() == Profile.FULL ? "FULL" : "PARTIAL";
    Map<String, Object> source = new LinkedHashMap<>();
    Sizes z = plan.profile().sizes();
    source.put("profile", plan.profile().label());
    source.put("stretch", plan.stretch());
    source.put("part", options.part().map(Part::label).orElse("all"));
    source.put("seed", LOAD_SEED);
    source.put("rate", (long) RATE);
    source.put("writers", (long) WRITERS);
    source.put("batchSize", (long) z.batchSize());
    source.put("sustainSeconds", (long) z.sustainSeconds());
    source.put("closedSeconds", (long) z.closedSeconds());
    Map<Resource, List<BigDecimal>> canaryCopy = new EnumMap<>(Resource.class);
    canary.forEach((resource, samples) -> canaryCopy.put(resource, List.copyOf(samples)));
    MeasurementSet set =
        LedgerEvaluator.set(
            configDigest(plan),
            scope,
            output,
            canaryCopy,
            List.of(runInfo()),
            source,
            new LinkedHashMap<>(env));
    AtomicFile.write(options.out().resolve("measurements.json"), set.toJson() + "\n");
  }

  // ---------------------------------------------------------------- the projection

  /**
   * The projected duration of the full run of a part, from the steps of the smoke run: per role the
   * seconds a unit of work took (the restart that a step paid is taken off), applied to the work of
   * the full plan, plus a restart and the six canary probes for the steps that have them. A role
   * that the smoke run did not measure is projected at an assumed rate and named.
   */
  static String projection(
      Plan full,
      Plan smoke,
      Map<String, Long> smokeSeconds,
      long restartSeconds,
      long deadlineMinutes) {
    Map<Role, double[]> measured = new EnumMap<>(Role.class);
    for (Step s : smoke.steps()) {
      if (!smokeSeconds.containsKey(s.id())) {
        continue;
      }
      long seconds = smokeSeconds.get(s.id());
      double work = Math.max(0.0, seconds - (s.restart() ? restartSeconds : 0));
      double[] acc = measured.computeIfAbsent(s.role(), r -> new double[2]);
      acc[0] += work;
      acc[1] += s.work();
    }
    double canarySeconds = 6 * full.profile().sizes().canary().toNanos() / 1e9;
    List<String> assumed = new ArrayList<>();
    double typical = 0;
    for (Step s : full.steps()) {
      Optional<double[]> acc = Optional.ofNullable(measured.get(s.role()));
      double rate;
      if (acc.isEmpty() || acc.get()[1] <= 0 || acc.get()[0] <= 0) {
        rate = ASSUMED_SECONDS_PER_WORK;
        if (!assumed.contains(s.role().name())) {
          assumed.add(s.role().name());
        }
      } else {
        rate = acc.get()[0] / acc.get()[1];
      }
      typical +=
          s.work() * rate + (s.restart() ? restartSeconds : 0) + (s.canary() ? canarySeconds : 0);
    }
    double typicalMinutes = typical / 60.0;
    double worstMinutes = typicalMinutes * WORST_FACTOR;
    long limit = Math.round(deadlineMinutes * PROJECTION_LIMIT_PERCENT / 100.0);
    String verdict;
    if (worstMinutes <= limit) {
      verdict = "within 85% of the " + deadlineMinutes + "-minute deadline (" + limit + " minutes)";
    } else if (typicalMinutes <= limit) {
      verdict =
          "typical case within, worst case ABOVE 85% of the "
              + deadlineMinutes
              + "-minute deadline ("
              + limit
              + " minutes): tell the owner";
    } else {
      verdict =
          "ABOVE 85% of the "
              + deadlineMinutes
              + "-minute deadline ("
              + limit
              + " minutes): tell the owner and propose fewer or shorter runs, never fewer than two"
              + " valid runs per count";
    }
    return "projected full duration: "
        + Math.round(typicalMinutes)
        + " minutes typical, "
        + Math.round(worstMinutes)
        + " minutes worst ("
        + full.steps().size()
        + " steps, "
        + restartSeconds
        + " s per restart"
        + (assumed.isEmpty()
            ? ""
            : "; assumed rate for " + String.join(", ", assumed) + " (no smoke measurement)")
        + "): "
        + verdict;
  }
}
