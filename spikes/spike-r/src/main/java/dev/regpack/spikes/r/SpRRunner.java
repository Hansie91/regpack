package dev.regpack.spikes.r;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.MeasuredOnly;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.eval.Num;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.bench.proto.EnvCapture;
import dev.regpack.bench.proto.ForkRunner;
import dev.regpack.bench.proto.ForkSeries;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.proto.Protocol;
import dev.regpack.bench.proto.RunDeadline;
import dev.regpack.bench.report.Git;
import dev.regpack.bench.stats.Stats;
import dev.regpack.ports.expr.Expression;
import dev.regpack.spikes.r.bench.Corpus;
import dev.regpack.spikes.r.bench.CostRepeats;
import dev.regpack.spikes.r.bench.HotSwap;
import dev.regpack.spikes.r.bench.ThroughputBench;
import dev.regpack.spikes.r.defaults.BudgetDefaults;
import dev.regpack.spikes.r.defaults.PoisonDefaults;
import dev.regpack.spikes.r.diff.DifferentialCheck;
import dev.regpack.spikes.r.diff.SkeletonDifferential;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.vectors.ConformanceHarness;
import dev.regpack.spikes.r.vectors.Vector;
import dev.regpack.spikes.r.vectors.VectorFile;
import dev.regpack.spikes.r.vectors.VectorResult;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Runs SP-R: every measurement of the plan, one series after another, and after each finished
 * series rewrites {@code measurements.json} atomically, so that whatever exists when the run ends
 * (the deadline, a failed step) can be sealed by {@code bench publish}. The run never decides a
 * verdict itself: it measures, derives the decisions of {@link SpRDecisions} and writes the set.
 *
 * <p>The rate series run as fork children of {@link SpRMain} under the fixed measurement protocol;
 * the rest (vectors, cost repeatability, defaults, hot swap, differential check) runs in this JVM.
 */
public final class SpRRunner {
  /** The profile: how large a run is. */
  public enum Profile {
    /** A wiring check: two forks per series, small samples. It can only be INCONCLUSIVE. */
    SMOKE("smoke", "smoke", "smoke", 16_384, 20_000, 2_000, 5, 100, 2, 2, List.of(40, 100)),
    /** The measurement run of the plan. */
    FULL(
        "full",
        "full",
        "informative",
        ThroughputBench.RING_SIZE,
        1_000_000,
        100_000,
        50,
        1_000,
        20,
        3,
        List.of(200, 5_000));

    private final String label;
    private final String gatedProtocol;
    private final String informativeProtocol;
    private final int ring;
    private final int cleanRecords;
    private final int diffRecords;
    private final int repeatInputs;
    private final int repeats;
    private final int swaps;
    private final int swapThreads;
    private final List<Integer> hotSwapSizes;

    Profile(
        String label,
        String gatedProtocol,
        String informativeProtocol,
        int ring,
        int cleanRecords,
        int diffRecords,
        int repeatInputs,
        int repeats,
        int swaps,
        int swapThreads,
        List<Integer> hotSwapSizes) {
      this.label = label;
      this.gatedProtocol = gatedProtocol;
      this.informativeProtocol = informativeProtocol;
      this.ring = ring;
      this.cleanRecords = cleanRecords;
      this.diffRecords = diffRecords;
      this.repeatInputs = repeatInputs;
      this.repeats = repeats;
      this.swaps = swaps;
      this.swapThreads = swapThreads;
      this.hotSwapSizes = hotSwapSizes;
    }

    public String label() {
      return label;
    }

    /** The sizes of the rule sweep (microseconds per record, one thread). */
    List<Integer> sweepSizes() {
      return this == SMOKE ? List.of(20, 50) : List.of(50, 500, 5_000);
    }

    /** The options of the fork JVMs: forks run with a fixed heap. */
    List<String> jvmArgs() {
      return this == SMOKE ? List.of("-Xmx1g") : List.of("-Xms2g", "-Xmx2g");
    }

    static Profile of(String label) {
      for (Profile p : values()) {
        if (p.label.equals(label)) {
          return p;
        }
      }
      throw new IllegalArgumentException("unknown profile " + label + " (smoke or full)");
    }
  }

