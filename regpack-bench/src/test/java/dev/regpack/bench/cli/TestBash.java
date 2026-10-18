package dev.regpack.bench.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds a real bash for the tests that run a workflow script. On Windows a bare {@code bash} is
 * resolved by CreateProcess to the WSL launcher in the system directory before the PATH is read,
 * and that launcher fails without a distribution; so the PATH is scanned by hand and anything under
 * the Windows directory is skipped. A test that needs bash is skipped when none is found.
 */
public final class TestBash {
  private TestBash() {}

  /** The first working bash on the PATH outside the Windows directory, if any. */
  public static Optional<String> find() {
    String path = System.getenv("PATH");
    if (path == null) {
      return Optional.empty();
    }
    for (String entry : path.split(File.pathSeparator)) {
      if (entry.isBlank() || isUnderWindowsDirectory(entry)) {
        continue;
      }
      for (String name : new String[] {"bash", "bash.exe"}) {
        Path candidate = Path.of(entry).resolve(name);
        if (Files.isRegularFile(candidate) && works(candidate.toString())) {
          return Optional.of(candidate.toString());
        }
      }
    }
    return Optional.empty();
  }

  private static boolean isUnderWindowsDirectory(String entry) {
    String root = System.getenv("SystemRoot");
    return root != null && entry.regionMatches(true, 0, root, 0, root.length());
  }

  private static boolean works(String bash) {
    try {
      Process p = new ProcessBuilder(bash, "-c", "echo ok").redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return p.waitFor() == 0 && out.trim().equals("ok");
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Runs {@code bash script args} in {@code dir}, merging the streams; returns the exit code. */
  public static int run(String bash, Path dir, java.util.Map<String, String> env, String... args)
      throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = bash;
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
    pb.redirectErrorStream(true);
    pb.environment().putAll(env);
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }
}
