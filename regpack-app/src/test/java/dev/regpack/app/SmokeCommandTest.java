package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.testkit.clock.ManualClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmokeCommandTest {
  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
  private final ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));

  private String stdout() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  private static String toyInput() throws IOException {
    try (InputStream in =
        SmokeCommandTest.class.getResourceAsStream("/smoke/toy-executions.ndjson")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void smokePrintsDecisionsAndExplainAndExitsZero() {
    int exit = SmokeCommand.run(List.of(), out, err, clock);

    assertThat(exit).isZero();
    List<String> lines = stdout().lines().toList();
    assertThat(lines.stream().filter(line -> line.startsWith("decision {"))).hasSize(3);
    assertThat(lines.stream().filter(line -> line.contains("\"outcome\":\"REPORT\""))).hasSize(1);
    assertThat(stdout())
        .contains("explain: Outcome: DO_NOT_REPORT")
        .contains("explain: Outcome: UNDETERMINED");
    assertThat(lines.get(lines.size() - 1)).isEqualTo("regpack smoke: OK decisions=3");
    assertThat(stderr()).isEmpty();
  }

  @Test
  void smokeFailsWhenAnOutcomeDiffers() throws IOException {
    String changed =
        toyInput()
            .replace(
                "\"venueExecutionId\":\"EX-0002\",\"eventKind\":\"NOVATION\"",
                "\"venueExecutionId\":\"EX-0002\",\"eventKind\":\"NEW\"");

    int exit = SmokeCommand.run(List.of(), out, err, clock, changed);

    assertThat(exit).isEqualTo(1);
    assertThat(stderr())
        .contains(
            "expected [REPORT, DO_NOT_REPORT, UNDETERMINED] got [REPORT, REPORT, UNDETERMINED]");
    assertThat(stdout()).doesNotContain("regpack smoke: OK");
  }

  @Test
  void unknownFlagExitsWithUsage() {
    assertThat(SmokeCommand.run(List.of("--bogus"), out, err, clock)).isEqualTo(2);
    assertThat(stderr()).contains("usage");
  }

  @Test
  void smokeWithTheRealClockExitsZero() {
    int exit = SmokeCommand.run(List.of(), out, err, new SystemClock());

    assertThat(exit).isZero();
    List<String> lines = stdout().lines().toList();
    assertThat(lines.get(lines.size() - 1)).isEqualTo("regpack smoke: OK decisions=3");
  }
}