  /** What a step measures. */
  public enum Role {
    VECTORS,
    DETERMINISM,
    DEFAULTS,
    /** The gated rate: 200 rules, shape S2, four threads (R-2). */
    GATE,
    EXPLAIN,
    HOTSWAP,
    /** The one-thread rate at 200 rules, shape S2 (R-3, and the t1S2 reading). */
    ONE_THREAD,
    DIFFERENTIAL,
    /** Microseconds per record at another rule count (R-3). */
    SWEEP,
    /** The realistic shape S1 (stretch). */
    STRETCH
  }

  /**
   * One step of the plan. {@code forks} is 0 for a step that runs in this JVM. The layer, shape,
   * rules and threads describe a rate series.
   */
  public record Step(
      String id,
      Role role,
      List<String> criteria,
      String protocolName,
      int forks,
      String layer,
      String shape,
      int rules,
      int threads) {
    public Step {
      criteria = List.copyOf(criteria);
    }

    boolean forked() {
      return forks > 0;
    }
  }

  /** The steps of a run, in the order they are run. */
  public record Plan(Profile profile, boolean stretch, List<Step> steps) {
    public Plan {
      steps = List.copyOf(steps);
    }

    /** Every criterion that some step produces. */
    public List<String> criteria() {
      return steps.stream().flatMap(s -> s.criteria().stream()).distinct().sorted().toList();
    }

    /** The forks of the plan: the sum over the forked steps. */
    public int totalForks() {
      return steps.stream().mapToInt(Step::forks).sum();
    }
  }

  /**
   * The inputs of a run.
   *
   * @param deadlineMinutes no fork starts after this many minutes, no step after the deadline
   * @param out the result directory; {@code measurements.json} is written into it
   * @param env the environment (run id, commit)
   * @param repo the repository whose HEAD is recorded when not on a runner
   */
  public record Options(
      Profile profile,
      boolean stretch,
      long deadlineMinutes,
      Path out,
      Map<String, String> env,
      Path repo) {}

  static final String INHOUSE = "inhouse";
  static final String CEL = "cel";
  private static final List<String> LAYERS = List.of(INHOUSE, CEL);
  private static final int GATE_RULES = 200;
  private static final int GATE_THREADS = 4;
  private static final long CLEAN_SEED = 2L;
  private static final long REPEAT_SEED = 3L;
  private static final long DIFF_SEED = 4L;
  private static final int DIFF_LIMIT = 1_000;
  private static final int LIST_ITEMS = 256;
  private static final int STRING_CODE_POINTS = 4_096;

  // the assumptions of the poison-circuit defaults (the plan's R-3 task text)
  private static final long RECORDS_PER_DAY = 50_000_000L;
  private static final double BACKGROUND_RATE = 1e-4;
  private static final double DEFECTIVE_RATE = 0.20;
  private static final double MIN_DETECTION = 0.99;
  private static final double MAX_FALSE_OPEN_PER_DAY = 0.01;
  private static final int[] N_GRID = {2, 3, 4, 5, 8, 10, 20};
  private static final int[] M_GRID = {20, 30, 40, 50, 60, 80, 100, 200, 500, 1000};
  private static final long BURST_RECORDS = 5_000;
  private static final double BURST_RATE = 0.05;

  /** 85 percent of the 320-minute deadline of the workflow: the limit of the projection. */
  static final long PROJECTION_LIMIT_MINUTES = 272;

  private final Options options;
  private final Plan plan;
  private final Criteria criteria;
  private final PrintStream out;
  private final RunDeadline deadline;

  // what has been measured so far
  private final Map<String, Series> series = new LinkedHashMap<>();
  private final Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
  private List<VectorResult> inhouseVectors = List.of();
  private List<VectorResult> celVectors = List.of();
  private final Map<String, BigDecimal> t4PerThread = new TreeMap<>();
  private final Map<String, BigDecimal> t1 = new TreeMap<>();
  private final Map<String, Integer> t1Kept = new TreeMap<>();
  private final Map<String, BudgetDefaults.Result> budget = new TreeMap<>();
  private Optional<PoisonDefaults.Choice> poison = Optional.empty();
  private Optional<BigDecimal> explainP99 = Optional.empty();
  private final Map<String, BigDecimal> hotSwapP99 = new TreeMap<>();
  private final Map<String, BigDecimal> usPerRecord = new TreeMap<>();
  private final Map<String, Object> extra = new TreeMap<>();
  private final List<String> notes = new ArrayList<>();
  private final Map<String, Long> stepSeconds = new LinkedHashMap<>();
  private final Map<String, Integer> forksRun = new LinkedHashMap<>();
  private boolean complete = true;

