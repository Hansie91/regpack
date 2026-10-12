package dev.regpack.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release isolation: the disposable spikes never reach the release build. The root POM lists no
 * spike module, no release module depends on a spike artefact, and nothing but the measurement
 * module itself has {@code regpack-bench} on its compile or runtime class path. POMs are read as
 * text; there is no XML parser dependency.
 *
 * <p>Problems: {@code ISO_ROOT_MODULE} and {@code ISO_RELEASE_DEPENDS <pom>}.
 */
public final class IsolationCheck {
  private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
  private static final Pattern MODULE = Pattern.compile("<module>([^<]*)</module>");
  private static final Pattern SPIKE_ARTIFACT = Pattern.compile("<artifactId>\\s*spike-");
  private static final Pattern MANAGEMENT =
      Pattern.compile("<dependencyManagement>.*?</dependencyManagement>", Pattern.DOTALL);
  private static final Pattern PLUGIN = Pattern.compile("<plugin>.*?</plugin>", Pattern.DOTALL);
  private static final Pattern DEPENDENCY =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
  private static final Pattern ARTIFACT_ID =
      Pattern.compile("<artifactId>\\s*([^<\\s]*)\\s*</artifactId>");
  private static final Pattern SCOPE = Pattern.compile("<scope>\\s*([^<\\s]*)\\s*</scope>");
  private static final String BENCH = "regpack-bench";

  private IsolationCheck() {}

  /**
   * Checks the POMs under a repository root.
   *
   * @param root the repository root
   * @return the problems, empty when clean
   */
  public static List<String> check(Path root) {
    List<String> problems = new ArrayList<>();
    String rootPom = read(root.resolve("pom.xml"), "pom.xml", problems);
    Set<String> poms = new TreeSet<>();
    if (rootPom != null) {
      boolean spikeModule = false;
      Matcher modules = MODULE.matcher(rootPom);
      while (modules.find()) {
        List<String> segments = normalise(modules.group(1));
        if (segments.stream().anyMatch(IsolationCheck::isSpikeSegment)) {
          spikeModule = true; // one ISO_ROOT_MODULE says it; the spike POM is not scanned too
          continue;
        }
        String pom = pomPath(segments);
        if (pom != null) {
          poms.add(pom);
        }
      }
      if (spikeModule) {
        problems.add("ISO_ROOT_MODULE");
      }
      if (dependsOnBench(rootPom)) {
        problems.add("ISO_RELEASE_DEPENDS pom.xml");
      }
    }
    for (String module : releaseModules(root)) {
      poms.add(module + "/pom.xml");
    }
    for (String relative : poms) {
      String pom = read(root.resolve(relative), relative, problems);
      if (pom == null) {
        continue;
      }
      boolean spike = SPIKE_ARTIFACT.matcher(pom).find();
      if (spike || (!relative.equals(BENCH + "/pom.xml") && dependsOnBench(pom))) {
        problems.add("ISO_RELEASE_DEPENDS " + relative);
      }
    }
    return List.copyOf(problems);
  }

  /**
   * A module path as its segments: backslashes read as slashes, empty and {@code .} segments
   * dropped, {@code ..} resolved against the segment before it (a leading {@code ..} is kept).
   */
  private static List<String> normalise(String module) {
    List<String> segments = new ArrayList<>();
    for (String segment : module.trim().replace('\\', '/').split("/")) {
      if (segment.isBlank() || segment.equals(".")) {
        continue;
      }
      if (segment.equals("..") && !segments.isEmpty() && !segments.getLast().equals("..")) {
        segments.removeLast();
      } else {
        segments.add(segment.trim());
      }
    }
    return segments;
  }

  private static boolean isSpikeSegment(String segment) {
    return segment.equals("spikes") || segment.startsWith("spike-");
  }

  /**
   * The POM file a module path names, relative to the root with slashes, or null for the root
   * itself and for a path that leaves the repository.
   */
  private static String pomPath(List<String> segments) {
    if (segments.isEmpty() || segments.contains("..")) {
      return null;
    }
    String path = String.join("/", segments);
    return path.endsWith(".xml") ? path : path + "/pom.xml";
  }

  private static boolean dependsOnBench(String pom) {
    String stripped = PLUGIN.matcher(MANAGEMENT.matcher(pom).replaceAll("")).replaceAll("");
    Matcher dependencies = DEPENDENCY.matcher(stripped);
    while (dependencies.find()) {
      String body = dependencies.group(1);
      Matcher artifact = ARTIFACT_ID.matcher(body);
      if (!artifact.find() || !artifact.group(1).equals(BENCH)) {
        continue;
      }
      Matcher scope = SCOPE.matcher(body);
      String value = scope.find() ? scope.group(1) : "compile";
      if (value.equals("compile") || value.equals("runtime")) {
        return true;
      }
    }
    return false;
  }

  /** The directories {@code regpack-*} of the root that hold a POM, in name order. */
  private static List<String> releaseModules(Path root) {
    List<String> names = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "regpack-*")) {
      for (Path entry : stream) {
        if (Files.isRegularFile(entry.resolve("pom.xml"))) {
          names.add(entry.getFileName().toString());
        }
      }
    } catch (IOException e) {
      return List.of();
    }
    names.sort(String::compareTo);
    return names;
  }

  /** The POM text without comments, or null when it is missing or cannot be read. */
  private static String read(Path file, String name, List<String> problems) {
    try {
      if (!Files.isRegularFile(file)) {
        return null;
      }
      return COMMENT.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll("");
    } catch (IOException e) {
      problems.add("ISO_UNREADABLE " + name);
      return null;
    }
  }
}
