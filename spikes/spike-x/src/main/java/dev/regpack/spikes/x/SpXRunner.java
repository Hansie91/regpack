package dev.regpack.spikes.x;

import dev.regpack.bench.data.TradeRecordGen;
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
import dev.regpack.bench.stats.Estimate;
import dev.regpack.bench.stats.Stats;
import dev.regpack.spikes.x.CapacityModel.Capacity;
import dev.regpack.spikes.x.CapacityModel.Rates;
import dev.regpack.spikes.x.bench.BisectBench;
import dev.regpack.spikes.x.bench.FileGateBench;
import dev.regpack.spikes.x.schema.SchemaStats;
import dev.regpack.spikes.x.schema.SyntheticSchema;
import dev.regpack.spikes.x.validate.Bisect;
import dev.regpack.spikes.x.validate.PolicyModel;
import dev.regpack.spikes.x.validate.PolicyModel.Policy;
import dev.regpack.spikes.x.xml.StaxRecordWriter;
import dev.regpack.spikes.x.xml.XmlRecordGen;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Runs SP-X: every measurement of the plan, one series after another, and after each finished step
 * rewrites {@code measurements.json} atomically, so that whatever exists when the run ends (the
 * deadline, a failed step) can be sealed by {@code bench publish}. The run never decides a verdict
 * itself: it measures, derives the decisions of {@link SpXDecisions} and writes the set.
 *
 * <p>The rate series run as fork children of {@link SpXMain} under the fixed measurement protocol:
 * the components (the writer, the per-record validation and the whole-file gate, at one and four
 * threads), the integrated stage under each policy, the bisect and the stage latency per file size.
 * The facts (schema statistics, the XSD 1.1 probe, bisect validation counts) run in this JVM, and
 * the model criteria (X-2, X-3) and the measured-only figures (X-4) are derived from what exists at
 * every write.
 *
 * <p>The model criteria need every component and the integrated ALL run with at least 7 kept forks
 * (P-6: fewer can never give a verdict); without them the criteria are left out, so the verdict is
 * NOT_RUN there and the report says why.
 */
public final class SpXRunner {
  /** The sizes of the data of a profile. */
  public record Sizes(
      int writerRing,
      int xsdRing,
      int gateRecords,
      int bisectRecords,
      int recordsPerFile,
      List<Integer> latencySizes) {
    public Sizes {
      latencySizes = List.copyOf(latencySizes);
    }
  }

  /** The profile: how large a run is. */
  public enum Profile {
    /** A wiring check: two forks per series, small files. It can only be INCONCLUSIVE. */
    SMOKE(
        "smoke",
        "smoke",
        "smoke",
        new Sizes(500, 200, 3_000, 2_048, 500, List.of(100, 200, 400, 800))),
    /** The measurement run of the plan. */
    FULL(
        "full",
        "full",
        "informative",
        new Sizes(5_000, 2_000, 100_000, 100_000, 10_000, List.of(1_000, 5_000, 25_000, 100_000)));

    private final String label;
    private final String integratedProtocol;
    private final String componentProtocol;
    private final Sizes sizes;

    Profile(String label, String integratedProtocol, String componentProtocol, Sizes sizes) {
      this.label = label;
      this.integratedProtocol = integratedProtocol;
      this.componentProtocol = componentProtocol;
      this.sizes = sizes;
    }

    public String label() {
      return label;
    }

    public Sizes sizes() {
      return sizes;
    }

    /**
     * The options of the fork JVMs. The full profile's heap is fixed by the plan; the smoke
     * profile's heap is {@code 1g} unless the system property {@code spike.x.smoke.heap} says
     * otherwise.
     */
    List<String> jvmArgs() {
      if (this == FULL) {
        return List.of("-Xms6g", "-Xmx6g");
      }
      return List.of("-Xmx" + System.getProperty("spike.x.smoke.heap", "1g"));
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
    /**
     * In this JVM: the schema statistics, the XSD 1.1 probe, bisect validation counts (X-5, X-6).
     */
    FACTS,
    /** A component of the stage at one thread count: writer, xsd or gate. */
    COMPONENT,
    /** The integrated four-thread stage under a policy (X-1). */
    INTEGRATED,
    /** Finding one defective record by halving (the bisect cost of the model). */
    BISECT,
    /** The stage latency per file size, under a policy. */
    LATENCY,
    /** Derived at every write: the model criteria X-2 and X-3. */
    MODEL,
    /** Derived at every write: the measured-only figures of X-4. */
    FIGURES
  }

