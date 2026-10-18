package dev.regpack.bench.eval;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a run measured, before any verdict: the input of {@link ResultsEvaluator}. The file is
 * {@code measurements.json}, schema {@code regpack.bench.measurements/1}, written in canonical JSON
 * and read strictly (unknown keys, a malformed digest and every {@code double} are rejected),
 * because a measurement set that comes back from CI is data from outside.
 *
 * <p>The sets of the parts and attempts of one configuration are pooled with {@link #merge}: the
 * forks of one criterion pool, the counts add, and the provenance of every run is kept, so that the
 * number of virtual machines behind a pooled estimate stays visible.
 */
public final class MeasurementSet {
  /** The schema label of the file. */
  public static final String SCHEMA = "regpack.bench.measurements/1";

  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SPIKE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
  private static final Pattern CRITERION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
  private static final Set<String> SCOPES = Set.of("FULL", "PARTIAL", "DOCUMENT");

  /** Keys that describe where and when a run happened, never what was configured. */
  private static final Set<String> IDENTITY_KEYS =
      Set.of("gitSha", "part", "runId", "attempt", "env", "cpuModel", "imageVersion");

  /** One kind of measurement of one criterion. */
  public sealed interface Series permits ForkValues, CountValue, MeasuredOnly, Projection {
    String criterionId();
  }

  /**
   * The statistic of one fork.
   *
   * @param index position of the fork in its series, from 0
   * @param statistic the fork statistic, before the series scale
   * @param discarded the fork is excluded from the estimate
   * @param discardReason empty if and only if the fork is not discarded
   * @param unstable the warm-up of the fork never settled (P-2)
   */
  public record ForkStat(
      int index, BigDecimal statistic, boolean discarded, String discardReason, boolean unstable) {
    public ForkStat {
      Objects.requireNonNull(statistic, "statistic");
      Objects.requireNonNull(discardReason, "discardReason");
      if (index < 0) {
        throw new IllegalArgumentException("fork index must not be negative: " + index);
      }
      if (discarded == discardReason.isEmpty()) {
        throw new IllegalArgumentException(
            "a fork has a discard reason if and only if it is discarded");
      }
    }
  }

  /**
   * The fork statistics of a criterion measured by repetitions.
   *
   * @param criterionId the criterion
   * @param unit the unit of the reported value (after the scale)
   * @param scale multiplies each statistic: aggregate to per thread, seconds to hours
   * @param forks every fork that was started, discarded ones included; the indexes are unique and
   *     ascending
   */
  public record ForkValues(String criterionId, String unit, BigDecimal scale, List<ForkStat> forks)
      implements Series {
    public ForkValues {
      checkId(criterionId);
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(scale, "scale");
      if (scale.signum() <= 0) {
        throw new IllegalArgumentException(criterionId + ": the scale must be positive");
      }
      forks = List.copyOf(forks);
      for (int i = 1; i < forks.size(); i++) {
        if (forks.get(i).index() <= forks.get(i - 1).index()) {
          throw new IllegalArgumentException(
              criterionId + ": fork indexes must be unique and ascending");
        }
      }
    }
  }

  /**
   * An exact count.
   *
   * @param criterionId the criterion
   * @param observed what was counted
   * @param validRuns the runs that count (a count of a single run cannot pass a criterion that
   *     needs two)
   */
  public record CountValue(String criterionId, long observed, int validRuns) implements Series {
    public CountValue {
      checkId(criterionId);
      if (observed < 0 || validRuns < 0) {
        throw new IllegalArgumentException(
            criterionId + ": a count and a run count are not negative");
      }
    }
  }

  /**
   * A value that is recorded and compared with nothing.
   *
   * @param criterionId the criterion
   * @param invariantsHold false if an invariant of the measurement broke
   * @param n the number of observations behind the value
   * @param value the recorded value
   * @param unit its unit
   */
  public record MeasuredOnly(
      String criterionId, boolean invariantsHold, int n, BigDecimal value, String unit)
      implements Series {
    public MeasuredOnly {
      checkId(criterionId);
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(unit, "unit");
      if (n < 0) {
        throw new IllegalArgumentException(criterionId + ": n must not be negative");
      }
    }
  }

  /**
   * Measurements at several sizes, projected to a target size. The Theil-Sen line is evaluated at
   * {@code target} with the slope and its bounds, each result is multiplied by {@code scale} and
   * raised to at least {@code floor} (a physical lower bound in the reported unit; zero for none).
   * Fewer than 3 points is INCONCLUSIVE; the series is evaluated with 3 as the least count.
   *
   * @param criterionId the criterion
   * @param unit the unit of the reported value (after the scale)
   * @param scale multiplies the projected value
   * @param xs the measured sizes
   * @param ys the measured values at those sizes
   * @param target the size to project to
   * @param floor the physical floor of the projected value, in the reported unit
   */
  public record Projection(
      String criterionId,
      String unit,
      BigDecimal scale,
      List<BigDecimal> xs,
      List<BigDecimal> ys,
      BigDecimal target,
      BigDecimal floor)
      implements Series {
    public Projection {
      checkId(criterionId);
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(scale, "scale");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(floor, "floor");
      xs = List.copyOf(xs);
      ys = List.copyOf(ys);
      if (scale.signum() <= 0) {
        throw new IllegalArgumentException(criterionId + ": the scale must be positive");
      }
      if (xs.size() != ys.size()) {
        throw new IllegalArgumentException(criterionId + ": xs and ys differ in length");
      }
    }
  }

  /**
   * Where one part of a dispatch ran.
   *
   * @param runId the run (virtual machine) identifier
   * @param attempt the attempt number of the run
   * @param part the part of the dispatch
   * @param gitSha the commit that was measured
   * @param cpuModel the CPU model of the machine
   * @param imageVersion the runner image version
   */
  public record RunInfo(
      String runId, int attempt, String part, String gitSha, String cpuModel, String imageVersion) {
    public RunInfo {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(part, "part");
      Objects.requireNonNull(gitSha, "gitSha");
      Objects.requireNonNull(cpuModel, "cpuModel");
      Objects.requireNonNull(imageVersion, "imageVersion");
      if (attempt < 0) {
        throw new IllegalArgumentException("attempt must not be negative");
      }
    }
  }

  private final String spike;
  private final String configDigest;
  private final String scope;
  private final List<Series> series;
  private final Map<Resource, List<BigDecimal>> canary;
  private final List<RunInfo> runs;
  private final Map<String, Object> source;
  private final Map<String, Object> env;
  private final Map<String, Object> decisions;
  private final List<String> open;

  /**
   * @param spike the spike id
   * @param configDigest 64 lowercase hex characters, see {@link #configDigest}
   * @param scope FULL, PARTIAL or DOCUMENT
   * @param series the measurements, at most one per criterion
   * @param canary the canary readings per resource, megabytes or operations per second
   * @param runs the provenance of every run pooled in this set
   * @param source what was measured (for example {@code gitSha})
   * @param env the environment of the run
   * @param decisions the decisions the spike derives
   * @param open what is left open
   */
  public MeasurementSet(
      String spike,
      String configDigest,
      String scope,
      List<Series> series,
      Map<Resource, List<BigDecimal>> canary,
      List<RunInfo> runs,
      Map<String, Object> source,
      Map<String, Object> env,
      Map<String, Object> decisions,
      List<String> open) {
    this.spike = require(SPIKE, spike, "spike");
    this.configDigest = require(HEX64, configDigest, "configDigest");
    if (!SCOPES.contains(scope)) {
      throw new IllegalArgumentException("scope must be FULL, PARTIAL or DOCUMENT: " + scope);
    }
    this.scope = scope;
    Set<String> ids = new LinkedHashSet<>();
    for (Series s : series) {
      if (!ids.add(s.criterionId())) {
        throw new IllegalArgumentException("two series for " + s.criterionId());
      }
    }
    this.series = List.copyOf(series);
    Map<Resource, List<BigDecimal>> copy = new EnumMap<>(Resource.class);
    canary.forEach((resource, samples) -> copy.put(resource, List.copyOf(samples)));
    this.canary = Collections.unmodifiableMap(copy);
    this.runs = List.copyOf(runs);
    this.source = frozenMap(source, "source");
    this.env = frozenMap(env, "env");
    this.decisions = frozenMap(decisions, "decisions");
    this.open = List.copyOf(open);
  }

  /** The spike id. */
  public String spike() {
    return spike;
  }

  /** The configuration digest shared by all parts of one dispatch. */
  public String configDigest() {
    return configDigest;
  }

  /** FULL, PARTIAL or DOCUMENT. */
  public String scope() {
    return scope;
  }

  /** The measurements, in file order. */
  public List<Series> series() {
    return series;
  }

  /** The canary readings per resource. */
  public Map<Resource, List<BigDecimal>> canary() {
    return canary;
  }

  /** The provenance of every pooled run. */
  public List<RunInfo> runs() {
    return runs;
  }

  /** What was measured. */
  public Map<String, Object> source() {
    return source;
  }

  /** The environment. */
  public Map<String, Object> env() {
    return env;
  }

  /** The derived decisions. */
  public Map<String, Object> decisions() {
    return decisions;
  }

  /** What is left open. */
  public List<String> open() {
    return open;
  }

  /** The number of distinct run ids: the virtual machines behind the pooled forks. */
  public int vmCount() {
    return (int) runs.stream().map(RunInfo::runId).distinct().count();
  }

  /** True when the pooled runs used more than one CPU model. */
  public boolean mixedHardware() {
    return runs.stream().map(RunInfo::cpuModel).distinct().count() > 1;
  }

  // ---------------------------------------------------------------- configDigest

  /**
   * The SHA-256 hex of the canonical JSON of {@code {spike, profile, stretch, criteriaDigest,
   * plan}}, where the plan is the run plan's protocol and run parameters. Keys that say where a run
   * happened ({@code gitSha, part, runId, attempt, env, cpuModel, imageVersion}) are dropped at any
   * depth, so every part of one dispatch shares the digest while a changed profile, stretch flag,
   * criteria digest or parameter changes it.
   */
  public static String configDigest(
      String spike,
      String profile,
      boolean stretch,
      String criteriaDigest,
      Map<String, Object> plan) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("spike", spike);
    content.put("profile", profile);
    content.put("stretch", stretch);
    content.put("criteriaDigest", criteriaDigest);
    content.put("plan", withoutIdentity(plan));
    return Sha256.ofUtf8(Json.canonical(content)).hex();
  }

  private static Object withoutIdentity(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException("object keys must be strings");
        }
        if (!IDENTITY_KEYS.contains(key)) {
          out.put(key, withoutIdentity(entry.getValue()));
        }
      }
      return out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object element : list) {
        out.add(withoutIdentity(element));
      }
      return out;
    }
    return value;
  }

  // ---------------------------------------------------------------- merge

  /**
   * Pools two sets of one configuration (equal spike and {@code configDigest}). A criterion in both
   * sets pools: forks are appended and renumbered, counts add, a measured-only series adds its
   * observations (its value is the first set's) and a projection appends its points; a criterion in
   * one set is kept. The canary readings and the runs concatenate, the open lists join, the scope
   * is PARTIAL unless both agree, a decision that differs keeps the first value and adds an open
   * item that names it (a measured decision is recomputed from the pooled data), source and
   * environment keep this set's entries first.
   */
  public MeasurementSet merge(MeasurementSet other) {
    if (!spike.equals(other.spike)) {
      throw new IllegalArgumentException("cannot merge " + spike + " with " + other.spike);
    }
    if (!configDigest.equals(other.configDigest)) {
      throw new IllegalArgumentException(
          "cannot merge sets with different configDigest: "
              + configDigest
              + " and "
              + other.configDigest);
    }
    Map<String, Series> pooled = new LinkedHashMap<>();
    for (Series s : series) {
      pooled.put(s.criterionId(), s);
    }
    for (Series s : other.series) {
      Series mine = pooled.get(s.criterionId());
      pooled.put(s.criterionId(), mine == null ? s : pool(mine, s));
    }
    Map<Resource, List<BigDecimal>> mergedCanary = new EnumMap<>(Resource.class);
    for (Resource r : Resource.values()) {
      List<BigDecimal> joined = new ArrayList<>(canary.getOrDefault(r, List.of()));
      joined.addAll(other.canary.getOrDefault(r, List.of()));
      if (!joined.isEmpty()) {
        mergedCanary.put(r, joined);
      }
    }
    List<RunInfo> mergedRuns = new ArrayList<>(runs);
    mergedRuns.addAll(other.runs);
    Set<String> mergedOpen = new LinkedHashSet<>(open);
    mergedOpen.addAll(other.open);
    Map<String, Object> mergedDecisions = new LinkedHashMap<>(decisions);
    other.decisions.forEach(
        (key, value) -> {
          Object existing = mergedDecisions.putIfAbsent(key, value);
          if (existing != null && !Json.canonical(existing).equals(Json.canonical(value))) {
            // a measured decision (a p99, a knee) differs between the parts of one configuration:
            // pooling must not fail there, the first value stays and the conflict is stated
            mergedOpen.add(
                "decision "
                    + key
                    + " differs between the pooled sets: kept "
                    + shortText(Json.canonical(existing))
                    + ", dropped "
                    + shortText(Json.canonical(value))
                    + "; recompute it from the pooled measurements");
          }
        });
    Map<String, Object> mergedSource = new LinkedHashMap<>(source);
    other.source.forEach(mergedSource::putIfAbsent);
    Map<String, Object> mergedEnv = new LinkedHashMap<>(env);
    other.env.forEach(mergedEnv::putIfAbsent);
    return new MeasurementSet(
        spike,
        configDigest,
        scope.equals(other.scope) ? scope : "PARTIAL",
        new ArrayList<>(pooled.values()),
        mergedCanary,
        mergedRuns,
        mergedSource,
        mergedEnv,
        mergedDecisions,
        new ArrayList<>(mergedOpen));
  }

  private static String shortText(String text) {
    return text.length() <= 60 ? text : text.substring(0, 60) + "...";
  }

  private static Series pool(Series a, Series b) {
    String id = a.criterionId();
    if (a instanceof ForkValues x && b instanceof ForkValues y) {
      if (!x.unit().equals(y.unit()) || x.scale().compareTo(y.scale()) != 0) {
        throw new IllegalArgumentException(id + ": the two sets differ in unit or scale");
      }
      List<ForkStat> forks = new ArrayList<>();
      for (ForkStat f : x.forks()) {
        forks.add(renumbered(f, forks.size()));
      }
      for (ForkStat f : y.forks()) {
        forks.add(renumbered(f, forks.size()));
      }
      return new ForkValues(id, x.unit(), x.scale(), forks);
    }
    if (a instanceof CountValue x && b instanceof CountValue y) {
      try {
        return new CountValue(
            id,
            Math.addExact(x.observed(), y.observed()),
            Math.addExact(x.validRuns(), y.validRuns()));
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(id + ": the counts overflow", e);
      }
    }
    if (a instanceof MeasuredOnly x && b instanceof MeasuredOnly y) {
      if (!x.unit().equals(y.unit())) {
        throw new IllegalArgumentException(id + ": the two sets differ in unit");
      }
      return new MeasuredOnly(
          id, x.invariantsHold() && y.invariantsHold(), x.n() + y.n(), x.value(), x.unit());
    }
    if (a instanceof Projection x && b instanceof Projection y) {
      if (!x.unit().equals(y.unit())
          || x.scale().compareTo(y.scale()) != 0
          || x.target().compareTo(y.target()) != 0
          || x.floor().compareTo(y.floor()) != 0) {
        throw new IllegalArgumentException(
            id + ": the two sets differ in unit, scale, target or floor");
      }
      List<BigDecimal> xs = new ArrayList<>(x.xs());
      xs.addAll(y.xs());
      List<BigDecimal> ys = new ArrayList<>(x.ys());
      ys.addAll(y.ys());
      return new Projection(id, x.unit(), x.scale(), xs, ys, x.target(), x.floor());
    }
    throw new IllegalArgumentException(id + ": the two sets hold different kinds of series");
  }

  private static ForkStat renumbered(ForkStat f, int index) {
    return new ForkStat(index, f.statistic(), f.discarded(), f.discardReason(), f.unstable());
  }

  // ---------------------------------------------------------------- JSON

  /** The canonical JSON of this set (sorted keys, no whitespace, no exponent notation). */
  public String toJson() {
    Map<String, Object> top = new LinkedHashMap<>();
    top.put("schema", SCHEMA);
    top.put("spike", spike);
    top.put("configDigest", configDigest);
    top.put("scope", scope);
    List<Object> list = new ArrayList<>();
    for (Series s : series) {
      list.add(seriesToMap(s));
    }
    top.put("series", list);
    Map<String, Object> canaryOut = new LinkedHashMap<>();
    for (Resource r : Resource.values()) {
      canaryOut.put(r.name(), new ArrayList<>(canary.getOrDefault(r, List.of())));
    }
    top.put("canary", canaryOut);
    List<Object> runList = new ArrayList<>();
    for (RunInfo run : runs) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("runId", run.runId());
      m.put("attempt", (long) run.attempt());
      m.put("part", run.part());
      m.put("gitSha", run.gitSha());
      m.put("cpuModel", run.cpuModel());
      m.put("imageVersion", run.imageVersion());
      runList.add(m);
    }
    top.put("runs", runList);
    top.put("source", source);
    top.put("env", env);
    top.put("decisions", decisions);
    top.put("open", new ArrayList<>(open));
    return Json.canonical(top);
  }

  private static Map<String, Object> seriesToMap(Series s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("criterionId", s.criterionId());
    switch (s) {
      case ForkValues f -> {
        m.put("kind", "forks");
        m.put("unit", f.unit());
        m.put("scale", f.scale());
        List<Object> forks = new ArrayList<>();
        for (ForkStat stat : f.forks()) {
          Map<String, Object> fm = new LinkedHashMap<>();
          fm.put("index", (long) stat.index());
          fm.put("statistic", stat.statistic());
          fm.put("discarded", stat.discarded());
          fm.put("discardReason", stat.discardReason());
          fm.put("unstable", stat.unstable());
          forks.add(fm);
        }
        m.put("forks", forks);
      }
      case CountValue c -> {
        m.put("kind", "count");
        m.put("observed", c.observed());
        m.put("validRuns", (long) c.validRuns());
      }
      case MeasuredOnly o -> {
        m.put("kind", "measuredOnly");
        m.put("invariantsHold", o.invariantsHold());
        m.put("n", (long) o.n());
        m.put("value", o.value());
        m.put("unit", o.unit());
      }
      case Projection p -> {
        m.put("kind", "projection");
        m.put("unit", p.unit());
        m.put("scale", p.scale());
        m.put("xs", new ArrayList<>(p.xs()));
        m.put("ys", new ArrayList<>(p.ys()));
        m.put("target", p.target());
        m.put("floor", p.floor());
      }
    }
    return m;
  }

  /** Parses {@link #toJson()}; every deviation from the schema is an IllegalArgumentException. */
  public static MeasurementSet parse(String json) {
    Object root;
    try {
      root = Json.parse(json);
    } catch (JsonException e) {
      throw new IllegalArgumentException("malformed measurements: " + e.getMessage(), e);
    }
    Map<String, Object> top = object(root, "root");
    keys(
        top,
        Set.of("schema", "spike", "configDigest", "scope", "series", "runs"),
        Set.of("canary", "source", "env", "decisions", "open"),
        "root");
    if (!SCHEMA.equals(top.get("schema"))) {
      throw new IllegalArgumentException("schema must be " + SCHEMA);
    }
    List<Series> series = new ArrayList<>();
    for (Object item : list(top.get("series"), "series")) {
      series.add(parseSeries(object(item, "series[]")));
    }
    Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
    if (top.containsKey("canary")) {
      Map<String, Object> raw = object(top.get("canary"), "canary");
      for (Map.Entry<String, Object> entry : raw.entrySet()) {
        Resource resource;
        try {
          resource = Resource.valueOf(entry.getKey());
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("canary: unknown resource " + entry.getKey());
        }
        canary.put(resource, decimals(entry.getValue(), "canary." + entry.getKey()));
      }
    }
    List<RunInfo> runs = new ArrayList<>();
    for (Object item : list(top.get("runs"), "runs")) {
      Map<String, Object> m = object(item, "runs[]");
      keys(
          m,
          Set.of("runId", "attempt", "part", "gitSha", "cpuModel", "imageVersion"),
          Set.of(),
          "run");
      runs.add(
          new RunInfo(
              text(m.get("runId"), "runId"),
              integer(m.get("attempt"), "attempt"),
              text(m.get("part"), "part"),
              text(m.get("gitSha"), "gitSha"),
              text(m.get("cpuModel"), "cpuModel"),
              text(m.get("imageVersion"), "imageVersion")));
    }
    List<String> open = new ArrayList<>();
    if (top.containsKey("open")) {
      for (Object item : list(top.get("open"), "open")) {
        open.add(text(item, "open[]"));
      }
    }
    return new MeasurementSet(
        text(top.get("spike"), "spike"),
        text(top.get("configDigest"), "configDigest"),
        text(top.get("scope"), "scope"),
        series,
        canary,
        runs,
        top.containsKey("source") ? object(top.get("source"), "source") : Map.of(),
        top.containsKey("env") ? object(top.get("env"), "env") : Map.of(),
        top.containsKey("decisions") ? object(top.get("decisions"), "decisions") : Map.of(),
        open);
  }

  private static Series parseSeries(Map<String, Object> m) {
    String kind = text(m.get("kind"), "kind");
    String id = text(m.get("criterionId"), "criterionId");
    switch (kind) {
      case "forks" -> {
        keys(m, Set.of("kind", "criterionId", "unit", "scale", "forks"), Set.of(), "series " + id);
        List<ForkStat> forks = new ArrayList<>();
        for (Object item : list(m.get("forks"), "forks")) {
          Map<String, Object> f = object(item, "forks[]");
          keys(
              f,
              Set.of("index", "statistic", "discarded", "discardReason", "unstable"),
              Set.of(),
              "fork");
          forks.add(
              new ForkStat(
                  integer(f.get("index"), "index"),
                  decimal(f.get("statistic"), "statistic"),
                  bool(f.get("discarded"), "discarded"),
                  string(f.get("discardReason"), "discardReason"),
                  bool(f.get("unstable"), "unstable")));
        }
        return new ForkValues(
            id, string(m.get("unit"), "unit"), decimal(m.get("scale"), "scale"), forks);
      }
      case "count" -> {
        keys(m, Set.of("kind", "criterionId", "observed", "validRuns"), Set.of(), "series " + id);
        return new CountValue(
            id, longValue(m.get("observed"), "observed"), integer(m.get("validRuns"), "validRuns"));
      }
      case "measuredOnly" -> {
        keys(
            m,
            Set.of("kind", "criterionId", "invariantsHold", "n", "value", "unit"),
            Set.of(),
            "series " + id);
        return new MeasuredOnly(
            id,
            bool(m.get("invariantsHold"), "invariantsHold"),
            integer(m.get("n"), "n"),
            decimal(m.get("value"), "value"),
            string(m.get("unit"), "unit"));
      }
      case "projection" -> {
        keys(
            m,
            Set.of("kind", "criterionId", "unit", "scale", "xs", "ys", "target", "floor"),
            Set.of(),
            "series " + id);
        return new Projection(
            id,
            string(m.get("unit"), "unit"),
            decimal(m.get("scale"), "scale"),
            decimals(m.get("xs"), "xs"),
            decimals(m.get("ys"), "ys"),
            decimal(m.get("target"), "target"),
            decimal(m.get("floor"), "floor"));
      }
      default -> throw new IllegalArgumentException("unknown series kind " + kind);
    }
  }

  // ---------------------------------------------------------------- helpers

  private static void checkId(String id) {
    require(CRITERION_ID, id, "criterionId");
  }

  private static String require(Pattern pattern, String value, String name) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " is malformed: " + value);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String where) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new IllegalArgumentException(where + " must be an object");
  }

  private static List<?> list(Object value, String where) {
    if (value instanceof List<?> list) {
      return list;
    }
    throw new IllegalArgumentException(where + " must be an array");
  }

  private static void keys(
      Map<String, Object> m, Set<String> required, Set<String> optional, String where) {
    for (String key : required) {
      if (!m.containsKey(key)) {
        throw new IllegalArgumentException(where + ": missing " + key);
      }
    }
    for (String key : m.keySet()) {
      if (!required.contains(key) && !optional.contains(key)) {
        throw new IllegalArgumentException(where + ": unknown key " + key);
      }
    }
  }

  private static String text(Object value, String where) {
    if (value instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException(where + " must be a non-empty string");
  }

  private static String string(Object value, String where) {
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException(where + " must be a string");
  }

  private static boolean bool(Object value, String where) {
    if (value instanceof Boolean b) {
      return b;
    }
    throw new IllegalArgumentException(where + " must be true or false");
  }

  private static BigDecimal decimal(Object value, String where) {
    if (value instanceof Long l) {
      return BigDecimal.valueOf(l);
    }
    if (value instanceof BigDecimal d) {
      return d;
    }
    throw new IllegalArgumentException(where + " must be a number");
  }

  private static long longValue(Object value, String where) {
    if (value instanceof Long l) {
      return l;
    }
    throw new IllegalArgumentException(where + " must be an integer");
  }

  private static int integer(Object value, String where) {
    long l = longValue(value, where);
    if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(where + " is out of range");
    }
    return (int) l;
  }

  private static List<BigDecimal> decimals(Object value, String where) {
    List<BigDecimal> out = new ArrayList<>();
    for (Object item : list(value, where)) {
      out.add(decimal(item, where + "[]"));
    }
    return out;
  }

  /** A deep immutable copy that accepts JSON types only: no double, no null, string keys. */
  private static Map<String, Object> frozenMap(Map<String, Object> map, String where) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      out.put(entry.getKey(), freeze(entry.getValue(), where + "." + entry.getKey()));
    }
    return Collections.unmodifiableMap(out);
  }

  private static Object freeze(Object value, String where) {
    switch (value) {
      case null -> throw new IllegalArgumentException(where + " is null");
      case String s -> {
        return s;
      }
      case Boolean b -> {
        return b;
      }
      case Long l -> {
        return l;
      }
      case Integer i -> {
        return (long) i;
      }
      case Short s -> {
        return (long) s;
      }
      case Byte b -> {
        return (long) b;
      }
      case BigDecimal d -> {
        return d;
      }
      case Map<?, ?> map -> {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw new IllegalArgumentException(where + ": object keys must be strings");
          }
          out.put(key, freeze(entry.getValue(), where + "." + key));
        }
        return Collections.unmodifiableMap(out);
      }
      case List<?> list -> {
        List<Object> out = new ArrayList<>();
        for (Object element : list) {
          out.add(freeze(element, where + "[]"));
        }
        return Collections.unmodifiableList(out);
      }
      default ->
          throw new IllegalArgumentException(
              where
                  + ": not a JSON value (a double is not allowed): "
                  + value.getClass().getName());
    }
  }
}
