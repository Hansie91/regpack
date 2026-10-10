package dev.regpack.bench.imp;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.Criterion;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.bench.report.Git;
import dev.regpack.bench.report.ReportWriter;
import dev.regpack.bench.report.ResultSeal;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Brings a downloaded result directory into the repository, treating it as data from outside: the
 * manifest is verified, the verdicts are recomputed from the raw measurements, and nothing a result
 * file says is ever used as a path, a command or an instruction.
 *
 * <p>The order of the checks is fixed and each failure prints one problem line, exits 1 and writes
 * nothing: (1) the seal and the file sizes, (2) the summary and the commit id (the format of the
 * commit of the summary and of the run, and their equality, before any process starts, then whether
 * the commit is an ancestor of HEAD), (3) the criteria digest against the registry and the lock,
 * (4) the artefact's own verdicts against a recomputation of that artefact alone, (5) the re-run
 * rule, (7) pooling, in memory, of the raw runs already imported with the given ones, so that a
 * failure to pool leaves nothing behind and a dry run prints the status the import will print, (6)
 * copy of the small raw files, (8) the report and the summary.
 */
public final class ImportCommand {
  /**
   * What to import.
   *
   * @param spike the id: {@code SP-<letter>} or {@code SELFTEST}
   * @param dirs the result directories of one configuration (empty with a summary text)
   * @param summaryText a pasted job summary: a PENDING report without recomputation
   * @param allowNoLock accept a missing {@code criteria.lock}: for a dry run or the self-test only
   * @param dryRun do steps 1 to 5 and 7 (the pooled verdict), write nothing
   */
  public record Options(
      String spike,
      List<Path> dirs,
      Optional<Path> summaryText,
      boolean allowNoLock,
      boolean dryRun) {}

  private static final long MAX_BYTES = 2L * 1024 * 1024;
  private static final Pattern ID = Pattern.compile("SP-[A-Z]|SELFTEST");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern FENCE = Pattern.compile("(`{3,})json[ \\t]*");
  private static final String SUMMARY_SUFFIX = ".summary.json";

  /** One problem, or several separated by line feeds. */
  private static final class Failure extends Exception {
    private static final long serialVersionUID = 1L;

    Failure(String message) {
      super(message);
    }
  }

  private record Artefact(
      Path dir, SpikeResult summary, MeasurementSet set, String sha, String runName) {}

  private ImportCommand() {}

  /** The spike of the criteria registry that an import id stands for. */
  static String registrySpike(String id) {
    return id.equals("SELFTEST") ? "HARNESS" : id;
  }

  /**
   * Imports result directories or a pasted job summary.
   *
   * @param repo the repository root (the {@code docs/spikes} folder lives there)
   * @param now the instant used to count the time of a running ledger
   * @return 0 on success, 1 after one or more problem lines on {@code err}
   */
  public static int run(Options o, Path repo, Instant now, PrintStream out, PrintStream err) {
    try {
      if (!ID.matcher(o.spike()).matches()) {
        throw new Failure("BAD_SPIKE_ID " + safe(o.spike()));
      }
      Criteria criteria = Criteria.load();
      if (o.summaryText().isPresent()) {
        if (!o.dirs().isEmpty()) {
          throw new Failure("give --dir or --summary-text, not both");
        }
        importSummaryText(o, repo, criteria, out);
      } else {
        if (o.dirs().isEmpty()) {
          throw new Failure("no --dir given");
        }
        importDirs(o, repo, now, criteria, out);
      }
      return 0;
    } catch (Failure f) {
      for (String line : f.getMessage().split("\n")) {
        err.println("import: " + safe(line));
      }
      return 1;
    } catch (IOException | RuntimeException e) {
      err.println("import: IO_ERROR " + safe(e.getClass().getSimpleName() + " " + e.getMessage()));
      return 1;
    }
  }

  // ---------------------------------------------------------------- result directories

