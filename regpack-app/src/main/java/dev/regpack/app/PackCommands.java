package dev.regpack.app;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.pack.CompiledPack;
import dev.regpack.engine.pack.PackRegime;
import dev.regpack.engine.packci.PackExplainer;
import dev.regpack.engine.packci.PackTestReport;
import dev.regpack.engine.packci.PackTester;
import dev.regpack.model.IngestKey;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import dev.regpack.pack.PackFormat;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.csv.TableCsv;
import dev.regpack.pack.csv.TableYamlWriter;
import dev.regpack.pack.golden.ReviewerRegistry;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.model.PackManifest;
import dev.regpack.pack.model.ReportDefinition;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.source.Seal;
import dev.regpack.pack.yaml.PackSyntaxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code pack} commands (plan T25): {@code test}, {@code explain}, {@code seal} and {@code
 * table export|import}. Exit codes: 0 success, 1 a check failed (a pack problem, a failing golden
 * case, a table import with problems), 2 a usage error or an input the command cannot use (a
 * missing or unreadable file, a file over {@link PackFormat#MAX_FILE_BYTES}, a file that is not
 * UTF-8, an unknown rule id, a table path that is not a scope table of the pack).
 *
 * <p>Output lines end in LF on every platform. Text that comes from files is bounded and escaped
 * (the problems through {@link PackProblem}, other text through {@link #printable}). A file named
 * on the command line is read with the size cap of the pack format before anything parses it.
 */
public final class PackCommands {
  /** The pack lines of the usage text of {@code regpack help}. */
  static final String COMMANDS =
      String.join(
          "\n",
          "  pack test <dir> [--reviewers <file>]",
          "          check a rule pack; exit 0 pass, 1 fail",
          "  pack explain <dir> --rule <id>",
          "          explain one rule of a pack",
          "  pack explain <dir> --event <execution.json>",
          "          explain one event (canonical execution JSON)",
          "  pack seal <dir>",
          "          rewrite the CHECKSUMS file of a pack",
          "  pack table export <dir> <table-path> [--out <file>]",
          "          write a decision table as CSV",
          "  pack table import <dir> <table-path> <csv> [--write]",
          "          check a CSV against a table; --write rewrites the table file",
          "");

  private static final String USAGE = "usage: regpack pack <command>\n" + COMMANDS;
  private static final String DEFAULT_REVIEWERS = "governance/golden-reviewers.txt";
  private static final String INGEST_SOURCE = "pack";
  private static final String INGEST_RECORD = "explain";
  private static final Instant EXPLAIN_AT = Instant.parse(PackTester.EVALUATED_AT);

  private PackCommands() {}

  /** A refusal that ends the command with an exit code and a message on standard error. */
  private static final class Refusal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int exit;
    private final boolean showUsage;

    Refusal(int exit, boolean showUsage, String message) {
      super(message, null, false, false);
      this.exit = exit;
      this.showUsage = showUsage;
    }
  }

  private static Refusal usage(String message) {
    return new Refusal(2, true, message);
  }

  private static Refusal input(String message) {
    return new Refusal(2, false, message);
  }

  /** Runs one {@code pack} command; the arguments are those after {@code pack}. */
  public static int run(List<String> args, PrintStream out, PrintStream err) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(err, "err");
    try {
      if (args.isEmpty()) {
        throw usage("a pack command is required");
      }
      List<String> rest = args.subList(1, args.size());
      return switch (args.get(0)) {
        case "test" -> test(rest, out);
        case "explain" -> explain(rest, out, err);
        case "seal" -> seal(rest, out);
        case "table" -> table(rest, out, err);
        default -> throw usage("unknown pack command '" + printable(args.get(0), 24) + "'");
      };
    } catch (Refusal r) {
      err.print(r.getMessage() + "\n");
      if (r.showUsage) {
        err.print(USAGE);
      }
      return r.exit;
    } catch (PackSyntaxException e) {
      err.print(
          "the pack cannot be read: "
              + e.code()
              + " "
              + printable(e.excerpt(), PackProblem.MAX_PATH_CHARS)
              + "\n");
      return 1;
    } catch (UncheckedIOException e) {
      err.print("input or output failed: " + e.getCause().getClass().getSimpleName() + "\n");
      return 2;
    } catch (RuntimeException e) {
      // last resort: a pack command never dies with a stack trace, whatever the input files hold
      err.print(
          "pack command failed: "
              + e.getClass().getSimpleName()
              + " "
              + printable(String.valueOf(e.getMessage()), 160)
              + "\n");
      return 1;
    }
  }

  // ---- pack test -------------------------------------------------------------------------------

  private static int test(List<String> args, PrintStream out) {
    if (args.isEmpty()) {
      throw usage("pack test needs a pack directory");
    }
    Path dir = directory(args.get(0));
    Optional<String> reviewersFile = Optional.empty();
    for (int i = 1; i < args.size(); i += 2) {
      if (!args.get(i).equals("--reviewers") || reviewersFile.isPresent()) {
        throw usage("unexpected argument '" + printable(args.get(i), 24) + "'");
      }
      if (i + 1 >= args.size()) {
        throw usage("--reviewers needs a file");
      }
      reviewersFile = Optional.of(args.get(i + 1));
    }
    ReviewerRegistry reviewers = reviewers(reviewersFile);
    PackSource source;
    try {
      source = PackSource.fromDirectory(dir);
    } catch (PackSyntaxException e) {
      PackProblem problem =
          new PackProblem(e.code(), e.excerpt(), 0, "the pack directory cannot be read");
      out.print(problemLine(problem) + "\npack test: FAIL (1 problems)\n");
      return 1;
    }
    PackTestReport report = PackTester.run(source, reviewers);
    out.print(report.render() + "\n");
    return report.passed() ? 0 : 1;
  }

  private static ReviewerRegistry reviewers(Optional<String> file) {
    Path path;
    if (file.isPresent()) {
      path = path(file.get());
    } else {
      path = Path.of(DEFAULT_REVIEWERS);
      if (!Files.isRegularFile(path)) {
        return ReviewerRegistry.parse("");
      }
    }
    try {
      return ReviewerRegistry.parse(readText(path, "the reviewer registry"));
    } catch (IllegalArgumentException e) {
      throw input("invalid reviewer registry: " + printable(e.getMessage(), 160));
    }
  }

  // ---- pack explain ----------------------------------------------------------------------------

  private static int explain(List<String> args, PrintStream out, PrintStream err) {
    if (args.size() != 3 || !(args.get(1).equals("--rule") || args.get(1).equals("--event"))) {
      throw usage("pack explain needs <dir> and either --rule <id> or --event <file>");
    }
    Path dir = directory(args.get(0));
    boolean rule = args.get(1).equals("--rule");
    Optional<CanonicalExecution> event =
        rule ? Optional.empty() : Optional.of(execution(path(args.get(2))));
    Optional<LoadedPack> pack = load(dir, err);
    if (pack.isEmpty()) {
      return 1;
    }
    if (rule) {
      try {
        out.print(PackExplainer.explainRule(pack.get(), args.get(2)) + "\n");
      } catch (IllegalArgumentException e) {
        throw input(printable(e.getMessage(), 160));
      }
      return 0;
    }
    List<ReportDefinition> reports = pack.get().manifest().reports();
    if (reports.size() != 1) {
      err.print("pack explain --event needs a pack with exactly one report\n");
      return 1;
    }
    PackRegime regime;
    try {
      regime =
          PackRegime.create(pack.get(), reports.get(0).id(), new MeteredExecutor(), Rulings.load());
    } catch (IllegalArgumentException e) {
      err.print(printable(e.getMessage(), 2000) + "\n");
      return 1;
    }
    String explanation;
    try {
      explanation =
          PackExplainer.explainEvent(
              regime,
              event.orElseThrow(),
              new IngestKey(INGEST_SOURCE, INGEST_RECORD, 1),
              EXPLAIN_AT);
    } catch (IllegalArgumentException e) {
      throw input("no decision can be made for the event: " + printable(e.getMessage(), 160));
    }
    out.print(explanation + "\n");
    return 0;
  }

  /** Reads a canonical execution JSON file (the form of {@link CanonicalExecutionCodec}). */
  private static CanonicalExecution execution(Path file) {
    Object parsed;
    try {
      parsed = Json.parse(readText(file, "the event file"));
    } catch (JsonException e) {
      throw input("the event file is not valid JSON: " + printable(e.getMessage(), 160));
    }
    if (!(parsed instanceof Map<?, ?> raw)) {
      throw input("the event file must hold one JSON object");
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      map.put((String) entry.getKey(), entry.getValue());
    }
    try {
      return CanonicalExecutionCodec.fromMap(map);
    } catch (IllegalArgumentException e) {
      throw input("the event file is not a canonical execution: " + printable(e.getMessage(), 160));
    }
  }

  // ---- pack seal -------------------------------------------------------------------------------

  private static int seal(List<String> args, PrintStream out) {
    if (args.size() != 1) {
      throw usage("pack seal needs exactly one pack directory");
    }
    int n = Seal.seal(directory(args.get(0)));
    out.print("sealed " + n + " files\n");
    return 0;
  }

  // ---- pack table ------------------------------------------------------------------------------

  private static int table(List<String> args, PrintStream out, PrintStream err) {
    if (args.isEmpty()) {
      throw usage("pack table needs export or import");
    }
    List<String> rest = args.subList(1, args.size());
    return switch (args.get(0)) {
      case "export" -> export(rest, out, err);
      case "import" -> importTable(rest, out, err);
      default -> throw usage("unknown table command '" + printable(args.get(0), 24) + "'");
    };
  }

  private static int export(List<String> args, PrintStream out, PrintStream err) {
    boolean toFile = args.size() == 4 && args.get(2).equals("--out");
    if (args.size() != 2 && !toFile) {
      throw usage("pack table export needs <dir> <table-path> [--out <file>]");
    }
    Path dir = directory(args.get(0));
    String tablePath = args.get(1);
    requireValidTablePath(tablePath);
    Optional<LoadedPack> pack = load(dir, err);
    if (pack.isEmpty()) {
      return 1;
    }
    DecisionTable table = scopeTable(pack.get(), tablePath);
    byte[] csv = TableCsv.export(table).getBytes(UTF_8);
    if (toFile) {
      try {
        Files.write(path(args.get(3)), csv);
      } catch (IOException e) {
        throw input("cannot write the output file: " + e.getClass().getSimpleName());
      }
      out.print("wrote " + table.rows().size() + " rows of " + tablePath + "\n");
    } else {
      out.write(csv, 0, csv.length);
      out.flush();
    }
    return 0;
  }

  private static int importTable(List<String> args, PrintStream out, PrintStream err) {
    boolean write = args.size() == 4 && args.get(3).equals("--write");
    if (args.size() != 3 && !write) {
      throw usage("pack table import needs <dir> <table-path> <csv> [--write]");
    }
    Path dir = directory(args.get(0));
    String tablePath = args.get(1);
    requireValidTablePath(tablePath);
    Optional<LoadedPack> pack = load(dir, err);
    if (pack.isEmpty()) {
      return 1;
    }
    DecisionTable old = scopeTable(pack.get(), tablePath);
    // the file is read under the pack-format size cap before TableCsv sees a character: the
    // importer itself has no size or row cap
    String csv = readText(path(args.get(2)), "the CSV file");

    List<PackProblem> problems = new ArrayList<>();
    Optional<DecisionTable> imported = TableCsv.importRows(csv, old, problems);
    String yaml = "";
    if (problems.isEmpty() && imported.isPresent()) {
      try {
        yaml = TableYamlWriter.write(imported.get());
      } catch (IllegalArgumentException e) {
        problems.add(
            new PackProblem("TABLE_NOT_WRITABLE", tablePath, 0, printable(e.getMessage(), 100)));
      }
    }
    if (problems.isEmpty()) {
      problems.addAll(checkCandidate(pack.get(), tablePath, yaml));
    }
    if (!problems.isEmpty()) {
      return refuseImport(problems, err);
    }

    int[] counts = diff(old, imported.orElseThrow());
    out.print("rows +" + counts[0] + " -" + counts[1] + " ~" + counts[2] + "\n");
    if (!write) {
      out.print("nothing written (add --write to rewrite " + tablePath + ")\n");
      return 0;
    }
    writeAtomically(dir.resolve(tablePath), yaml.getBytes(UTF_8));
    out.print(
        "wrote "
            + tablePath
            + " in the canonical layout (comments are not kept);"
            + " run pack seal to update CHECKSUMS\n");
    return 0;
  }

  private static int refuseImport(List<PackProblem> problems, PrintStream err) {
    // at most the load cap of problems is printed, whatever stage produced them
    List<PackProblem> sorted =
        ProblemSink.cap(problems, PackFormat.MAX_PROBLEMS_PER_LOAD, PackManifest.FILE);
    for (PackProblem p : sorted) {
      err.print(problemLine(p) + "\n");
    }
    err.print("pack table import: FAIL (" + sorted.size() + " problems), nothing written\n");
    return 1;
  }

  /**
   * Loads the pack as it would be after the import (the table replaced, CHECKSUMS recomputed) and
   * compiles it, so a table that passes the table checks but breaks a cross-reference (an unknown
   * reason, a duplicate row id in the pack) or an expression is refused before anything is written.
   */
  private static List<PackProblem> checkCandidate(LoadedPack pack, String tablePath, String yaml) {
    PackSource source = pack.source();
    Map<String, byte[]> files = new HashMap<>();
    for (String path : source.paths()) {
      files.put(path, source.bytes(path));
    }
    files.put(tablePath, yaml.getBytes(UTF_8));
    LoadResult loaded;
    try {
      files.put(PackSource.CHECKSUMS, Checksums.render(PackSource.of(files)).getBytes(UTF_8));
      loaded = PackLoader.load(PackSource.of(files));
    } catch (PackSyntaxException e) {
      return List.of(new PackProblem(e.code(), tablePath, 0, "the table file cannot be used"));
    }
    if (loaded.pack().isEmpty()) {
      return loaded.problems();
    }
    List<PackProblem> problems = new ArrayList<>();
    Rulings rulings = Rulings.load();
    for (ReportDefinition report : loaded.pack().get().manifest().reports()) {
      problems.addAll(CompiledPack.compile(loaded.pack().get(), report.id(), rulings).problems());
    }
    return problems;
  }

  /** Rows added, removed and changed (same id, other content). */
  private static int[] diff(DecisionTable before, DecisionTable after) {
    Map<String, TableRow> old = new HashMap<>();
    before.rows().forEach(r -> old.put(r.id(), r));
    int added = 0;
    int changed = 0;
    Map<String, TableRow> now = new HashMap<>();
    for (TableRow row : after.rows()) {
      now.put(row.id(), row);
      TableRow previous = old.get(row.id());
      if (previous == null) {
        added++;
      } else if (!previous.equals(row)) {
        changed++;
      }
    }
    int removed = 0;
    for (String id : old.keySet()) {
      if (!now.containsKey(id)) {
        removed++;
      }
    }
    return new int[] {added, removed, changed};
  }

  /** Temp file in the same directory, then an atomic move; the temp file never survives. */
  private static void writeAtomically(Path target, byte[] bytes) {
    Path temp = null;
    try {
      temp = Files.createTempFile(target.getParent(), ".table-", ".tmp");
      Files.write(temp, bytes);
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        throw input("cannot replace the table file atomically on this file system");
      }
    } catch (IOException e) {
      throw input("cannot write the table file: " + e.getClass().getSimpleName());
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // nothing to do: the original file is untouched or already replaced
        }
      }
    }
  }

  private static void requireValidTablePath(String tablePath) {
    if (!PackSource.isValidPath(tablePath)) {
      throw input("TABLE_PATH_INVALID '" + printable(tablePath, 64) + "' is not a pack path");
    }
  }

  private static DecisionTable scopeTable(LoadedPack pack, String tablePath) {
    boolean listed =
        pack.manifest().reports().stream().anyMatch(r -> r.scopeTables().contains(tablePath));
    DecisionTable table = pack.scopeTables().get(tablePath);
    if (!listed || table == null) {
      throw input(
          "TABLE_PATH_INVALID '"
              + printable(tablePath, 64)
              + "' is not a scope table listed by a report of the pack");
    }
    return table;
  }

  // ---- shared ----------------------------------------------------------------------------------

  /** Loads a pack directory; on problems prints them to {@code err} and returns empty. */
  private static Optional<LoadedPack> load(Path dir, PrintStream err) {
    LoadResult result = PackLoader.load(PackSource.fromDirectory(dir));
    if (result.pack().isPresent()) {
      return result.pack();
    }
    List<PackProblem> shown =
        ProblemSink.cap(result.problems(), PackFormat.MAX_PROBLEMS_PER_LOAD, PackManifest.FILE);
    for (PackProblem p : shown) {
      err.print(problemLine(p) + "\n");
    }
    err.print("the pack has " + shown.size() + " problems; run pack test\n");
    return Optional.empty();
  }

  private static String problemLine(PackProblem p) {
    return "problem "
        + p.code()
        + " "
        + p.path()
        + (p.line() > 0 ? ":" + p.line() : "")
        + " "
        + p.message();
  }

  private static Path path(String name) {
    try {
      return Path.of(name);
    } catch (InvalidPathException e) {
      throw input("not a valid file name: '" + printable(name, 64) + "'");
    }
  }

  private static Path directory(String name) {
    Path dir = path(name);
    if (!Files.isDirectory(dir)) {
      throw input("not a directory: '" + printable(name, 64) + "'");
    }
    return dir;
  }

  /**
   * Reads a regular file as strict UTF-8, at most {@link PackFormat#MAX_FILE_BYTES} bytes (a larger
   * file is refused without reading the rest). A leading BOM is kept: the CSV importer accepts one.
   */
  private static String readText(Path file, String what) {
    if (!Files.isRegularFile(file)) {
      throw input(what + " is not a readable file: '" + printable(file.toString(), 64) + "'");
    }
    byte[] data;
    try (InputStream in = Files.newInputStream(file)) {
      data = in.readNBytes(PackFormat.MAX_FILE_BYTES + 1);
    } catch (IOException e) {
      throw input("cannot read " + what + ": " + e.getClass().getSimpleName());
    }
    if (data.length > PackFormat.MAX_FILE_BYTES) {
      throw input(what + " is larger than " + PackFormat.MAX_FILE_BYTES + " bytes");
    }
    try {
      return UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString();
    } catch (CharacterCodingException e) {
      throw input(what + " is not valid UTF-8");
    }
  }

  /** Text safe to print: printable ASCII only (anything else as an escape), cut at {@code max}. */
  private static String printable(String text, int max) {
    String source = text == null ? "" : text;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < source.length() && out.length() < max; i++) {
      char c = source.charAt(i);
      if (c >= 0x20 && c <= 0x7E) {
        out.append(c);
      } else {
        out.append("\\u");
        String hex = Integer.toHexString(c);
        out.append("0".repeat(4 - hex.length())).append(hex);
      }
    }
    return out.toString();
  }
}