  private SpRRunner(Options options, PrintStream out) {
    this.options = options;
    this.plan = plan(options.profile(), options.stretch());
    this.criteria = Criteria.load();
    this.out = out;
    this.deadline = RunDeadline.afterMinutes(NanoClock.system(), options.deadlineMinutes());
  }

  // ---------------------------------------------------------------- the plan

  /** The plan of a run; the same plan is run and, in a test, inspected. */
  public static Plan plan(Profile profile, boolean stretch) {
    List<Step> steps = new ArrayList<>();
    steps.add(inProcess("vectors", Role.VECTORS, "R-1a", "R-1b"));
    steps.add(inProcess("determinism", Role.DETERMINISM, "R-6a", "R-6b"));
    steps.add(inProcess("defaults", Role.DEFAULTS, "R-7"));
    String gated = profile.gatedProtocol;
    String informative = profile.informativeProtocol;
    for (String layer : LAYERS) {
      steps.add(
          rate(
              "s2-200-t4-" + layer,
              Role.GATE,
              layer.equals(INHOUSE) ? "R-2a" : "R-2b",
              gated,
              layer,
              "S2",
              GATE_RULES,
              GATE_THREADS));
    }
    steps.add(
        new Step(
            "explain",
            Role.EXPLAIN,
            List.of("R-4"),
            gated,
            SpRWorker.protocol(gated).forks(),
            "",
            "",
            0,
            1));
    steps.add(inProcess("hotswap", Role.HOTSWAP, "R-5"));
    for (String layer : LAYERS) {
      steps.add(
          rate(
              "s2-200-t1-" + layer,
              Role.ONE_THREAD,
              "R-3",
              informative,
              layer,
              "S2",
              GATE_RULES,
              1));
    }
    steps.add(inProcess("differential", Role.DIFFERENTIAL, "R-8"));
    for (int rules : profile.sweepSizes()) {
      for (String layer : LAYERS) {
        steps.add(
            rate(
                "s2-" + rules + "-t1-" + layer,
                Role.SWEEP,
                "R-3",
                informative,
                layer,
                "S2",
                rules,
                1));
      }
    }
    if (stretch) {
      for (String layer : LAYERS) {
        steps.add(
            new Step(
                "s1-200-t4-" + layer,
                Role.STRETCH,
                List.of(),
                informative,
                SpRWorker.protocol(informative).forks(),
                layer,
                "S1",
                GATE_RULES,
                GATE_THREADS));
      }
    }
    return new Plan(profile, stretch, steps);
  }

  private static Step inProcess(String id, Role role, String... criteria) {
    return new Step(id, role, List.of(criteria), "", 0, "", "", 0, 0);
  }

  private static Step rate(
      String id,
      Role role,
      String criterion,
      String protocolName,
      String layer,
      String shape,
      int rules,
      int threads) {
    return new Step(
        id,
        role,
        List.of(criterion),
        protocolName,
        SpRWorker.protocol(protocolName).forks(),
        layer,
        shape,
        rules,
        threads);
  }

  private List<String> workerArgs(Step step) {
    List<String> args = new ArrayList<>();
    if (step.role() == Role.EXPLAIN) {
      args.addAll(List.of("--bench", "explain", "--protocol", step.protocolName()));
    } else {
      args.addAll(
          List.of(
              "--bench", "throughput",
              "--layer", step.layer(),
              "--shape", step.shape(),
              "--rules", Integer.toString(step.rules()),
              "--threads", Integer.toString(step.threads()),
              "--protocol", step.protocolName(),
              "--ring", Integer.toString(plan.profile().ring)));
    }
    List<String> all = new ArrayList<>();
    all.add("worker");
    all.addAll(args);
    return all;
  }

  // ---------------------------------------------------------------- the run

  /**
   * Runs the plan and writes {@code measurements.json} into the result directory.
   *
   * @return 0; a step that fails is recorded as an open item and its criteria stay NOT_RUN
   */
  public static int run(Options options, PrintStream out) throws IOException, InterruptedException {
    SpRRunner runner = new SpRRunner(options, out);
    runner.execute();
    return 0;
  }

