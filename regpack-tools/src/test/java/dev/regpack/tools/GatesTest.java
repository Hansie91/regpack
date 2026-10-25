package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesTest {
  @TempDir Path repo;

  private record Outcome(int rc, String out, String err) {}

  private static Outcome run(String... args) {
    var outBytes = new ByteArrayOutputStream();
    var errBytes = new ByteArrayOutputStream();
    // An explicit, empty environment: the result must not depend on the process environment (CI
    // sets GITHUB_ACTIONS=true, which switches traceability to error annotations).
    int rc =
        Gates.run(
            args,
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8),
            Map.of());
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
    assertThat(outcome.err())
        .contains("usage: Gates <traceability|pins|eol|g3|isolation|entry|annotate>");
  }

  @Test
  void realStoreFlagIsParsed() throws IOException {
    String file = table(true);
    String[] base = {"traceability", "--file", file, "--root", repo.toString(), "--scan", "."};
    var all = run(base);
    assertThat(all.out().strip()).isEqualTo("traceability: OK rows=24 closed=0 open=24");
    assertThat(run(append(base, "--realstore", "all")).out()).isEqualTo(all.out());

    var skip = run(append(base, "--realstore", "skip"));
    assertThat(skip.rc()).isZero();
    assertThat(skip.out().strip())
        .isEqualTo("traceability: OK rows=24 closed=0 open=24 deferred=0");
    var only = run(append(base, "--realstore", "only"));
    assertThat(only.rc()).isZero();
    assertThat(only.out().strip()).isEqualTo("traceability: OK rows=24 closed=0 open=24");

    var bogus = run(append(base, "--realstore", "sometimes"));
    assertThat(bogus.rc()).isEqualTo(2);
    assertThat(bogus.err()).contains("usage: Gates traceability");
    assertThat(run(append(base, "--realstore")).rc()).isEqualTo(2);
  }

  /**
   * A table whose first {@code missing} rows cite a test that no report holds (two problems each).
   */
  private void tableWithMissingTests(int missing) throws IOException {
    StringBuilder md = new StringBuilder("exited_plans: P3\n\n");
    int cited = 0;
    for (int i = 1; i <= 24; i++) {
      String id = i <= 14 ? "R" + i : "I" + (i - 14);
      if (cited < missing) {
        cited++;
        md.append("| ").append(id).append(" | P3 | TEST | a.B#it").append(cited);
        md.append(" | REALSTORE | BUILD | n |\n");
      } else {
        md.append("| ").append(id).append(" | P9 | NONE | - | NA | NA | n |\n");
      }
    }
    Files.writeString(repo.resolve("traceability.md"), md.toString(), StandardCharsets.UTF_8);
  }

  private Outcome runWithEnvironment(Map<String, String> env, String... args) {
    var outBytes = new ByteArrayOutputStream();
    var errBytes = new ByteArrayOutputStream();
    int rc =
        Gates.run(
            args,
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8),
            env);
    return new Outcome(
        rc, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aFailedTraceabilityCheckIsAnErrorAnnotationWithTheReason() throws IOException {
    tableWithMissingTests(1);
    String[] base = {
      "traceability", "--file", "traceability.md", "--root", repo.toString(), "--scan", "."
    };

    // without the flag and outside GitHub Actions: the PROBLEM line only
    var plain = run(base);
    assertThat(plain.rc()).isEqualTo(1);
    assertThat(plain.out()).contains("traceability: PROBLEM ARTEFACT_MISSING:R1:a.B#it1");
    assertThat(plain.out()).doesNotContain("::error");

    // with the flag: the same line and one error annotation that names the row and the test
    var flagged = run(append(base, "--annotate"));
    assertThat(flagged.rc()).isEqualTo(1);
    assertThat(flagged.out().lines().toList())
        .containsExactly(
            "traceability: PROBLEM ARTEFACT_MISSING:R1:a.B#it1",
            "traceability: PROBLEM ROW_WITHOUT_EVIDENCE:R1",
            "::error title=traceability::ARTEFACT_MISSING:R1:a.B#it1",
            "::error title=traceability::ROW_WITHOUT_EVIDENCE:R1");

    // in GitHub Actions the step needs no flag, so that the workflow file does not change
    var inActions = runWithEnvironment(Map.of("GITHUB_ACTIONS", "true"), base);
    assertThat(inActions.out()).contains("::error title=traceability::ARTEFACT_MISSING:R1:a.B#it1");
    var elsewhere = runWithEnvironment(Map.of("GITHUB_ACTIONS", "false"), base);
    assertThat(elsewhere.out()).doesNotContain("::error");

    // a clean check prints no annotation, flag or not
    String clean = table(true);
    var ok =
        run(
            "traceability",
            "--file",
            clean,
            "--root",
            repo.toString(),
            "--scan",
            ".",
            "--annotate");
    assertThat(ok.rc()).isZero();
    assertThat(ok.out()).doesNotContain("::error");
  }

  @Test
  void manyTraceabilityProblemsAreAtMostTenAnnotations() throws IOException {
    tableWithMissingTests(12);
    var outcome =
        run(
            "traceability",
            "--file",
            "traceability.md",
            "--root",
            repo.toString(),
            "--scan",
            ".",
            "--annotate");
    var errors = outcome.out().lines().filter(l -> l.startsWith("::error")).toList();
    assertThat(errors).hasSize(10);
    assertThat(errors.get(0)).isEqualTo("::error title=traceability::ARTEFACT_MISSING:R1:a.B#it1");
    assertThat(errors.get(9))
        .isEqualTo("::error title=traceability::and 15 more problems, see the log");
    assertThat(outcome.out().lines().filter(l -> l.startsWith("traceability: PROBLEM")).count())
        .isEqualTo(24);
  }

  @Test
  void standbyModeIsParsedAndTheUsageNamesIt() throws IOException {
    String file = table(true);
    String[] base = {"traceability", "--file", file, "--root", repo.toString(), "--scan", "."};
    var standby = run(append(base, "--realstore", "standby"));
    assertThat(standby.rc()).isZero();
    assertThat(standby.out().strip()).isEqualTo("traceability: OK rows=24 closed=0 open=24");

    var bogus = run(append(base, "--realstore", "sometimes"));
    assertThat(bogus.rc()).isEqualTo(2);
    assertThat(bogus.err()).contains("[--realstore all|skip|only|standby|worm]");

    // the mode reads the standby lines only: a passing one closes a due I1 row
    Path reports = repo.resolve("target/failsafe-reports");
    Files.createDirectories(reports);
    Files.writeString(
        reports.resolve("TEST-a.B.xml"),
        "<testsuite><testcase classname=\"a.B\" name=\"up\"/></testsuite>",
        StandardCharsets.UTF_8);
    StringBuilder md = new StringBuilder("exited_plans: P3\n\n");
    for (int i = 1; i <= 24; i++) {
      String id = i <= 14 ? "R" + i : "I" + (i - 14);
      if (id.equals("I1")) {
        md.append("| I1 | P3 | TEST | a.B#up | REALSTANDBY | BUILD | n |\n");
      } else {
        md.append("| ").append(id).append(" | P9 | NONE | - | NA | NA | n |\n");
      }
    }
    Files.writeString(repo.resolve("traceability.md"), md.toString(), StandardCharsets.UTF_8);
    var closed = run(append(base, "--realstore", "standby"));
    assertThat(closed.rc()).isZero();
    assertThat(closed.out().strip()).isEqualTo("traceability: OK rows=24 closed=1 open=23");
    // the build jobs (skip) do not read the line, and the ledger job (only) does not either
    var skip = run(append(base, "--realstore", "skip"));
    assertThat(skip.out().lines().toList())
        .containsExactly(
            "traceability: INFO REALSTORE_DEFERRED:I1",
            "traceability: OK rows=24 closed=0 open=24 deferred=1");
  }

  @Test
  void wormModeIsParsedAndTheUsageNamesIt() throws IOException {
    String file = table(true);
    String[] base = {"traceability", "--file", file, "--root", repo.toString(), "--scan", "."};
    var worm = run(append(base, "--realstore", "worm"));
    assertThat(worm.rc()).isZero();
    assertThat(worm.out().strip()).isEqualTo("traceability: OK rows=24 closed=0 open=24");

    var bogus = run(append(base, "--realstore", "sometimes"));
    assertThat(bogus.rc()).isEqualTo(2);
    assertThat(bogus.err()).contains("[--realstore all|skip|only|standby|worm]");

    // the mode reads the REALWORM lines only: a passing one closes a due I1 row
    Path reports = repo.resolve("target/failsafe-reports");
    Files.createDirectories(reports);
    Files.writeString(
        reports.resolve("TEST-a.B.xml"),
        "<testsuite><testcase classname=\"a.B\" name=\"up\"/></testsuite>",
        StandardCharsets.UTF_8);
    StringBuilder md = new StringBuilder("exited_plans: P3\n\n");
    for (int i = 1; i <= 24; i++) {
      String id = i <= 14 ? "R" + i : "I" + (i - 14);
      if (id.equals("I1")) {
        md.append("| I1 | P3 | TEST | a.B#up | REALWORM | BUILD | n |\n");
      } else {
        md.append("| ").append(id).append(" | P9 | NONE | - | NA | NA | n |\n");
      }
    }
    Files.writeString(repo.resolve("traceability.md"), md.toString(), StandardCharsets.UTF_8);
    var closed = run(append(base, "--realstore", "worm"));
    assertThat(closed.rc()).isZero();
    assertThat(closed.out().strip()).isEqualTo("traceability: OK rows=24 closed=1 open=23");
    // the build jobs (skip) do not read the line; the ledger job (only) does not either
    var skip = run(append(base, "--realstore", "skip"));
    assertThat(skip.out().lines().toList())
        .containsExactly(
            "traceability: INFO REALSTORE_DEFERRED:I1",
            "traceability: OK rows=24 closed=0 open=24 deferred=1");
    var only = run(append(base, "--realstore", "only"));
    assertThat(only.rc()).isEqualTo(1);
    assertThat(only.out()).contains("traceability: PROBLEM ROW_WITHOUT_EVIDENCE:I1");
  }

  @Test
  void realStoreSkipPrintsDeferredRowsAndExitsWithZero() throws IOException {
    Path file = repo.resolve("traceability.md");
    StringBuilder md = new StringBuilder("exited_plans: P3\n\n");
    for (int i = 1; i <= 24; i++) {
      String id = i <= 14 ? "R" + i : "I" + (i - 14);
      if (id.equals("I2")) {
        md.append("| I2 | P3 | TEST | a.B#it | REALSTORE | BUILD | n |\n");
      } else {
        md.append("| ").append(id).append(" | P9 | NONE | - | NA | NA | n |\n");
      }
    }
    Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
    String[] base = {"traceability", "--file", "traceability.md", "--root", repo.toString()};
    var all = run(append(base, "--scan", "."));
    assertThat(all.rc()).isEqualTo(1);
    assertThat(all.out()).contains("traceability: PROBLEM ARTEFACT_MISSING:I2:a.B#it");
    var skip = run(append(base, "--scan", ".", "--realstore", "skip"));
    assertThat(skip.rc()).isZero();
    assertThat(skip.out().lines().toList())
        .containsExactly(
            "traceability: INFO REALSTORE_DEFERRED:I2",
            "traceability: OK rows=24 closed=0 open=24 deferred=1");
  }

  private static String[] append(String[] args, String... more) {
    String[] all = Arrays.copyOf(args, args.length + more.length);
    System.arraycopy(more, 0, all, args.length, more.length);
    return all;
  }

  @Test
  void annotatePrintsTheTallyOfTheScannedReports() throws IOException {
    Path reports = repo.resolve("target/surefire-reports");
    Files.createDirectories(reports);
    Files.writeString(
        reports.resolve("TEST-a.B.xml"),
        "<testsuite name=\"a.B\"><testcase classname=\"a.B\" name=\"m()\"/>"
            + "<testcase classname=\"a.B\" name=\"f()\"><failure message=\"boom\"/></testcase>"
            + "</testsuite>",
        StandardCharsets.UTF_8);
    var outcome = run("annotate", "--scan", repo.toString());
    assertThat(outcome.rc()).isZero();
    assertThat(outcome.out().lines().toList())
        .contains(
            "::notice title=tally-classes::classes=1 class=a.B run=2 failed=1 errors=0 skipped=0",
            "::notice title=tally-total::classes=1 run=2 failed=1 errors=0 skipped=0",
            "::error title=a.B#f::boom");
    assertThat(outcome.out().lines().findFirst().orElseThrow())
        .startsWith("::notice title=tally-total::");
    assertThat(run("annotate").rc()).isEqualTo(2);
    assertThat(run("annotate", "--scan").rc()).isEqualTo(2);
    assertThat(run("annotate", "--bogus", "x").rc()).isEqualTo(2);
    var missing = run("annotate", "--scan", repo.resolve("no-such-dir").toString());
    assertThat(missing.rc()).isEqualTo(2);
    assertThat(missing.err()).contains("scan directory not found");
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
  void eolReadsPathsFromStandardInputAndReportsMissingFiles() throws IOException {
    Files.writeString(repo.resolve("a.sh"), "#!/bin/sh\r\n", StandardCharsets.UTF_8);
    var outBytes = new ByteArrayOutputStream();
    int rc =
        Gates.run(
            new String[] {"eol", "--root", repo.toString()},
            new ByteArrayInputStream("a.sh\ngone.sh\n".getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertThat(rc).isEqualTo(1);
    assertThat(outBytes.toString(StandardCharsets.UTF_8).strip().lines())
        .containsExactly("eol: PROBLEM a.sh: CR_IN_LF_FILE", "eol: PROBLEM gone.sh: NOT_FOUND");
  }

  @Test
  void eolReadsNulTerminatedPathsWithNonAsciiAndSpacedNames() throws IOException {
    String name = "café two.md";
    String tag = new String(Character.toChars(0xE0041));
    Files.writeString(repo.resolve(name), "a " + tag + " b\n", StandardCharsets.UTF_8);
    var outBytes = new ByteArrayOutputStream();
    int rc =
        Gates.run(
            new String[] {"eol", "--root", repo.toString()},
            new ByteArrayInputStream((name + "\u0000").getBytes(StandardCharsets.UTF_8)),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertThat(rc).isEqualTo(1);
    assertThat(outBytes.toString(StandardCharsets.UTF_8).strip())
        .isEqualTo("eol: PROBLEM " + name + ": HIDDEN_CHARACTER (line 1, U+E0041)");
  }
}
