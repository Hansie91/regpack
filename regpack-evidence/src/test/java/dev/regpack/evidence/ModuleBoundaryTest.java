package dev.regpack.evidence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * AP-5: the module {@code regpack-evidence} has no third-party dependency, names no database
 * driver, and no other module of the build depends on the application's side of it.
 */
class ModuleBoundaryTest {
  private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]*)</groupId>");

  private static Path repoRoot() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static String read(String relative) throws IOException {
    return Files.readString(repoRoot().resolve(relative), UTF_8);
  }

  @Test
  void theModuleHasNoThirdPartyDependency() throws IOException {
    String pom = read("regpack-evidence/pom.xml");
    List<String> groups = new ArrayList<>();
    Matcher m = GROUP_ID.matcher(pom);
    while (m.find()) {
      groups.add(m.group(1).trim());
    }
    assertThat(groups).isNotEmpty().allMatch("dev.regpack"::equals);
    // test libraries are inherited from the parent; the module declares none of its own
    assertThat(pom).doesNotContain("junit").doesNotContain("assertj");
  }

  /** One dependency of a pom: its artifactId and its scope (compile when none is given). */
  private record Dependency(String artifactId, String scope) {}

  private static final Pattern DEPENDENCY =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
  private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]*)</artifactId>");
  private static final Pattern SCOPE = Pattern.compile("<scope>([^<]*)</scope>");

  /** The test-scope dev.regpack artifacts the module may use, and no others. */
  private static final Set<String> TEST_ALLOW_LIST =
      Set.of("regpack-testkit", "regpack-inmemory", "regpack-tools");

  private static List<Dependency> dependenciesOf(String pom) {
    List<Dependency> found = new ArrayList<>();
    Matcher block = DEPENDENCY.matcher(pom);
    while (block.find()) {
      Matcher artifact = ARTIFACT_ID.matcher(block.group(1));
      Matcher scope = SCOPE.matcher(block.group(1));
      assertThat(artifact.find()).as("a dependency without an artifactId").isTrue();
      found.add(
          new Dependency(
              artifact.group(1).trim(), scope.find() ? scope.group(1).trim() : "compile"));
    }
    return found;
  }

  /** What is wrong with the dependencies of the pom, in words; empty when the boundary holds. */
  private static List<String> boundaryViolations(String pom) {
    List<Dependency> dependencies = dependenciesOf(pom);
    List<String> violations = new ArrayList<>();
    Set<String> compile = new TreeSet<>();
    for (Dependency dependency : dependencies) {
      if (dependency.scope().equals("compile")) {
        compile.add(dependency.artifactId());
      } else if (dependency.scope().equals("test")) {
        if (dependency.artifactId().startsWith("regpack-")
            && !TEST_ALLOW_LIST.contains(dependency.artifactId())) {
          violations.add("test dependency not on the allow-list: " + dependency.artifactId());
        }
      } else {
        violations.add("scope " + dependency.scope() + ": " + dependency.artifactId());
      }
    }
    if (!compile.equals(Set.of("regpack-model", "regpack-ports"))) {
      violations.add("compile dependencies are " + compile);
    }
    return violations;
  }

  @Test
  void theModuleDependsOnTheModelAndThePortsOnly() throws IOException {
    String pom = read("regpack-evidence/pom.xml");

    assertThat(boundaryViolations(pom)).isEmpty();
    // the parse sees what the pom declares (it is not an empty list that trivially holds)
    assertThat(dependenciesOf(pom))
        .contains(
            new Dependency("regpack-model", "compile"),
            new Dependency("regpack-ports", "compile"),
            new Dependency("regpack-testkit", "test"));
  }

  @Test
  void theBoundaryCheckCatchesTheChangesItIsMeantToCatch() throws IOException {
    String pom = read("regpack-evidence/pom.xml");
    String testkit = "<artifactId>regpack-testkit</artifactId><scope>test</scope>";
    String inmemory = "<artifactId>regpack-inmemory</artifactId><scope>test</scope>";
    assertThat(pom).contains(testkit).contains(inmemory);
    String bench =
        "<dependency><groupId>dev.regpack</groupId><artifactId>regpack-bench</artifactId>"
            + "</dependency></dependencies>";

    // the test libraries lose their scope, and the bench CLI is added as a compile dependency
    String widened =
        pom.replace(testkit, "<artifactId>regpack-testkit</artifactId>")
            .replace(inmemory, "<artifactId>regpack-inmemory</artifactId>")
            .replace("</dependencies>", bench);
    assertThat(boundaryViolations(widened)).hasSize(1);
    assertThat(boundaryViolations(widened).get(0))
        .contains("regpack-bench")
        .contains("regpack-testkit")
        .contains("regpack-inmemory");

    // a module outside the allow-list in test scope
    String testBench =
        pom.replace(
            "</dependencies>",
            "<dependency><groupId>dev.regpack</groupId><artifactId>regpack-bench</artifactId>"
                + "<scope>test</scope></dependency></dependencies>");
    assertThat(boundaryViolations(testBench))
        .containsExactly("test dependency not on the allow-list: regpack-bench");

    // the application side as a compile dependency, and a dependency that drops out
    assertThat(boundaryViolations(pom.replace("</dependencies>", bench.replace("bench", "app"))))
        .hasSize(1);
    String ports =
        "<dependency><groupId>dev.regpack</groupId><artifactId>regpack-ports</artifactId>"
            + "</dependency>";
    assertThat(pom).contains(ports);
    assertThat(boundaryViolations(pom.replace(ports, "")))
        .containsExactly("compile dependencies are [regpack-model]");
  }

  @Test
  void theAppDoesNotDependOnIt() throws IOException {
    assertThat(read("regpack-app/pom.xml")).doesNotContain("regpack-evidence");
  }

  @Test
  void noDriverIsNamed() throws IOException {
    String pom = read("regpack-evidence/pom.xml");
    assertThat(pom)
        .doesNotContain("postgres")
        .doesNotContain("jdbc")
        .doesNotContain("checker-qual")
        .doesNotContain("org.checkerframework");
  }
}
