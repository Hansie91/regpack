package dev.regpack.evidence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review focus KF11 (P3c1 T9), as {@code NoBypassTest} of {@code regpack-evidence-s3} for WF10 and
 * D-W4: nothing in the main code of this module sends the governance bypass header or issues a
 * delete request. The retention engine shortens nothing and removes nothing; the only way to a
 * shorter retention or a deleted object would be one of these two, and neither is in the module.
 * The patterns are built from pieces so that this file does not match itself (and tests are not
 * scanned anyway).
 */
class NoBypassInEvidenceTest {
  private static final String BYPASS_HEADER = "x-amz-bypass-" + "governance-retention";
  private static final String DELETE_LITERAL = "\"DEL" + "ETE\"";

  /** Java sources below {@code dir} whose text contains {@code needle}, case-insensitively. */
  static List<String> offenders(Path dir, String needle) throws IOException {
    String lower = needle.toLowerCase(ROOT);
    List<String> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        if (Files.readString(file, UTF_8).toLowerCase(ROOT).contains(lower)) {
          found.add(dir.relativize(file).toString().replace('\\', '/'));
        }
      }
    }
    return found;
  }

  private static Path mainSources() {
    Path sources =
        Path.of(System.getProperty("regpack.repo.root")).resolve("regpack-evidence/src/main/java");
    // the scan is not vacuous: the stores, the sealer and the engine are there
    assertThat(sources.resolve("dev/regpack/evidence/sealed/SealedEvidenceStore.java")).exists();
    assertThat(sources.resolve("dev/regpack/evidence/seal/DurableSeal.java")).exists();
    assertThat(sources.resolve("dev/regpack/evidence/retention/RetentionEngine.java")).exists();
    return sources;
  }

  @Test
  void noMainSourceNamesTheBypassHeader() throws IOException {
    assertThat(offenders(mainSources(), BYPASS_HEADER)).isEmpty();
  }

  @Test
  void theBypassScanHasTeeth(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve("Bad.java"), "class Bad { String h = \"" + BYPASS_HEADER + "\"; }\n", UTF_8);
    Files.writeString(
        dir.resolve("Bad2.java"),
        "class Bad2 { String h = \"X-Amz-Bypass-" + "Governance-Retention\"; }\n",
        UTF_8);
    Files.writeString(
        dir.resolve("Good.java"), "class Good { String h = \"x-amz-date\"; }\n", UTF_8);
    assertThat(offenders(dir, BYPASS_HEADER)).containsExactly("Bad.java", "Bad2.java");
  }

  @Test
  void noMainSourceIssuesADeleteRequest() throws IOException {
    assertThat(offenders(mainSources(), DELETE_LITERAL)).isEmpty();
  }

  @Test
  void theDeleteScanHasTeeth(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve("Bad.java"), "class Bad { String m = " + DELETE_LITERAL + "; }\n", UTF_8);
    Files.writeString(dir.resolve("Good.java"), "class Good { String m = \"GET\"; }\n", UTF_8);
    assertThat(offenders(dir, DELETE_LITERAL)).containsExactly("Bad.java");
  }
}
