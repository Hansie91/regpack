package dev.regpack.ports;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * The ports module holds the rule-reachable {@code CostMeter}, {@code Value} and {@code Expression}
 * types, so its main code is held to all three determinism rule sets.
 */
class NoWallClockTest {
  @Test
  void portsMainClassesAreClean() throws IOException {
    assertThat(
            ForbiddenCallScan.scan(
                Path.of(System.getProperty("regpack.classes.dir")), EnumSet.allOf(RuleSet.class)))
        .isEmpty();
  }
}
