package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceabilityCheckTest {
  private static final List<String> IDS = ids();

  @TempDir Path repo;

  private static List<String> ids() {
    List<String> all = new ArrayList<>();
    for (int i = 1; i <= 14; i++) {
      all.add("R" + i);
    }
    for (int i = 1; i <= 10; i++) {
      all.add("I" + i);
    }
    return List.copyOf(all);
  }

  /** A traceability table that starts with one open NONE line per id and is changed by tests. */
  private static final class Table {
    private final Map<String, List<String>> lines = new LinkedHashMap<>();
    private String exited = "none";

    Table() {
      for (String id : IDS) {
        lines.put(id, new ArrayList<>(List.of(line(id, "P9", "NONE", "-", "NA", "NA"))));
      }
    }

    static String line(String id, String closes, String kind, String ref, String store, String ci) {
      return "| "
          + id
          + " | "
          + closes
          + " | "
          + kind
          + " | "
          + ref
          + " | "
          + store
          + " | "
          + ci
          + " | note |";
    }

    Table exited(String value) {
      exited = value;
      return this;
    }

    Table without(String id) {
      lines.remove(id);
      return this;
    }

    /** Replaces all lines of a row. */
    Table set(String id, String... rowLines) {
      lines.put(id, new ArrayList<>(List.of(rowLines)));
      return this;
    }

    String render() {
      StringBuilder out = new StringBuilder("# Traceability\n\nexited_plans: " + exited + "\n\n");
      out.append("| row | closes_in | kind | ref | store | ci | note |\n");
      out.append("|---|---|---|---|---|---|---|\n");
      lines.values().forEach(rows -> rows.forEach(l -> out.append(l).append('\n')));
      return out.toString();
    }
  }

  private TraceabilityCheck.Result check(Table table) {
    return TraceabilityCheck.check(table.render(), repo, List.of(repo));
  }

  private void write(String relative, String content) throws IOException {
    Path file = repo.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private void report(String testcaseXml) throws IOException {
    write(
        "target/surefire-reports/TEST-a.B.xml",
        "<testsuite name=\"a.B\">" + testcaseXml + "</testsuite>");
  }

  private static String r12(String ref) {
    return Table.line("R12", "P3", "TEST", ref, "INMEMORY", "BUILD");
  }

  @Test
  void missingRowFails() {
    var result =
        check(
            new Table().without("I7").set("R1", Table.line("R1", "P3x", "NONE", "-", "NA", "NA")));
    assertThat(result.ok()).isFalse();
    assertThat(result.problems()).contains("MISSING_ROW:I7", "BAD_PLAN:R1:P3x");
    assertThat(result.rows()).isEqualTo(23);

    var malformed =
        check(
            new Table()
                .exited("P1,Px")
                .set("R2", "| R2 | P1 | NONE | - | NA |")
                .set("R99", Table.line("R99", "P1", "NONE", "-", "NA", "NA"))
                .set(
                    "R4",
                    Table.line("R4", "P3", "NONE", "-", "NA", "NA"),
                    Table.line("R4", "P4", "NONE", "-", "NA", "NA")));
    assertThat(malformed.problems())
        .contains(
            "UNKNOWN_ROW:R99",
            "CLOSES_IN_MISMATCH:R4",
            "BAD_PLAN:exited_plans:Px",
            "MISSING_ROW:R2");
    assertThat(malformed.problems()).anyMatch(p -> p.startsWith("BAD_LINE:"));
    var noExitedLine =
        TraceabilityCheck.check(
            new Table().render().replace("exited_plans: none\n", ""), repo, List.of(repo));
    assertThat(noExitedLine.problems()).containsExactly("NO_EXITED_PLANS");
  }

  @Test
  void openRowMayHaveNoEvidence() {
    var result = check(new Table().set("I3", Table.line("I3", "P5,P8", "NONE", "-", "NA", "NA")));
    assertThat(result.problems()).isEmpty();
    assertThat(result.ok()).isTrue();
    assertThat(result.rows()).isEqualTo(24);
    assertThat(result.closed()).isZero();
    assertThat(result.open()).isEqualTo(24);
  }

  @Test
  void rowWithoutEvidenceFailsWhenItsPlanHasExited() {
    var result =
        check(
            new Table()
                .exited("P2a,P6a")
                .set("I5", Table.line("I5", "P2a,P6a", "NONE", "-", "NA", "NA")));
    assertThat(result.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I5");
    assertThat(result.open()).isEqualTo(23);
    assertThat(result.closed()).isZero();
  }

  @Test
  void missingTestArtefactFails() throws IOException {
    var result = check(new Table().exited("P3").set("R12", r12("a.B#m")));
    assertThat(result.problems()).contains("ARTEFACT_MISSING:R12:a.B#m");
    assertThat(result.ok()).isFalse();

    // reports that must not be evidence: a DOCTYPE (XXE hardening) and skipped directories
    write(
        "target/surefire-reports/TEST-x.Y.xml",
        "<!DOCTYPE testsuite [<!ENTITY e \"v\">]><testsuite>"
            + "<testcase classname=\"x.Y\" name=\"m\"/></testsuite>");
    write(
        "node_modules/p/TEST-n.M.xml",
        "<testsuite><testcase classname=\"n.M\" name=\"m\"/></testsuite>");
    write(".git/TEST-g.H.xml", "<testsuite><testcase classname=\"g.H\" name=\"m\"/></testsuite>");
    var hostile =
        check(new Table().exited("P3").set("R12", r12("x.Y#m"), r12("n.M#m"), r12("g.H#m")));
    assertThat(hostile.problems())
        .contains(
            "ARTEFACT_MISSING:R12:x.Y#m",
            "ARTEFACT_MISSING:R12:n.M#m",
            "ARTEFACT_MISSING:R12:g.H#m",
            "ROW_WITHOUT_EVIDENCE:R12");
  }

  @Test
  void skippedTestDoesNotCount() throws IOException {
    report("<testcase classname=\"a.B\" name=\"m()\"><skipped/></testcase>");
    var result = check(new Table().exited("P3").set("R12", r12("a.B#m")));
    assertThat(result.problems()).contains("NOT_PASSING:R12:a.B#m", "ROW_WITHOUT_EVIDENCE:R12");

    // an error child does not count, and a failure in any report wins over a pass in another
    write(
        "target/surefire-reports/TEST-c.D.xml",
        "<testsuite><testcase classname=\"c.D\" name=\"err\"><error/></testcase>"
            + "<testcase classname=\"c.D\" name=\"stale\"><failure/></testcase></testsuite>");
    write(
        "target/failsafe-reports/TEST-c.D.xml",
        "<testsuite><testcase classname=\"c.D\" name=\"stale\"/></testsuite>");
    var errored = check(new Table().exited("P3").set("R12", r12("c.D#err")));
    assertThat(errored.problems()).contains("NOT_PASSING:R12:c.D#err");
    var stale = check(new Table().exited("P3").set("R12", r12("c.D#stale")));
    assertThat(stale.problems()).contains("NOT_PASSING:R12:c.D#stale");
  }

  /**
   * Failed, errored or skipped in any report is not passed, whatever other reports say: the same
   * test fails in one of two reports and passes in the other, each way round, so that the result
   * does not depend on the order in which the files are read.
   */
  @Test
  void aFailureInAnyReportWinsWhicheverReportIsReadFirst() throws IOException {
    List<String> directories = List.of("failsafe-reports", "surefire-reports");
    for (String failing : directories) {
      String passing = failing.equals(directories.get(0)) ? directories.get(1) : directories.get(0);
      for (String bad : List.of("failure", "error", "skipped")) {
        String name = bad + "_in_" + failing.substring(0, 4);
        write(
            "target/" + failing + "/TEST-e.F.xml",
            "<testsuite><testcase classname=\"e.F\" name=\""
                + name
                + "\"><"
                + bad
                + "/></testcase></testsuite>");
        write(
            "target/" + passing + "/TEST-e.F.xml",
            "<testsuite><testcase classname=\"e.F\" name=\"" + name + "\"/></testsuite>");
        var result = check(new Table().exited("P3").set("R12", r12("e.F#" + name)));
        assertThat(result.problems()).as(name).contains("NOT_PASSING:R12:e.F#" + name);
      }
    }
  }

  @Test
  void testNamesWithParenthesesAreMatched() throws IOException {
    report(
        "<testcase classname=\"a.B\" name=\"m()\"/>"
            + "<testcase classname=\"a.B\" name=\"broken\"><failure message=\"x\"/></testcase>");
    var result = check(new Table().exited("P3").set("R12", r12("a.B#m")));
    assertThat(result.problems()).isEmpty();
    assertThat(result.closed()).isEqualTo(1);
    var failing = check(new Table().exited("P3").set("R12", r12("a.B#broken")));
    assertThat(failing.problems()).contains("NOT_PASSING:R12:a.B#broken");
  }

  @Test
  void inMemoryOnlyEvidenceDoesNotCloseI1() throws IOException {
    report("<testcase classname=\"a.B\" name=\"m()\"/><testcase classname=\"a.B\" name=\"r()\"/>");
    String interim = Table.line("I1", "P3", "TEST", "a.B#m", "INMEMORY", "BUILD");
    var result = check(new Table().exited("P3").set("I1", interim));
    assertThat(result.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I1");
    var closed =
        check(
            new Table()
                .exited("P3")
                .set(
                    "I1",
                    interim,
                    Table.line("I1", "P3", "TEST", "a.B#r", "REALSTORE", "NIGHTLY")));
    assertThat(closed.problems()).isEmpty();
    assertThat(closed.closed()).isEqualTo(1);
    var sameEvidenceOnR12 = check(new Table().exited("P3").set("R12", r12("a.B#m")));
    assertThat(sameEvidenceOnR12.problems()).isEmpty();
    assertThat(sameEvidenceOnR12.closed()).isEqualTo(1);
  }

  @Test
  void consistencyOnlyGoldenNeverCounts() throws IOException {
    write(
        "target/golden-audit.tsv",
        "GC-A\tCONSISTENCY_ONLY\t-\tfalse\t-\n" + "GC-B\tESMA_QA\trev-1\ttrue\t-\n");
    var consistency =
        check(
            new Table()
                .exited("P2a,P6a")
                .set("I5", Table.line("I5", "P2a,P6a", "GOLDEN", "GC-A", "UNIT", "BUILD")));
    assertThat(consistency.problems()).contains("ROW_WITHOUT_EVIDENCE:I5");
    assertThat(consistency.closed()).isZero();
    var counted =
        check(
            new Table()
                .exited("P2a,P6a")
                .set("I5", Table.line("I5", "P2a,P6a", "GOLDEN", "GC-B", "UNIT", "BUILD")));
    assertThat(counted.problems()).isEmpty();
    var absent =
        check(
            new Table()
                .exited("P2a,P6a")
                .set("I5", Table.line("I5", "P2a,P6a", "GOLDEN", "GC-Z", "UNIT", "BUILD")));
    assertThat(absent.problems()).contains("ARTEFACT_MISSING:I5:GC-Z");
  }

  private TraceabilityCheck.Result check(Table table, TraceabilityCheck.Mode mode) {
    return TraceabilityCheck.check(table.render(), repo, List.of(repo), mode);
  }

  private static String realStore(String id, String ref) {
    return Table.line(id, "P3", "TEST", ref, "REALSTORE", "BUILD");
  }

  private static String inMemory(String id, String ref) {
    return Table.line(id, "P3", "TEST", ref, "INMEMORY", "BUILD");
  }

  @Test
  void realStoreSkipIgnoresRealStoreLines() throws IOException {
    // the real-store test was never run on this machine: no report, so mode ALL reports it missing
    var all = check(new Table().exited("P3").set("I2", realStore("I2", "a.B#it")));
    assertThat(all.problems()).contains("ARTEFACT_MISSING:I2:a.B#it", "ROW_WITHOUT_EVIDENCE:I2");
    assertThat(all.deferred()).isEmpty();

    var skip =
        check(
            new Table().exited("P3").set("I2", realStore("I2", "a.B#it")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(skip.problems()).isEmpty();
    assertThat(skip.ok()).isTrue();
    assertThat(skip.deferred()).containsExactly("REALSTORE_DEFERRED:I2");
    assertThat(skip.closed()).isZero();
    assertThat(skip.open()).isEqualTo(24);

    // a passing in-memory line is interim for I2 and does not close it; a failing real-store test
    // is not looked at in this mode
    report(
        "<testcase classname=\"a.B\" name=\"m()\"/>"
            + "<testcase classname=\"a.B\" name=\"it\"><failure/></testcase>");
    var interim =
        check(
            new Table().exited("P3").set("I2", inMemory("I2", "a.B#m"), realStore("I2", "a.B#it")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(interim.problems()).isEmpty();
    assertThat(interim.deferred()).containsExactly("REALSTORE_DEFERRED:I2");

    // a row that is not due defers nothing
    var notDue =
        check(
            new Table().set("I2", realStore("I2", "a.B#it")).exited("none"),
            TraceabilityCheck.Mode.SKIP);
    assertThat(notDue.problems()).isEmpty();
    assertThat(notDue.deferred()).isEmpty();
  }

  @Test
  void realStoreSkipStillFailsADueRowWithNoEvidenceAtAll() throws IOException {
    var none =
        check(
            new Table().exited("P3").set("I1", Table.line("I1", "P3", "NONE", "-", "NA", "NA")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(none.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I1");
    assertThat(none.deferred()).isEmpty();

    report("<testcase classname=\"a.B\" name=\"m()\"/>");
    var onlyInMemory =
        check(
            new Table().exited("P3").set("I9", inMemory("I9", "a.B#m")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(onlyInMemory.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I9");
    assertThat(onlyInMemory.deferred()).isEmpty();

    // a row that never needs the real store does not defer: its real-store line is not evidence
    var r12 =
        check(
            new Table().exited("P3").set("R12", realStore("R12", "a.B#it")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(r12.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:R12");
    assertThat(r12.deferred()).isEmpty();

    // a counting line next to the real-store line closes an ordinary row
    var counted =
        check(
            new Table()
                .exited("P3")
                .set("R12", realStore("R12", "a.B#it"), inMemory("R12", "a.B#m")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(counted.problems()).isEmpty();
    assertThat(counted.closed()).isEqualTo(1);
  }

  @Test
  void realStoreOnlyRequiresPassedTests() throws IOException {
    report(
        "<testcase classname=\"a.B\" name=\"it()\"/>"
            + "<testcase classname=\"a.B\" name=\"bad\"><failure/></testcase>");
    var passed =
        check(
            new Table().exited("P3").set("I2", realStore("I2", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(passed.problems()).isEmpty();
    assertThat(passed.closed()).isEqualTo(1);
    assertThat(passed.deferred()).isEmpty();

    var failed =
        check(
            new Table().exited("P3").set("I2", realStore("I2", "a.B#bad")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(failed.problems()).contains("NOT_PASSING:I2:a.B#bad", "ROW_WITHOUT_EVIDENCE:I2");

    var missing =
        check(
            new Table().exited("P3").set("I2", realStore("I2", "a.B#gone")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(missing.problems())
        .contains("ARTEFACT_MISSING:I2:a.B#gone", "ROW_WITHOUT_EVIDENCE:I2");

    // a due real-store-only row with no real-store line at all needs one
    var noLine =
        check(
            new Table().exited("P3").set("I10", inMemory("I10", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(noLine.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I10");
  }

  @Test
  void skippedRealStoreTestNeverCounts() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"><skipped message=\"no server\"/></testcase>");
    var only =
        check(
            new Table().exited("P3").set("I2", realStore("I2", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(only.problems()).contains("NOT_PASSING:I2:a.B#it", "ROW_WITHOUT_EVIDENCE:I2");
    assertThat(only.closed()).isZero();
    // a skipped test in mode ALL never counts either
    var all = check(new Table().exited("P3").set("I2", realStore("I2", "a.B#it")));
    assertThat(all.problems()).contains("NOT_PASSING:I2:a.B#it", "ROW_WITHOUT_EVIDENCE:I2");
  }

  @Test
  void realStoreOnlyIgnoresInMemoryLines() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    // the in-memory line cites a test that does not exist: only mode does not look at it
    var result =
        check(
            new Table()
                .exited("P3")
                .set("I2", inMemory("I2", "x.Y#nowhere"), realStore("I2", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(result.problems()).isEmpty();
    assertThat(result.closed()).isEqualTo(1);

    // a due row that has no real-store line and does not need one is not evaluated
    var ordinary =
        check(
            new Table().exited("P3").set("R12", inMemory("R12", "x.Y#nowhere")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(ordinary.problems()).isEmpty();
    assertThat(ordinary.closed()).isZero();
  }

  // ---------------------------------------------------------------- REALFILES and REALSTANDBY
  // (A14)

  private static String line(String id, String closes, String ref, String store) {
    return Table.line(id, closes, "TEST", ref, store, "BUILD");
  }

  private static String realFiles(String id, String ref) {
    return line(id, "P3", ref, "REALFILES");
  }

  private static String realStandby(String id, String ref) {
    return line(id, "P3", ref, "REALSTANDBY");
  }

  @Test
  void realFilesLinesAreEvaluatedInSkipMode() throws IOException {
    report(
        "<testcase classname=\"a.B\" name=\"files()\"/>"
            + "<testcase classname=\"a.B\" name=\"bad\"><failure/></testcase>");
    var passed =
        check(
            new Table().exited("P3").set("I1", realFiles("I1", "a.B#files")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(passed.problems()).isEmpty();
    assertThat(passed.closed()).isEqualTo(1);
    assertThat(passed.deferred()).isEmpty();

    var failed =
        check(
            new Table().exited("P3").set("I1", realFiles("I1", "a.B#bad")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(failed.problems()).contains("NOT_PASSING:I1:a.B#bad", "ROW_WITHOUT_EVIDENCE:I1");

    var missing =
        check(
            new Table().exited("P3").set("I10", realFiles("I10", "a.B#gone")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(missing.problems())
        .contains("ARTEFACT_MISSING:I10:a.B#gone", "ROW_WITHOUT_EVIDENCE:I10");

    // next to a real-store line the real-files line counts, so nothing is deferred
    var both =
        check(
            new Table()
                .exited("P3")
                .set("I1", realFiles("I1", "a.B#files"), realStore("I1", "a.B#gone")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(both.problems()).isEmpty();
    assertThat(both.closed()).isEqualTo(1);
    assertThat(both.deferred()).isEmpty();
  }

  @Test
  void realFilesLinesAreIgnoredInOnlyMode() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    // the real-files line cites a test that does not exist: mode ONLY does not look at it
    var result =
        check(
            new Table()
                .exited("P3")
                .set("I1", realFiles("I1", "x.Y#nowhere"), realStore("I1", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(result.problems()).isEmpty();
    assertThat(result.closed()).isEqualTo(1);
  }

  @Test
  void realStandbyLinesAreIgnoredInSkipAndOnlyModes() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/><testcase classname=\"a.B\" name=\"f()\"/>");
    // a real-standby line that cites a missing test is not looked up in SKIP or ONLY
    for (TraceabilityCheck.Mode mode :
        List.of(TraceabilityCheck.Mode.SKIP, TraceabilityCheck.Mode.ONLY)) {
      var result =
          check(
              new Table()
                  .exited("P3")
                  .set(
                      "I1",
                      realStandby("I1", "x.Y#nowhere"),
                      realStore("I1", "a.B#it"),
                      realFiles("I1", "a.B#f")),
              mode);
      assertThat(result.problems()).as(mode.name()).isEmpty();
      assertThat(result.closed()).as(mode.name()).isEqualTo(1);
    }
    // SKIP defers a due row whose only non-interim line is a real-standby line
    var deferred =
        check(
            new Table()
                .exited("P3")
                .set("I1", realStandby("I1", "x.Y#nowhere"), inMemory("I1", "a.B#it")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(deferred.problems()).isEmpty();
    assertThat(deferred.deferred()).containsExactly("REALSTORE_DEFERRED:I1");
    assertThat(deferred.closed()).isZero();
    // ONLY needs a real-store line for I1: a real-standby line is not one
    var only =
        check(
            new Table().exited("P3").set("I1", realStandby("I1", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(only.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I1");
  }

  @Test
  void realStandbyLinesAreEvaluatedOnlyInStandbyMode() throws IOException {
    report(
        "<testcase classname=\"a.B\" name=\"up()\"/>"
            + "<testcase classname=\"a.B\" name=\"bad\"><failure/></testcase>");
    // the other labels cite tests that do not exist: STANDBY does not look at them
    var passed =
        check(
            new Table()
                .exited("P3")
                .set(
                    "I1",
                    realStandby("I1", "a.B#up"),
                    realFiles("I1", "x.Y#nowhere"),
                    realStore("I1", "x.Y#nowhere"),
                    inMemory("I1", "x.Y#nowhere")),
            TraceabilityCheck.Mode.STANDBY);
    assertThat(passed.problems()).isEmpty();
    assertThat(passed.closed()).isEqualTo(1);
    assertThat(passed.deferred()).isEmpty();

    var failed =
        check(
            new Table().exited("P3").set("I1", realStandby("I1", "a.B#bad")),
            TraceabilityCheck.Mode.STANDBY);
    assertThat(failed.problems()).contains("NOT_PASSING:I1:a.B#bad", "ROW_WITHOUT_EVIDENCE:I1");

    var missing =
        check(
            new Table().exited("P3").set("I1", realStandby("I1", "a.B#gone")),
            TraceabilityCheck.Mode.STANDBY);
    assertThat(missing.problems())
        .contains("ARTEFACT_MISSING:I1:a.B#gone", "ROW_WITHOUT_EVIDENCE:I1");

    // mode ALL evaluates the line as well
    var all = check(new Table().exited("P3").set("I1", realStandby("I1", "a.B#gone")));
    assertThat(all.problems()).contains("ARTEFACT_MISSING:I1:a.B#gone");
  }

  @Test
  void aRowWithoutStandbyLinesIsOpenInStandbyMode() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    var result =
        check(
            new Table()
                .exited("P3")
                .set("I1", realFiles("I1", "a.B#it"), realStore("I1", "a.B#it"))
                .set("I10", Table.line("I10", "P3", "NONE", "-", "NA", "NA"))
                .set("R12", inMemory("R12", "x.Y#nowhere")),
            TraceabilityCheck.Mode.STANDBY);
    assertThat(result.problems()).isEmpty();
    assertThat(result.closed()).isZero();
    assertThat(result.open()).isEqualTo(24);
    assertThat(result.deferred()).isEmpty();
  }

  @Test
  void aSkippedTestNeverCountsForTheNewLabels() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"><skipped message=\"no server\"/></testcase>");
    for (TraceabilityCheck.Mode mode :
        List.of(TraceabilityCheck.Mode.ALL, TraceabilityCheck.Mode.SKIP)) {
      var files = check(new Table().exited("P3").set("I1", realFiles("I1", "a.B#it")), mode);
      assertThat(files.problems())
          .as("files " + mode)
          .contains("NOT_PASSING:I1:a.B#it", "ROW_WITHOUT_EVIDENCE:I1");
      assertThat(files.closed()).isZero();
    }
    for (TraceabilityCheck.Mode mode :
        List.of(TraceabilityCheck.Mode.ALL, TraceabilityCheck.Mode.STANDBY)) {
      var standby = check(new Table().exited("P3").set("I10", realStandby("I10", "a.B#it")), mode);
      assertThat(standby.problems())
          .as("standby " + mode)
          .contains("NOT_PASSING:I10:a.B#it", "ROW_WITHOUT_EVIDENCE:I10");
      assertThat(standby.closed()).isZero();
    }
  }

  @Test
  void realFilesAndRealStandbyCountOnlyForI1AndI10() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    for (String id : List.of("I1", "I10")) {
      var files =
          check(
              new Table().exited("P3,P4").set(id, line(id, "P3,P4", "a.B#it", "REALFILES")),
              TraceabilityCheck.Mode.SKIP);
      assertThat(files.problems()).as(id + " files").isEmpty();
      assertThat(files.closed()).as(id + " files").isEqualTo(1);
      var standby =
          check(
              new Table().exited("P3,P4").set(id, line(id, "P3,P4", "a.B#it", "REALSTANDBY")),
              TraceabilityCheck.Mode.STANDBY);
      assertThat(standby.problems()).as(id + " standby").isEmpty();
      assertThat(standby.closed()).as(id + " standby").isEqualTo(1);
    }
    // R4 and R12 are ordinary rows: neither label counts for them, in any mode that reads it
    for (String id : List.of("R4", "R12")) {
      var files =
          check(
              new Table().exited("P3,P4").set(id, line(id, "P3,P4", "a.B#it", "REALFILES")),
              TraceabilityCheck.Mode.SKIP);
      assertThat(files.problems()).as(id + " files").containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
      assertThat(files.closed()).as(id + " files").isZero();
      var standby =
          check(
              new Table().exited("P3,P4").set(id, line(id, "P3,P4", "a.B#it", "REALSTANDBY")),
              TraceabilityCheck.Mode.STANDBY);
      assertThat(standby.problems())
          .as(id + " standby")
          .containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
      assertThat(standby.closed()).as(id + " standby").isZero();
      var all =
          check(
              new Table()
                  .exited("P3,P4")
                  .set(
                      id,
                      line(id, "P3,P4", "a.B#it", "REALFILES"),
                      line(id, "P3,P4", "a.B#it", "REALSTANDBY")));
      assertThat(all.problems()).as(id + " all").containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
    }
  }

  @Test
  void realFilesLinesNeverCloseI2OrI9() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    for (String id : List.of("I2", "I9")) {
      var alone =
          check(
              new Table().exited("P3").set(id, realFiles(id, "a.B#it")),
              TraceabilityCheck.Mode.SKIP);
      assertThat(alone.problems()).as(id + " alone").containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
      assertThat(alone.closed()).as(id + " alone").isZero();

      // with a real-store line next to it the row is deferred to the ledger job, never closed
      var withRealStore =
          check(
              new Table().exited("P3").set(id, realFiles(id, "a.B#it"), realStore(id, "a.B#gone")),
              TraceabilityCheck.Mode.SKIP);
      assertThat(withRealStore.problems()).as(id + " deferred").isEmpty();
      assertThat(withRealStore.deferred()).containsExactly("REALSTORE_DEFERRED:" + id);
      assertThat(withRealStore.closed()).isZero();

      var standby =
          check(
              new Table().exited("P3").set(id, realStandby(id, "a.B#it")),
              TraceabilityCheck.Mode.STANDBY);
      assertThat(standby.problems())
          .as(id + " standby")
          .containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
      assertThat(standby.closed()).isZero();
    }
  }

  @Test
  void anI2RowWithOnlyARealFilesLineStaysOpenOrIsAProblem() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    String files = realFiles("I2", "a.B#it");
    var notDue = check(new Table().set("I2", files).exited("none"), TraceabilityCheck.Mode.SKIP);
    assertThat(notDue.problems()).isEmpty();
    assertThat(notDue.open()).isEqualTo(24);
    assertThat(notDue.closed()).isZero();
    for (TraceabilityCheck.Mode mode :
        List.of(TraceabilityCheck.Mode.ALL, TraceabilityCheck.Mode.SKIP)) {
      var due = check(new Table().exited("P3").set("I2", files), mode);
      assertThat(due.problems()).as(mode.name()).containsExactly("ROW_WITHOUT_EVIDENCE:I2");
    }
  }

  @Test
  void anI1RowWithOnlyARealFilesLineIsAProblemInOnlyMode() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    var only =
        check(
            new Table().exited("P3").set("I1", realFiles("I1", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(only.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I1");
    assertThat(only.closed()).isZero();
    var i10 =
        check(
            new Table().exited("P3,P4").set("I10", line("I10", "P3,P4", "a.B#it", "REALFILES")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(i10.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I10");
    // the same table passes where the real-files line is evaluated
    var skip =
        check(
            new Table().exited("P3").set("I1", realFiles("I1", "a.B#it")),
            TraceabilityCheck.Mode.SKIP);
    assertThat(skip.problems()).isEmpty();
    assertThat(skip.closed()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- REALWORM (P3b2, T6)

  private static String realWorm(String id, String ref) {
    return line(id, "P3", ref, "REALWORM");
  }

  @Test
  void realWormLinesAreIgnoredInSkipOnlyAndStandbyModes() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    // a REALWORM line that cites a missing test is not looked up in SKIP, ONLY or STANDBY
    // and the passing line of the mode's own label closes the row
    Map<TraceabilityCheck.Mode, String> own =
        Map.of(
            TraceabilityCheck.Mode.SKIP, realFiles("I1", "a.B#it"),
            TraceabilityCheck.Mode.ONLY, realStore("I1", "a.B#it"),
            TraceabilityCheck.Mode.STANDBY, realStandby("I1", "a.B#it"));
    for (var entry : own.entrySet()) {
      var result =
          check(
              new Table().exited("P3").set("I1", realWorm("I1", "x.Y#nowhere"), entry.getValue()),
              entry.getKey());
      assertThat(result.problems()).as(entry.getKey().name()).isEmpty();
      assertThat(result.deferred()).as(entry.getKey().name()).isEmpty();
      assertThat(result.closed()).as(entry.getKey().name()).isEqualTo(1);
    }
    // SKIP defers a due row whose only non-interim line is a REALWORM line
    for (String id : List.of("I1", "I2", "I9", "I10")) {
      var deferred =
          check(
              new Table().exited("P3").set(id, realWorm(id, "x.Y#nowhere"), inMemory(id, "a.B#it")),
              TraceabilityCheck.Mode.SKIP);
      assertThat(deferred.problems()).as(id).isEmpty();
      assertThat(deferred.deferred()).as(id).containsExactly("REALSTORE_DEFERRED:" + id);
      assertThat(deferred.closed()).as(id).isZero();
    }
    // ONLY needs a REALSTORE line for I1, STANDBY has no REALSTANDBY line: a REALWORM line is
    // neither
    var only =
        check(
            new Table().exited("P3").set("I1", realWorm("I1", "a.B#it")),
            TraceabilityCheck.Mode.ONLY);
    assertThat(only.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I1");
    var standby =
        check(
            new Table().exited("P3").set("I1", realWorm("I1", "a.B#it")),
            TraceabilityCheck.Mode.STANDBY);
    assertThat(standby.problems()).isEmpty();
    assertThat(standby.closed()).isZero();
  }

  @Test
  void realWormLinesAreEvaluatedOnlyInAllAndWormModes() throws IOException {
    report(
        "<testcase classname=\"a.B\" name=\"worm()\"/>"
            + "<testcase classname=\"a.B\" name=\"bad\"><failure/></testcase>");
    // WORM does not look at the other labels
    var passed =
        check(
            new Table()
                .exited("P3")
                .set(
                    "I1",
                    realWorm("I1", "a.B#worm"),
                    realStore("I1", "x.Y#nowhere"),
                    realFiles("I1", "x.Y#nowhere"),
                    realStandby("I1", "x.Y#nowhere"),
                    inMemory("I1", "x.Y#nowhere")),
            TraceabilityCheck.Mode.WORM);
    assertThat(passed.problems()).isEmpty();
    assertThat(passed.closed()).isEqualTo(1);
    assertThat(passed.deferred()).isEmpty();

    var failed =
        check(
            new Table().exited("P3").set("I1", realWorm("I1", "a.B#bad")),
            TraceabilityCheck.Mode.WORM);
    assertThat(failed.problems()).contains("NOT_PASSING:I1:a.B#bad", "ROW_WITHOUT_EVIDENCE:I1");
    var missing =
        check(
            new Table().exited("P3").set("I10", realWorm("I10", "a.B#gone")),
            TraceabilityCheck.Mode.WORM);
    assertThat(missing.problems())
        .contains("ARTEFACT_MISSING:I10:a.B#gone", "ROW_WITHOUT_EVIDENCE:I10");

    // mode ALL evaluates the line as well, and a passing one closes the row
    var all = check(new Table().exited("P3").set("I1", realWorm("I1", "a.B#gone")));
    assertThat(all.problems()).contains("ARTEFACT_MISSING:I1:a.B#gone");
    var allPassed = check(new Table().exited("P3").set("I1", realWorm("I1", "a.B#worm")));
    assertThat(allPassed.problems()).isEmpty();
    assertThat(allPassed.closed()).isEqualTo(1);
  }

  @Test
  void aDueI1OrI10RowWithoutAPassedRealWormLineIsAProblemInWormMode() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    for (String id : List.of("I1", "I10")) {
      var none =
          check(
              new Table().exited("P3").set(id, Table.line(id, "P3", "NONE", "-", "NA", "NA")),
              TraceabilityCheck.Mode.WORM);
      assertThat(none.problems()).as(id + " none").containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
      // the other labels do not stand in for it
      var others =
          check(
              new Table()
                  .exited("P3")
                  .set(
                      id,
                      realStore(id, "a.B#it"),
                      realFiles(id, "a.B#it"),
                      realStandby(id, "a.B#it"),
                      inMemory(id, "a.B#it")),
              TraceabilityCheck.Mode.WORM);
      assertThat(others.problems())
          .as(id + " others")
          .containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
      assertThat(others.closed()).as(id + " others").isZero();
      var passed =
          check(
              new Table().exited("P3").set(id, realWorm(id, "a.B#it")),
              TraceabilityCheck.Mode.WORM);
      assertThat(passed.problems()).as(id + " passed").isEmpty();
      assertThat(passed.closed()).as(id + " passed").isEqualTo(1);
    }
  }

  @Test
  void aRowThatIsNotDueIsNeverAProblemInWormMode() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    for (String id : List.of("I1", "I10")) {
      var empty =
          check(
              new Table().exited("none").set(id, Table.line(id, "P3", "NONE", "-", "NA", "NA")),
              TraceabilityCheck.Mode.WORM);
      assertThat(empty.problems()).as(id + " empty").isEmpty();
      assertThat(empty.open()).as(id + " empty").isEqualTo(24);
      var withLine =
          check(
              new Table().exited("none").set(id, realWorm(id, "a.B#it")),
              TraceabilityCheck.Mode.WORM);
      assertThat(withLine.problems()).as(id + " line").isEmpty();
      assertThat(withLine.closed()).as(id + " line").isZero();
      assertThat(withLine.open()).as(id + " line").isEqualTo(24);
    }
  }

  @Test
  void realWormCountsOnlyForI1AndI10() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    for (String id : List.of("I1", "I10")) {
      for (TraceabilityCheck.Mode mode :
          List.of(TraceabilityCheck.Mode.ALL, TraceabilityCheck.Mode.WORM)) {
        var result =
            check(
                new Table().exited("P3,P4").set(id, line(id, "P3,P4", "a.B#it", "REALWORM")), mode);
        assertThat(result.problems()).as(id + " " + mode).isEmpty();
        assertThat(result.closed()).as(id + " " + mode).isEqualTo(1);
      }
    }
    // an ordinary row and the other real-store rows: the line never counts; WORM leaves a due row
    // open, ALL reports it
    for (String id : List.of("R4", "R12", "I2", "I9")) {
      var all = check(new Table().exited("P3,P4").set(id, line(id, "P3,P4", "a.B#it", "REALWORM")));
      assertThat(all.problems()).as(id + " all").containsExactly("ROW_WITHOUT_EVIDENCE:" + id);
      assertThat(all.closed()).as(id + " all").isZero();
      var worm =
          check(
              new Table().exited("P3,P4").set(id, line(id, "P3,P4", "a.B#it", "REALWORM")),
              TraceabilityCheck.Mode.WORM);
      assertThat(worm.problems()).as(id + " worm").isEmpty();
      assertThat(worm.closed()).as(id + " worm").isZero();
      assertThat(worm.open()).as(id + " worm").isEqualTo(24);
    }
  }

  @Test
  void anI2RowWithOnlyARealWormLineStaysInterim() throws IOException {
    report("<testcase classname=\"a.B\" name=\"it()\"/>");
    String worm = realWorm("I2", "a.B#it");
    var notDue = check(new Table().set("I2", worm).exited("none"), TraceabilityCheck.Mode.SKIP);
    assertThat(notDue.problems()).isEmpty();
    assertThat(notDue.open()).isEqualTo(24);
    assertThat(notDue.closed()).isZero();
    var due = check(new Table().exited("P3").set("I2", worm));
    assertThat(due.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:I2");
    assertThat(due.closed()).isZero();
    // the build jobs defer it, the worm job leaves it open
    var skip = check(new Table().exited("P3").set("I2", worm), TraceabilityCheck.Mode.SKIP);
    assertThat(skip.problems()).isEmpty();
    assertThat(skip.deferred()).containsExactly("REALSTORE_DEFERRED:I2");
    var wormMode = check(new Table().exited("P3").set("I2", worm), TraceabilityCheck.Mode.WORM);
    assertThat(wormMode.problems()).isEmpty();
    assertThat(wormMode.closed()).isZero();
    assertThat(wormMode.open()).isEqualTo(24);
  }

  @Test
  void aSkippedTestNeverCountsForRealWorm() throws IOException {
    report(
        "<testcase classname=\"a.B\" name=\"it()\"><skipped message=\"no service\"/></testcase>");
    for (TraceabilityCheck.Mode mode :
        List.of(TraceabilityCheck.Mode.ALL, TraceabilityCheck.Mode.WORM)) {
      for (String id : List.of("I1", "I10")) {
        var result = check(new Table().exited("P3").set(id, realWorm(id, "a.B#it")), mode);
        assertThat(result.problems())
            .as(id + " " + mode)
            .contains("NOT_PASSING:" + id + ":a.B#it", "ROW_WITHOUT_EVIDENCE:" + id);
        assertThat(result.closed()).as(id + " " + mode).isZero();
      }
    }
  }

  @Test
  void realWormLineOfAFakeBackedClassIsAProblem() throws IOException {
    String fake = "dev.regpack.evidence.s3.WormEvidenceStoreContractTest";
    report(
        "<testcase classname=\""
            + fake
            + "\" name=\"it()\"/><testcase classname=\"a.B\" name=\"it()\"/>");
    // the fake is never REALWORM evidence, even when the test passes and in every mode
    for (TraceabilityCheck.Mode mode : TraceabilityCheck.Mode.values()) {
      var result = check(new Table().exited("P3").set("I1", realWorm("I1", fake + "#it")), mode);
      assertThat(result.problems())
          .as(mode.name())
          .contains("REALWORM_FAKE_BACKED:I1:" + fake + "#it");
    }
    var worm =
        check(
            new Table().exited("P3").set("I1", realWorm("I1", fake + "#it")),
            TraceabilityCheck.Mode.WORM);
    assertThat(worm.closed()).isZero();
    // a class that does not end in Test is not flagged by the table check
    var integration =
        check(
            new Table().exited("P3").set("I1", realWorm("I1", "a.B#it")),
            TraceabilityCheck.Mode.WORM);
    assertThat(integration.problems()).isEmpty();
  }

  /** The repository root: the nearest directory above the working directory with the table. */
  private static Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isRegularFile(dir.resolve("docs/traceability.md"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repository root with docs/traceability.md").isNotNull();
    return dir;
  }

  private static Optional<Path> sourceOf(Path root, String qualifiedName) throws IOException {
    String suffix = "/" + qualifiedName.replace('.', '/') + ".java";
    try (var files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                String s = root.relativize(p).toString().replace('\\', '/');
                return !s.startsWith(".git/")
                    && !s.contains("/target/")
                    && !s.contains("node_modules/")
                    && ("/" + s).endsWith(suffix);
              })
          .findFirst();
    }
  }

  private static boolean declaresMethod(Path root, Path file, String method, int depth)
      throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8);
    if (Pattern.compile("\\bvoid\\s+" + Pattern.quote(method) + "\\s*\\(").matcher(text).find()) {
      return true;
    }
    Matcher base =
        Pattern.compile("\\bclass\\s+\\w+(?:<[^{]*>)?\\s+extends\\s+(\\w+)").matcher(text);
    if (depth == 0 || !base.find()) {
      return false;
    }
    String name = base.group(1) + ".java";
    Path module = root.resolve(root.relativize(file).getName(0));
    List<Path> places =
        List.of(
            module.resolve("src/test/java"),
            module.resolve("src/main/java"),
            root.resolve("regpack-testkit/src/main/java"));
    for (Path place : places) {
      if (!Files.isDirectory(place)) {
        continue;
      }
      try (var files = Files.walk(place)) {
        Optional<Path> found =
            files.filter(p -> p.getFileName().toString().equals(name)).findFirst();
        if (found.isPresent() && declaresMethod(root, found.get(), method, depth - 1)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The problems of the REALWORM lines of a table with respect to the sources under the root. */
  private static List<String> realWormSourceProblems(String markdown, Path root)
      throws IOException {
    List<String> problems = new ArrayList<>();
    for (String raw : markdown.split("\\R")) {
      String text = raw.strip();
      if (!text.startsWith("|")) {
        continue;
      }
      String[] cells = text.substring(1).split("\\|", -1);
      if (cells.length < 5
          || !cells[4].strip().equals("REALWORM")
          || !cells[2].strip().equals("TEST")) {
        continue;
      }
      String ref = cells[3].strip();
      int hash = ref.indexOf('#');
      String type = hash < 0 ? ref : ref.substring(0, hash);
      String method = hash < 0 ? "" : ref.substring(hash + 1).replaceAll("\\(\\)$", "");
      if (!type.substring(type.lastIndexOf('.') + 1).endsWith("IT")) {
        problems.add("NOT_AN_IT:" + ref);
      }
      Optional<Path> source = sourceOf(root, type);
      if (source.isEmpty()) {
        problems.add("NO_SOURCE:" + ref);
        continue;
      }
      if (!Files.readString(source.get(), StandardCharsets.UTF_8).contains("@Tag(\"worm\")")) {
        problems.add("NO_WORM_TAG:" + ref);
      }
      if (!declaresMethod(root, source.get(), method, 10)) {
        problems.add("NO_METHOD:" + ref);
      }
    }
    return problems;
  }

  @Test
  void everyRealWormLineNamesATestMethodThatExistsInTheSources() throws IOException {
    // the helper has teeth: each fault of a synthetic table is found, a sound line is not
    write(
        "regpack-x/src/test/java/a/GoodIT.java",
        "package a;\n@Tag(\"worm\")\nclass GoodIT extends BaseContract { @Test void own() {} }\n");
    write(
        "regpack-x/src/test/java/a/BaseContract.java",
        "package a;\nabstract class BaseContract { @Test void inherited() {} }\n");
    write(
        "regpack-x/src/test/java/a/UntaggedIT.java",
        "package a;\nclass UntaggedIT { @Test void own() {} }\n");
    write(
        "regpack-x/src/test/java/a/FakeTest.java",
        "package a;\n@Tag(\"worm\")\nclass FakeTest { @Test void own() {} }\n");
    write(
        "regpack-y/src/test/java/a/OtherIT.java",
        "package a;\n@Tag(\"worm\")\nclass OtherIT extends BaseContract { }\n");
    Table fixture =
        new Table()
            .exited("P3")
            .set(
                "I1",
                realWorm("I1", "a.GoodIT#own"),
                realWorm("I1", "a.GoodIT#inherited"),
                realWorm("I1", "a.GoodIT#missing"),
                realWorm("I1", "a.UntaggedIT#own"),
                realWorm("I1", "a.FakeTest#own"),
                realWorm("I1", "a.NoSuchIT#own"),
                realWorm("I1", "a.OtherIT#inherited"),
                realStore("I1", "a.NoSuchClass#ignored"));
    assertThat(realWormSourceProblems(fixture.render(), repo))
        .containsExactly(
            "NO_METHOD:a.GoodIT#missing",
            "NO_WORM_TAG:a.UntaggedIT#own",
            "NOT_AN_IT:a.FakeTest#own",
            "NO_SOURCE:a.NoSuchIT#own",
            "NO_METHOD:a.OtherIT#inherited");

    // the real table: vacuous until the REALWORM lines of T10 and T11 exist
    Path root = repositoryRoot();
    String table = Files.readString(root.resolve("docs/traceability.md"), StandardCharsets.UTF_8);
    assertThat(realWormSourceProblems(table, root)).isEmpty();
  }

  @Test
  void planPatternAcceptsP3aToP3d() {
    String line = Table.line("R1", "P3a,P3d", "NONE", "-", "NA", "NA");
    var accepted = check(new Table().exited("P3a,P3b,P3c,P3d,P2a,P1a").set("R1", line));
    assertThat(accepted.problems()).containsExactly("ROW_WITHOUT_EVIDENCE:R1");
    var open = check(new Table().exited("P3a").set("R1", line));
    assertThat(open.problems()).isEmpty();
    var bad =
        check(
            new Table().exited("P3e").set("R1", Table.line("R1", "P3e", "NONE", "-", "NA", "NA")));
    assertThat(bad.problems()).contains("BAD_PLAN:exited_plans:P3e", "BAD_PLAN:R1:P3e");
  }

  @Test
  void defaultModeIsUnchanged() throws IOException {
    Table table = new Table().exited("P3").set("I2", realStore("I2", "a.B#it"));
    var implicit = TraceabilityCheck.check(table.render(), repo, List.of(repo));
    var explicit = check(table, TraceabilityCheck.Mode.ALL);
    assertThat(implicit).isEqualTo(explicit);
    assertThat(implicit.problems())
        .contains("ARTEFACT_MISSING:I2:a.B#it", "ROW_WITHOUT_EVIDENCE:I2");
    assertThat(implicit.deferred()).isEmpty();
    // the new labels change nothing for the old modes: ALL evaluates every line of every label
    Table labels =
        new Table()
            .exited("P3")
            .set(
                "I1",
                realFiles("I1", "a.B#files"),
                realStandby("I1", "a.B#standby"),
                realWorm("I1", "a.B#worm"),
                realStore("I1", "a.B#it"));
    var allLabels = TraceabilityCheck.check(labels.render(), repo, List.of(repo));
    assertThat(allLabels.problems())
        .contains(
            "ARTEFACT_MISSING:I1:a.B#files",
            "ARTEFACT_MISSING:I1:a.B#standby",
            "ARTEFACT_MISSING:I1:a.B#worm",
            "ARTEFACT_MISSING:I1:a.B#it");
    assertThat(allLabels).isEqualTo(check(labels, TraceabilityCheck.Mode.ALL));
    var fourArguments = new TraceabilityCheck.Result(List.of("p"), 1, 2, 3);
    assertThat(fourArguments.deferred()).isEmpty();
  }

  @Test
  void reviewFileNeedsOwnerAndPassStatus() throws IOException {
    String review = Table.line("R14", "P0", "REVIEW", "docs/r14.md", "NA", "NA");
    write("docs/r14.md", "# R14\nowner: hans\nstatus: PENDING\n");
    var due = check(new Table().exited("P0").set("R14", review));
    assertThat(due.problems())
        .containsExactly("NOT_PASSING:R14:docs/r14.md", "ROW_WITHOUT_EVIDENCE:R14");
    // PENDING is tolerated only while the row is open, even next to evidence that counts
    report("<testcase classname=\"a.B\" name=\"m\"/>");
    var dueWithOtherEvidence =
        check(
            new Table()
                .exited("P0")
                .set("R14", Table.line("R14", "P0", "TEST", "a.B#m", "UNIT", "BUILD"), review));
    assertThat(dueWithOtherEvidence.problems()).containsExactly("NOT_PASSING:R14:docs/r14.md");
    assertThat(dueWithOtherEvidence.closed()).isEqualTo(1);
    var open = check(new Table().set("R14", review));
    assertThat(open.problems()).isEmpty();
    assertThat(open.closed()).isZero();

    write("docs/r14.md", "# R14\nowner: hans\nstatus: FAIL\n");
    assertThat(check(new Table().set("R14", review)).problems())
        .contains("NOT_PASSING:R14:docs/r14.md");
    write("docs/r14.md", "# R14\nowner: hans\n");
    assertThat(check(new Table().set("R14", review)).problems())
        .contains("NOT_PASSING:R14:docs/r14.md");
    write("docs/r14.md", "# R14\nowner:   \nstatus: PASS\n");
    var noOwner = check(new Table().exited("P0").set("R14", review));
    assertThat(noOwner.problems())
        .containsExactly("NO_OWNER:R14:docs/r14.md", "ROW_WITHOUT_EVIDENCE:R14");
    assertThat(noOwner.ok()).isFalse();
    write("docs/r14.md", "# R14\nowner: hans\nstatus: PASS\n");
    var pass = check(new Table().exited("P0").set("R14", review));
    assertThat(pass.problems()).isEmpty();
    assertThat(pass.closed()).isEqualTo(1);
    var missing = Table.line("R14", "P0", "REVIEW", "docs/nope.md", "NA", "NA");
    assertThat(check(new Table().set("R14", missing)).problems())
        .contains("ARTEFACT_MISSING:R14:docs/nope.md");
  }
}
