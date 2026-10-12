package dev.regpack.bench.cli;

import dev.regpack.bench.consolidate.LatencyBudget;
import dev.regpack.bench.consolidate.ResultsTable;
import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.imp.ImportCommand;
import dev.regpack.bench.imp.SummaryJson;
import dev.regpack.bench.imp.TimeLedger;
import dev.regpack.bench.proto.EnvCapture;
import dev.regpack.bench.report.Git;
import dev.regpack.bench.report.Publish;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code bench} command line: criteria and the lock, the self-test, publishing a result
 * directory, importing, verifying the committed reports and the time ledger. Exit code 0 is
 * success, 1 a problem with the data or the repository, 2 a usage error.
 *
 * <p>This class reads the clock (the time ledger) and the environment; the packages that decide a
 * result do not.
 */
public final class Bench {
  private static final String USAGE =
      "usage: bench criteria --write <file> | criteria --lock <file> --by <name> --on <date>"
          + " | selftest --out <dir> | publish --dir <dir> --os <label>"
          + " | import --spike <ID> (--dir <dir>... | --summary-text <file>) [--allow-no-lock]"
          + " [--dry-run] | verify-committed"
          + " | results --write <file> | budget --write <file>"
          + " | timebox start|pause|resume|stop|status <ID> [--note <text>] [--at <instant>]";

  private Bench() {}

