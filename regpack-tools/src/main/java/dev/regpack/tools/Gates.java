package dev.regpack.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Entry point of the build and CI gates. */
public final class Gates {
  private Gates() {}

  /** Runs the gate named by the first argument and exits with its status. */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * Runs a gate.
   *
   * @return 0 on success, 1 when violations were found, 2 on a usage error
   */
  static int run(String[] args, PrintStream out, PrintStream err) {
    return run(args, System.in, out, err);
  }

  /** Runs a gate that may read a list of paths from {@code in}. */
  static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
    return run(args, in, out, err, System.getenv());
  }

  /** Runs a gate with an explicit environment (the tests pass one). */
  static int run(
      String[] args, InputStream in, PrintStream out, PrintStream err, Map<String, String> env) {
    if (args.length > 0) {
      List<String> rest = Arrays.asList(args).subList(1, args.length);
      switch (args[0]) {
        case "traceability" -> {
          return traceability(rest, out, err, env);
        }
        case "pins" -> {
          return pins(rest, out, err);
        }
        case "eol" -> {
          return eol(rest, in, out, err);
        }
        case "g3" -> {
          return g3(rest, in, out, err);
        }
        case "isolation" -> {
          return isolation(rest, out, err);
        }
        case "entry" -> {
          return entry(rest, out, err);
        }
        case "annotate" -> {
          return annotate(rest, out, err);
        }
        default -> {
          // falls through to the usage message
        }
      }
    }
    err.println("usage: Gates <traceability|pins|eol|g3|isolation|entry|annotate>");
    return 2;
  }

  /**
   * The {@code annotate} command: {@code --scan <dir> [--scan <dir> ...]}. Prints the workflow
   * commands for the test reports found under the directories (see {@link Annotations}).
   */
  private static int annotate(List<String> args, PrintStream out, PrintStream err) {
    List<Path> scans = new ArrayList<>();
    for (int i = 0; i < args.size(); i += 2) {
      if (i + 1 >= args.size() || !args.get(i).equals("--scan")) {
        return annotateUsage(err);
      }
      scans.add(Path.of(args.get(i + 1)));
    }
    if (scans.isEmpty()) {
      return annotateUsage(err);
    }
    for (Path scan : scans) {
      if (!Files.isDirectory(scan)) {
        err.println("annotate: scan directory not found: " + scan);
        return 2;
      }
    }
    Annotations.render(scans).forEach(out::println);
    return 0;
  }

  private static int annotateUsage(PrintStream err) {
    err.println("usage: Gates annotate --scan <dir> [--scan <dir> ...]");
    return 2;
  }

  /**
   * The {@code traceability} gate: {@code --file <file> [--root <dir>] --scan <dir> [--scan <dir>
   * ...] [--realstore all|skip|only] [--annotate]}. A relative file or scan directory is resolved
   * against the root. The mode (default {@code all}) is described at {@link
   * TraceabilityCheck.Mode}; with {@code skip} the deferred rows are printed as information and the
   * OK line gains {@code deferred=<n>}. When the check fails and {@code --annotate} is given, or
   * the environment says it runs in GitHub Actions ({@code GITHUB_ACTIONS=true}), each problem is
   * also printed as an {@code ::error} workflow command (at most ten), so that the reason of a red
   * step is in the annotation listing and not only in the log (CI-1).
   */
  private static int traceability(
      List<String> arguments, PrintStream out, PrintStream err, Map<String, String> env) {
    boolean annotate = "true".equals(env.get("GITHUB_ACTIONS")) || arguments.contains("--annotate");
    List<String> args = arguments.stream().filter(a -> !a.equals("--annotate")).toList();
    String file = null;
    String root = ".";
    TraceabilityCheck.Mode mode = TraceabilityCheck.Mode.ALL;
    List<String> scans = new ArrayList<>();
    for (int i = 0; i < args.size(); i += 2) {
      if (i + 1 >= args.size()) {
        return traceabilityUsage(err);
      }
      String value = args.get(i + 1);
      switch (args.get(i)) {
        case "--file" -> file = value;
        case "--root" -> root = value;
        case "--scan" -> scans.add(value);
        case "--realstore" -> {
          switch (value) {
            case "all" -> mode = TraceabilityCheck.Mode.ALL;
            case "skip" -> mode = TraceabilityCheck.Mode.SKIP;
            case "only" -> mode = TraceabilityCheck.Mode.ONLY;
            default -> {
              return traceabilityUsage(err);
            }
          }
        }
        default -> {
          return traceabilityUsage(err);
        }
      }
    }
    if (file == null || scans.isEmpty()) {
      return traceabilityUsage(err);
    }
    Path repoRoot = Path.of(root);
    for (String scan : scans) {
      if (!Files.isDirectory(repoRoot.resolve(scan))) {
        err.println("traceability: scan directory not found: " + scan);
        return 2;
      }
    }
    String markdown;
    try {
      markdown = Files.readString(repoRoot.resolve(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      err.println("traceability: cannot read " + file + ": " + e.getMessage());
      return 2;
    }
    TraceabilityCheck.Result result =
        TraceabilityCheck.check(
            markdown, repoRoot, scans.stream().map(repoRoot::resolve).toList(), mode);
    result.deferred().forEach(d -> out.println("traceability: INFO " + d));
    if (result.ok()) {
      out.println(
          "traceability: OK rows="
              + result.rows()
              + " closed="
              + result.closed()
              + " open="
              + result.open()
              + (mode == TraceabilityCheck.Mode.SKIP
                  ? " deferred=" + result.deferred().size()
                  : ""));
      return 0;
    }
    result.problems().forEach(p -> out.println("traceability: PROBLEM " + p));
    if (annotate) {
      Annotations.problemErrors("traceability", result.problems()).forEach(out::println);
    }
    return 1;
  }

  private static int traceabilityUsage(PrintStream err) {
    err.println(
        "usage: Gates traceability --file <file> [--root <dir>] --scan <dir> [--scan <dir> ...]"
            + " [--realstore all|skip|only] [--annotate]");
    return 2;
  }

  /** The {@code pins} gate: {@code --dockerfile <file> --workflows <dir>}. */
  private static int pins(List<String> args, PrintStream out, PrintStream err) {
    String dockerfile = null;
    String workflows = null;
    for (int i = 0; i < args.size(); i += 2) {
      if (i + 1 >= args.size()) {
        return pinsUsage(err);
      }
      switch (args.get(i)) {
        case "--dockerfile" -> dockerfile = args.get(i + 1);
        case "--workflows" -> workflows = args.get(i + 1);
        default -> {
          return pinsUsage(err);
        }
      }
    }
    if (dockerfile == null || workflows == null) {
      return pinsUsage(err);
    }
    List<String> problems = new ArrayList<>();
    try {
      problems.addAll(
          PinCheck.checkDockerfile(
              dockerfile, Files.readString(Path.of(dockerfile), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      problems.add("cannot read " + dockerfile + ": " + e.getMessage());
    }
    problems.addAll(PinCheck.checkWorkflowDirectory(Path.of(workflows)));
    return report("pins", problems, out);
  }

  private static int pinsUsage(PrintStream err) {
    err.println("usage: Gates pins --dockerfile <file> --workflows <dir>");
    return 2;
  }

  /**
   * The {@code eol} gate: {@code [--root <dir>]}, with relative paths, one per line or NUL
   * terminated, on standard input.
   */
  private static int eol(List<String> args, InputStream in, PrintStream out, PrintStream err) {
    String root = rootOption(args);
    if (root == null) {
      err.println("usage: Gates eol [--root <dir>] (relative paths, one per line, on stdin)");
      return 2;
    }
    List<String> paths = readPaths("eol", in, err);
    if (paths == null) {
      return 2;
    }
    return report("eol", EolCheck.check(Path.of(root), paths), out);
  }

  /**
   * The {@code g3} gate: {@code [--root <dir>]}, with relative paths, one per line or NUL
   * terminated, on standard input.
   */
  private static int g3(List<String> args, InputStream in, PrintStream out, PrintStream err) {
    String root = rootOption(args);
    if (root == null) {
      err.println("usage: Gates g3 [--root <dir>] (relative paths, one per line, on stdin)");
      return 2;
    }
    List<String> paths = readPaths("g3", in, err);
    if (paths == null) {
      return 2;
    }
    return report("g3", G3Check.check(Path.of(root), paths), out);
  }

  /** The {@code isolation} gate: {@code [--root <dir>]}. */
  private static int isolation(List<String> args, PrintStream out, PrintStream err) {
    String root = rootOption(args);
    if (root == null) {
      err.println("usage: Gates isolation [--root <dir>]");
      return 2;
    }
    return report("isolation", IsolationCheck.check(Path.of(root)), out);
  }

  /**
   * The {@code entry} gate: {@code --target <target> [--root <dir>]}. Prints one line per
   * requirement of the target and exits 0 only when every line is OK; an unknown target is a usage
   * error.
   */
  private static int entry(List<String> args, PrintStream out, PrintStream err) {
    String target = null;
    String root = ".";
    for (int i = 0; i < args.size(); i += 2) {
      if (i + 1 >= args.size()) {
        return entryUsage(err);
      }
      switch (args.get(i)) {
        case "--target" -> target = args.get(i + 1);
        case "--root" -> root = args.get(i + 1);
        default -> {
          return entryUsage(err);
        }
      }
    }
    if (target == null) {
      return entryUsage(err);
    }
    Path repoRoot = Path.of(root);
    String wiring = "docs/spikes/entry-wiring.tsv";
    try {
      List<EntryCheck.Requirement> all =
          EntryCheck.parse(Files.readString(repoRoot.resolve(wiring), StandardCharsets.UTF_8));
      EntryCheck.Outcome outcome = EntryCheck.check(target, all, repoRoot);
      outcome.lines().forEach(out::println);
      return outcome.satisfied() ? 0 : 1;
    } catch (IOException e) {
      err.println("entry: cannot read " + wiring + ": " + e.getMessage());
    } catch (IllegalArgumentException e) {
      err.println("entry: " + e.getMessage());
    }
    return 2;
  }

  private static int entryUsage(PrintStream err) {
    err.println("usage: Gates entry --target <target> [--root <dir>]");
    return 2;
  }

  /** The value of {@code [--root <dir>]}, {@code "."} when absent, null on a usage error. */
  private static String rootOption(List<String> args) {
    String root = ".";
    for (int i = 0; i < args.size(); i += 2) {
      if (i + 1 >= args.size() || !args.get(i).equals("--root")) {
        return null;
      }
      root = args.get(i + 1);
    }
    return root;
  }

  /**
   * Relative paths from standard input: NUL-terminated entries when the input holds a NUL (the
   * {@code git ls-files -z} form, which never quotes or escapes a name), otherwise one per line.
   * Null (after a message) when unreadable.
   */
  private static List<String> readPaths(String gate, InputStream in, PrintStream err) {
    List<String> paths = new ArrayList<>();
    try {
      String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (all.indexOf((char) 0) >= 0) {
        for (String entry : all.split("\\x00")) {
          if (!entry.isEmpty()) {
            paths.add(entry);
          }
        }
      } else {
        for (String line : all.split("\n")) {
          if (!line.isBlank()) {
            paths.add(line.strip());
          }
        }
      }
    } catch (IOException e) {
      err.println(gate + ": cannot read standard input: " + e.getMessage());
      return null;
    }
    return paths;
  }

  private static int report(String gate, List<String> problems, PrintStream out) {
    if (problems.isEmpty()) {
      out.println(gate + ": OK");
      return 0;
    }
    problems.forEach(p -> out.println(gate + ": PROBLEM " + p));
    return 1;
  }
}
