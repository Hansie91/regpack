package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** In-memory adapters take a {@code Clock}; they never read the wall clock themselves. */
class NoWallClockTest {
  @Test
  void inMemoryMainClassesReadNoWallClock() throws IOException {
    assertThat(
            ForbiddenCallScan.scan(
                Path.of(System.getProperty("regpack.classes.dir")), EnumSet.of(RuleSet.WALL_CLOCK)))
        .isEmpty();
  }
}
