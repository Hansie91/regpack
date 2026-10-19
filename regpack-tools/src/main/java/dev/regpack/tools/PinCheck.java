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
 * labels. The image rule covers service and job containers, {@code uses: docker://} steps and the
 * image of {@code docker run|pull|create} on one line of shell text.
 *
 * <p>A {@code runs-on} value is read inline (scalar, flow list, flow map) and, when the key is
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
  // the key may be quoted: 'image': and "image": are the same key in YAML (CI-4)
  private static final Pattern IMAGE_KEY =
      Pattern.compile("^\\s*(?:-\\s+)?['\"]?image['\"]?:\\s*(.*)$");
  private static final Pattern CONTAINER_KEY =
      Pattern.compile("^\\s*(?:-\\s+)?['\"]?container['\"]?:\\s*(\\S.*)$");
  private static final Pattern FLOW_IMAGE =
      Pattern.compile("[{,]\\s*['\"]?image['\"]?:\\s*([^,}]*)");
  // docker run|pull|create (optionally "docker container run") in shell text, with its arguments
  private static final Pattern DOCKER_COMMAND =
      Pattern.compile("\\bdocker\\s+(?:container\\s+|image\\s+)?(run|pull|create)\\b(.*)$");
  // the options of docker run|create|pull that take their value as the next word
  private static final Set<String> OPTIONS_WITH_VALUE =
      Set.of(
          "-e",
          "--env",
          "--env-file",
          "-v",
          "--volume",
          "--mount",
          "-p",
          "--publish",
          "--name",
          "--network",
          "--net",
          "-w",
          "--workdir",
          "-u",
          "--user",
          "--entrypoint",
          "-m",
          "--memory",
          "--memory-swap",
          "--shm-size",
          "--cpus",
          "-l",
          "--label",
          "--platform",
          "--restart",
          "--add-host",
          "-h",
          "--hostname",
          "--cap-add",
          "--cap-drop",
          "--device",
          "--pull",
          "--log-driver",
          "--log-opt",
          "--tmpfs",
          "--ulimit",
          "--health-cmd",
          "--health-interval",
          "--health-timeout",
          "--health-retries",
          "--stop-signal",
          "--stop-timeout",
          "--cpuset-cpus",
          "--security-opt",
          "--group-add",
          "--dns",
          "--ip",
          "--pid",
          "--ipc",
          "--user-ns",
          "--cgroupns",
          "--runtime",
          "--gpus",
          "--init-path",
          "--link",
          "--expose",
          "-a",
          "--attach");
  // the tag of an image that the workflow builds itself: docker [buildx] build ... -t|--tag NAME
  private static final Pattern DOCKER_BUILD_TAG =
      Pattern.compile(
          "\\bdocker\\s+(?:buildx\\s+)?build\\b[^\\n]*?\\s(?:-t|--tag)[ =]['\"]?([^\\s'\"]+)");
  private static final Pattern IMAGE_DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}$");

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
    Set<String> builtHere = imagesBuiltHere(content);
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
        if (value.startsWith("docker://")) {
          // a container image run as a step: the digest rule of the images applies (CI-4)
          String image = value.substring("docker://".length());
          if (!IMAGE_DIGEST.matcher(image).find()) {
            problems.add(where + "UNPINNED_IMAGE " + image);
          }
        } else if (!value.startsWith("./")) {
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
      addUnpinnedImages(problems, where, code);
      addUnpinnedDockerCommands(problems, where, code, builtHere);
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

  /**
   * Flags every container image of a workflow line that is not pinned by digest: the {@code image:}
   * key of a service container or a {@code container:} map (block or flow form) and the scalar
   * value of {@code container:}. A value that is not a plain reference with a lower-case SHA-256
   * digest at its end (including an expression or a block scalar indicator) is unverifiable and
   * flagged too.
   */
  private static void addUnpinnedImages(List<String> problems, String where, String code) {
    List<String> images = new ArrayList<>();
    Matcher image = IMAGE_KEY.matcher(code);
    Matcher container = CONTAINER_KEY.matcher(code);
    if (image.matches()) {
      images.add(image.group(1));
    } else if (container.matches() && !container.group(1).strip().startsWith("{")) {
      images.add(container.group(1));
    }
    if (code.indexOf('{') >= 0) {
      Matcher flow = FLOW_IMAGE.matcher(code);
      while (flow.find()) {
        images.add(flow.group(1));
      }
    }
    for (String raw : images) {
      String value = unquote(raw.strip());
      if (!IMAGE_DIGEST.matcher(value).find()) {
        problems.add(where + "UNPINNED_IMAGE " + value);
      }
    }
  }

  /**
   * Flags the image of {@code docker run}, {@code docker pull} and {@code docker create} in the
   * shell text of a line when it has no digest. The image is the first word after the command that
   * is not an option or the value of an option that takes one (the list is that of {@code docker
   * run}); a word with a variable is unverifiable and flagged. An image that the same workflow
   * builds ({@code docker build -t NAME}) has no digest to pin and is not flagged. Only a command
   * whose image is on the same line is seen: a line continued with a backslash hides it, and other
   * commands that start a container ({@code docker compose}, {@code docker service}, {@code docker
   * buildx}) are not looked at.
   */
  private static void addUnpinnedDockerCommands(
      List<String> problems, String where, String code, Set<String> builtHere) {
    Matcher command = DOCKER_COMMAND.matcher(code);
    if (!command.find()) {
      return;
    }
    String[] words = command.group(2).trim().split("\\s+");
    boolean skipValue = false;
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if (skipValue) {
        skipValue = false;
        continue;
      }
      if (word.startsWith("-")) {
        skipValue = OPTIONS_WITH_VALUE.contains(word);
        continue;
      }
      String value = unquote(word);
      if (!IMAGE_DIGEST.matcher(value).find() && !builtHere.contains(value)) {
        problems.add(where + "UNPINNED_IMAGE " + value);
      }
      return;
    }
  }

  /** The tags of the images that the workflow builds with {@code docker build -t} itself. */
  private static Set<String> imagesBuiltHere(String content) {
    Set<String> tags = new HashSet<>();
    Matcher build = DOCKER_BUILD_TAG.matcher(content);
    while (build.find()) {
      tags.add(build.group(1));
    }
    return tags;
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
