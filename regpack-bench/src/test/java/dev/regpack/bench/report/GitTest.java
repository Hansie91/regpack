package dev.regpack.bench.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitTest {
  @TempDir Path dir;

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  @Test
  void aProcessThatWritesAndThenHangsIsGivenUpAfterTheTimeout() throws Exception {
    // the process keeps its output open and sleeps for a minute: reading to the end of the
    // stream before waiting would block here for the whole minute
    Path source = dir.resolve("Hang.java");
    Files.writeString(
        source,
        "public class Hang {\n"
            + "  public static void main(String[] a) throws Exception {\n"
            + "    System.out.println(\"started\");\n"
            + "    System.out.flush();\n"
            + "    Thread.sleep(60_000);\n"
            + "  }\n"
            + "}\n",
        StandardCharsets.UTF_8);

    Optional<Git.Outcome> outcome =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () -> Git.run(List.of(java(), source.toString()), Duration.ofSeconds(8)));
    assertThat(outcome).isEmpty();
  }

  @Test
  void aProcessThatFinishesGivesItsExitCodeAndItsOutput() {
    Optional<Git.Outcome> outcome = Git.run(List.of(java(), "-version"), Duration.ofSeconds(60));
    assertThat(outcome).isPresent();
    assertThat(outcome.get().exitCode()).isZero();
    // java -version writes to the error stream, which is discarded; the output may be empty
    assertThat(outcome.get().output()).isNotNull();
  }

  @Test
  void aLargeOutputDoesNotBlockTheProcess() throws Exception {
    Path source = dir.resolve("Flood.java");
    Files.writeString(
        source,
        "public class Flood {\n"
            + "  public static void main(String[] a) {\n"
            + "    String line = \"x\".repeat(99);\n"
            + "    for (int i = 0; i < 50_000; i++) System.out.println(line);\n"
            + "  }\n"
            + "}\n",
        StandardCharsets.UTF_8);
    Optional<Git.Outcome> outcome =
        assertTimeoutPreemptively(
            Duration.ofSeconds(60),
            () -> Git.run(List.of(java(), source.toString()), Duration.ofSeconds(50)));
    assertThat(outcome).isPresent();
    assertThat(outcome.get().exitCode()).isZero();
    assertThat(outcome.get().output().length()).isGreaterThan(4_000_000);
  }
}