  private static void importDirs(
      Options o, Path repo, Instant now, Criteria criteria, PrintStream out)
      throws Failure, IOException {
    String id = o.spike();
    String spike = registrySpike(id);

    // (1) and (2): everything that needs no process
    List<Artefact> artefacts = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (Path dir : o.dirs()) {
      try {
        artefacts.add(readArtefact(dir, spike));
      } catch (Failure f) {
        problems.addAll(List.of(f.getMessage().split("\n")));
      }
    }
    fail(problems);

    // (2) the commit is one of ours
    Set<String> checked = new LinkedHashSet<>();
    for (Artefact a : artefacts) {
      if (checked.add(a.sha()) && !Git.isAncestor(repo, a.sha())) {
        problems.add("UNKNOWN_GIT_SHA " + a.sha() + " is not an ancestor of HEAD");
      }
    }
    fail(problems);

    // (3) the criteria are the confirmed ones
    String registry = criteria.digest().hex();
    checkLock(o, repo, registry, problems);
    for (Artefact a : artefacts) {
      if (!a.summary().criteriaDigest().equals(registry)) {
        problems.add(
            "CRITERIA_DIGEST_MISMATCH artefact="
                + safe(a.summary().criteriaDigest()).substring(0, 12)
                + " registry="
                + registry.substring(0, 12));
      }
    }
    fail(problems);

    // (4) the artefact's own verdicts are what its raw measurements give
    for (Artefact a : artefacts) {
      Map<String, Object> artefactBox =
          Map.of(
              "usedMinutes", a.summary().timeBox().get("usedMinutes"),
              "boxMinutes", a.summary().timeBox().get("boxMinutes"));
      SpikeResult recomputed;
      try {
        recomputed = ResultsEvaluator.evaluate(a.set(), criteria, "RAW", artefactBox);
      } catch (IllegalArgumentException e) {
        throw new Failure("BAD_MEASUREMENTS " + e.getMessage());
      }
      problems.addAll(compare("artefact", a.summary(), recomputed));
    }
    fail(problems);

    // (5) one configuration, each run once, a PASS is never re-run
    Set<String> configs = new TreeSet<>();
    Set<String> names = new LinkedHashSet<>();
    for (Artefact a : artefacts) {
      configs.add(a.set().configDigest());
      if (!names.add(a.runName())) {
        problems.add("ALREADY_IMPORTED " + a.runName() + " is given twice");
      }
    }
    if (configs.size() > 1) {
      problems.add("MIXED_CONFIG the directories hold " + configs.size() + " configurations");
    }
    fail(problems);
    String config = configs.iterator().next();
    Path rawConfig = docs(repo).resolve("raw").resolve(id).resolve(config);
    Map<String, Object> timeBox = timeBox(repo, id, spike, criteria, now);
    for (Artefact a : artefacts) {
      if (Files.exists(rawConfig.resolve(a.runName()), LinkOption.NOFOLLOW_LINKS)) {
        problems.add("ALREADY_IMPORTED " + a.runName());
      }
    }
    fail(problems);
    Map<String, MeasurementSet> raw = loadRawRuns(rawConfig, config);
    if (!raw.isEmpty()) {
      SpikeResult sofar = evaluate(pool(raw, config), criteria, timeBox);
      if (allRequiredPass(sofar, criteria)) {
        throw new Failure(
            "RERUN_NOT_JUSTIFIED every required criterion of "
                + id
                + " already passes on this configuration; a PASS is never re-run");
      }
    }

    // (7) before anything is copied: the runs already imported and the given ones are pooled in
    // memory, in the order of their directory names (the order verify-committed recomputes in),
    // so a failure to pool leaves nothing behind and a dry run says what the import will say
    Map<String, MeasurementSet> all = new TreeMap<>(raw);
    for (Artefact a : artefacts) {
      all.put(a.runName(), a.set());
    }
    SpikeResult result = evaluate(pool(all, config), criteria, timeBox);
    if (o.dryRun()) {
      out.println("import: OK (dry run) " + id + " status=" + result.status() + " evidence=RAW");
      return;
    }

    // (6) only the small raw files
    Files.createDirectories(rawConfig);
    for (Artefact a : artefacts) {
      Path target = Files.createDirectories(rawConfig.resolve(a.runName()));
      Files.copy(a.dir().resolve("measurements.json"), target.resolve("measurements.json"));
      Path runner = a.dir().resolve("runner.txt");
      if (Files.isRegularFile(runner, LinkOption.NOFOLLOW_LINKS)) {
        Files.copy(runner, target.resolve("runner.txt"));
      }
    }

    // (8) the report and the summary of every dispatch of the configuration
    writeReport(repo, id, result, ReportWriter.render(result, criteria));
    out.println(
        "import: OK "
            + id
            + " status="
            + result.status()
            + " evidence=RAW runs="
            + result.runs().size()
            + " vms="
            + result.vmCount());
  }

