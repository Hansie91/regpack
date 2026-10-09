package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.regpack.testkit.clock.ManualClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
  void decisionsComeInFileOrderEachFollowedByItsOwnExplainLines() {
    assertThat(SmokeCommand.run(List.of(), out, err, clock)).isZero();
    List<String> lines = stdout().lines().toList();

    List<Integer> decisionAt = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith("decision {")) {
        decisionAt.add(i);
      }
    }
    assertThat(decisionAt).hasSize(3);
    List<String> outcomes = List.of("REPORT", "DO_NOT_REPORT", "UNDETERMINED");
    for (int n = 0; n < 3; n++) {
      int at = decisionAt.get(n);
      assertThat(lines.get(at + 1))
          .startsWith("explain: Decision ")
          .contains("|EX-000" + (n + 1) + " under ");
      assertThat(lines.get(at + 2)).isEqualTo("explain: Outcome: " + outcomes.get(n));
      if (n < 2) {
        assertThat(decisionAt.get(n + 1)).isGreaterThan(at + 2);
      }
    }
  }

  @Test
  void healthProbesOfAReadyServerPrintOkForLiveThenReadyAndRunThePipeline() {
    try (HealthServer server = HealthServer.start("127.0.0.1", 0)) {
      server.markReady();

      int exit =
          SmokeCommand.run(
              List.of("--health-url", "http://127.0.0.1:" + server.port() + "/"), out, err, clock);

      assertThat(exit).isZero();
      List<String> lines = stdout().lines().toList();
      assertThat(lines.subList(0, 2))
          .containsExactly(
              "regpack smoke: health /health/live OK", "regpack smoke: health /health/ready OK");
      assertThat(lines.get(lines.size() - 1)).isEqualTo("regpack smoke: OK decisions=3");
      assertThat(stderr()).isEmpty();
    }
  }

  @Test
  void aServerThatIsNotReadyFailsTheProbeWithoutRunningThePipeline() {
    try (HealthServer server = HealthServer.start("127.0.0.1", 0)) {
      int exit =
          SmokeCommand.run(
              List.of("--health-url", "http://127.0.0.1:" + server.port()), out, err, clock);

      assertThat(exit).isEqualTo(1);
      assertThat(stderr())
          .contains("regpack smoke: FAILED health /health/ready status 503")
          .contains("STARTING");
      assertThat(stdout())
          .contains("regpack smoke: health /health/live OK")
          .doesNotContain("/health/ready OK")
          .doesNotContain("decision {");
    }
  }

  private void probeAgainstFixedAnswer(int status, String json, String expectedStderr)
      throws IOException {
    HttpServer fake =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    fake.createContext(
        "/",
        exchange -> {
          byte[] body = json.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    fake.start();
    try {
      int exit =
          SmokeCommand.run(
              List.of("--health-url", "http://127.0.0.1:" + fake.getAddress().getPort()),
              out,
              err,
              clock);

      assertThat(exit).isEqualTo(1);
      assertThat(stderr()).contains(expectedStderr);
      assertThat(stdout()).doesNotContain("OK");
    } finally {
      fake.stop(0);
    }
  }

  @Test
  void a200ResponseWithoutStatusUpFailsTheProbe() throws IOException {
    probeAgainstFixedAnswer(
        200, "{\"status\":\"DOWN\"}", "regpack smoke: FAILED health /health/live status 200");
  }

  @Test
  void aNon200ResponseFailsTheProbeEvenWhenTheBodySaysUp() throws IOException {
    probeAgainstFixedAnswer(
        503, "{\"status\":\"UP\"}", "regpack smoke: FAILED health /health/live status 503");
  }

  @Test
  void anUnreachableHealthUrlFailsTheProbe() throws IOException {
    int freePort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      freePort = socket.getLocalPort();
    }

    int exit =
        SmokeCommand.run(List.of("--health-url", "http://127.0.0.1:" + freePort), out, err, clock);

    assertThat(exit).isEqualTo(1);
    assertThat(stderr()).contains("regpack smoke: FAILED health /health/live");
    assertThat(stdout()).isEmpty();
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
