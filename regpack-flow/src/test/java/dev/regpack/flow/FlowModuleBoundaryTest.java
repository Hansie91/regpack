package dev.regpack.flow;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5: the module {@code regpack-flow} has no third-party dependency, depends on the model, the
 * ports, the pack and the engine only, and names no route or authority as a string literal (the
 * destination is data in the profile, never a branch in the flow).
 */
class FlowModuleBoundaryTest {
  private static final String MODULE = "regpack-flow";
  private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]*)</groupId>");

  private static Path repoRoot() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static String read(String relative) throws IOException {
    return Files.readString(repoRoot().resolve(relative), UTF_8);
  }

  /** One dependency of a pom: its group, artifactId and scope (compile when none is given). */
  private record Dependency(String groupId, String artifactId, String scope) {}

  private static final Pattern DEPENDENCY =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
  private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]*)</artifactId>");
  private static final Pattern SCOPE = Pattern.compile("<scope>([^<]*)</scope>");

  /** The test-scope dev.regpack artifacts the module may use, and no others. */
  private static final Set<String> TEST_ALLOW_LIST =
      Set.of("regpack-testkit", "regpack-inmemory", "regpack-tools");

  private static final Set<String> COMPILE_SET =
      Set.of("regpack-model", "regpack-ports", "regpack-pack", "regpack-engine");

  private static List<Dependency> dependenciesOf(String pom) {
    List<Dependency> found = new ArrayList<>();
    Matcher block = DEPENDENCY.matcher(pom);
    while (block.find()) {
      Matcher group = GROUP_ID.matcher(block.group(1));
      Matcher artifact = ARTIFACT_ID.matcher(block.group(1));
      Matcher scope = SCOPE.matcher(block.group(1));
      assertThat(group.find()).as("a dependency without a groupId").isTrue();
      assertThat(artifact.find()).as("a dependency without an artifactId").isTrue();
      found.add(
          new Dependency(
              group.group(1).trim(),
              artifact.group(1).trim(),
              scope.find() ? scope.group(1).trim() : "compile"));
    }
    return found;
  }

  /** What is wrong with the dependencies of the pom, in words; empty when the boundary holds. */
  private static List<String> boundaryViolations(String pom) {
    List<String> violations = new ArrayList<>();
    Set<String> compile = new TreeSet<>();
    for (Dependency dependency : dependenciesOf(pom)) {
      if (!dependency.groupId().equals("dev.regpack")) {
        violations.add("not a dev.regpack group: " + dependency.groupId());
      }
      if (dependency.scope().equals("compile")) {
        compile.add(dependency.artifactId());
      } else if (dependency.scope().equals("test")) {
        if (!TEST_ALLOW_LIST.contains(dependency.artifactId())) {
          violations.add("test dependency not on the allow-list: " + dependency.artifactId());
        }
      } else {
        violations.add("scope " + dependency.scope() + ": " + dependency.artifactId());
      }
    }
    if (!compile.equals(COMPILE_SET)) {
      violations.add("compile dependencies are " + compile);
    }
    return violations;
  }

  @Test
  void compileDependenciesAreModelPortsPackAndEngineOnly() throws IOException {
    String pom = read(MODULE + "/pom.xml");

    assertThat(boundaryViolations(pom)).isEmpty();
    // the parse sees what the pom declares (it is not an empty list that trivially holds)
    assertThat(dependenciesOf(pom))
        .contains(
            new Dependency("dev.regpack", "regpack-model", "compile"),
            new Dependency("dev.regpack", "regpack-ports", "compile"),
            new Dependency("dev.regpack", "regpack-pack", "compile"),
            new Dependency("dev.regpack", "regpack-engine", "compile"),
            new Dependency("dev.regpack", "regpack-testkit", "test"),
            new Dependency("dev.regpack", "regpack-inmemory", "test"),
            new Dependency("dev.regpack", "regpack-tools", "test"));

    // the check can fail: a third-party coordinate, a test module off the allow-list, a widened
    // scope, a compile dependency that drops out and one that is added
    String bench =
        "<dependency><groupId>dev.regpack</groupId><artifactId>regpack-bench</artifactId>";
    assertThat(
            boundaryViolations(
                pom.replace("</dependencies>", bench + "</dependency></dependencies>")))
        .containsExactly(
            "compile dependencies are [regpack-bench, regpack-engine, regpack-model,"
                + " regpack-pack, regpack-ports]");
    assertThat(
            boundaryViolations(
                pom.replace(
                    "</dependencies>", bench + "<scope>test</scope></dependency></dependencies>")))
        .containsExactly("test dependency not on the allow-list: regpack-bench");
    assertThat(
            boundaryViolations(
                pom.replace(
                    "</dependencies>",
                    "<dependency><groupId>org.example</groupId><artifactId>lib</artifactId>"
                        + "<scope>test</scope></dependency></dependencies>")))
        .contains("not a dev.regpack group: org.example");
    assertThat(
            boundaryViolations(
                pom.replace(
                    "<artifactId>regpack-testkit</artifactId><scope>test</scope>",
                    "<artifactId>regpack-testkit</artifactId><scope>provided</scope>")))
        .containsExactly("scope provided: regpack-testkit");
    String engine =
        "<dependency><groupId>dev.regpack</groupId><artifactId>regpack-engine</artifactId>"
            + "</dependency>";
    assertThat(pom).contains(engine);
    assertThat(boundaryViolations(pom.replace(engine, "")))
        .containsExactly("compile dependencies are [regpack-model, regpack-pack, regpack-ports]");
  }

  @Test
  void theAppEvidenceAndPostgresModulesAreNotDependencies() throws IOException {
    Set<String> declared = new TreeSet<>();
    for (Dependency dependency : dependenciesOf(read(MODULE + "/pom.xml"))) {
      declared.add(dependency.artifactId());
    }
    assertThat(declared).isNotEmpty();
    // the flow reaches the evidence tier and the ledger through the ports; only the composition
    // root names the sealed store, the object-lock client and the database adapter
    assertThat(declared)
        .doesNotContain(
            "regpack-app",
            "regpack-evidence",
            "regpack-evidence-s3",
            "regpack-postgres",
            "regpack-bench",
            "regpack-sandbox");
    assertThat(read("regpack-app/pom.xml")).doesNotContain(MODULE);
    assertThat(read("regpack-engine/pom.xml")).doesNotContain(MODULE);
    assertThat(read("regpack-pack/pom.xml")).doesNotContain(MODULE);
  }

  @Test
  void theRootPomListsTheModuleAndManagesItsVersion() throws IOException {
    String root = read("pom.xml");
    assertThat(root).contains("<module>" + MODULE + "</module>");
    assertThat(root)
        .contains(
            "<dependency><groupId>dev.regpack</groupId><artifactId>"
                + MODULE
                + "</artifactId><version>${project.version}</version></dependency>");
  }

  /** A string literal that is exactly a route or authority word. */
  private static final Pattern ROUTE_LITERAL = Pattern.compile("\"(ARM|NCA|SELF)\"");

  private static List<Path> mainSources(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root.resolve(MODULE + "/src/main/java"))) {
      return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static List<String> routeLiterals(Path root, List<Path> files) throws IOException {
    List<String> found = new ArrayList<>();
    for (Path file : files) {
      String[] lines = Files.readString(file, UTF_8).split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (ROUTE_LITERAL.matcher(lines[i]).find()) {
          found.add(root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1));
        }
      }
    }
    return found;
  }

  @Test
  void noFlowMainSourceBranchesOnARouteOrAuthorityLiteral(@TempDir Path planted)
      throws IOException {
    List<Path> files = mainSources(repoRoot());
    assertThat(files).as("the scan reads the flow main sources").isNotEmpty();
    assertThat(files.stream().map(p -> p.getFileName().toString())).contains("package-info.java");
    assertThat(routeLiterals(repoRoot(), files)).as("route literals in flow sources").isEmpty();

    // the scan can fail: a planted source with each literal is found, a clean one is not
    Path dir = planted.resolve(MODULE + "/src/main/java/dev/regpack/flow/send");
    Files.createDirectories(dir);
    Path source = dir.resolve("Router.java");
    Files.writeString(
        source,
        "class Router {\n"
            + "  boolean viaArm(String route) { return route.equals(\"ARM\"); }\n"
            + "  boolean viaNca(String authority) { return \"NCA\".equals(authority); }\n"
            + "  boolean viaSelf(String route) { return route.equals(\"SELF\"); }\n"
            + "  String text = \"an ARM or an NCA\";\n"
            + "}\n",
        UTF_8);
    assertThat(routeLiterals(planted, mainSources(planted)))
        .containsExactly(
            MODULE + "/src/main/java/dev/regpack/flow/send/Router.java:2",
            MODULE + "/src/main/java/dev/regpack/flow/send/Router.java:3",
            MODULE + "/src/main/java/dev/regpack/flow/send/Router.java:4");
    Files.writeString(source, "class Router {\n  // the route is profile data\n}\n", UTF_8);
    assertThat(routeLiterals(planted, mainSources(planted))).isEmpty();
  }
}