  /** Runs one command and exits with its code. */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    return run(args, out, err, System.getenv(), Path.of("").toAbsolutePath(), Instant.now());
  }

  /** The command with its inputs injected: environment, repository root and the current instant. */
  static int run(
      String[] args,
      PrintStream out,
      PrintStream err,
      Map<String, String> env,
      Path repo,
      Instant now) {
    if (args.length == 0) {
      err.println("bench: a command is required");
      err.println(USAGE);
      return 2;
    }
    try {
      return switch (args[0]) {
        case "criteria" -> criteria(args, repo, out, err);
        case "selftest" -> selftest(args, env, repo, out);
        case "publish" -> publish(args, env, repo, out, err);
        case "import" -> importCommand(args, repo, now, out, err);
        case "verify-committed" -> {
          Args.parse(args, 1, Set.of(), Set.of()).noPositionals();
          yield ImportCommand.verifyCommitted(repo, out, err);
        }
        case "timebox" -> timebox(args, repo, now, out, err);
        case "results" -> results(args, repo, out, err);
        case "budget" -> budget(args, repo, out, err);
        default -> throw new IllegalArgumentException("unknown command " + args[0]);
      };
    } catch (IllegalArgumentException | DateTimeParseException e) {
      err.println("bench: " + e.getMessage());
      err.println(USAGE);
      return 2;
    } catch (IOException e) {
      err.println("bench: " + e.getClass().getSimpleName() + " " + e.getMessage());
      return 1;
    }
  }

  // ---------------------------------------------------------------- criteria

  private static int criteria(String[] args, Path repo, PrintStream out, PrintStream err)
      throws IOException {
    Args a =
        Args.parse(args, 1, Set.of("--write", "--lock", "--by", "--on"), Set.of()).noPositionals();
    Optional<String> write = a.single("--write");
    Optional<String> lock = a.single("--lock");
    if (write.isPresent() == lock.isPresent()) {
      throw new IllegalArgumentException("criteria needs --write <file> or --lock <file>");
    }
    Criteria criteria = Criteria.load();
    if (write.isPresent()) {
      Path file = repo.resolve(write.get());
      createParent(file);
      Files.writeString(file, criteria.toMarkdown(), StandardCharsets.UTF_8);
      out.println("criteria: wrote " + write.get() + " (digest " + criteria.digest().hex() + ")");
      return 0;
    }
    String by = a.required("--by");
    String on = a.required("--on");
    if (by.isBlank() || by.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new IllegalArgumentException("--by must be a name on one line");
    }
    LocalDate.parse(on);
    Path file = repo.resolve(lock.get());
    createParent(file);
    String text =
        "digest: "
            + criteria.digest().hex()
            + "\nconfirmed_by: "
            + by.strip()
            + "\nconfirmed_on: "
            + on
            + "\n";
    try {
      Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    } catch (FileAlreadyExistsException e) {
      err.println("bench: LOCK_EXISTS " + lock.get() + " is never rewritten");
      return 1;
    }
    out.println("criteria: locked " + lock.get() + " (digest " + criteria.digest().hex() + ")");
    return 0;
  }

  private static void createParent(Path file) throws IOException {
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }

  // ---------------------------------------------------------------- selftest

  /**
   * A tiny synthetic {@code measurements.json} for ST-1 (a count) and ST-2 (fifteen forks): the
   * whole pipeline of publish, seal, import and report runs on it without any spike.
   */
  private static int selftest(String[] args, Map<String, String> env, Path repo, PrintStream out)
      throws IOException {
    Args a = Args.parse(args, 1, Set.of("--out"), Set.of()).noPositionals();
    Path dir = repo.resolve(a.required("--out"));
    Criteria criteria = Criteria.load();

    List<ForkStat> forks = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      forks.add(new ForkStat(i, BigDecimal.valueOf(500 + (i - 7) * 5L, 2), false, "", false));
    }
    List<Series> series =
        List.of(
            new CountValue("ST-1", 0, 1),
            new ForkValues(
                "ST-2", criteria.byId("ST-2").orElseThrow().unit(), BigDecimal.ONE, forks));
    Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
    for (Resource r : Resource.values()) {
      List<BigDecimal> samples = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        samples.add(BigDecimal.valueOf(1000L + (i % 3)));
      }
      canary.put(r, samples);
    }

    Map<String, Object> captured = EnvCapture.capture();
    String sha = env.get("GITHUB_SHA");
    if (sha == null || !Git.SHA.matcher(sha).matches()) {
      sha = Git.head(repo).orElse("unknown");
    }
    String runId = env.getOrDefault("GITHUB_RUN_ID", "local");
    int attempt = 1;
    try {
      attempt = Integer.parseInt(env.getOrDefault("GITHUB_RUN_ATTEMPT", "1"));
    } catch (NumberFormatException e) {
      // a runner always sets a number; anything else is a local run
    }
    RunInfo run =
        new RunInfo(
            runId,
            attempt,
            "all",
            sha,
            String.valueOf(captured.get("cpu.model")),
            env.getOrDefault("ImageVersion", "local"));

    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("forks", 15L);
    plan.put("series", List.of("ST-1", "ST-2"));
    String config =
        MeasurementSet.configDigest("HARNESS", "selftest", false, criteria.digest().hex(), plan);
    MeasurementSet set =
        new MeasurementSet(
            "HARNESS",
            config,
            "FULL",
            series,
            canary,
            List.of(run),
            Map.of(),
            captured,
            Map.of(),
            List.of());
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("measurements.json"), set.toJson() + "\n", StandardCharsets.UTF_8);
    out.println("selftest: wrote " + dir.getFileName() + "/measurements.json");
    return 0;
  }

  // ---------------------------------------------------------------- publish, import

  private static int publish(
      String[] args, Map<String, String> env, Path repo, PrintStream out, PrintStream err) {
    Args a = Args.parse(args, 1, Set.of("--dir", "--os"), Set.of()).noPositionals();
    return Publish.run(repo.resolve(a.required("--dir")), a.required("--os"), env, repo, out, err);
  }

  private static int importCommand(
      String[] args, Path repo, Instant now, PrintStream out, PrintStream err) {
    Args a =
        Args.parse(
                args,
                1,
                Set.of("--spike", "--dir", "--summary-text"),
                Set.of("--allow-no-lock", "--dry-run"))
            .noPositionals();
    List<Path> dirs = new ArrayList<>();
    for (String dir : a.all("--dir")) {
      dirs.add(repo.resolve(dir));
    }
    ImportCommand.Options options =
        new ImportCommand.Options(
            a.required("--spike"),
            dirs,
            a.single("--summary-text").map(repo::resolve),
            a.flag("--allow-no-lock"),
            a.flag("--dry-run"));
    return ImportCommand.run(options, repo, now, out, err);
  }

  // ---------------------------------------------------------------- consolidation

  /**
   * Writes the results table from the committed summaries. A spike without a committed summary is
   * listed as having no report; a summary that does not parse is a problem with the data.
   */
  private static int results(String[] args, Path repo, PrintStream out, PrintStream err)
      throws IOException {
    Args a = Args.parse(args, 1, Set.of("--write"), Set.of()).noPositionals();
    String target = a.required("--write");
    Map<String, SpikeResult> results = new LinkedHashMap<>();
    for (String id : ResultsTable.SPIKES) {
      Path file = summaryFile(repo, id);
      if (!Files.isRegularFile(file)) {
        continue;
      }
      Optional<SpikeResult> read = readSummary(file, id, "results", err);
      if (read.isEmpty()) {
        return 1;
      }
      results.put(id, read.get());
    }
    String text = ResultsTable.render(Criteria.load(), results);
    writeText(repo.resolve(target), text);
    out.println("results: wrote " + target + " (" + results.size() + " summaries)");
    return 0;
  }

  /**
   * Writes the partial latency budget from the committed SP-R, SP-L and SP-X summaries. A missing
   * summary or decision key prints {@code budget: MISSING <spike> <key>} and writes nothing.
   */
  private static int budget(String[] args, Path repo, PrintStream out, PrintStream err)
      throws IOException {
    Args a = Args.parse(args, 1, Set.of("--write"), Set.of()).noPositionals();
    String target = a.required("--write");
    Map<String, Map<String, Object>> decisions = new LinkedHashMap<>();
    for (String id : List.of(LatencyBudget.SP_R, LatencyBudget.SP_L, LatencyBudget.SP_X)) {
      Path file = summaryFile(repo, id);
      if (!Files.isRegularFile(file)) {
        err.println("budget: MISSING " + id + " summary.json");
        return 1;
      }
      Optional<SpikeResult> read = readSummary(file, id, "budget", err);
      if (read.isEmpty()) {
        return 1;
      }
      decisions.put(id, read.get().decisions());
    }
    Optional<String> missing = LatencyBudget.firstMissing(decisions);
    if (missing.isPresent()) {
      err.println("budget: MISSING " + missing.get());
      return 1;
    }
    Criteria criteria = Criteria.load();
    double burst = criteria.param("burstRecordsPerSecond").orElseThrow().doubleValue();
    double margin = criteria.param("availableMarginHours").orElseThrow().doubleValue();
    int n = LatencyBudget.DEFAULT_MICRO_BATCH_N;
    int threads = LatencyBudget.DEFAULT_THREADS;
    LatencyBudget.Result result = LatencyBudget.assemble(decisions, n, burst, margin, threads);
    writeText(repo.resolve(target), LatencyBudget.render(result, n, burst, margin, threads));
    out.println(
        "budget: wrote "
            + target
            + " (verdict "
            + result.verdict()
            + ", "
            + result.pending().size()
            + " pending intervals)");
    return 0;
  }

  private static Path summaryFile(Path repo, String id) {
    return repo.resolve("docs").resolve("spikes").resolve(id + ".summary.json");
  }

  private static Optional<SpikeResult> readSummary(
      Path file, String id, String command, PrintStream err) throws IOException {
    try {
      SpikeResult result = SummaryJson.parse(Files.readString(file, StandardCharsets.UTF_8));
      if (!result.spike().equals(id)) {
        err.println(command + ": " + id + ".summary.json holds the summary of " + result.spike());
        return Optional.empty();
      }
      return Optional.of(result);
    } catch (IllegalArgumentException e) {
      err.println(command + ": " + id + ".summary.json: " + e.getMessage());
      return Optional.empty();
    }
  }

  /** UTF-8, no byte order mark, the text as given (it holds LF only). */
  private static void writeText(Path file, String text) throws IOException {
    createParent(file);
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------- time ledger

  private static int timebox(
      String[] args, Path repo, Instant now, PrintStream out, PrintStream err) throws IOException {
    Args a = Args.parse(args, 1, Set.of("--note", "--at"), Set.of());
    if (a.positionals().size() != 2) {
      throw new IllegalArgumentException("timebox needs an action and an id");
    }
    String action = a.positionals().get(0);
    String id = a.positionals().get(1);
    Duration box = Criteria.load().box(id);
    Instant at = a.single("--at").map(Instant::parse).orElse(now);
    String note = a.single("--note").orElse("");
    if (a.single("--at").isPresent() && !action.equals("status")) {
      // --at lets a start be recorded late, but the ledger never holds the future, and an
      // instant well before the clock is said out loud: a back-dated pause hides active time
      if (at.isAfter(now)) {
        err.println("bench: timebox " + id + ": --at " + at + " is later than the clock " + now);
        return 1;
      }
      long minutes = Duration.between(at, now).toMinutes();
      if (minutes > 1) {
        err.println("bench: note: --at is " + minutes + " minutes before the clock");
      }
    }
    Optional<TimeLedger.Event> event =
        switch (action) {
          case "start" -> Optional.of(TimeLedger.Event.START);
          case "pause" -> Optional.of(TimeLedger.Event.PAUSE);
          case "resume" -> Optional.of(TimeLedger.Event.RESUME);
          case "stop" -> Optional.of(TimeLedger.Event.STOP);
          case "status" -> Optional.empty();
          default -> throw new IllegalArgumentException("unknown timebox action " + action);
        };
    Path file = repo.resolve("docs").resolve("spikes").resolve("timebox").resolve(id + ".tsv");
    TimeLedger ledger;
    try {
      ledger =
          Files.isRegularFile(file)
              ? TimeLedger.parse(Files.readString(file, StandardCharsets.UTF_8))
              : TimeLedger.empty();
      if (event.isPresent()) {
        ledger = ledger.append(at, event.get(), note);
      }
    } catch (IllegalArgumentException e) {
      err.println("bench: timebox " + id + ": " + e.getMessage());
      return 1;
    }
    if (event.isEmpty()) {
      long used = ledger.used(now).toMinutes();
      out.println(
          id
              + ": used "
              + used
              + " of "
              + box.toMinutes()
              + " minutes, "
              + ledger.state()
              + ", box reached: "
              + (ledger.overBox(box, now) ? "yes" : "no"));
      return 0;
    }
    createParent(file);
    Files.writeString(file, ledger.toTsv(), StandardCharsets.UTF_8);
    out.println("timebox: " + id + " " + action + " at " + at);
    return 0;
  }
}
