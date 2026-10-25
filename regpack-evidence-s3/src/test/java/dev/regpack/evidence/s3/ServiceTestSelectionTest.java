package dev.regpack.evidence.s3;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A local catch of a selection error that CI would otherwise be the first to see (T10): the job
 * {@code evidence-worm} selects the real-service classes of this module with {@code
 * -Dit.test='Worm*IT'}; a pattern that matched nothing, matched a fake-backed {@code *Test} class
 * or missed a class would only show at the checkpoint. The integration tests themselves skip here
 * (no service), so this unit test is what shows locally that the selection is the five classes. The
 * method name still says four: it was four until {@code WormVersionsIT} was added, and the plan
 * cites the name.
 */
class ServiceTestSelectionTest {
  /** The pattern of the job step (the {@code -Dit.test} value), matched against the class name. */
  private static final String WORM_PATTERN = "Worm*IT";

  /** The pattern of the PostgreSQL step of the job (the {@code -Dit.test} value). */
  private static final String PG_WORM_PATTERN = "PgWorm*IT";

  private static final Pattern EXCLUDED_GROUPS =
      Pattern.compile("<excludedGroups>([^<]*)</excludedGroups>");

  private static final List<String> FIVE =
      List.of(
          "WormEvidenceStoreContractIT",
          "WormSealScenariosIT",
          "WormServiceBehaviourIT",
          "WormVerifyIT",
          "WormVersionsIT");

  private static Path testSources() {
    return Path.of(System.getProperty("regpack.repo.root"))
        .resolve("regpack-evidence-s3/src/test/java");
  }

  /** The simple names of the test source files whose name ends in {@code suffix}. */
  private static List<String> classesEndingIn(Path root, String suffix) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(suffix + ".java"))
          .map(name -> name.substring(0, name.length() - ".java".length()))
          .sorted()
          .toList();
    }
  }

  private static List<String> selectedBy(String pattern, List<String> names) {
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    List<String> selected = new ArrayList<>();
    for (String name : names) {
      if (matcher.matches(Path.of(name))) {
        selected.add(name);
      }
    }
    return selected;
  }

  private static List<String> allClasses(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".java"))
          .map(name -> name.substring(0, name.length() - ".java".length()))
          .sorted()
          .toList();
    }
  }

  @Test
  void theWormPatternSelectsTheFourServiceClassesAndNoUnitTest() throws IOException {
    Path root = testSources();
    List<String> all = allClasses(root);

    // the pattern selects exactly the five service classes, among every class of the module
    assertThat(selectedBy(WORM_PATTERN, all)).containsExactlyElementsOf(FIVE);
    // the Worm*IT files of the module are exactly those five
    assertThat(classesEndingIn(root, "IT").stream().filter(n -> n.startsWith("Worm")).toList())
        .containsExactlyElementsOf(FIVE);
    // each is tagged worm, so that the group filter of the job and the exclusion elsewhere agree
    for (String name : FIVE) {
      Path file;
      try (Stream<Path> files = Files.walk(root)) {
        file =
            files
                .filter(p -> p.getFileName().toString().equals(name + ".java"))
                .findFirst()
                .orElseThrow();
      }
      assertThat(Files.readString(file, UTF_8))
          .as("%s carries the tag worm", name)
          .contains("@Tag(\"worm\")");
    }
    // no unit test (a class named ...Test, fake-backed or not) matches the pattern
    assertThat(selectedBy(WORM_PATTERN, classesEndingIn(root, "Test"))).isEmpty();
    // the probe is selected by its own job step and never by this pattern
    assertThat(selectedBy(WORM_PATTERN, List.of("S3ServiceProbeIT"))).isEmpty();
  }

  /**
   * T11: the second step of the job {@code evidence-worm} selects the PostgreSQL-plus-S3 classes of
   * {@code regpack-postgres} with {@code -Dit.test='PgWorm*IT'}; the job {@code ledger-pg} runs the
   * whole module and must not run them (they need the S3 service), which is the {@code worm} group
   * in the {@code excludedGroups} property of that module.
   */
  @Test
  void thePgWormPatternSelectsTheTwoPostgresClassesAndTheLedgerJobExcludesThem()
      throws IOException {
    Path repo = Path.of(System.getProperty("regpack.repo.root"));
    Path root = repo.resolve("regpack-postgres/src/test/java");
    List<String> all = allClasses(root);
    List<String> two = List.of("PgWormSealScenarioIT", "PgWormVerifyIT");

    assertThat(selectedBy(PG_WORM_PATTERN, all)).containsExactlyElementsOf(two);
    assertThat(classesEndingIn(root, "IT").stream().filter(n -> n.startsWith("PgWorm")).toList())
        .containsExactlyElementsOf(two);
    assertThat(selectedBy(PG_WORM_PATTERN, classesEndingIn(root, "Test"))).isEmpty();
    for (String name : two) {
      Path file;
      try (Stream<Path> files = Files.walk(root)) {
        file =
            files
                .filter(p -> p.getFileName().toString().equals(name + ".java"))
                .findFirst()
                .orElseThrow();
      }
      assertThat(Files.readString(file, UTF_8))
          .as("%s carries the tag worm", name)
          .contains("@Tag(\"worm\")");
    }
    // the module's own run (job ledger-pg, the build jobs) leaves the group out
    String pom = Files.readString(repo.resolve("regpack-postgres/pom.xml"), UTF_8);
    Matcher excluded = EXCLUDED_GROUPS.matcher(pom);
    assertThat(excluded.find()).as("excludedGroups of regpack-postgres").isTrue();
    assertThat(List.of(excluded.group(1).split(",")))
        .contains("worm", "mutant", "capacity", "durability");
    // the selections of the two service classes do not overlap
    assertThat(selectedBy(WORM_PATTERN, two)).isEmpty();
    assertThat(selectedBy(PG_WORM_PATTERN, FIVE)).isEmpty();
  }

  @Test
  void theSelectionHasTeeth() {
    // a pattern that is too narrow, too wide or misspelt is seen
    List<String> names = new ArrayList<>(FIVE);
    names.add("WormEvidenceStoreContractTest");
    names.add("S3ServiceProbeIT");
    assertThat(selectedBy("Worm*IT", names)).containsExactlyElementsOf(FIVE);
    assertThat(selectedBy("Worm*", names)).contains("WormEvidenceStoreContractTest");
    assertThat(selectedBy("*IT", names)).contains("S3ServiceProbeIT");
    assertThat(selectedBy("Wor*IT", List.of("NoWormIT"))).isEmpty();
  }
}
