package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainTest {
  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private int run(String... args) {
    return Main.run(args, Map.of(), out, err);
  }

  private String stdout() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void helpListsCommandsAndExitsZero() {
    assertThat(run("help")).isZero();
    assertThat(stdout()).contains("serve").contains("smoke").contains("help");
    outBytes.reset();
    assertThat(run()).isZero();
    assertThat(stdout()).contains("smoke");
    outBytes.reset();
    assertThat(run("-h")).isZero();
    assertThat(stdout()).contains("smoke");
  }

  @Test
  void unknownCommandExitsTwo() {
    assertThat(run("frobnicate")).isEqualTo(2);
    assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("frobnicate").contains("usage");
    assertThat(stdout()).isEmpty();
  }

  @Test
  void serveWithABadFlagValueExitsTwoWithUsageAndStartsNothing() {
    assertThat(run("serve", "--port", "abc")).isEqualTo(2);
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("port is not a number: abc")
        .contains("usage: regpack");
    assertThat(stdout()).isEmpty();
  }

  @Test
  void smokeThroughMainExitsZero() {
    assertThat(run("smoke")).isZero();
    assertThat(stdout()).contains("regpack smoke: OK decisions=3");
  }
}
