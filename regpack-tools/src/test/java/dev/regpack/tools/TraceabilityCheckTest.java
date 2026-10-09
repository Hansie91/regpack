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
