package dev.regpack.app;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Command line entry point: {@code serve}, {@code smoke} and {@code help}. */
public final class Main {
  private static final String USAGE =
      """
      usage: regpack <command> [options]
      commands:
        serve   run the selected roles (--roles, --bind, --port)
        smoke   run the walking skeleton end to end [--health-url <base>]
        help    print this text""";

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), System.out, System.err));
  }

  static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(env, "env");
    if (args.length == 0 || args[0].equals("help") || args[0].equals("-h")) {
      out.println(USAGE);
      return 0;
    }
    List<String> rest = List.of(Arrays.copyOfRange(args, 1, args.length));
    switch (args[0]) {
      case "smoke":
        return SmokeCommand.run(rest, out, err, new SystemClock());
      case "serve":
        return serve(rest, env, err);
      default:
        err.println("unknown command '" + args[0] + "'");
        err.println(USAGE);
        return 2;
    }
  }

  private static int serve(List<String> args, Map<String, String> env, PrintStream err) {
    AppConfig config;
    try {
      config = AppConfig.parse(args, env);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      err.println(USAGE);
      return 2;
    }
    Service service = Service.start(config, System.out);
    Runtime.getRuntime().addShutdownHook(new Thread(service::stop, "regpack-shutdown"));
    try {
      service.awaitStop();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      service.stop();
    }
    return 0;
  }
}
