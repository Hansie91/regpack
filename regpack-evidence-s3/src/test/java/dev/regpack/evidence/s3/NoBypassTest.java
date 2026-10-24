package dev.regpack.evidence.s3;

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
 * Review focus WF10 and D-W4: nothing in the main code of the module sends the governance bypass
 * header or issues a delete request. The patterns are built from pieces so that this file does not
 * match itself (and tests are not scanned anyway).
 */
class NoBypassTest {
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
        Path.of(System.getProperty("regpack.repo.root"))
            .resolve("regpack-evidence-s3/src/main/java");
    // the scan is not vacuous: the signer is there
    assertThat(sources.resolve("dev/regpack/evidence/s3/client/SigV4.java")).exists();
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
