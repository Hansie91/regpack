package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
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
    assertThat(outcome.err()).contains("usage: Gates <traceability|pins|eol|g3|isolation|entry>");
  }

  private static Outcome runWithInput(String input, String... args) {
    var outBytes = new ByteArrayOutputStream();
    var errBytes = new ByteArrayOutputStream();
    int rc =
        Gates.run(
            args,
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    return new Outcome(
        rc, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void g3ReadsPathsFromStandardInputAndReportsProblems() throws IOException {
    Files.writeString(repo.resolve("clean.txt"), "hello\n", StandardCharsets.UTF_8);
    var clean = runWithInput("clean.txt\n", "g3", "--root", repo.toString());
    assertThat(clean.out().strip()).isEqualTo("g3: OK");
    assertThat(clean.rc()).isZero();
    var bad =
        runWithInput("clean.txt\ndocs/leak.xsd\nvendor/a.zip\n", "g3", "--root", repo.toString());
    assertThat(bad.rc()).isEqualTo(1);
    assertThat(bad.out().lines().toList())
        .containsExactly(
            "g3: PROBLEM G3_XSD_PATH docs/leak.xsd", "g3: PROBLEM G3_ARCHIVE vendor/a.zip");
    assertThat(runWithInput("", "g3", "--bogus", "x").rc()).isEqualTo(2);
  }

  @Test
  void isolationChecksThePomsUnderTheRoot() throws IOException {
    Files.writeString(
        repo.resolve("pom.xml"),
        "<project><modules><module>spikes</module></modules></project>",
        StandardCharsets.UTF_8);
    var bad = run("isolation", "--root", repo.toString());
    assertThat(bad.rc()).isEqualTo(1);
    assertThat(bad.out().strip()).isEqualTo("isolation: PROBLEM ISO_ROOT_MODULE");
    Files.writeString(
        repo.resolve("pom.xml"),
        "<project><modules><module>regpack-model</module></modules></project>",
        StandardCharsets.UTF_8);
    var ok = run("isolation", "--root", repo.toString());
    assertThat(ok.out().strip()).isEqualTo("isolation: OK");
    assertThat(ok.rc()).isZero();
    assertThat(run("isolation", "--root").rc()).isEqualTo(2);
  }

  @Test
  void entryWithoutATargetOrWithAMissingWiringFileIsAUsageError() {
    assertThat(run("entry").rc()).isEqualTo(2);
    assertThat(run("entry", "--target").rc()).isEqualTo(2);
    var missing = run("entry", "--target", "P2a", "--root", repo.toString());
    assertThat(missing.rc()).isEqualTo(2);
    assertThat(missing.err()).contains("cannot read docs/spikes/entry-wiring.tsv");
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

  @Test
  void pinsReportsMissingWorkflowsAndPassesPinnedFiles() throws IOException {
    Path dockerfile = repo.resolve("Dockerfile");
    Files.writeString(dockerfile, "FROM scratch\n", StandardCharsets.UTF_8);
    Path workflows = repo.resolve("workflows");
    String docker = dockerfile.toString();
    var missing = run("pins", "--dockerfile", docker, "--workflows", workflows.toString());
    assertThat(missing.rc()).isEqualTo(1);
    assertThat(missing.out()).startsWith("pins: PROBLEM no workflow files in ");
    Files.createDirectories(workflows);
    Files.writeString(
        workflows.resolve("ci.yml"),
        "permissions:\n  contents: read\njobs:\n  b:\n    runs-on: ubuntu-24.04\n",
        StandardCharsets.UTF_8);
    var ok = run("pins", "--dockerfile", docker, "--workflows", workflows.toString());
    assertThat(ok.out().strip()).isEqualTo("pins: OK");
    assertThat(ok.rc()).isZero();
    assertThat(run("pins", "--dockerfile", "x").rc()).isEqualTo(2);
  }

  @Test
  void eolReadsPathsFromStandardInputAndSkipsMissingFiles() throws IOException {
    Files.writeString(repo.resolve("a.sh"), "#!/bin/sh\r\n", StandardCharsets.UTF_8);
    var outBytes = new ByteArrayOutputStream();
    int rc =
        Gates.run(
            new String[] {"eol", "--root", repo.toString()},
            new ByteArrayInputStream("a.sh\ngone.sh\n".getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertThat(rc).isEqualTo(1);
    assertThat(outBytes.toString(StandardCharsets.UTF_8).strip())
        .isEqualTo("eol: PROBLEM a.sh: CR_IN_LF_FILE");
  }
}
