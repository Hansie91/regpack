package dev.regpack.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** Review focus PF10: the pack main code uses no wall clock, randomness or default locale. */
class NoWallClockRandomLocaleTest {
  @Test
  void packMainClassesAreClean() throws IOException {
    Path classes = Path.of(System.getProperty("regpack.classes.dir"));
    assertThat(classes.resolve("dev/regpack/pack/PackFormat.class")).exists();
    assertThat(Files.isDirectory(classes)).isTrue();
    assertThat(ForbiddenCallScan.scan(classes, EnumSet.allOf(RuleSet.class))).isEmpty();
  }
}