  private void execute() throws IOException, InterruptedException {
    Files.createDirectories(options.out());
    out.println(
        "sp-r: "
            + plan.profile().label()
            + " profile, "
            + plan.steps().size()
            + " steps, "
            + plan.totalForks()
            + " forks, deadline "
            + options.deadlineMinutes()
            + " minutes");
    for (Step step : plan.steps()) {
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
      try {
        runStep(step);
      } catch (InterruptedException e) {
        throw e;
      } catch (IOException | RuntimeException | LinkageError e) {
        complete = false;
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        notes.add("step " + step.id() + " failed: " + message);
        out.println("sp-r: step " + step.id() + " failed: " + message);
      }
      long seconds = (System.nanoTime() - started) / 1_000_000_000L;
      stepSeconds.put(step.id(), seconds);
      out.println("sp-r: step " + step.id() + " done in " + seconds + " s");
      write();
    }
    write();
    if (options.profile() == Profile.SMOKE) {
      out.println(projection());
    }
  }

  private void runStep(Step step) throws IOException, InterruptedException {
    switch (step.role()) {
      case VECTORS -> vectors();
      case DETERMINISM -> determinism();
      case DEFAULTS -> defaults();
      case GATE, ONE_THREAD, SWEEP, STRETCH -> throughput(step);
      case EXPLAIN -> explain(step);
      case HOTSWAP -> hotSwap();
      case DIFFERENTIAL -> differential();
    }
  }

  // ---------------------------------------------------------------- R-1

  private void vectors() {
    List<Vector> all = VectorFile.load();
    inhouseVectors = ConformanceHarness.run(SpRWorker.vectorLayer(INHOUSE), all);
    celVectors = ConformanceHarness.run(SpRWorker.vectorLayer(CEL), all);
    series.put(
        "R-1a", new CountValue("R-1a", ConformanceHarness.failures(inhouseVectors, "MUST"), 1));
    series.put("R-1b", new CountValue("R-1b", ConformanceHarness.failures(celVectors, "MUST"), 1));
    vectorFigures(INHOUSE, inhouseVectors);
    vectorFigures(CEL, celVectors);
  }

  private void vectorFigures(String layer, List<VectorResult> results) {
    for (String result : List.of("PASS", "FAIL", "UNSUPPORTED", "FUNCTIONAL")) {
      long count = results.stream().filter(r -> r.result().equals(result)).count();
      extra.put("vectors." + layer + "." + result.toLowerCase(java.util.Locale.ROOT), count);
    }
    List<String> notPass = new ArrayList<>();
    for (VectorResult r : results) {
      if (!r.result().equals("PASS")) {
        notPass.add(r.id() + " " + r.vectorClass() + " " + r.result() + " " + r.actual());
      }
    }
    extra.put("vectors." + layer + ".notPass", notPass);
  }

  // ---------------------------------------------------------------- R-6

  private void determinism() {
    List<TradeRecord> inputs = new ArrayList<>();
    for (TradeRecord r : new Corpus(REPEAT_SEED, plan.profile().repeatInputs)) {
      inputs.add(r);
    }
    for (String layer : LAYERS) {
      long differing = 0;
      for (PackShape shape : PackShape.values()) {
        GeneratedPack pack = RulePackGen.generate(SpRWorker.SEED, GATE_RULES, shape);
        differing +=
            CostRepeats.differingRuns(
                SpRWorker.expression(layer), pack, inputs, plan.profile().repeats);
      }
      series.put(
          layer.equals(INHOUSE) ? "R-6a" : "R-6b",
          new CountValue(layer.equals(INHOUSE) ? "R-6a" : "R-6b", differing, 1));
      extra.put("determinism." + layer + ".runsPerInput", (long) plan.profile().repeats);
      extra.put(
          "determinism." + layer + ".inputs", (long) inputs.size() * PackShape.values().length);
    }
  }

  // ---------------------------------------------------------------- R-7

