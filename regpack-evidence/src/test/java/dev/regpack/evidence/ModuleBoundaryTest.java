package dev.regpack.evidence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

  @Test
  void theModuleDependsOnTheModelAndThePortsOnly() throws IOException {
    String pom = read("regpack-evidence/pom.xml");
    assertThat(pom).contains("<artifactId>regpack-model</artifactId>");
    assertThat(pom).contains("<artifactId>regpack-ports</artifactId>");
    assertThat(pom).doesNotContain("<artifactId>regpack-app</artifactId>");
    assertThat(pom).doesNotContain("<artifactId>regpack-engine</artifactId>");
    assertThat(pom).doesNotContain("<artifactId>regpack-pack</artifactId>");
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
