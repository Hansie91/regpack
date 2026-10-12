package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolationCheckTest {
  @TempDir Path root;

  private void write(String relative, String content) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static String rootPom(String modules) {
    return "<project><modules>"
        + modules
        + "</modules><dependencyManagement><dependencies><dependency>"
        + "<groupId>dev.regpack</groupId><artifactId>regpack-bench</artifactId>"
        + "<version>1</version></dependency></dependencies></dependencyManagement></project>";
  }

  private static String dependency(String artifact, String scope) {
    return "<project><artifactId>x</artifactId><dependencies><dependency>"
        + "<groupId>dev.regpack</groupId><artifactId>"
        + artifact
        + "</artifactId>"
        + (scope.isEmpty() ? "" : "<scope>" + scope + "</scope>")
        + "</dependency></dependencies></project>";
  }

  @Test
  void flagsASpikeModuleInTheRootPom() throws IOException {
    write("pom.xml", rootPom("<module>regpack-model</module><module>spikes</module>"));
    assertThat(IsolationCheck.check(root)).containsExactly("ISO_ROOT_MODULE");
    write("pom.xml", rootPom("<module>regpack-model</module><module> spikes/spike-r </module>"));
    assertThat(IsolationCheck.check(root)).containsExactly("ISO_ROOT_MODULE");
  }

  @Test
  void flagsSpellingVariantsOfASpikeModulePath() throws IOException {
    for (String path :
        List.of(
            "./spikes",
            "../spikes/spike-r",
            "spike-r",
            "./spike-r/",
            "regpack-model/../spikes",
            "spikes/../spikes",
            "a\\spikes")) {
      write("pom.xml", rootPom("<module>regpack-model</module><module>" + path + "</module>"));
      assertThat(IsolationCheck.check(root)).as(path).contains("ISO_ROOT_MODULE");
    }
  }

  @Test
  void doesNotFlagReleaseModulePathsThatMerelyContainTheWordSpike() throws IOException {
    for (String path : List.of("regpack-model", "./regpack-model", "libs/spikesaw", "my-spikes")) {
      write("pom.xml", rootPom("<module>" + path + "</module>"));
      assertThat(IsolationCheck.check(root)).as(path).doesNotContain("ISO_ROOT_MODULE");
    }
  }

  @Test
  void scansEveryListedModuleWhateverItsDirectoryIsCalled() throws IOException {
    write("pom.xml", rootPom("<module>./svc/engine</module><module>tools</module>"));
    write("svc/engine/pom.xml", dependency("regpack-bench", "compile"));
    write("tools/pom.xml", dependency("spike-r", "test"));
    assertThat(IsolationCheck.check(root))
        .containsExactly(
            "ISO_RELEASE_DEPENDS svc/engine/pom.xml", "ISO_RELEASE_DEPENDS tools/pom.xml");
  }

  @Test
  void aListedModuleDirectoryWithoutAPomIsIgnored() throws IOException {
    write("pom.xml", rootPom("<module>missing</module><module>regpack-model/pom.xml</module>"));
    write("regpack-model/pom.xml", dependency("regpack-ports", ""));
    assertThat(IsolationCheck.check(root)).isEmpty();
  }

  @Test
  void ignoresACommentedOutSpikeModuleAndTheManagementEntry() throws IOException {
    write("pom.xml", rootPom("<module>regpack-model</module><!-- <module>spikes</module> -->"));
    write("regpack-model/pom.xml", dependency("regpack-ports", ""));
    assertThat(IsolationCheck.check(root)).isEmpty();
  }

  @Test
  void flagsASpikeArtifactInAReleasePom() throws IOException {
    write("pom.xml", rootPom("<module>regpack-engine</module>"));
    write("regpack-engine/pom.xml", dependency("spike-r", "test"));
    assertThat(IsolationCheck.check(root))
        .containsExactly("ISO_RELEASE_DEPENDS regpack-engine/pom.xml");
  }

  @Test
  void flagsBenchAsADependencyOfTheApp() throws IOException {
    write("pom.xml", rootPom("<module>regpack-app</module>"));
    write("regpack-app/pom.xml", dependency("regpack-bench", ""));
    write("regpack-engine/pom.xml", dependency("regpack-bench", "runtime"));
    write("regpack-model/pom.xml", dependency("regpack-bench", "compile"));
    write("regpack-testkit/pom.xml", dependency("regpack-bench", "test"));
    write("regpack-bench/pom.xml", dependency("regpack-bench", ""));
    assertThat(IsolationCheck.check(root))
        .containsExactly(
            "ISO_RELEASE_DEPENDS regpack-app/pom.xml",
            "ISO_RELEASE_DEPENDS regpack-engine/pom.xml",
            "ISO_RELEASE_DEPENDS regpack-model/pom.xml");
  }

  @Test
  void flagsBenchAsADependencyOfTheRootPom() throws IOException {
    write(
        "pom.xml",
        "<project><modules><module>regpack-bench</module></modules><dependencies><dependency>"
            + "<artifactId>regpack-bench</artifactId></dependency></dependencies></project>");
    assertThat(IsolationCheck.check(root)).containsExactly("ISO_RELEASE_DEPENDS pom.xml");
  }

  @Test
  void passesOnTheRepositoryAsCommitted() {
    Path repo = Path.of(System.getProperty("regpack.repo.root"));
    assertThat(IsolationCheck.check(repo)).isEmpty();
  }
}
