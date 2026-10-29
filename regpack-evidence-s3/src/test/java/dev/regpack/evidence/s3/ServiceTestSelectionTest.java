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
import org.junit.jupiter.api.io.TempDir;

/**
 * A local catch of a selection error that CI would otherwise be the first to see (T10): the job
 * {@code evidence-worm} selects the real-service classes of this module with {@code
 * -Dit.test='Worm*IT'}; a pattern that matched nothing, matched a fake-backed {@code *Test} class
 * or missed a class would only show at the checkpoint. The integration tests themselves skip here
 * (no service), so this unit test is what shows locally that the selection is the eight classes
 * (the five of P3b2 and, since P3c1 T13, the three of the encrypted tier). The method name still
 * says four: it was four until {@code WormVersionsIT} was added, and the plan cites the name.
 *
 * <p>The tag is checked as a line of the source: {@code @Tag("worm")} at the start of a line, on
 * every one of the classes, because the job's group filter and the exclusion in the other jobs read
 * the tag of the class itself (an inherited tag works for the filter but is not what a reader of
 * the file sees), and because a comment that quotes the text must not pass for the annotation.
 */
class ServiceTestSelectionTest {
  /** The pattern of the job step (the {@code -Dit.test} value), matched against the class name. */
  private static final String WORM_PATTERN = "Worm*IT";

  /** The pattern of the PostgreSQL step of the job (the {@code -Dit.test} value). */
  private static final String PG_WORM_PATTERN = "PgWorm*IT";

  private static final Pattern EXCLUDED_GROUPS =
      Pattern.compile("<excludedGroups>([^<]*)</excludedGroups>");

  /**
   * The tag line a service class carries: the annotation alone on a line, from its first column.
   */
  private static final Pattern TAG_LINE =
      Pattern.compile("^@Tag\\(\"worm\"\\)[ \\t]*$", Pattern.MULTILINE);

  /** The service classes of the module, in the order of a sorted listing. */
  private static final List<String> EIGHT =
      List.of(
          "WormEncryptedContractIT",
          "WormEncryptedSealScenariosIT",
          "WormEncryptedVerifyIT",
          "WormEvidenceStoreContractIT",
          "WormSealScenariosIT",
          "WormServiceBehaviourIT",
          "WormVerifyIT",
          "WormVersionsIT");

  /**
   * A comment of either kind, the leftmost first (so that a {@code //} inside a block comment and a
   * block opener inside a line comment are read as the text of that comment).
   */
  private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

  /**
   * True when the code of the source (its comments taken out) has the annotation
   * {@code @Tag("worm")} as a line of its own. Each comment is replaced by an empty block comment
   * and not by nothing, so that an annotation followed by a comment is still not a line of its own.
   */
  private static boolean hasTheTagLine(String source) {
    String code = COMMENT.matcher(source).replaceAll(Matcher.quoteReplacement("/**/"));
    return TAG_LINE.matcher(code).find();
  }