  private static Artefact readArtefact(Path dir, String spike) throws Failure {
    String name = safe(String.valueOf(dir.getFileName()));
    List<String> sealed = ResultSeal.verify(dir);
    if (!sealed.isEmpty()) {
      throw new Failure(String.join("\n", sealed));
    }
    String measurementsText = readText(dir.resolve("measurements.json"), "measurements.json");
    String summaryText = readText(dir.resolve("summary.json"), "summary.json");
    Path runner = dir.resolve("runner.txt");
    try {
      if (Files.isRegularFile(runner, LinkOption.NOFOLLOW_LINKS)
          && Files.size(runner) > MAX_BYTES) {
        throw new Failure("TOO_LARGE runner.txt");
      }
    } catch (IOException e) {
      throw new Failure("UNREADABLE runner.txt");
    }

    SpikeResult summary;
    try {
      summary = SummaryJson.parse(summaryText);
    } catch (IllegalArgumentException e) {
      throw new Failure("BAD_SUMMARY " + e.getMessage());
    }
    if (!summary.evidenceLevel().equals("RAW")) {
      throw new Failure("BAD_SUMMARY the evidence level of an artefact must be RAW");
    }
    if (!summary.spike().equals(spike)) {
      throw new Failure("BAD_SPIKE artefact=" + safe(summary.spike()) + " expected=" + spike);
    }
    // the format of the commit id is decided before any process is started
    Object sha = summary.source().get("gitSha");
    if (!(sha instanceof String s) || !Git.SHA.matcher(s).matches()) {
      throw new Failure("BAD_GIT_SHA " + safe(String.valueOf(sha)) + " in " + name);
    }
    MeasurementSet set;
    try {
      set = MeasurementSet.parse(measurementsText);
    } catch (IllegalArgumentException e) {
      throw new Failure("BAD_MEASUREMENTS " + e.getMessage());
    }
    if (!set.spike().equals(spike)) {
      throw new Failure("BAD_SPIKE measurements=" + safe(set.spike()) + " expected=" + spike);
    }
    if (set.runs().size() != 1) {
      throw new Failure("BAD_MEASUREMENTS an artefact holds exactly one run in " + name);
    }
    RunInfo run = set.runs().get(0);
    // the commit of the run is what the report shows: it is checked like the one of the summary
    // and must be the same, before any process is started
    if (!Git.SHA.matcher(run.gitSha()).matches()) {
      throw new Failure("BAD_GIT_SHA " + safe(run.gitSha()) + " in the run of " + name);
    }
    if (!run.gitSha().equals(s)) {
      throw new Failure(
          "BAD_GIT_SHA the run says " + run.gitSha() + " and the summary " + s + " in " + name);
    }
    // the run directory is built from these tokens only, never from free text of the file
    if (!TOKEN.matcher(run.runId()).matches() || !TOKEN.matcher(run.part()).matches()) {
      throw new Failure("BAD_RUN_ID the run id and the part must be plain tokens in " + name);
    }
    return new Artefact(dir, summary, set, s, run.runId() + "-" + run.attempt() + "-" + run.part());
  }

  // ---------------------------------------------------------------- pasted summary

