package dev.regpack.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Supply-chain pin checks: container images are pinned by digest, workflow actions by full commit
 * SHA with a version comment, workflows declare least-privilege permissions and fixed runner
 * labels. A {@code runs-on} value is read inline (scalar, flow list, flow map) and, when the key is
 * bare, from the indented block sequence or map below it; labels that come from an expression such
 * as {@code matrix.os} are not resolved.
 */
public final class PinCheck {
  private static final Pattern FROM =
      Pattern.compile("^\\s*FROM\\s+(.+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}$");
  private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*(\\S+)(.*)$");
  private static final Pattern PINNED_ACTION =
      Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[^@\\s]+)?@[0-9a-f]{40}");
  private static final Pattern VERSION_COMMENT = Pattern.compile("^\\s+#\\s*v[0-9]");
  private static final Pattern TOP_PERMISSIONS = Pattern.compile("^permissions:");
  private static final Pattern WRITE_ALL = Pattern.compile("^\\s*permissions:\\s*write-all\\b");
  private static final Pattern PULL_REQUEST_TARGET = Pattern.compile("\\bpull_request_target\\b");
  private static final Pattern RUNS_ON = Pattern.compile("^(\\s*(?:-\\s+)?)runs-on:\\s*(.*)$");
  private static final Pattern COMMENT_START = Pattern.compile("(^|\\s)#");

  private PinCheck() {}

  /**
   * Checks the {@code FROM} instructions of a Dockerfile.
   *
   * @return the problems, empty when clean
   */
  public static List<String> checkDockerfile(String name, String content) {
    List<String> problems = new ArrayList<>();
    Set<String> aliases = new HashSet<>();
    String[] lines = content.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      Matcher from = FROM.matcher(lines[i]);
      if (!from.matches()) {
        continue;
      }
      List<String> tokens = new ArrayList<>();
      for (String token : from.group(1).trim().split("\\s+")) {
        if (!token.startsWith("--")) {
          tokens.add(token);
        }
      }
      if (tokens.isEmpty()) {
        continue;
      }
      String image = tokens.get(0);
      String key = image.toLowerCase(Locale.ROOT);
      String where = name + ":" + (i + 1) + ": ";
      if (image.contains("$")) {
        problems.add(where + "FROM image is unverifiable (contains a variable)");
      } else if (!key.equals("scratch")
          && !aliases.contains(key)
          && !DIGEST.matcher(image).find()) {
        problems.add(where + "FROM not pinned by digest");
      }
      if (tokens.size() >= 3 && tokens.get(1).equalsIgnoreCase("AS")) {
        aliases.add(tokens.get(2).toLowerCase(Locale.ROOT));
      }
    }
    return List.copyOf(problems);
  }

  /**
   * Checks one GitHub Actions workflow file.
   *
   * @return the problems, empty when clean
   */
  public static List<String> checkWorkflow(String name, String content) {
    List<String> problems = new ArrayList<>();
    boolean topLevelPermissions = false;
    String[] lines = content.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      String where = name + ":" + (i + 1) + ": ";
      if (TOP_PERMISSIONS.matcher(line).find()) {
        topLevelPermissions = true;
      }
      Matcher uses = USES.matcher(line);
      if (uses.matches()) {
        String value = unquote(uses.group(1));
        if (!value.startsWith("./") && !value.startsWith("docker://")) {
          if (!PINNED_ACTION.matcher(value).matches()) {
            problems.add(where + "UNPINNED_ACTION " + value);
          }
          if (!VERSION_COMMENT.matcher(uses.group(2)).find()) {
            problems.add(where + "MISSING_VERSION_COMMENT " + value);
          }
        }
        continue;
      }
      String code = withoutComment(line);
      if (WRITE_ALL.matcher(code).find()) {
        problems.add(where + "WRITE_ALL");
      }
      if (PULL_REQUEST_TARGET.matcher(code).find()) {
        problems.add(where + "PULL_REQUEST_TARGET");
      }
      Matcher runsOn = RUNS_ON.matcher(code);
      if (runsOn.matches()) {
        addLatestRunners(problems, where, runsOn.group(2));
        if (runsOn.group(2).isBlank()) {
          scanRunsOnContinuation(problems, name, lines, i, runsOn.group(1).length());
        }
      }
    }
    if (!topLevelPermissions) {
      problems.add(name + ": MISSING_PERMISSIONS no top-level permissions key");
    }
    return List.copyOf(problems);
  }

  /**
   * Checks every {@code .yml} and {@code .yaml} file of a workflow directory.
   *
   * @return the problems; an empty or missing directory is itself a problem
   */
  public static List<String> checkWorkflowDirectory(Path directory) {
    List<Path> files = new ArrayList<>();
    if (Files.isDirectory(directory)) {
      try (Stream<Path> children = Files.list(directory)) {
        children
            .filter(Files::isRegularFile)
            .filter(
                p -> {
                  String file = p.getFileName().toString();
                  return file.endsWith(".yml") || file.endsWith(".yaml");
                })
            .sorted()
            .forEach(files::add);
      } catch (IOException e) {
        return List.of("cannot list " + directory + ": " + e.getMessage());
      }
    }
    if (files.isEmpty()) {
      return List.of("no workflow files in " + directory);
    }
    List<String> problems = new ArrayList<>();
    for (Path file : files) {
      String label = directory + "/" + file.getFileName();
      try {
        problems.addAll(checkWorkflow(label, Files.readString(file, StandardCharsets.UTF_8)));
      } catch (IOException e) {
        problems.add("cannot read " + label + ": " + e.getMessage());
      }
    }
    return List.copyOf(problems);
  }

  /**
   * Scans the lines below a bare {@code runs-on:} key: indented block sequence or block map
   * entries, and sequence entries at the indentation of the key.
   */
  private static void scanRunsOnContinuation(
      List<String> problems, String name, String[] lines, int keyLine, int keyIndent) {
    for (int j = keyLine + 1; j < lines.length; j++) {
      String next = withoutComment(lines[j]);
      if (next.isBlank()) {
        continue;
      }
      int indent = next.length() - next.stripLeading().length();
      boolean sequenceEntry = indent == keyIndent && next.stripLeading().startsWith("- ");
      if (indent < keyIndent || (indent == keyIndent && !sequenceEntry)) {
        return;
      }
      addLatestRunners(problems, name + ":" + (j + 1) + ": ", next);
    }
  }

  private static void addLatestRunners(List<String> problems, String where, String labels) {
    for (String label : labels.split("[\\s,:\\[\\]{}'\"]+")) {
      if (label.endsWith("-latest")) {
        problems.add(where + "LATEST_RUNNER " + label);
      }
    }
  }

  private static String withoutComment(String line) {
    Matcher comment = COMMENT_START.matcher(line);
    return comment.find() ? line.substring(0, comment.start()) : line;
  }

  private static String unquote(String value) {
    boolean quoted =
        value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")));
    return quoted ? value.substring(1, value.length() - 1) : value;
  }
}
