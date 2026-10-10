package dev.regpack.bench.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The few git questions the harness asks, each one process with fixed arguments and {@code -C
 * <repository>}. A commit id is checked against {@link #SHA} by the caller before it reaches a
 * process, so no text from a result file is ever an argument.
 */
public final class Git {
  /** A full commit id: 40 lowercase hex characters. */
  public static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private Git() {}

  /** What a finished process gave: its exit code and its standard output. */
  record Outcome(int exitCode, String output) {}

  /** The commit id of HEAD, or empty when the directory is not a repository with a commit. */
  public static Optional<String> head(Path repo) {
    Optional<Outcome> outcome = run(repo, "rev-parse", "HEAD");
    if (outcome.isEmpty() || outcome.get().exitCode() != 0) {
      return Optional.empty();
    }
    String sha = outcome.get().output().strip();
    return SHA.matcher(sha).matches() ? Optional.of(sha) : Optional.empty();
  }

  /**
   * True when the working tree differs from HEAD in a tracked file or holds an untracked file that
   * is not ignored, or when that cannot be told.
   */
  public static boolean dirty(Path repo) {
    Optional<Outcome> outcome = run(repo, "status", "--porcelain");
    return outcome.isEmpty() || outcome.get().exitCode() != 0 || !outcome.get().output().isBlank();
  }

  /** True when the commit is an ancestor of HEAD (or HEAD itself). */
  public static boolean isAncestor(Path repo, String sha) {
    if (!SHA.matcher(sha).matches()) {
      return false;
    }
    Optional<Outcome> outcome = run(repo, "merge-base", "--is-ancestor", sha, "HEAD");
    return outcome.isPresent() && outcome.get().exitCode() == 0;
  }

  private static Optional<Outcome> run(Path repo, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(repo.toString());
    command.addAll(List.of(arguments));
    return run(command, TIMEOUT);
  }

  /**
   * Runs one process and returns what it gave, or empty when it cannot be started, is interrupted
   * or does not finish within the timeout (it is then killed). The output is read by a second
   * thread while this one waits, so a process that hangs with its output open cannot block the
   * wait.
   */
  static Optional<Outcome> run(List<String> command, Duration timeout) {
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
    // a repository chosen by the environment of a hook must not replace the one we were given
    builder.environment().remove("GIT_DIR");
    builder.environment().remove("GIT_WORK_TREE");
    builder.environment().remove("GIT_INDEX_FILE");
    Process process = null;
    try {
      process = builder.start();
      ByteArrayOutputStream collected = new ByteArrayOutputStream();
      InputStream stream = process.getInputStream();
      Thread reader =
          new Thread(
              () -> {
                try {
                  stream.transferTo(collected);
                } catch (IOException e) {
                  // the process was killed or closed its output: what was read is all there is
                }
              },
              "git-output");
      reader.setDaemon(true);
      reader.start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      // the process has ended, so its output ends too: wait for the reader to drain it
      reader.join(timeout.toMillis());
      if (reader.isAlive()) {
        return Optional.empty();
      }
      return Optional.of(
          new Outcome(
              process.exitValue(), new String(collected.toByteArray(), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (process != null) {
        process.destroyForcibly();
      }
      return Optional.empty();
    }
  }
}
