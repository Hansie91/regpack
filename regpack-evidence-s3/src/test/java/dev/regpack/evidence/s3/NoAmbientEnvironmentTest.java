package dev.regpack.evidence.s3;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review focus EF20 (the CP-4 lesson of P3a): no test of this module reads the process environment
 * or the working directory, except the named helper of the allow-list ({@code S3TestService.java},
 * the one class that reads the {@code REGPACK_S3_*} variables). The two patterns are built from two
 * pieces so that this file does not match itself.
 */
class NoAmbientEnvironmentTest {
  private static final String ENVIRONMENT_READ = "get" + "env(";
  private static final String WORKING_DIRECTORY = "\"user" + ".dir\"";

  /** Relative names of the test sources that may read the environment: one named helper. */
  private static final Set<String> ALLOW_LIST =
      Set.of("dev/regpack/evidence/s3/S3TestService.java");

  /** Test sources below {@code dir} that contain one of the patterns, as relative names. */
  static List<String> offenders(Path dir, Set<String> allowed) throws IOException {
    List<String> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        String name = dir.relativize(file).toString().replace('\\', '/');
        String text = Files.readString(file, UTF_8);
        if ((text.contains(ENVIRONMENT_READ) || text.contains(WORKING_DIRECTORY))
            && !allowed.contains(name)) {
          found.add(name);
        }
      }
    }
    return found;
  }

  @Test
  void noTestSourceReadsTheEnvironmentOrTheWorkingDirectory() throws IOException {
    Path sources =
        Path.of(System.getProperty("regpack.repo.root"))
            .resolve("regpack-evidence-s3/src/test/java");
    assertThat(sources.resolve("dev/regpack/evidence/s3/NoAmbientEnvironmentTest.java")).exists();
    assertThat(offenders(sources, ALLOW_LIST)).isEmpty();
  }

  @Test
  void theAllowListIsExactlyTheS3TestServiceAndItExists() {
    Path sources =
        Path.of(System.getProperty("regpack.repo.root"))
            .resolve("regpack-evidence-s3/src/test/java");
    assertThat(ALLOW_LIST).containsExactly("dev/regpack/evidence/s3/S3TestService.java");
    for (String name : ALLOW_LIST) {
      assertThat(sources.resolve(name)).as("allow-listed helper " + name).exists();
    }
  }

  @Test
  void theScanHasTeeth(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve("Bad.java"),
        "class Bad { String s = System." + ENVIRONMENT_READ + "\"X\"); }\n",
        UTF_8);
    Files.writeString(
        dir.resolve("Bad2.java"), "class Bad2 { String s = " + WORKING_DIRECTORY + "; }\n", UTF_8);
    Files.writeString(dir.resolve("Good.java"), "class Good { }\n", UTF_8);
    assertThat(offenders(dir, Set.of())).containsExactly("Bad.java", "Bad2.java");
    // a helper on the allow-list is the only exception
    assertThat(offenders(dir, Set.of("Bad2.java"))).containsExactly("Bad.java");
  }
}
