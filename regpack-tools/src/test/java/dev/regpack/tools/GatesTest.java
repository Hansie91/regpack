package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GatesTest {
  @Test
  void noArgumentsPrintsUsageAndExitsWithTwo() {
    var outBytes = new ByteArrayOutputStream();
    var errBytes = new ByteArrayOutputStream();
    int rc =
        Gates.run(
            new String[0],
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    assertThat(rc).isEqualTo(2);
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("usage: Gates <traceability|pins|eol>");
  }
}