  private void defaults() {
    long violations = 0;
    for (String layer : LAYERS) {
      GeneratedPack pack =
          RulePackGen.generate(SpRWorker.SEED, GATE_RULES, PackShape.S2_FULL_SCAN_WORST_CASE);
      CompiledPack compiled = CompiledPack.compile(pack, SpRWorker.expression(layer));
      BudgetDefaults.Result result =
          BudgetDefaults.derive(
              compiled,
              new Corpus(CLEAN_SEED, plan.profile().cleanRecords),
              LIST_ITEMS,
              STRING_CODE_POINTS);
      budget.put(layer, result);
      violations += result.violations();
      extra.put("budget." + layer + ".staticWorstCaseUnits", result.staticWorstCaseUnits());
      extra.put("budget." + layer + ".maxObservedCleanUnits", result.maxObservedCleanUnits());
      extra.put("budget." + layer + ".violations", result.violations());
      extra.put("budget." + layer + ".cleanRecords", (long) plan.profile().cleanRecords);
    }
    try {
      PoisonDefaults.Choice choice =
          PoisonDefaults.choose(
              RECORDS_PER_DAY,
              BACKGROUND_RATE,
              DEFECTIVE_RATE,
              MIN_DETECTION,
              MAX_FALSE_OPEN_PER_DAY,
              N_GRID,
              M_GRID);
      poison = Optional.of(choice);
      violations += poisonViolations(choice);
      extra.put("poison.falseOpenPerDay", Num.dec(choice.falseOpenPerDay(), 12));
      extra.put("poison.detectionProbability", Num.dec(choice.detectionProbability(), 9));
      extra.put(
          "poison.clusteredFalseOpenPerDay",
          Num.dec(
              PoisonDefaults.clusteredFalseOpenPerDay(
                  RECORDS_PER_DAY,
                  BACKGROUND_RATE,
                  BURST_RECORDS,
                  BURST_RATE,
                  choice.n(),
                  choice.m()),
              9));
    } catch (IllegalStateException e) {
      violations++;
      notes.add("R-7: no poison-circuit pair of the grids qualifies: " + e.getMessage());
    }
    extra.put("poison.assumed.backgroundRate", "1e-4");
    extra.put("poison.assumed.defectiveRate", "0.20");
    extra.put("poison.assumed.recordsPerDay", RECORDS_PER_DAY);
    extra.put("poison.assumed.burst", "5000 consecutive records at 5%, once a day (informational)");
    series.put("R-7", new CountValue("R-7", violations, 1));
  }

  /** The chosen pair must satisfy both constraints and be minimal: M first, then N. */
  private static long poisonViolations(PoisonDefaults.Choice choice) {
    long violations = 0;
    if (choice.detectionProbability() < MIN_DETECTION) {
      violations++;
    }
    if (choice.falseOpenPerDay() > MAX_FALSE_OPEN_PER_DAY) {
      violations++;
    }
    // no smaller M may satisfy the constraints
    List<Integer> smaller = new ArrayList<>();
    for (int m : M_GRID) {
      if (m < choice.m()) {
        smaller.add(m);
      }
    }
    if (!smaller.isEmpty()) {
      int[] grid = smaller.stream().mapToInt(Integer::intValue).toArray();
      try {
        PoisonDefaults.choose(
            RECORDS_PER_DAY,
            BACKGROUND_RATE,
            DEFECTIVE_RATE,
            MIN_DETECTION,
            MAX_FALSE_OPEN_PER_DAY,
            N_GRID,
            grid);
        violations++;
      } catch (IllegalStateException expected) {
        // none qualifies: the choice is minimal in M
      }
    }
    // no smaller N may satisfy the constraints at the chosen M
    for (int n : N_GRID) {
      if (n < choice.n()
          && n <= choice.m()
          && PoisonDefaults.detectionProbability(choice.m(), n, DEFECTIVE_RATE) >= MIN_DETECTION
          && PoisonDefaults.falseOpenPerDay(RECORDS_PER_DAY, choice.m(), n, BACKGROUND_RATE)
              <= MAX_FALSE_OPEN_PER_DAY) {
        violations++;
      }
    }
    return violations;
  }

  // ---------------------------------------------------------------- R-2, R-3, R-4

  private ForkSeries forks(Step step) throws IOException, InterruptedException {
    Protocol protocol = SpRWorker.protocol(step.protocolName());
    ForkSeries result =
        ForkRunner.runForked(
            protocol,
            plan.profile().jvmArgs(),
            SpRMain.class.getName(),
            workerArgs(step),
            deadline);
    forksRun.put(step.id(), result.forks().size());
    ForkStats.addCanary(result, canary);
    if (result.forks().size() < protocol.forks()) {
      complete = false;
      notes.add(
          "step "
              + step.id()
              + " ran "
              + result.forks().size()
              + " of "
              + protocol.forks()
              + " forks: the deadline passed");
    }
    return result;
  }