  /**
   * One step of the plan. {@code forks} is 0 for a step that runs in this JVM or is derived.
   *
   * <p>The derived roles ({@link Role#MODEL}, {@link Role#FIGURES}) have no forks and no work to
   * run: they are in the plan only to carry the criterion ids they produce (the plan test and the
   * configuration digest use them), and their series are computed at every write from what exists.
   *
   * @param bench the worker benchmark of a forked step, empty otherwise
   * @param policy the policy of an integrated or latency step, empty otherwise
   */
  public record Step(
      String id,
      Role role,
      List<String> criteria,
      String protocolName,
      int forks,
      String bench,
      int threads,
      String policy) {
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

  private static final String ALL = "ALL";
  private static final String SAMPLED = "SAMPLED_10";
  private static final int INTEGRATED_THREADS = 4;
  private static final BigDecimal PER_THREAD = new BigDecimal("0.25");
  private static final long FACTS_SEED = 7L;
  private static final int AVERAGE_BYTES_RECORDS = 2_000;
  private static final int GEN_RECORDS = 2_000;

  /** 85 percent of the 275-minute deadline of the workflow: the limit of the projection. */
  static final long PROJECTION_LIMIT_MINUTES = 233;

  private final Options options;
  private final Plan plan;
  private final Criteria criteria;
  private final PrintStream out;
  private final RunDeadline deadline;

  // what has been measured so far
  private final Map<String, ForkSeries> forkSeries = new LinkedHashMap<>();
  private final Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
  private Optional<Long> xsd11Rejected = Optional.empty();
  private Optional<Long> outsideTargets = Optional.empty();
  private BigDecimal averageRecordBytes = BigDecimal.ZERO;
  private final Map<String, Object> extra = new TreeMap<>();
  private final List<String> notes = new ArrayList<>();
  private final Map<String, Long> stepSeconds = new LinkedHashMap<>();
  private final Map<String, Long> stepNanos = new LinkedHashMap<>();
  private final Map<String, Integer> forksRun = new LinkedHashMap<>();
  private boolean complete = true;

  private SpXRunner(Options options, PrintStream out) {
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
    steps.add(new Step("facts", Role.FACTS, List.of("X-5", "X-6"), "", 0, "", 0, ""));
    String components = profile.componentProtocol;
    for (String bench : List.of("writer", "xsd", "gate")) {
      for (int threads : List.of(1, INTEGRATED_THREADS)) {
        steps.add(
            forked(
                bench + "-t" + threads, Role.COMPONENT, List.of(), components, bench, threads, ""));
      }
    }
    steps.add(
        forked(
            "assemble-ALL",
            Role.INTEGRATED,
            List.of("X-1a"),
            profile.integratedProtocol,
            "assemble",
            INTEGRATED_THREADS,
            ALL));
    steps.add(
        forked(
            "assemble-SAMPLED_10",
            Role.INTEGRATED,
            List.of("X-1b"),
            profile.integratedProtocol,
            "assemble",
            INTEGRATED_THREADS,
            SAMPLED));
    steps.add(forked("bisect-t1", Role.BISECT, List.of(), components, "bisect", 1, ""));
    steps.add(forked("latency-ALL", Role.LATENCY, List.of(), components, "latency", 1, ALL));
    steps.add(
        forked("latency-SAMPLED_10", Role.LATENCY, List.of(), components, "latency", 1, SAMPLED));
    steps.add(new Step("model", Role.MODEL, List.of("X-2a", "X-2b", "X-3"), "", 0, "", 0, ""));
    steps.add(new Step("figures", Role.FIGURES, List.of("X-4"), "", 0, "", 0, ""));
    return new Plan(profile, stretch, steps);
  }

  private static Step forked(
      String id,
      Role role,
      List<String> criteria,
      String protocolName,
      String bench,
      int threads,
      String policy) {
    return new Step(
        id,
        role,
        criteria,
        protocolName,
        SpXWorker.protocol(protocolName).forks(),
        bench,
        threads,
        policy);
  }

  private List<String> workerArgs(Step step) {
    List<String> args = new ArrayList<>();
    args.add("worker");
    args.addAll(
        List.of(
            "--bench", step.bench(),
            "--protocol", step.protocolName(),
            "--profile", plan.profile().label(),
            "--threads", Integer.toString(step.threads())));
    if (!step.policy().isEmpty()) {
      args.addAll(List.of("--policy", step.policy()));
    }
    return args;
  }

  // ---------------------------------------------------------------- the run

  /**
   * Runs the plan and writes {@code measurements.json} into the result directory.
   *
   * @return 0; a step that fails is recorded as an open item and its criteria stay NOT_RUN
   */
  public static int run(Options options, PrintStream out) throws IOException, InterruptedException {
    SpXRunner runner = new SpXRunner(options, out);
    runner.execute();
    return 0;
  }

  private void execute() throws IOException, InterruptedException {
    Files.createDirectories(options.out());
    out.println(
        "sp-x: "
            + plan.profile().label()
            + " profile, "
            + plan.steps().size()
            + " steps, "
            + plan.totalForks()
            + " forks, deadline "
            + options.deadlineMinutes()
            + " minutes");
    if (options.stretch()) {
      notes.add(
          "SP-X has no stretch series (allocation per record is deferred): the flag is ignored");
    }
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
        out.println("sp-x: step " + step.id() + " failed: " + message);
      }
      long nanos = System.nanoTime() - started;
      stepNanos.put(step.id(), nanos);
      stepSeconds.put(step.id(), nanos / 1_000_000_000L);
      out.println("sp-x: step " + step.id() + " done in " + (nanos / 1_000_000_000L) + " s");
      write();
    }
    write();
    if (options.profile() == Profile.SMOKE) {
      out.println(projection());
    }
  }

