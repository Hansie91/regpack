package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesTest {
  @TempDir Path repo;

  private record Outcome(int rc, String out, String err) {}

  private static Outcome run(String... args) {
    var outBytes = new ByteArrayOutputStream();
    var errBytes = new ByteArrayOutputStream();
    int rc =
        Gates.run(
            args,
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    return new Outcome(
        rc, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
  }

  private String table(boolean withI7) throws IOException {
    StringBuilder md = new StringBuilder("exited_plans: none\n\n");
    for (int i = 1; i <= 24; i++) {
      String id = i <= 14 ? "R" + i : "I" + (i - 14);
      if (withI7 || !id.equals("I7")) {
        md.append("| ").append(id).append(" | P9 | NONE | - | NA | NA | n |\n");
      }
    }
    Path file = repo.resolve("traceability.md");
    Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
    return file.getFileName().toString();
  }

  @Test
  void noArgumentsPrintsUsageAndExitsWithTwo() {
    var outcome = run();
    assertThat(outcome.rc()).isEqualTo(2);
    assertThat(outcome.err()).contains("usage: Gates <traceability|pins|eol>");
  }

  @Test
  void traceabilityPrintsOkLineAndExitsWithZero() throws IOException {
    String file = table(true);
    var outcome = run("traceability", "--file", file, "--root", repo.toString(), "--scan", ".");
    assertThat(outcome.out())
        .isEqualTo(
            "traceability: OK rows=24 closed=0 open=24\n".replace("\n", System.lineSeparator()));
    assertThat(outcome.rc()).isZero();
  }

  @Test
  void traceabilityPrintsOneProblemLineAndExitsWithOne() throws IOException {
    String file = table(false);
    var outcome = run("traceability", "--file", file, "--root", repo.toString(), "--scan", ".");
    assertThat(outcome.out().strip()).isEqualTo("traceability: PROBLEM MISSING_ROW:I7");
    assertThat(outcome.rc()).isEqualTo(1);
  }

  @Test
  void traceabilityWithoutFileOrScanIsAUsageError() {
    assertThat(run("traceability", "--scan", ".").rc()).isEqualTo(2);
    assertThat(run("traceability", "--file", "x.md").rc()).isEqualTo(2);
    assertThat(run("traceability", "--bogus", "x").rc()).isEqualTo(2);
  }

  @Test
  void traceabilityWithMissingScanDirectoryIsAUsageError() throws IOException {
    String file = table(true);
    var outcome =
        run("traceability", "--file", file, "--root", repo.toString(), "--scan", "no-such-dir");
    assertThat(outcome.rc()).isEqualTo(2);
    assertThat(outcome.err()).contains("scan directory not found: no-such-dir");
    assertThat(outcome.out()).isEmpty();
  }
}