  private void throughput(Step step) throws IOException, InterruptedException {
    ForkSeries result = forks(step);
    Optional<BigDecimal> median = ForkStats.median(result);
    BigDecimal perThreadScale =
        BigDecimal.ONE.divide(BigDecimal.valueOf(step.threads()), 9, RoundingMode.HALF_EVEN);
    switch (step.role()) {
      case GATE -> {
        String id = step.layer().equals(INHOUSE) ? "R-2a" : "R-2b";
        series.put(
            id, new ForkValues(id, "records/s per thread", perThreadScale, ForkStats.of(result)));
        median.ifPresent(m -> t4PerThread.put(step.layer(), m.multiply(perThreadScale)));
      }
      case ONE_THREAD -> {
        median.ifPresent(
            m -> {
              t1.put(step.layer(), m);
              t1Kept.put(step.layer(), result.kept().size());
              usPerRecord.put(step.layer() + "." + step.rules(), microseconds(m));
            });
      }
      case SWEEP ->
          median.ifPresent(
              m -> usPerRecord.put(step.layer() + "." + step.rules(), microseconds(m)));
      case STRETCH ->
          median.ifPresent(
              m ->
                  extra.put(
                      "stretch.s1.t4PerThread." + step.layer(),
                      m.multiply(perThreadScale).setScale(3, RoundingMode.HALF_EVEN)));
      default -> throw new IllegalStateException("not a rate step: " + step.role());
    }
  }

  private static BigDecimal microseconds(BigDecimal recordsPerSecond) {
    if (recordsPerSecond.signum() <= 0) {
      return BigDecimal.ZERO.setScale(3);
    }
    return BigDecimal.valueOf(1_000_000L).divide(recordsPerSecond, 3, RoundingMode.HALF_EVEN);
  }

  private void explain(Step step) throws IOException, InterruptedException {
    ForkSeries result = forks(step);
    series.put("R-4", new ForkValues("R-4", "ms", BigDecimal.ONE, ForkStats.of(result)));
    explainP99 = ForkStats.median(result).map(m -> m.setScale(3, RoundingMode.HALF_EVEN));
  }

  // ---------------------------------------------------------------- R-5

  private void hotSwap() {
    long count = 0;
    int runs = 0;
    for (String layer : LAYERS) {
      Expression expression = SpRWorker.expression(layer);
      for (int size : plan.profile().hotSwapSizes) {
        if (deadline.passed()) {
          throw new IllegalStateException("the deadline passed during the hot-swap runs");
        }
        GeneratedPack a =
            RulePackGen.generate(SpRWorker.SEED, size, PackShape.S2_FULL_SCAN_WORST_CASE);
        GeneratedPack b =
            RulePackGen.generate(SpRWorker.SEED + 1, size, PackShape.S2_FULL_SCAN_WORST_CASE);
        HotSwap.Run run =
            HotSwap.measure(expression, a, b, plan.profile().swapThreads, plan.profile().swaps);
        count += run.mixedPackBatches() + run.lostRecords();
        runs++;
        long[] nanos = run.activationNanos();
        double[] millis = new double[nanos.length];
        for (int i = 0; i < nanos.length; i++) {
          millis[i] = nanos[i] / 1_000_000.0;
        }
        hotSwapP99.put(layer + "." + size, Num.dec(Stats.percentile(millis, 99), 3));
        extra.put("hotswap." + layer + "." + size + ".mixedPackBatches", run.mixedPackBatches());
        extra.put("hotswap." + layer + "." + size + ".lostRecords", run.lostRecords());
      }
    }
    extra.put("hotswap.swapsPerRun", (long) plan.profile().swaps);
    extra.put("hotswap.evaluatorThreads", (long) plan.profile().swapThreads);
    extra.put("hotswap.batchRecords", (long) HotSwap.BATCH_SIZE);
    series.put("R-5", new CountValue("R-5", count, runs));
  }

  // ---------------------------------------------------------------- R-8

  private void differential() {
    long total = 0;
    List<String> skeleton = SkeletonDifferential.disagreements(SpRWorker.expression(INHOUSE));
    total += skeleton.size();
    extra.put("diff.skeleton", (long) skeleton.size());
    skeleton.forEach(line -> notes.add("R-8 skeleton: " + line));
    for (PackShape shape : PackShape.values()) {
      GeneratedPack pack = RulePackGen.generate(SpRWorker.SEED, GATE_RULES, shape);
      List<DifferentialCheck.Mismatch> mismatches =
          DifferentialCheck.run(
              SpRWorker.expression(INHOUSE),
              SpRWorker.expression(CEL),
              pack,
              new Corpus(DIFF_SEED, plan.profile().diffRecords),
              DIFF_LIMIT);
      total += mismatches.size();
      String key = shape == PackShape.S1_FIRST_HIT_REALISTIC ? "S1" : "S2";
      extra.put("diff.inhouseVsCel." + key, (long) mismatches.size());
      for (int i = 0; i < Math.min(3, mismatches.size()); i++) {
        DifferentialCheck.Mismatch m = mismatches.get(i);
        notes.add(
            "R-8 in-house against cel-java, "
                + key
                + ", record "
                + m.seq()
                + ": "
                + m.left()
                + " against "
                + m.right());
      }
    }
    extra.put("diff.records", (long) plan.profile().diffRecords);
    series.put("R-8", new CountValue("R-8", total, 1));
  }

