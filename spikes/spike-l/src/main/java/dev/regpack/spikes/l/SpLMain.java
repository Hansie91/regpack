package dev.regpack.spikes.l;

import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.restore.Shell;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The command line of SP-L. {@code run --profile smoke|full --part ingest|restore|misc|all
 * --stretch true|false --deadline-minutes <n> --out <dir>} runs one part of the plan (or all of it)
 * against the fixture that {@code pg-up.sh} started on the runner and writes {@code
 * measurements.json} into the directory. Exit code 0 is success, 1 a failed run, 2 a usage error.
 *
 * <p>The run needs the system property {@code spike.pg.url} (the JDBC URL of the primary): without
 * it nothing can be measured, and a run that carried on would write a file that looks like a result
 * (review focus RF11). It is checked before anything else, and the run exits with status 2.
 */
public final class SpLMain {
  private static final String USAGE =
      "usage: run --profile smoke|full --part ingest|restore|misc|all --stretch true|false"
          + " --deadline-minutes <n> --out <dir>";
  private static final Set<String> RUN_OPTIONS =
      Set.of("--profile", "--part", "--stretch", "--deadline-minutes", "--out");

  private SpLMain() {}

  public static void main(String[] args) {
    System.exit(execute(args, System.out, System.err));
  }

  static int execute(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0) {
      err.println("SpLMain: a command is required");
      err.println(USAGE);
      return 2;
    }
    try {
      if (!args[0].equals("run")) {
        throw new IllegalArgumentException("unknown command " + args[0]);
      }
      // the fixture first: a run without it measures nothing (RF11)
      Optional<PgFixture> fixture = PgFixture.fromSystemProperty();
      if (fixture.isEmpty()) {
        err.println("SpLMain: " + PgFixture.PROPERTY + " is not set");
        return 2;
      }
      try (PgFixture pg = fixture.get()) {
        SpLRunner.Options options = options(Arrays.asList(args).subList(1, args.length));
        return SpLRunner.run(options, pg, Shell.system(), out);
      }
    } catch (IllegalArgumentException e) {
      err.println("SpLMain: " + e.getMessage());
      err.println(USAGE);
      return 2;
    } catch (IOException e) {
      err.println("SpLMain: " + e.getClass().getSimpleName() + " " + e.getMessage());
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      err.println("SpLMain: interrupted");
      return 1;
    }
  }

  private static SpLRunner.Options options(List<String> args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i < args.size(); i += 2) {
      String name = args.get(i);
      if (!RUN_OPTIONS.contains(name) || i + 1 >= args.size()) {
        throw new IllegalArgumentException("bad option " + name);
      }
      if (options.put(name, args.get(i + 1)) != null) {
        throw new IllegalArgumentException(name + " is given more than once");
      }
    }
    SpLRunner.Profile profile = SpLRunner.Profile.of(required(options, "--profile"));
    String partText = options.getOrDefault("--part", "all");
    Optional<SpLRunner.Part> part;
    if (partText.equals("all")) {
      part = Optional.empty();
    } else {
      part =
          Optional.of(
              SpLRunner.Part.of(partText)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "unknown part " + partText + " (ingest, restore, misc or all)")));
    }
    boolean stretch =
        switch (options.getOrDefault("--stretch", "false")) {
          case "true" -> true;
          case "false" -> false;
          default -> throw new IllegalArgumentException("--stretch is true or false");
        };
    long minutes;
    try {
      minutes = Long.parseLong(required(options, "--deadline-minutes"));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--deadline-minutes is a number of minutes");
    }
    if (minutes < 0) {
      throw new IllegalArgumentException("--deadline-minutes must not be negative");
    }
    Path out = Path.of(required(options, "--out"));
    return new SpLRunner.Options(
        profile, stretch, part, minutes, out, System.getenv(), Path.of("").toAbsolutePath());
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
