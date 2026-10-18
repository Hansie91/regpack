package dev.regpack.bench.proto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForkRunnerTest {
  private static final String WORKER = TestWorker.class.getName();

  private static Protocol forks(int n) {
    return new Protocol(
        n, Duration.ofSeconds(1), Duration.ofMillis(100), 3, 0.03, 3, Duration.ZERO, 0.05, 3, 7);
  }

  @Test
  void childRunsTheWorkerAndReturnsOneResultLine() throws Exception {
    ForkSeries series =
        ForkRunner.runForked(forks(2), List.of(), WORKER, List.of("ok"), RunDeadline.none());
    assertThat(series.forks()).hasSize(2);
    assertThat(series.forks()).extracting(ForkResult::index).containsExactly(0, 1);
    assertThat(series.forks()).allSatisfy(f -> assertThat(f.discarded()).isFalse());
    assertThat(series.forks().get(0).statistic()).isEqualTo(2.5);
    assertThat(series.forks().get(1).checksum()).isEqualTo(42);
    assertThat(series.kept()).hasSize(2);
    assertThat(series.protocol()).isEqualTo(forks(2));
  }

  /** Review focus RF11: a run that does not receive the PostgreSQL URL measures nothing. */
  @Test
  void childReceivesSpikeSystemProperties(@TempDir Path dir) throws Exception {
    Path echo = dir.resolve("echo.txt");
    String url = "jdbc:postgresql://db.invalid:5432/spike?user=u&sslmode=disable";
    System.setProperty("spike.pg.url", url);
    System.setProperty("x.other", "must-not-be-forwarded");
    try {
      ForkSeries series =
          ForkRunner.runForked(
              forks(1),
              List.of("-Dchild.marker=from-jvm-args"),
              WORKER,
              List.of("echo", echo.toString()),
              RunDeadline.none());
      assertThat(series.forks().get(0).discarded()).isFalse();
    } finally {
      System.clearProperty("spike.pg.url");
      System.clearProperty("x.other");
    }
    assertThat(Files.readAllLines(echo, StandardCharsets.UTF_8))
        .containsExactly("spike.pg.url=" + url, "x.other=ABSENT", "child.marker=from-jvm-args");
  }

  @Test
  void missingResultLineIsAHarnessErrorDiscard() throws Exception {
    ForkSeries series =
        ForkRunner.runForked(forks(1), List.of(), WORKER, List.of("noline"), RunDeadline.none());
    ForkResult only = series.forks().get(0);
    assertThat(only.discarded()).isTrue();
    assertThat(only.discardReason()).startsWith("HARNESS_ERROR").contains("FORK-RESULT");
    assertThat(only.windowRates()).isEmpty();
    assertThat(series.kept()).isEmpty();
    assertThat(series.discarded()).containsExactly(only);
  }

  @Test
  void aNonZeroExitIsAHarnessErrorEvenWithAResultLine() throws Exception {
    ForkSeries series =
        ForkRunner.runForked(forks(1), List.of(), WORKER, List.of("fail"), RunDeadline.none());
    ForkResult only = series.forks().get(0);
    assertThat(only.discarded()).isTrue();
    assertThat(only.discardReason()).startsWith("HARNESS_ERROR").contains("exit code 3");
  }

  @Test
  void twoResultLinesAreAHarnessErrorNotTheLastOneWinning() throws Exception {
    ForkSeries series =
        ForkRunner.runForked(forks(1), List.of(), WORKER, List.of("twolines"), RunDeadline.none());
    ForkResult only = series.forks().get(0);
    assertThat(only.discarded()).isTrue();
    assertThat(only.discardReason()).startsWith("HARNESS_ERROR").contains("multiple FORK-RESULT");
    assertThat(series.kept()).isEmpty();
  }

  @Test
  void aKeptResultWithoutWindowRatesIsAHarnessErrorAtTheParseStep() throws Exception {
    ForkSeries series =
        ForkRunner.runForked(
            forks(1), List.of(), WORKER, List.of("emptywindows"), RunDeadline.none());
    ForkResult only = series.forks().get(0);
    assertThat(only.discarded()).isTrue();
    assertThat(only.discardReason()).startsWith("HARNESS_ERROR").contains("window");
    assertThat(series.kept()).isEmpty();
  }

  @Test
  void aChildThatCannotBeLoadedIsAHarnessErrorDiscard() throws Exception {
    ForkSeries series =
        ForkRunner.runForked(
            forks(1),
            List.of(),
            "dev.regpack.bench.proto.NoSuchWorker",
            List.of(),
            RunDeadline.none());
    assertThat(series.forks().get(0).discarded()).isTrue();
    assertThat(series.forks().get(0).discardReason()).startsWith("HARNESS_ERROR");
  }

  @Test
  void noForkStartsAfterTheDeadline(@TempDir Path dir) throws Exception {
    Path marker = dir.resolve("marker.txt");
    List<String> args = List.of("mark", marker.toString());

    // a deadline of zero minutes has passed before the first fork
    ForkSeries none =
        ForkRunner.runForked(
            forks(3), List.of(), WORKER, args, RunDeadline.afterMinutes(ScriptedClock.manual(), 0));
    assertThat(none.forks()).isEmpty();
    assertThat(marker).doesNotExist();

    // a clock that moves one minute per reading: the deadline is read once at its creation and
    // once before each fork, so the first fork starts at 1 minute and none starts at 2
    ScriptedClock minutes = new ScriptedClock(60L * 1_000_000_000L);
    ForkSeries one =
        ForkRunner.runForked(
            forks(5), List.of(), WORKER, args, RunDeadline.afterMinutes(minutes, 2));
    assertThat(one.forks()).hasSize(1);
    assertThat(lines(marker)).hasSize(1);
  }

  @Test
  void aDeadlineOfNoneNeverPasses() {
    RunDeadline none = RunDeadline.none();
    for (int i = 0; i < 3; i++) {
      assertThat(none.passed()).isFalse();
    }
  }

  @Test
  void aDeadlinePassesOnceTheMinutesHaveElapsed() {
    ScriptedClock clock = ScriptedClock.manual();
    RunDeadline deadline = RunDeadline.afterMinutes(clock, 305);
    clock.now = 305L * 60 * 1_000_000_000L - 1;
    assertThat(deadline.passed()).isFalse();
    clock.now = 305L * 60 * 1_000_000_000L;
    assertThat(deadline.passed()).isTrue();
  }

  private static List<String> lines(Path file) throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8);
  }
}