  // ---------------------------------------------------------------- the file

  private MeasurementSet assemble(String scope, Map<String, Object> decisions, List<String> open) {
    List<Series> all = new ArrayList<>(series.values());
    Optional<String> r3Layer =
        t1.containsKey(INHOUSE)
            ? Optional.of(INHOUSE)
            : t1.containsKey(CEL) ? Optional.of(CEL) : Optional.empty();
    r3Layer.ifPresent(
        layer ->
            all.add(new MeasuredOnly("R-3", true, t1Kept.get(layer), t1.get(layer), "records/s")));
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("profile", plan.profile().label());
    source.put("stretch", plan.stretch());
    source.put("part", "all");
    source.put("ringRecords", (long) plan.profile().ring);
    source.put("seed", SpRWorker.SEED);
    Map<String, Object> env = new LinkedHashMap<>(EnvCapture.capture());
    env.put("cpu.physicalCores", (long) PhysicalCores.detect());
    env.put("fork.jvmArgs", String.join(" ", plan.profile().jvmArgs()));
    Map<Resource, List<BigDecimal>> canaryCopy = new EnumMap<>(Resource.class);
    canary.forEach((resource, samples) -> canaryCopy.put(resource, List.copyOf(samples)));
    return new MeasurementSet(
        "SP-R",
        configDigest(),
        scope,
        all,
        canaryCopy,
        List.of(runInfo(env)),
        source,
        env,
        decisions,
        open);
  }

  private RunInfo runInfo(Map<String, Object> env) {
    Map<String, String> vars = options.env();
    String sha = vars.get("GITHUB_SHA");
    if (sha == null || !Git.SHA.matcher(sha).matches()) {
      sha = Git.head(options.repo()).orElse("unknown");
    }
    int attempt = 1;
    try {
      attempt = Integer.parseInt(vars.getOrDefault("GITHUB_RUN_ATTEMPT", "1"));
    } catch (NumberFormatException e) {
      // a runner always sets a number; anything else is a local run
    }
    return new RunInfo(
        vars.getOrDefault("GITHUB_RUN_ID", "local"),
        attempt,
        "all",
        sha,
        String.valueOf(env.get("cpu.model")),
        vars.getOrDefault("ImageVersion", "local"));
  }

  /**
   * The digest of the configuration: the profile, the stretch flag and the plan, never the host.
   */
  private String configDigest() {
    Map<String, Object> planMap = new LinkedHashMap<>();
    planMap.put("ringRecords", (long) plan.profile().ring);
    planMap.put("seed", SpRWorker.SEED);
    planMap.put("cleanRecords", (long) plan.profile().cleanRecords);
    planMap.put("diffRecords", (long) plan.profile().diffRecords);
    planMap.put("repeatInputs", (long) plan.profile().repeatInputs);
    planMap.put("repeats", (long) plan.profile().repeats);
    planMap.put("swaps", (long) plan.profile().swaps);
    planMap.put("swapThreads", (long) plan.profile().swapThreads);
    planMap.put("jvmArgs", plan.profile().jvmArgs());
    List<Object> steps = new ArrayList<>();
    for (Step step : plan.steps()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", step.id());
      m.put("role", step.role().name());
      m.put("criteria", step.criteria());
      m.put("protocol", step.protocolName());
      m.put("forks", (long) step.forks());
      m.put("layer", step.layer());
      m.put("shape", step.shape());
      m.put("rules", (long) step.rules());
      m.put("threads", (long) step.threads());
      steps.add(m);
    }
    planMap.put("steps", steps);
    return MeasurementSet.configDigest(
        "SP-R", plan.profile().label(), plan.stretch(), criteria.digest().hex(), planMap);
  }

