package dev.regpack.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** Review focus RF6: the engine main code uses no wall clock, randomness or default locale. */
class NoWallClockRandomLocaleTest {
  @Test
  void engineMainClassesAreClean() throws IOException {
    assertThat(
            ForbiddenCallScan.scan(
                Path.of(System.getProperty("regpack.classes.dir")), EnumSet.allOf(RuleSet.class)))
        .isEmpty();
  }
}