  private void runStep(Step step) throws IOException, InterruptedException {
    switch (step.role()) {
      case FACTS -> facts();
      case COMPONENT, INTEGRATED, BISECT, LATENCY -> forks(step);
      case MODEL, FIGURES -> {
        // derived from what exists at every write
      }
    }
  }

  // ---------------------------------------------------------------- X-5, X-6 and the facts

  private void facts() {
    SchemaStats.Stats stats;
    try {
      stats = SchemaStats.ofResource(SyntheticSchema.TXN);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    List<String> outside = stats.outsideTargets();
    outsideTargets = Optional.of((long) outside.size());
    extra.put("schema.outsideTargets", outside);
    extra.put("schema.leafElementsUnderTx", (long) stats.leafElementsUnderTx());
    extra.put("schema.maxDepth", (long) stats.maxDepth());
    extra.put("schema.choices", (long) stats.choices());
    extra.put("schema.unboundedGroups", (long) stats.unboundedGroups());
    extra.put("schema.optionalShare", stats.optionalShare());
    extra.put("schema.maxEnumerationSize", (long) stats.maxEnumerationSize());
    extra.put("schema.patternAlternations", (long) stats.patternAlternations());
    xsd11Rejected = Optional.of(SyntheticSchema.xsd11ConstructsRejected());

    // the serialised size of a record, for the MB/s of the writer
    CountingSink sink = new CountingSink();
    StaxRecordWriter writer = new StaxRecordWriter(sink);
    writer.startDocument("facts", 0L);
    TradeRecordGen trades = new TradeRecordGen(SpXWorker.SEED);
    XmlRecordGen gen = new XmlRecordGen(SpXWorker.SEED);
    for (int i = 0; i < AVERAGE_BYTES_RECORDS; i++) {
      writer.writeTx(gen.next(trades.next()));
    }
    writer.endDocument();
    averageRecordBytes =
        BigDecimal.valueOf(writer.bytesWritten())
            .divide(BigDecimal.valueOf(AVERAGE_BYTES_RECORDS), 1, RoundingMode.HALF_EVEN);

    // the validations of a bisect do not depend on the machine: count them at the profile's size
    int n = plan.profile().sizes().bisectRecords();
    extra.put("bisect.records", (long) n);
    for (int k : new int[] {1, 10, 100}) {
      Bisect.Result result = BisectBench.validationCount(n, k, FACTS_SEED + k);
      if (result.offenders().size() != k) {
        throw new IllegalStateException(
            "the bisect found " + result.offenders().size() + " of " + k);
      }
      BigDecimal perDefect =
          BigDecimal.valueOf(result.validations())
              .divide(BigDecimal.valueOf(k), 3, RoundingMode.HALF_EVEN);
      extra.put("bisect.validationsPerDefect" + (k == 1 ? "" : ".k" + k), perDefect);
    }
  }

  /** Counts the bytes of the stream and discards them. */
  private static final class CountingSink extends OutputStream {
    @Override
    public void write(int b) {}

    @Override
    public void write(byte[] bytes, int offset, int length) {}
  }

  // ---------------------------------------------------------------- the forks

  private void forks(Step step) throws IOException, InterruptedException {
    Protocol protocol = SpXWorker.protocol(step.protocolName());
    ForkSeries result =
        ForkRunner.runForked(
            protocol,
            plan.profile().jvmArgs(),
            SpXMain.class.getName(),
            workerArgs(step),
            deadline);
    forkSeries.put(step.id(), result);
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
  }

  // ---------------------------------------------------------------- the model and the figures

  private static Optional<BigDecimal> median(
      Map<String, ForkSeries> measured, String stepId, BigDecimal scale) {
    ForkSeries s = measured.get(stepId);
    if (s == null) {
      return Optional.empty();
    }
    return ForkStats.median(s).map(m -> m.multiply(scale));
  }

  private static Optional<Estimate> estimate(
      Map<String, ForkSeries> measured, String stepId, BigDecimal scale) {
    ForkSeries s = measured.get(stepId);
    return s == null ? Optional.empty() : ForkStats.estimate(s, scale);
  }

  private static int kept(Map<String, ForkSeries> measured, String stepId) {
    ForkSeries s = measured.get(stepId);
    return s == null ? 0 : s.kept().size();
  }

  private static Optional<Rates> rates(Map<String, ForkSeries> measured) {
    Optional<Estimate> writer = estimate(measured, "writer-t4", PER_THREAD);
    Optional<Estimate> xsd = estimate(measured, "xsd-t4", PER_THREAD);
    Optional<Estimate> gate = estimate(measured, "gate-t4", PER_THREAD);
    if (writer.isEmpty() || xsd.isEmpty() || gate.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Rates(
            writer.get(), xsd.get(), gate.get(), estimate(measured, "bisect-t1", BigDecimal.ONE)));
  }

  /**
   * The model series X-2a, X-2b and X-3 and the figures of the model, from the forks measured so
   * far; {@code why} says what is missing. X-2 takes the lower bounds of the components, X-3 the
   * point estimates. The four-thread component series are aggregates and enter per thread.
   */
  static void model(
      Map<String, ForkSeries> measured,
      List<Series> series,
      Map<String, Object> figures,
      List<String> why) {
    Optional<Rates> rates = rates(measured);
    if (rates.isEmpty()) {
      why.add(
          "model criteria X-2a, X-2b and X-3 not evaluated: a component series (writer, xsd or"
              + " gate at four threads) has fewer than 7 kept forks");
      return;
    }
    Policy[] policies = {PolicyModel.ALL, PolicyModel.SAMPLED_10};
    String[] ids = {"X-2a", "X-2b"};
    List<Capacity> allCapacities = List.of();
    for (int i = 0; i < policies.length; i++) {
      Optional<List<Capacity>> capacities = CapacityModel.capacities(rates.get(), 4, policies[i]);
      if (capacities.isEmpty()) {
        why.add(
            "model criterion "
                + ids[i]
                + " not evaluated: the bisect series has fewer than 7 kept forks");
        continue;
      }
      long minimum = CapacityModel.minimumLowerBound(capacities.get());
      series.add(new CountValue(ids[i], minimum, 1));
      figures.put("model.minLowerBound." + policies[i].name(), minimum);
      for (Capacity c : capacities.get()) {
        String key = "model." + c.policy() + "." + defectKey(c.defectRate());
        figures.put(key + ".lowerBound", Num.dec(c.lowerBound(), 3));
        figures.put(key + ".point", Num.dec(c.point(), 3));
      }
      if (i == 0) {
        allCapacities = capacities.get();
      }
    }
    Optional<Estimate> integrated = estimate(measured, "assemble-ALL", BigDecimal.ONE);
    if (integrated.isEmpty() || allCapacities.isEmpty()) {
      why.add(
          "model criterion X-3 not evaluated: the integrated ALL run has fewer than 7 kept forks");
      return;
    }
    double point = integrated.get().point().doubleValue();
    if (!(point > 0.0)) {
      why.add(
          "model criterion X-3 not evaluated: the median of the integrated ALL run is not positive");
      return;
    }
    series.add(new CountValue("X-3", CapacityModel.deviationExceeds(allCapacities, point), 1));
    figures.put("model.deviation.ALL.0", Num.dec(CapacityModel.deviation(allCapacities, point), 6));
  }

  private static String defectKey(double rate) {
    return rate == 0.0 ? "0" : BigDecimal.valueOf(rate).toPlainString();
  }

  /**
   * The measured-only figures of the series that exist: the writer in records and megabytes per
   * second, the per-record XSD in microseconds, the gate in seconds per file, the bisect and the
   * integrated runs. A four-thread series is an aggregate; its per-thread figure is a quarter.
   */
  static Map<String, Object> figures(
      Map<String, ForkSeries> measured, Sizes sizes, BigDecimal averageRecordBytes) {
    Map<String, Object> f = new TreeMap<>();
    BigDecimal bytes = averageRecordBytes;
    if (bytes.signum() > 0) {
      f.put("xml.avgRecordBytes", bytes);
    }
    Optional<BigDecimal> w4 = median(measured, "writer-t4", PER_THREAD);
    Optional<BigDecimal> w1 = median(measured, "writer-t1", BigDecimal.ONE);
    w4.ifPresent(
        v -> {
          f.put("xml.write.recPerSec.perThread", v.setScale(3, RoundingMode.HALF_EVEN));
          mbPerSec(f, "xml.write.mbPerSec", v, bytes);
        });
    w1.ifPresent(
        v -> {
          f.put("xml.write.recPerSec.t1", v.setScale(3, RoundingMode.HALF_EVEN));
          mbPerSec(f, "xml.write.mbPerSec.t1", v, bytes);
        });
    median(measured, "xsd-t4", PER_THREAD)
        .ifPresent(v -> f.put("xsd.perRecord.usPerRecord", micros(v)));
    median(measured, "xsd-t1", BigDecimal.ONE)
        .ifPresent(v -> f.put("xsd.perRecord.usPerRecord.t1", micros(v)));
    int gateRecords = sizes.gateRecords();
    f.put("gate.fileRecords", (long) gateRecords);
    median(measured, "gate-t1", BigDecimal.ONE)
        .ifPresent(v -> f.put("gate.file100k.seconds.t1", seconds(gateRecords, v)));
    median(measured, "gate-t4", PER_THREAD)
        .ifPresent(v -> f.put("gate.file100k.seconds.t4", seconds(gateRecords, v)));
    median(measured, "bisect-t1", BigDecimal.ONE)
        .ifPresent(v -> f.put("bisect.secondsPerDefect", seconds(1, v)));
    median(measured, "assemble-ALL", BigDecimal.ONE)
        .ifPresent(v -> f.put("integrated.recPerSec.ALL", v.setScale(3, RoundingMode.HALF_EVEN)));
    median(measured, "assemble-SAMPLED_10", BigDecimal.ONE)
        .ifPresent(
            v -> f.put("integrated.recPerSec.SAMPLED_10", v.setScale(3, RoundingMode.HALF_EVEN)));
    f.put("integrated.threads", (long) INTEGRATED_THREADS);
    return f;
  }

  private static void mbPerSec(
      Map<String, Object> f, String key, BigDecimal recPerSec, BigDecimal bytes) {
    if (bytes.signum() > 0) {
      f.put(
          key,
          recPerSec
              .multiply(bytes)
              .divide(BigDecimal.valueOf(1_000_000L), 3, RoundingMode.HALF_EVEN));
    }
  }

  private static BigDecimal micros(BigDecimal recordsPerSecond) {
    return recordsPerSecond.signum() <= 0
        ? BigDecimal.ZERO.setScale(3)
        : BigDecimal.valueOf(1_000_000L).divide(recordsPerSecond, 3, RoundingMode.HALF_EVEN);
  }

  private static BigDecimal seconds(long units, BigDecimal unitsPerSecond) {
    return unitsPerSecond.signum() <= 0
        ? BigDecimal.ZERO.setScale(3)
        : BigDecimal.valueOf(units).divide(unitsPerSecond, 3, RoundingMode.HALF_EVEN);
  }

  /** The median latency in milliseconds per file size for a policy; empty without a kept fork. */
  private Map<Integer, BigDecimal> latency(String policy) {
    ForkSeries s = forkSeries.get("latency-" + policy);
    Map<Integer, BigDecimal> out = new TreeMap<>();
    if (s == null || s.kept().isEmpty()) {
      return out;
    }
    List<Integer> sizes = plan.profile().sizes().latencySizes();
    for (int i = 0; i < sizes.size(); i++) {
      final int index = i;
      double[] values =
          s.kept().stream()
              .filter(f -> f.windowRates().size() > index)
              .mapToDouble(f -> f.windowRates().get(index))
              .toArray();
      if (values.length > 0) {
        out.put(sizes.get(i), Num.dec(Stats.median(values), 3));
      }
    }
    return out;
  }

  /** The measured-only series X-4, when every part of it was measured at least once. */
  private Optional<Series> figuresSeries(List<String> why) {
    List<String> needed =
        List.of(
            "writer-t1",
            "writer-t4",
            "xsd-t1",
            "xsd-t4",
            "gate-t1",
            "gate-t4",
            "bisect-t1",
            "latency-ALL");
    int least = Integer.MAX_VALUE;
    for (String id : needed) {
      least = Math.min(least, kept(forkSeries, id));
    }
    Optional<BigDecimal> value = median(forkSeries, "writer-t4", PER_THREAD);
    if (least < 1 || value.isEmpty()) {
      why.add("X-4 not recorded: a component, the bisect or the latency series has no kept fork");
      return Optional.empty();
    }
    return Optional.of(
        new MeasuredOnly(
            "X-4",
            true,
            least,
            value.get().setScale(3, RoundingMode.HALF_EVEN),
            "records/s per thread (writer, four threads busy)"));
  }

  // ---------------------------------------------------------------- the file

  private MeasurementSet assemble(
      String scope, List<Series> series, Map<String, Object> decisions, List<String> open) {
    Map<String, Object> source = new LinkedHashMap<>();
    Sizes sizes = plan.profile().sizes();
    source.put("profile", plan.profile().label());
    source.put("stretch", plan.stretch());
    source.put("part", "all");
    source.put("seed", SpXWorker.SEED);
    source.put("writerRing", (long) sizes.writerRing());
    source.put("xsdRing", (long) sizes.xsdRing());
    source.put("gateRecords", (long) sizes.gateRecords());
    source.put("bisectRecords", (long) sizes.bisectRecords());
    source.put("recordsPerFile", (long) sizes.recordsPerFile());
    Map<String, Object> env = new LinkedHashMap<>(EnvCapture.capture());
    env.put("fork.jvmArgs", String.join(" ", plan.profile().jvmArgs()));
    Map<Resource, List<BigDecimal>> canaryCopy = new EnumMap<>(Resource.class);
    canary.forEach((resource, samples) -> canaryCopy.put(resource, List.copyOf(samples)));
    return new MeasurementSet(
        "SP-X",
        configDigest(),
        scope,
        series,
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
    Sizes sizes = plan.profile().sizes();
    Map<String, Object> planMap = new LinkedHashMap<>();
    planMap.put("seed", SpXWorker.SEED);
    planMap.put("writerRing", (long) sizes.writerRing());
    planMap.put("xsdRing", (long) sizes.xsdRing());
    planMap.put("gateRecords", (long) sizes.gateRecords());
    planMap.put("bisectRecords", (long) sizes.bisectRecords());
    planMap.put("recordsPerFile", (long) sizes.recordsPerFile());
    planMap.put("latencySizes", sizes.latencySizes().stream().map(Integer::longValue).toList());
    planMap.put("jvmArgs", plan.profile().jvmArgs());
    List<Object> steps = new ArrayList<>();
    for (Step step : plan.steps()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", step.id());
      m.put("role", step.role().name());
      m.put("criteria", step.criteria());
      m.put("protocol", step.protocolName());
      m.put("forks", (long) step.forks());
      m.put("bench", step.bench());
      m.put("threads", (long) step.threads());
      m.put("policy", step.policy());
      steps.add(m);
    }
    planMap.put("steps", steps);
    return MeasurementSet.configDigest(
        "SP-X", plan.profile().label(), plan.stretch(), criteria.digest().hex(), planMap);
  }

  /** The series that are not derived from the model: facts, the integrated runs. */
  private List<Series> measuredSeries() {
    List<Series> all = new ArrayList<>();
    xsd11Rejected.ifPresent(v -> all.add(new CountValue("X-5", v, 1)));
    outsideTargets.ifPresent(v -> all.add(new CountValue("X-6", v, 1)));
    for (String policy : List.of(ALL, SAMPLED)) {
      ForkSeries s = forkSeries.get("assemble-" + policy);
      if (s != null) {
        String id = policy.equals(ALL) ? "X-1a" : "X-1b";
        all.add(new ForkValues(id, "records/s", BigDecimal.ONE, ForkStats.of(s)));
      }
    }
    return all;
  }

  /** Evaluates what exists, derives the decisions from the verdicts and rewrites the file. */
  private void write() throws IOException {
    String scope = complete && plan.profile() == Profile.FULL ? "FULL" : "PARTIAL";
    List<String> why = new ArrayList<>();
    Map<String, Object> figures = figures(forkSeries, plan.profile().sizes(), averageRecordBytes);
    List<Series> series = measuredSeries();
    model(forkSeries, series, figures, why);
    figuresSeries(why).ifPresent(series::add);

    MeasurementSet bare = assemble(scope, series, Map.of(), List.of());
    Map<String, Object> timeBox = new LinkedHashMap<>();
    timeBox.put("usedMinutes", 0L);
    timeBox.put("boxMinutes", criteria.box("SP-X").toMinutes());
    SpikeResult evaluated = ResultsEvaluator.evaluate(bare, criteria, "RAW", timeBox);
    Map<String, Verdict> verdicts = new LinkedHashMap<>();
    for (CriterionResult r : evaluated.criteria()) {
      verdicts.put(r.id(), r.verdict());
    }
    Map<String, Map<Integer, BigDecimal>> latency = new LinkedHashMap<>();
    for (String policy : List.of(ALL, SAMPLED)) {
      Map<Integer, BigDecimal> perSize = latency(policy);
      if (!perSize.isEmpty()) {
        latency.put(policy, perSize);
      }
    }
    SpXDecisions.Derived derived =
        SpXDecisions.derive(new SpXDecisions.Inputs(verdicts, latency, merge(figures)));
    Map<String, Object> decisions = new LinkedHashMap<>(derived.decisions());
    List<String> open = new ArrayList<>(notes);
    open.addAll(why);
    open.addAll(derived.open());
    MeasurementSet full = assemble(scope, series, decisions, open);
    AtomicFile.write(options.out().resolve("measurements.json"), full.toJson() + "\n");
  }

  /** The facts and the figures, in one map for the decisions. */
  private Map<String, Object> merge(Map<String, Object> figures) {
    Map<String, Object> all = new LinkedHashMap<>(extra);
    all.putAll(figures);
    return all;
  }

  // ---------------------------------------------------------------- the projection

  private String projection() {
    Plan full = plan(Profile.FULL, options.stretch());
    Sizes smoke = Profile.SMOKE.sizes();
    double gen = generationSecondsPerRecord();
    Step first =
        plan.steps().stream()
            .filter(s -> s.role() == Role.COMPONENT && s.bench().equals("writer"))
            .findFirst()
            .orElseThrow();
    int ran = Math.max(1, forksRun.getOrDefault(first.id(), 1));
    double measured = stepNanos.getOrDefault(first.id(), 0L) / 1e9 / ran;
    double fixed =
        Math.max(
            0.5,
            measured
                - Projection.windowForkSeconds(Protocol.smoke(), 0, false)
                - gen * smoke.writerRing());
    Map<String, Double> rates = new LinkedHashMap<>();
    for (Map.Entry<String, ForkSeries> e : forkSeries.entrySet()) {
      ForkStats.median(e.getValue()).ifPresent(m -> rates.put(e.getKey(), m.doubleValue()));
    }
    double latencySeconds;
    Map<Integer, BigDecimal> smokeLatency = latency(ALL);
    if (!smokeLatency.isEmpty()) {
      double records = smokeLatency.keySet().stream().mapToInt(Integer::intValue).sum();
      double seconds =
          smokeLatency.values().stream().mapToDouble(BigDecimal::doubleValue).sum() / 1000.0;
      latencySeconds = seconds / records;
    } else {
      latencySeconds = 1.0 / Projection.ASSUMED_RATE;
    }
    Projection.Observed observed =
        new Projection.Observed(
            fixed, gen, rates, latencySeconds, stepNanos.getOrDefault("facts", 0L) / 1e9);
    Projection.Result result = Projection.project(full, smoke, observed);
    String verdict;
    if (result.worstMinutes() <= PROJECTION_LIMIT_MINUTES) {
      verdict = "within 85% of the 275-minute deadline (" + PROJECTION_LIMIT_MINUTES + " minutes)";
    } else if (result.typicalMinutes() <= PROJECTION_LIMIT_MINUTES) {
      verdict =
          "typical case within, worst case ABOVE 85% of the 275-minute deadline ("
              + PROJECTION_LIMIT_MINUTES
              + " minutes): tell the owner";
    } else {
      verdict =
          "ABOVE 85% of the 275-minute deadline ("
              + PROJECTION_LIMIT_MINUTES
              + " minutes): tell the owner and propose fewer forks, never below 7 per series";
    }
    String assumed =
        result.assumed().isEmpty()
            ? ""
            : "; assumed rate for " + String.join(", ", result.assumed()) + " (no smoke rate)";
    return "projected full duration: "
        + Math.round(result.typicalMinutes())
        + " minutes typical, "
        + Math.round(result.worstMinutes())
        + " minutes if every warm-up runs to its limit ("
        + full.totalForks()
        + " forks, "
        + String.format(Locale.ROOT, "%.1f", fixed)
        + " s fixed cost per fork from the measured first series, "
        + String.format(Locale.ROOT, "%.0f", gen * 1e6)
        + " us to generate and write a record"
        + assumed
        + "): "
        + verdict;
  }

  /** Seconds to generate and write one record in this JVM (the second of two rounds). */
  private static double generationSecondsPerRecord() {
    try {
      Path dir = Files.createTempDirectory("sp-x-gen-");
      try {
        double seconds = 0;
        for (int round = 0; round < 2; round++) {
          long start = System.nanoTime();
          FileGateBench.generate(dir, GEN_RECORDS + round, SpXWorker.SEED);
          seconds = (System.nanoTime() - start) / 1e9 / (GEN_RECORDS + round);
        }
        return seconds;
      } finally {
        SpXWorker.deleteTree(dir);
      }
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