  /** Evaluates what exists, derives the decisions from the verdicts and rewrites the file. */
  private void write() throws IOException {
    String scope = complete && plan.profile() == Profile.FULL ? "FULL" : "PARTIAL";
    MeasurementSet bare = assemble(scope, Map.of(), List.of());
    Map<String, Object> timeBox = new LinkedHashMap<>();
    timeBox.put("usedMinutes", 0L);
    timeBox.put("boxMinutes", criteria.box("SP-R").toMinutes());
    SpikeResult evaluated = ResultsEvaluator.evaluate(bare, criteria, "RAW", timeBox);
    Map<String, Verdict> verdicts = new LinkedHashMap<>();
    for (CriterionResult r : evaluated.criteria()) {
      verdicts.put(r.id(), r.verdict());
    }
    BigDecimal gate = criteria.byId("R-2a").orElseThrow().threshold().orElseThrow();
    SpRDecisions.Derived derived =
        SpRDecisions.derive(
            new SpRDecisions.Inputs(
                verdicts,
                inhouseVectors,
                celVectors,
                t4PerThread,
                t1,
                GATE_THREADS,
                PhysicalCores.detect(),
                budget,
                poison,
                explainP99,
                hotSwapP99,
                usPerRecord),
            gate);
    Map<String, Object> decisions = new LinkedHashMap<>(extra);
    decisions.putAll(derived.decisions());
    List<String> open = new ArrayList<>(notes);
    open.addAll(derived.open());
    MeasurementSet full = assemble(scope, decisions, open);
    AtomicFile.write(options.out().resolve("measurements.json"), full.toJson() + "\n");
  }

  // ---------------------------------------------------------------- the projection

  private String projection() {
    Plan full = plan(Profile.FULL, options.stretch());
    Protocol smoke = Protocol.smoke();
    Step first = plan.steps().stream().filter(s -> s.role() == Role.GATE).findFirst().orElseThrow();
    double ringFull = fullRingSeconds();
    double ringSmoke = ringFull * Profile.SMOKE.ring / ThroughputBench.RING_SIZE;
    int ran = Math.max(1, forksRun.getOrDefault(first.id(), 1));
    double measured = stepSeconds.getOrDefault(first.id(), 0L) / (double) ran;
    double overhead =
        Math.max(0.5, measured - Projection.forkSeconds(smoke, 0, false) - ringSmoke) + ringFull;
    double inProcess =
        Projection.inProcessSeconds(
            stepSeconds.getOrDefault("defaults", 0L),
            stepSeconds.getOrDefault("determinism", 0L),
            stepSeconds.getOrDefault("differential", 0L),
            stepSeconds.getOrDefault("hotswap", 0L));
    double typical = Projection.totalMinutes(full, overhead, inProcess, false);
    double worst = Projection.totalMinutes(full, overhead, inProcess, true);
    String verdict;
    if (worst <= PROJECTION_LIMIT_MINUTES) {
      verdict = "within 85% of the 320-minute deadline (" + PROJECTION_LIMIT_MINUTES + " minutes)";
    } else if (typical <= PROJECTION_LIMIT_MINUTES) {
      verdict =
          "typical case within, worst case ABOVE 85% of the 320-minute deadline ("
              + PROJECTION_LIMIT_MINUTES
              + " minutes): tell the owner";
    } else {
      verdict =
          "ABOVE 85% of the 320-minute deadline ("
              + PROJECTION_LIMIT_MINUTES
              + " minutes): tell the owner and propose fewer forks, never below 7 per series";
    }
    return "projected full duration: "
        + Math.round(typical)
        + " minutes typical, "
        + Math.round(worst)
        + " minutes if every warm-up runs to its limit ("
        + full.totalForks()
        + " forks, "
        + String.format(java.util.Locale.ROOT, "%.1f", overhead)
        + " s fixed cost per fork from the measured first series, "
        + String.format(java.util.Locale.ROOT, "%.1f", inProcess / 60.0)
        + " minutes of in-process steps): "
        + verdict;
  }

  /** The time to generate a full ring in this JVM: the part of a fork that the smoke ring hides. */
  private static double fullRingSeconds() {
    long start = System.nanoTime();
    TradeRecord[] ring = ThroughputBench.ring(SpRWorker.SEED, ThroughputBench.RING_SIZE);
    long elapsed = System.nanoTime() - start;
    return ring.length == 0 ? 0 : elapsed / 1e9;
  }
}
