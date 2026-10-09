package dev.regpack.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class NoWallClockRandomLocaleTest {
  @Test
  void modelMainClassesAreClean() throws IOException {
    assertThat(
            ForbiddenCallScan.scan(
                Path.of(System.getProperty("regpack.classes.dir")), EnumSet.allOf(RuleSet.class)))
        .isEmpty();
  }
}