  private static void importSummaryText(Options o, Path repo, Criteria criteria, PrintStream out)
      throws Failure, IOException {
    String id = o.spike();
    String spike = registrySpike(id);
    String text = readText(o.summaryText().orElseThrow(), "the summary text");
    String json = extractJson(text);
    SpikeResult pasted;
    try {
      pasted = SummaryJson.parse(json);
    } catch (IllegalArgumentException e) {
      throw new Failure("BAD_SUMMARY " + e.getMessage());
    }
    if (!pasted.spike().equals(spike)) {
      throw new Failure("BAD_SPIKE pasted=" + safe(pasted.spike()) + " expected=" + spike);
    }
    String registry = criteria.digest().hex();
    List<String> lockProblems = new ArrayList<>();
    checkLock(o, repo, registry, lockProblems);
    fail(lockProblems);
    if (!pasted.criteriaDigest().equals(registry)) {
      throw new Failure(
          "CRITERIA_DIGEST_MISMATCH pasted="
              + safe(pasted.criteriaDigest()).substring(0, 12)
              + " registry="
              + registry.substring(0, 12));
    }
    Path committed = docs(repo).resolve(id + SUMMARY_SUFFIX);
    if (Files.isRegularFile(committed, LinkOption.NOFOLLOW_LINKS)) {
      try {
        SpikeResult existing = SummaryJson.parse(readText(committed, id + SUMMARY_SUFFIX));
        if (existing.evidenceLevel().equals("RAW")) {
          throw new Failure(
              "RAW_REPORT_EXISTS a report from raw measurements exists; a pasted summary never replaces it");
        }
      } catch (IllegalArgumentException e) {
        throw new Failure("BAD_SUMMARY the committed summary is unreadable: " + e.getMessage());
      }
    }
    SpikeResult pending =
        new SpikeResult(
            pasted.spike(),
            pasted.status(),
            pasted.scope(),
            "SUMMARY",
            pasted.criteria(),
            pasted.decisions(),
            pasted.open(),
            pasted.source(),
            pasted.env(),
            pasted.timeBox(),
            pasted.criteriaDigest(),
            pasted.vmCount(),
            pasted.mixedHardware(),
            pasted.runs());
    if (!o.dryRun()) {
      writeReport(repo, id, pending, ReportWriter.render(pending, criteria));
    }
    out.println(
        "import: OK "
            + (o.dryRun() ? "(dry run) " : "")
            + id
            + " status=PENDING evidence=SUMMARY computed="
            + pasted.status());
  }

