package dev.regpack.spikes.x;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command line of SP-X. {@code run --profile smoke|full --part all --stretch true|false
 * --deadline-minutes <n> --out <dir>} runs the plan and writes {@code measurements.json} into the
 * directory; {@code worker ...} is the fork child that the run starts (see {@link SpXWorker}) and
 * prints one {@code FORK-RESULT} line. Exit code 0 is success, 1 a failed run, 2 a usage error.
 *
 * <p>The system property {@code spike.x.smoke.heap} (default {@code 1g}) sets the heap of the smoke
 * profile's fork JVMs on a machine that cannot give it the heap of the full profile; the full
 * profile's heap is fixed at {@code -Xms6g -Xmx6g}.
 */
public final class SpXMain {
  private static final String USAGE =
      "usage: run --profile smoke|full --part all --stretch true|false --deadline-minutes <n>"
          + " --out <dir> | worker --bench writer|xsd|gate|bisect|assemble|latency"
          + " --protocol smoke|informative|full --profile smoke|full [--threads <t>]"
          + " [--policy ALL|SAMPLED_10]";
  private static final Set<String> RUN_OPTIONS =
      Set.of("--profile", "--part", "--stretch", "--deadline-minutes", "--out");

  private SpXMain() {}

  public static void main(String[] args) {
    System.exit(execute(args));
  }

  static int execute(String[] args) {
    if (args.length == 0) {
      System.err.println("sp-x: a command is required");
      System.err.println(USAGE);
      return 2;
    }
    try {
      return switch (args[0]) {
        case "run" -> run(Arrays.asList(args).subList(1, args.length));
        case "worker" -> SpXWorker.run(Arrays.asList(args).subList(1, args.length));
        default -> throw new IllegalArgumentException("unknown command " + args[0]);
      };
    } catch (IllegalArgumentException e) {
      System.err.println("sp-x: " + e.getMessage());
      System.err.println(USAGE);
      return 2;
    } catch (IOException e) {
      System.err.println("sp-x: " + e.getClass().getSimpleName() + " " + e.getMessage());
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("sp-x: interrupted");
      return 1;
    }
  }

  private static int run(List<String> args) throws IOException, InterruptedException {
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
    SpXRunner.Profile profile = SpXRunner.Profile.of(required(options, "--profile"));
    String part = options.getOrDefault("--part", "all");
    if (!part.equals("all")) {
      throw new IllegalArgumentException("SP-X has one part, all: " + part);
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
    return SpXRunner.run(
        new SpXRunner.Options(
            profile, stretch, minutes, out, System.getenv(), Path.of("").toAbsolutePath()),
        System.out);
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
