package dev.regpack.spikes.l.restore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a command and returns what it printed on standard output; the restore study drives the
 * fixture scripts through it, and a test replaces it with scripted output. Standard error goes to
 * the standard error of this process, so the log of the runner shows it.
 */
@FunctionalInterface
public interface Shell {
  /**
   * Runs the command.
   *
   * @return the lines printed on standard output
   * @throws IOException if the command cannot be started or exits with a non-zero status
   */
  List<String> run(List<String> command) throws IOException, InterruptedException;

  /**
   * The scripts of the fixture: {@code -Dspike.scripts.dir}, else {@code spikes/scripts} of the
   * repository.
   */
  static Path scriptsDir() {
    String property = System.getProperty("spike.scripts.dir");
    if (property != null && !property.isBlank()) {
      return Path.of(property);
    }
    String root = System.getProperty("regpack.repo.root");
    Path base = root == null || root.isBlank() ? Path.of("") : Path.of(root);
    return base.resolve("spikes").resolve("scripts");
  }

  /** Runs commands as child processes, printing their output as it is collected. */
  static Shell system() {
    return command -> {
      Process process =
          new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      List<String> lines = new ArrayList<>();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          lines.add(line);
          System.out.println(line);
        }
      }
      int status = process.waitFor();
      if (status != 0) {
        throw new IOException(String.join(" ", command) + " exited with status " + status);
      }
      return List.copyOf(lines);
    };
  }

  /** {@code bash <scripts dir>/<script> args...}: the exec bit of the script is not needed. */
  default List<String> script(String script, String... args)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("bash");
    command.add(scriptsDir().resolve(script).toString());
    command.addAll(List.of(args));
    return run(command);
  }
}
