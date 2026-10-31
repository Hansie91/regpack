package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The PostgreSQL driver and its annotation jar are the only third-party runtime libraries of the
 * release build (AP-5), and only this module may name them (A9, review focus LF14). No spike
 * artefact enters a release module.
 */
class ThirdPartyBoundaryTest {
  /** Directories that are never part of a release module or its build files. */
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of("target", ".git", ".superpowers", ".idea", "node_modules");

  private static final String THIS_MODULE = "regpack-postgres";

  private static Path repoRoot() {
    return Path.of(System.getProperty("regpack.repo.root")).toAbsolutePath().normalize();
  }

  private static String text(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(file.toString(), e);
    }
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  /** Every regular file under the root that is not in a skipped directory, as a path list. */
  private static List<Path> files(Path root, boolean includeSpikes) throws IOException {
    List<Path> result = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile)
          .filter(
              p -> {
                for (Path part : root.relativize(p)) {
                  String name = part.toString();
                  if (SKIPPED_DIRECTORIES.contains(name)) {
                    return false;
                  }
                }
                String rel = relative(root, p);
                return includeSpikes || !rel.startsWith("spikes/");
              })
          .forEach(result::add);
    }
    return result;
  }

  @Test
  void onlyThePostgresModuleNamesTheDriver() throws IOException {
    Path root = repoRoot();
    List<String> offenders = new ArrayList<>();
    String thisPom = THIS_MODULE + "/pom.xml";
    for (Path file : files(root, false)) {
      String rel = relative(root, file);
      boolean isPom = rel.equals("pom.xml") || rel.endsWith("/pom.xml");
      boolean isMainSource = rel.contains("/src/main/") && !rel.startsWith(THIS_MODULE + "/");
      boolean isPackaging = rel.startsWith("packaging/");
      if (!(isPom || isMainSource || isPackaging) || rel.equals(thisPom)) {
        continue;
      }
      String content = text(file);
      if (content.contains("org.postgresql") || content.contains("org.checkerframework")) {
        offenders.add(rel);
      }
    }
    assertThat(offenders)
        .as("files other than %s and spikes/ that name the driver or its annotation jar", thisPom)
        .isEmpty();

    String ownPom = text(root.resolve(thisPom));
    assertThat(ownPom).contains("org.postgresql").contains("org.checkerframework");

    // A9, OI-12: the application (and every other module) does not depend on this module, so the
    // distribution stays JDK-only
    List<String> dependents = new ArrayList<>();
    for (Path file : files(root, false)) {
      String rel = relative(root, file);
      boolean isOtherPom = rel.endsWith("/pom.xml") && !rel.equals(thisPom);
      if ((isOtherPom || rel.startsWith("packaging/"))
          && text(file).contains("<artifactId>" + THIS_MODULE)) {
        dependents.add(rel);
      }
    }
    assertThat(dependents).as("files that depend on %s", THIS_MODULE).isEmpty();
    assertThat(text(root.resolve("regpack-app/pom.xml"))).doesNotContain(THIS_MODULE);
  }

  @Test
  void noSpikeArtefactIsReferenced() throws IOException {
    Path root = repoRoot();
    Path module = root.resolve(THIS_MODULE);
    List<String> offenders = new ArrayList<>();
    for (Path file : files(module, true)) {
      String rel = relative(module, file);
      boolean isPom = rel.equals("pom.xml");
      boolean isMainSource = rel.startsWith("src/main/");
      if (!(isPom || isMainSource || rel.startsWith("src/test/"))
          || rel.equals("src/test/java/dev/regpack/postgres/ThirdPartyBoundaryTest.java")) {
        continue;
      }
      String content = text(file);
      if (content.contains("dev.regpack.spikes")
          || content.contains("<artifactId>spike-")
          || content.contains("regpack-spikes")) {
        offenders.add(rel);
      }
    }
    assertThat(offenders)
        .as("no spike package or spike artifact id in the POM or the sources of %s", THIS_MODULE)
        .isEmpty();
  }

  /**
   * Of the modules of this repository only the model and the ports are compile dependencies of this
   * module; every other one (the in-memory adapter, the evidence tier, the test kit) is test scope,
   * in this direction only (AP-5). The enforcer lets {@code dev.regpack} through at every scope, so
   * the scope is pinned here. Every dependency element is read in any layout (on one line or on
   * several, with or without a version child), and a missing scope counts as compile, as it does
   * for Maven. The three entries asserted first make a reformatted POM fail loudly instead of
   * passing with nothing read.
   */
  @Test
  void everyRegpackDependencyBeyondModelAndPortsIsTestScope() {
    String pom = text(repoRoot().resolve(THIS_MODULE).resolve("pom.xml"));
    Matcher block = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
    Map<String, String> scopeOf = new TreeMap<>();
    while (block.find()) {
      String element = block.group(1);
      Matcher group = Pattern.compile("<groupId>([^<]*)</groupId>").matcher(element);
      Matcher artifact = Pattern.compile("<artifactId>([^<]*)</artifactId>").matcher(element);
      Matcher scope = Pattern.compile("<scope>([^<]*)</scope>").matcher(element);
      if (group.find() && group.group(1).trim().equals("dev.regpack") && artifact.find()) {
        scopeOf.put(artifact.group(1).trim(), scope.find() ? scope.group(1).trim() : "compile");
      }
    }

    assertThat(scopeOf)
        .containsEntry("regpack-inmemory", "test")
        .containsEntry("regpack-model", "compile")
        .containsEntry("regpack-ports", "compile");
    scopeOf.forEach(
        (artifact, scope) -> {
          if (!artifact.equals("regpack-model") && !artifact.equals("regpack-ports")) {
            assertThat(scope).as(artifact + " is a test-scope dependency").isEqualTo("test");
          }
        });
  }
}