  private static String sourceOf(Path root, String className) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      Path file =
          files
              .filter(p -> p.getFileName().toString().equals(className + ".java"))
              .findFirst()
              .orElseThrow();
      return Files.readString(file, UTF_8);
    }
  }

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

    // the pattern selects exactly the eight service classes, among every class of the module
    assertThat(selectedBy(WORM_PATTERN, all)).containsExactlyElementsOf(EIGHT);
    // the Worm*IT files of the module are exactly those eight
    assertThat(classesEndingIn(root, "IT").stream().filter(n -> n.startsWith("Worm")).toList())
        .containsExactlyElementsOf(EIGHT);
    // each carries the tag worm as a line of its own, so that the group filter of the job and the
    // exclusion elsewhere agree
    for (String name : EIGHT) {
      assertThat(hasTheTagLine(sourceOf(root, name)))
          .as("%s carries the line @Tag(\"worm\") at the start of a line", name)
          .isTrue();
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
      assertThat(hasTheTagLine(sourceOf(root, name)))
          .as("%s carries the line @Tag(\"worm\") at the start of a line", name)
          .isTrue();
    }
    // PgEncryptedSealScenarioIT is NOT tagged worm, on purpose: it needs PostgreSQL and no S3
    // service, the job ledger-pg excludes the group worm (below), so a tag would silently stop it
    // running there, and the job evidence-worm selects classes by the name pattern, never this one
    assertThat(hasTheTagLine(sourceOf(root, "PgEncryptedSealScenarioIT")))
        .as("PgEncryptedSealScenarioIT carries no line @Tag(\"worm\")")
        .isFalse();
    // the job ledger-pg excludes four groups and no gate compares its tally, so a tag of any of the
    // four on one of these two classes would drop it from the job without a red mark: the first is
    // the encrypted tier over the ledger, the second the only check that the two ledgers agree
    for (String name : List.of("PgEncryptedSealScenarioIT", "PgInMemoryDifferentialIT")) {
      String code =
          COMMENT.matcher(sourceOf(root, name)).replaceAll(Matcher.quoteReplacement("/**/"));
      assertThat(Pattern.compile("(?m)^\\s*@Tag\\(").matcher(code).find())
          .as("%s carries no @Tag, because ledger-pg excludes four groups", name)
          .isFalse();
    }
    // the module's own run (job ledger-pg, the build jobs) leaves the group out
    String pom = Files.readString(repo.resolve("regpack-postgres/pom.xml"), UTF_8);
    Matcher excluded = EXCLUDED_GROUPS.matcher(pom);
    assertThat(excluded.find()).as("excludedGroups of regpack-postgres").isTrue();
    assertThat(List.of(excluded.group(1).split(",")))
        .contains("worm", "mutant", "capacity", "durability");
    // the selections of the two service classes do not overlap
    assertThat(selectedBy(WORM_PATTERN, two)).isEmpty();
    assertThat(selectedBy(PG_WORM_PATTERN, EIGHT)).isEmpty();
  }

  @Test
  void theSelectionHasTeeth() {
    // a pattern that is too narrow, too wide or misspelt is seen
    List<String> names = new ArrayList<>(EIGHT);
    names.add("WormEvidenceStoreContractTest");
    names.add("WormEncryptedSealScenariosTest");
    names.add("EncryptedWormContractTest");
    names.add("S3ServiceProbeIT");
    assertThat(selectedBy("Worm*IT", names)).containsExactlyElementsOf(EIGHT);
    assertThat(selectedBy("Worm*", names))
        .contains("WormEvidenceStoreContractTest", "WormEncryptedSealScenariosTest");
    assertThat(selectedBy("*IT", names)).contains("S3ServiceProbeIT");
    assertThat(selectedBy("Wor*IT", List.of("NoWormIT"))).isEmpty();
  }

  /**
   * The tag check has teeth, on temporary files: the annotation as a line passes (also with
   * trailing blanks and a Windows line end), and a comment that holds the text, an inherited tag, a
   * tag with another name, an indented or a trailing annotation do not.
   */
  @Test
  void theTagLineCheckHasTeethOnTemporaryFiles(@TempDir Path dir) throws IOException {
    String declaration = "class WormSomethingIT extends Base {}\n";
    Path real = dir.resolve("Real.java");
    Files.writeString(real, "import x;\n\n@Tag(\"worm\")\n" + declaration, UTF_8);
    Path windows = dir.resolve("Windows.java");
    Files.writeString(windows, "@Tag(\"worm\")  \r\n" + declaration, UTF_8);
    assertThat(hasTheTagLine(Files.readString(real, UTF_8))).isTrue();
    assertThat(hasTheTagLine(Files.readString(windows, UTF_8))).isTrue();
    // a comment before the annotation, and a block comment elsewhere, do not hide it
    Path commented = dir.resolve("Commented.java");
    Files.writeString(
        commented,
        "/*\n * A comment.\n */\n// a line comment\n@Tag(\"worm\")\n" + declaration,
        UTF_8);
    assertThat(hasTheTagLine(Files.readString(commented, UTF_8))).isTrue();

    List<String> imposters =
        List.of(
            "// @Tag(\"worm\")\n" + declaration,
            "/**\n * Runs under @Tag(\"worm\") through its parent.\n */\n" + declaration,
            " * @Tag(\"worm\")\n" + declaration,
            "  @Tag(\"worm\")\n" + declaration,
            "@Tag(\"worm\") " + declaration,
            "@Tag(\"probe\")\n" + declaration,
            "@Tag(\"worms\")\n" + declaration,
            // a block comment without leading asterisks holds the annotation as a line of its own
            "/*\n@Tag(\"worm\")\n*/\n" + declaration,
            // the same after a line that closes another comment, and a trailing line comment
            "/* old */\n/*\n@Tag(\"worm\")\n   */\n" + declaration,
            "@Tag(\"worm\") // runs on the service\n" + declaration,
            declaration);
    for (int i = 0; i < imposters.size(); i++) {
      Path file = dir.resolve("Imposter" + i + ".java");
      Files.writeString(file, imposters.get(i), UTF_8);
      assertThat(hasTheTagLine(Files.readString(file, UTF_8)))
          .as("imposter %d is not the annotation: %s", i, imposters.get(i))
          .isFalse();
    }
  }
}