  /** The first fenced {@code json} block of a job summary. */
  private static String extractJson(String text) throws Failure {
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      Matcher fence = FENCE.matcher(stripCr(lines[i]));
      if (!fence.matches()) {
        continue;
      }
      StringBuilder json = new StringBuilder();
      for (int j = i + 1; j < lines.length; j++) {
        String line = stripCr(lines[j]);
        if (line.stripTrailing().equals(fence.group(1))) {
          return json.toString();
        }
        json.append(line).append('\n');
      }
      break;
    }
    throw new Failure("NO_SUMMARY_JSON no closed json block in the summary text");
  }

  private static String stripCr(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  // ---------------------------------------------------------------- verify-committed

  /**
   * Recomputes every committed report: for a report from raw measurements the verdicts are
   * recomputed from the committed raw files of its configuration; for a pending report the
   * committed text is re-rendered. The committed {@code <ID>.summary.json} and the generated part
   * of {@code <ID>.md} must equal the recomputation byte for byte.
   *
   * @return 0 when every report is what the evidence gives, 1 with problem lines otherwise
   */
  public static int verifyCommitted(Path repo, PrintStream out, PrintStream err) {
    List<String> problems = new ArrayList<>();
    int count = 0;
    Path docs = docs(repo);
    try {
      // a report is an id with a summary or with a report text: a hand-written <ID>.md whose
      // summary was left out (or deleted) is as much a report as any other, and is refused
      Set<String> ids = new TreeSet<>();
      if (Files.isDirectory(docs)) {
        try (Stream<Path> list = Files.list(docs)) {
          for (Path p : list.sorted().toList()) {
            String file = p.getFileName().toString();
            if (file.endsWith(SUMMARY_SUFFIX)) {
              String id = file.substring(0, file.length() - SUMMARY_SUFFIX.length());
              if (ID.matcher(id).matches()) {
                ids.add(id);
              } else {
                problems.add("BAD_NAME " + safe(file));
              }
            } else if (file.endsWith(".md")) {
              String id = file.substring(0, file.length() - ".md".length());
              if (ID.matcher(id).matches()) {
                ids.add(id);
              }
            }
          }
        }
      }
      Criteria criteria = Criteria.load();
      for (String id : ids) {
        count++;
        try {
          problems.addAll(verifyOne(repo, id, criteria));
        } catch (Failure f) {
          problems.addAll(List.of(f.getMessage().split("\n")));
        }
      }
    } catch (IOException | RuntimeException e) {
      problems.add("IO_ERROR " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
    if (!problems.isEmpty()) {
      for (String problem : problems) {
        err.println("verify-committed: " + safe(problem));
      }
      return 1;
    }
    out.println("verify-committed: OK " + count + " report(s)");
    return 0;
  }

  private static List<String> verifyOne(Path repo, String id, Criteria criteria)
      throws Failure, IOException {
    List<String> problems = new ArrayList<>();
    String committedText = readText(docs(repo).resolve(id + SUMMARY_SUFFIX), id + SUMMARY_SUFFIX);
    SpikeResult committed;
    try {
      committed = SummaryJson.parse(committedText);
    } catch (IllegalArgumentException e) {
      throw new Failure("BAD_SUMMARY " + id + ": " + e.getMessage());
    }
    String spike = registrySpike(id);
    if (!committed.spike().equals(spike)) {
      throw new Failure("BAD_SPIKE " + id + ": the summary is of " + safe(committed.spike()));
    }
    if (!committed.criteriaDigest().equals(criteria.digest().hex())) {
      throw new Failure(
          "CRITERIA_DIGEST_MISMATCH " + id + ": the criteria changed after the report");
    }
    SpikeResult expected;
    if (committed.evidenceLevel().equals("RAW")) {
      Object config = committed.source().get("configDigest");
      if (!(config instanceof String c) || !HEX64.matcher(c).matches()) {
        throw new Failure("NO_CONFIG_DIGEST " + id + ": the summary names no configuration");
      }
      Path rawConfig = docs(repo).resolve("raw").resolve(id).resolve(c);
      MeasurementSet pooled =
          loadRaw(rawConfig, c)
              .orElseThrow(() -> new Failure("MISSING_RAW " + id + ": no raw runs for " + c));
      Map<String, Object> box = new LinkedHashMap<>();
      box.put("usedMinutes", committed.timeBox().get("usedMinutes"));
      box.put("boxMinutes", committed.timeBox().get("boxMinutes"));
      expected = evaluate(pooled, criteria, box);
    } else {
      expected = committed;
    }
    problems.addAll(compare("committed", committed, expected));
    if (!committedText.equals(expected.toJson() + "\n")) {
      problems.add("SUMMARY_DIFFERS " + id + SUMMARY_SUFFIX + " differs from the recomputation");
    }

    Path md = docs(repo).resolve(id + ".md");
    if (!Files.isRegularFile(md, LinkOption.NOFOLLOW_LINKS)) {
      problems.add("MISSING " + id + ".md");
      return problems;
    }
    String actual = generatedPart(readText(md, id + ".md"));
    String wanted = ReportWriter.render(expected, criteria);
    if (!actual.equals(wanted)) {
      String[] a = actual.split("\n", -1);
      String[] w = wanted.split("\n", -1);
      int line = 0;
      while (line < a.length && line < w.length && a[line].equals(w[line])) {
        line++;
      }
      problems.add("MD_DIFFERS " + id + ".md differs from the recomputation at line " + (line + 1));
    }
    return problems;
  }

  // ---------------------------------------------------------------- shared

  private static Path docs(Path repo) {
    return repo.resolve("docs").resolve("spikes");
  }

  private static void fail(List<String> problems) throws Failure {
    if (!problems.isEmpty()) {
      throw new Failure(String.join("\n", problems));
    }
  }

  /** The verdict mismatches of a stated result against a recomputation. */
  private static List<String> compare(String label, SpikeResult stated, SpikeResult recomputed) {
    List<String> problems = new ArrayList<>();
    Map<String, Verdict> statedVerdicts = new LinkedHashMap<>();
    for (CriterionResult c : stated.criteria()) {
      statedVerdicts.put(c.id(), c.verdict());
    }
    Set<String> seen = new LinkedHashSet<>();
    for (CriterionResult c : recomputed.criteria()) {
      seen.add(c.id());
      Verdict v = statedVerdicts.get(c.id());
      if (v != c.verdict()) {
        problems.add(
            "MISMATCH "
                + c.id()
                + " "
                + label
                + "="
                + (v == null ? "ABSENT" : v.name())
                + " recomputed="
                + c.verdict());
      }
    }
    statedVerdicts.forEach(
        (id, v) -> {
          if (!seen.contains(id)) {
            problems.add("MISMATCH " + id + " " + label + "=" + v + " recomputed=ABSENT");
          }
        });
    if (stated.status() != recomputed.status()) {
      problems.add(
          "MISMATCH status "
              + label
              + "="
              + stated.status()
              + " recomputed="
              + recomputed.status());
    }
    return problems;
  }

  private static boolean allRequiredPass(SpikeResult r, Criteria criteria) {
    Map<String, Verdict> verdicts = new LinkedHashMap<>();
    for (CriterionResult c : r.criteria()) {
      verdicts.put(c.id(), c.verdict());
    }
    for (Criterion c : criteria.forSpike(r.spike())) {
      if (c.required() && verdicts.get(c.id()) != Verdict.PASS) {
        return false;
      }
    }
    return true;
  }

  private static SpikeResult evaluate(
      MeasurementSet set, Criteria criteria, Map<String, Object> timeBox) throws Failure {
    try {
      return ResultsEvaluator.evaluate(set, criteria, "RAW", timeBox);
    } catch (IllegalArgumentException e) {
      throw new Failure("BAD_MEASUREMENTS " + e.getMessage());
    }
  }

  /**
   * {@code usedMinutes} from the time ledger of the id (zero without one), the box of the spike.
   */
  private static Map<String, Object> timeBox(
      Path repo, String id, String spike, Criteria criteria, Instant now) throws Failure {
    long used = 0;
    Path file = docs(repo).resolve("timebox").resolve(id + ".tsv");
    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      try {
        used = TimeLedger.parse(readText(file, id + ".tsv")).used(now).toMinutes();
      } catch (IllegalArgumentException e) {
        throw new Failure("BAD_TIME_LEDGER " + id + ": " + e.getMessage());
      }
    }
    Map<String, Object> box = new LinkedHashMap<>();
    box.put("usedMinutes", used);
    box.put("boxMinutes", criteria.box(spike).toMinutes());
    return box;
  }

  /**
   * Pools every raw run of one configuration, or empty when there is none.
   *
   * @see #loadRawRuns
   * @see #pool
   */
  private static Optional<MeasurementSet> loadRaw(Path rawConfig, String config)
      throws Failure, IOException {
    Map<String, MeasurementSet> runs = loadRawRuns(rawConfig, config);
    return runs.isEmpty() ? Optional.empty() : Optional.of(pool(runs, config));
  }

  /**
   * The raw runs of one configuration by directory name, in the order of those names (compared as
   * text, so the order is the same on every operating system), or none.
   */
  private static Map<String, MeasurementSet> loadRawRuns(Path rawConfig, String config)
      throws Failure, IOException {
    Map<String, MeasurementSet> runs = new TreeMap<>();
    if (!Files.isDirectory(rawConfig, LinkOption.NOFOLLOW_LINKS)) {
      return runs;
    }
    List<Path> dirs = new ArrayList<>();
    try (Stream<Path> list = Files.list(rawConfig)) {
      list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).forEach(dirs::add);
    }
    for (Path run : dirs) {
      String name = String.valueOf(run.getFileName());
      String where = safe(name);
      try {
        MeasurementSet m = MeasurementSet.parse(readText(run.resolve("measurements.json"), where));
        if (!config.equals(m.configDigest())) {
          throw new Failure("BAD_RAW " + where + " holds another configuration");
        }
        runs.put(name, m);
      } catch (IllegalArgumentException e) {
        throw new Failure("BAD_RAW " + where + ": " + e.getMessage());
      }
    }
    return runs;
  }

  /**
   * Pools the runs in the order given. The pooled source names the configuration and the commit, or
   * none when the runs come from several commits.
   */
  private static MeasurementSet pool(Map<String, MeasurementSet> runs, String config)
      throws Failure {
    MeasurementSet pooled = null;
    try {
      for (MeasurementSet m : runs.values()) {
        pooled = pooled == null ? m : pooled.merge(m);
      }
    } catch (IllegalArgumentException e) {
      throw new Failure("CANNOT_POOL " + safe(String.valueOf(e.getMessage())));
    }
    return withProvenance(pooled, config);
  }

  private static MeasurementSet withProvenance(MeasurementSet m, String config) {
    Set<String> shas = new TreeSet<>();
    for (RunInfo run : m.runs()) {
      shas.add(run.gitSha());
    }
    Map<String, Object> source = new LinkedHashMap<>(m.source());
    source.put("configDigest", config);
    if (shas.size() == 1) {
      source.put("gitSha", shas.iterator().next());
    } else {
      source.remove("gitSha");
    }
    return new MeasurementSet(
        m.spike(),
        m.configDigest(),
        m.scope(),
        m.series(),
        m.canary(),
        m.runs(),
        source,
        m.env(),
        m.decisions(),
        m.open());
  }

  /** Writes {@code <ID>.md}, keeping the hand-written part below the first {@code ---} line. */
  private static void writeReport(Path repo, String id, SpikeResult result, String generated)
      throws IOException, Failure {
    Path dir = Files.createDirectories(docs(repo));
    Path md = dir.resolve(id + ".md");
    String tail = "";
    if (Files.isRegularFile(md, LinkOption.NOFOLLOW_LINKS)) {
      String existing = readText(md, id + ".md");
      tail = existing.substring(generatedPart(existing).length());
    }
    Files.writeString(md, generated + tail, StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve(id + SUMMARY_SUFFIX), result.toJson() + "\n", StandardCharsets.UTF_8);
  }

  /** The text up to and including the first line that is exactly {@code ---}. */
  static String generatedPart(String report) {
    int from = 0;
    while (from <= report.length()) {
      int end = report.indexOf('\n', from);
      if (end < 0) {
        break;
      }
      if (report.substring(from, end).equals("---")) {
        return report.substring(0, end + 1);
      }
      from = end + 1;
    }
    return report;
  }

  /**
   * The criteria of the registry must be the ones the owner locked. A missing lock is a problem;
   * {@code --allow-no-lock} is accepted only for a dry run and for the self-test, never for a real
   * import of a spike (nothing may be committed before the thresholds are confirmed), and the rule
   * is the same for a pasted summary.
   */
  private static void checkLock(Options o, Path repo, String registry, List<String> problems)
      throws Failure {
    Optional<String> lock = readLock(repo);
    if (lock.isEmpty()) {
      boolean mayLook = o.allowNoLock() && (o.dryRun() || o.spike().equals("SELFTEST"));
      if (!mayLook) {
        problems.add(
            "NO_CRITERIA_LOCK docs/spikes/criteria.lock does not exist"
                + (o.allowNoLock()
                    ? " (--allow-no-lock is accepted with --dry-run or for SELFTEST only)"
                    : ""));
      }
    } else if (!lock.get().equals(registry)) {
      problems.add(
          "CRITERIA_DIGEST_MISMATCH lock="
              + lock.get().substring(0, 12)
              + " registry="
              + registry.substring(0, 12));
    }
  }

  private static Optional<String> readLock(Path repo) throws Failure {
    Path lock = docs(repo).resolve("criteria.lock");
    if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    String text = readText(lock, "criteria.lock");
    String digest = null;
    String by = null;
    String on = null;
    for (String line : text.split("\n")) {
      if (line.startsWith("digest:")) {
        digest = line.substring("digest:".length()).strip();
      } else if (line.startsWith("confirmed_by:")) {
        by = line.substring("confirmed_by:".length()).strip();
      } else if (line.startsWith("confirmed_on:")) {
        on = line.substring("confirmed_on:".length()).strip();
      }
    }
    if (digest == null || !HEX64.matcher(digest).matches()) {
      throw new Failure("BAD_CRITERIA_LOCK no digest line with 64 hex characters");
    }
    if (by == null || by.isEmpty() || on == null) {
      throw new Failure("BAD_CRITERIA_LOCK confirmed_by and confirmed_on are required");
    }
    try {
      LocalDate.parse(on);
    } catch (DateTimeParseException e) {
      throw new Failure("BAD_CRITERIA_LOCK confirmed_on is not a date");
    }
    return Optional.of(digest);
  }

  private static String readText(Path file, String name) throws Failure {
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new Failure("MISSING " + safe(name));
      }
      if (Files.size(file) > MAX_BYTES) {
        throw new Failure("TOO_LARGE " + safe(name));
      }
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new Failure("UNREADABLE " + safe(name));
    }
  }

  /** Text for a message: control characters replaced, at most 200 characters. */
  static String safe(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length() && i < 200; i++) {
      char ch = text.charAt(i);
      out.append(ch < 0x20 || ch == 0x7f ? '?' : ch);
    }
    return out.toString();
  }
}
