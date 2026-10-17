package dev.regpack.engine.exec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec 14.1: every executor budget test counts cost units and never reads wall-clock time (review
 * focus RF7, PF9). The budget tests are the toy-program {@code MeteredExecutorTest}, the seeded
 * over-budget cases of {@code ScopeEngineTest} and {@code PackScopeEngineTest}, the interpreter
 * test {@code PackProgramTest} and the expression layer's {@code CostTest} and {@code
 * AmplificationTest}, and the poison circuit's {@code RuleCircuitTest}.
 */
class ExecutorBudgetTestsAreWallClockFreeTest {
  private static final List<String> SCANNED =
      List.of(
          "MeteredExecutorTest",
          "ScopeEngineTest",
          "PackScopeEngineTest",
          "PackProgramTest",
          "CostTest",
          "AmplificationTest",
          "RuleCircuitTest");

  @Test
  void budgetTestsUseNoWallClock(@TempDir Path tmp) throws IOException {
    Path testClasses = Path.of(System.getProperty("regpack.test.classes.dir"));
    for (String name : SCANNED) {
      List<Path> classes = classesMatching(testClasses, name + ".*\\.class");
      assertThat(classes).as(name).isNotEmpty();
      for (Path source : classes) {
        copy(testClasses, source, tmp);
      }
    }

    assertThat(ForbiddenCallScan.scan(tmp, EnumSet.of(RuleSet.WALL_CLOCK))).isEmpty();
  }

  private static List<Path> classesMatching(Path testClasses, String fileNameRegex)
      throws IOException {
    try (Stream<Path> files = Files.walk(testClasses)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().matches(fileNameRegex))
          .toList();
    }
  }

  private static void copy(Path testClasses, Path source, Path tmp) throws IOException {
    Path target = tmp.resolve(testClasses.relativize(source).toString());
    Files.createDirectories(target.getParent());
    Files.copy(source, target);
  }
}
