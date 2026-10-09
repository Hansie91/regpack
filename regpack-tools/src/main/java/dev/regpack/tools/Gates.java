package dev.regpack.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    if (args.length > 0 && args[0].equals("traceability")) {
      return traceability(Arrays.asList(args).subList(1, args.length), out, err);
    }
    err.println("usage: Gates <traceability|pins|eol>");
    return 2;
  }

  /**
   * The {@code traceability} gate: {@code --file <file> [--root <dir>] --scan <dir> [--scan <dir>
   * ...]}. A relative file or scan directory is resolved against the root.
   */
  private static int traceability(List<String> args, PrintStream out, PrintStream err) {
    String file = null;
    String root = ".";
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
        TraceabilityCheck.check(markdown, repoRoot, scans.stream().map(repoRoot::resolve).toList());
    if (result.ok()) {
      out.println(
          "traceability: OK rows="
              + result.rows()
              + " closed="
              + result.closed()
              + " open="
              + result.open());
      return 0;
    }
    result.problems().forEach(p -> out.println("traceability: PROBLEM " + p));
    return 1;
  }

  private static int traceabilityUsage(PrintStream err) {
    err.println(
        "usage: Gates traceability --file <file> [--root <dir>] --scan <dir> [--scan <dir> ...]");
    return 2;
  }
}
