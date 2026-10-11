package dev.regpack.spikes.g;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.proto.EnvCapture;
import dev.regpack.bench.report.Git;
import dev.regpack.model.Sha256;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The command line of SP-G: {@code run --out <dir>} counts the six EMIR action types in the pack
 * files, reads the committed register, runs the {@link CoexistenceCheck} and writes {@code
 * measurements.json} (scope {@code DOCUMENT}) into the directory. SP-G is run locally: its criteria
 * are counts and documents with no hardware sensitivity. Exit code 0 is success, 1 a failed run, 2
 * a usage error. The register is only read, never edited. Disposable spike code.
 */
public final class SpGMain {
  private static final String USAGE = "usage: run --out <dir>";
  private static final Set<String> SIX = Set.of("NEWT", "MODI", "TERM", "EROR", "REVI", "VALU");
  private static final String DOCS = "docs/spikes/sp-g";
  private static final List<String> DOCUMENTS =
      List.of(
          DOCS + "/emir-pack/derive/action-type.yaml",
          DOCS + "/emir-pack/validate/sequencing.yaml",
          DOCS + "/register.md");

  private SpGMain() {}

  public static void main(String[] args) {
    System.exit(execute(args));
  }

  static int execute(String[] args) {
    if (args.length == 0) {
      System.err.println("sp-g: a command is required");
      System.err.println(USAGE);
      return 2;
    }
    try {
      if (!args[0].equals("run")) {
        throw new IllegalArgumentException("unknown command " + args[0]);
      }
      Path out = null;
      for (int i = 1; i < args.length; i += 2) {
        if (!args[i].equals("--out") || i + 1 >= args.length || out != null) {
          throw new IllegalArgumentException("bad option " + args[i]);
        }
        out = Path.of(args[i + 1]);
      }
      if (out == null) {
        throw new IllegalArgumentException("--out is required");
      }
      return run(out, repoRoot(Path.of("").toAbsolutePath()), System.getenv(), System.out);
    } catch (IllegalArgumentException e) {
      System.err.println("sp-g: " + e.getMessage());
      System.err.println(USAGE);
      return 2;
    }
  }

  /** The nearest folder at or above {@code start} that holds the SP-G documents. */
  private static Path repoRoot(Path start) {
    for (Path p = start; p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve(DOCS).resolve("register.md"))) {
        return p;
      }
    }
    throw new IllegalArgumentException("run from inside the repository: no " + DOCS + " above");
  }

  /**
   * Runs the plan and writes {@code measurements.json} into {@code out}.
   *
   * @param out the result directory (created)
   * @param repo the repository root that holds {@code docs/spikes/sp-g}
   * @param env the process environment (the commit comes from {@code GITHUB_SHA} on a runner)
   * @return 0, or 1 when a document is missing or malformed (no file is written)
   */
  static int run(Path out, Path repo, Map<String, String> env, PrintStream stdout) {
    try {
      Path sp = repo.resolve(DOCS);
      Path pack = sp.resolve("emir-pack");
      Set<String> derived = new TreeSet<>();
      for (Object row : list(map(parse(pack.resolve("derive/action-type.yaml"))).get("rows"))) {
        Object value = map(row).get("value");
        if (value instanceof String s) {
          derived.add(s);
        }
      }
      Set<String> sequenced = new TreeSet<>();
      for (Object rule : list(map(parse(pack.resolve("validate/sequencing.yaml"))).get("rules"))) {
        if (map(rule).get("action") instanceof String s) {
          sequenced.add(s);
        }
      }
      Set<String> both = new TreeSet<>(SIX);
      both.retainAll(derived);
      both.retainAll(sequenced);

      List<Register.Row> register = Register.parse(read(sp.resolve("register.md")));
      long open = Register.open(register);
      long unfixable = Register.unfixable(register);
      CoexistenceCheck.Result coexistence = CoexistenceCheck.run();

      List<Series> series =
          List.of(
              new CountValue("G-1", both.size(), 1),
              // open rows: no valid run, so the criterion is INCONCLUSIVE and never a FAIL
              new CountValue("G-2", open, open == 0 ? 1 : 0),
              new CountValue("G-3", unfixable, 1),
              new CountValue("G-4", coexistence.failures(), 1));
      List<String> notes = new ArrayList<>();
      if (open > 0) {
        notes.add(
            "G-2: "
                + open
                + " of "
                + register.size()
                + " register rows are not dispositioned by the owner");
      }
      if (both.size() < SIX.size()) {
        notes.add("G-1: action types missing from the pack files: " + missing(both));
      }
      for (String failure : coexistence.failed()) {
        notes.add("G-4: " + failure);
      }

      Map<String, Object> env2 = new LinkedHashMap<>(EnvCapture.capture());
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("profile", "local");
      source.put("stretch", false);
      source.put("part", "all");
      source.put("registerRows", (long) register.size());
      source.put("coexistenceChecks", (long) coexistence.checks());
      MeasurementSet set =
          new MeasurementSet(
              "SP-G",
              configDigest(repo),
              "DOCUMENT",
              series,
              Map.<Resource, List<BigDecimal>>of(),
              List.of(runInfo(env, env2, repo)),
              source,
              env2,
              Map.of(),
              notes);
      Files.createDirectories(out);
      Path target = out.resolve("measurements.json");
      Path temporary = Files.createTempFile(out, "measurements", ".tmp");
      try {
        Files.writeString(temporary, set.toJson() + "\n", StandardCharsets.UTF_8);
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
      stdout.println(
          "sp-g: G-1="
              + both.size()
              + " G-2="
              + open
              + " G-3="
              + unfixable
              + " G-4="
              + coexistence.failures()
              + " -> "
              + target);
      return 0;
    } catch (IOException | IllegalArgumentException | ClassCastException e) {
      System.err.println("sp-g: " + e.getClass().getSimpleName() + " " + e.getMessage());
      return 1;
    }
  }

  private static String missing(Set<String> found) {
    Set<String> gone = new TreeSet<>(SIX);
    gone.removeAll(found);
    return String.join(", ", gone);
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private static Object parse(Path file) throws IOException {
    return MiniYaml.parse(read(file));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("a mapping was expected, found " + value);
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    if (!(value instanceof List)) {
      throw new IllegalArgumentException("a sequence was expected, found " + value);
    }
    return (List<Object>) value;
  }

  /**
   * The digest of the configuration: the documents that the criteria are read from (line endings
   * normalised), never the host, so a changed register is a new configuration.
   */
  private static String configDigest(Path repo) throws IOException {
    Map<String, Object> documents = new LinkedHashMap<>();
    for (String document : DOCUMENTS) {
      documents.put(
          document, Sha256.ofUtf8(read(repo.resolve(document)).replace("\r\n", "\n")).hex());
    }
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("documents", documents);
    plan.put("coexistenceChecks", List.copyOf(CoexistenceCheck.CHECKS));
    return MeasurementSet.configDigest(
        "SP-G", "local", false, Criteria.load().digest().hex(), plan);
  }

  private static RunInfo runInfo(Map<String, String> vars, Map<String, Object> env, Path repo) {
    String sha = vars.get("GITHUB_SHA");
    if (sha == null || !Git.SHA.matcher(sha).matches()) {
      sha = Git.head(repo).orElse("unknown");
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
}
