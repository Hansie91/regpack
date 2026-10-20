package dev.regpack.evidence;

import static java.nio.charset.StandardCharsets.UTF_8;
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
 * Review focus EF20 (the CP-4 lesson of P3a): no test of this module reads the process environment
 * or the working directory. The two patterns are built from two pieces so that this file does not
 * match itself.
 */
class NoAmbientEnvironmentTest {
  private static final String ENVIRONMENT_READ = "get" + "env(";
  private static final String WORKING_DIRECTORY = "\"user" + ".dir\"";

  /** Test sources below {@code dir} that contain one of the patterns, as relative names. */
  static List<String> offenders(Path dir) throws IOException {
    List<String> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        String text = Files.readString(file, UTF_8);
        if (text.contains(ENVIRONMENT_READ) || text.contains(WORKING_DIRECTORY)) {
          found.add(dir.relativize(file).toString().replace('\\', '/'));
        }
      }
    }
    return found;
  }

  @Test
  void noTestSourceReadsTheEnvironmentOrTheWorkingDirectory() throws IOException {
    Path sources =
        Path.of(System.getProperty("regpack.repo.root")).resolve("regpack-evidence/src/test/java");
    assertThat(sources.resolve("dev/regpack/evidence/NoAmbientEnvironmentTest.java")).exists();
    assertThat(offenders(sources)).isEmpty();
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
    assertThat(offenders(dir)).containsExactly("Bad.java", "Bad2.java");
  }
}
