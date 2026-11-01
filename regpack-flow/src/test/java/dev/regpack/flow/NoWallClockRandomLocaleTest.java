package dev.regpack.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Review focus RF6: the flow main code uses no wall clock, randomness or default locale. */
class NoWallClockRandomLocaleTest {
  @Test
  void flowMainClassesAreClean() throws IOException {
    Path classes = Path.of(System.getProperty("regpack.classes.dir"));
    // non-vacuity: an empty or misplaced classes directory would make the scan pass on nothing
    try (Stream<Path> walk = Files.walk(classes)) {
      assertThat(
              walk.filter(p -> p.endsWith(Path.of("dev/regpack/flow/ingest/Mapper.class"))).count())
          .as("the classes directory holds the flow Mapper class")
          .isEqualTo(1);
    }
    assertThat(ForbiddenCallScan.scan(classes, EnumSet.allOf(RuleSet.class))).isEmpty();
  }
}
