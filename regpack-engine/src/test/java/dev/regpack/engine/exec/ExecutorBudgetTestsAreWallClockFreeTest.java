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

/** Spec 14.1: budget tests count cost units and never read wall-clock time (review focus RF7). */
class ExecutorBudgetTestsAreWallClockFreeTest {
  @Test
  void budgetTestsUseNoWallClock(@TempDir Path tmp) throws IOException {
    Path testClasses = Path.of(System.getProperty("regpack.test.classes.dir"));
    List<Path> sources;
    try (Stream<Path> files = Files.walk(testClasses)) {
      sources =
          files
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().matches("MeteredExecutorTest.*\\.class"))
              .toList();
    }
    for (Path source : sources) {
      Path target = tmp.resolve(testClasses.relativize(source).toString());
      Files.createDirectories(target.getParent());
      Files.copy(source, target);
    }

    assertThat(sources).isNotEmpty();
    assertThat(ForbiddenCallScan.scan(tmp, EnumSet.of(RuleSet.WALL_CLOCK))).isEmpty();
  }
}
