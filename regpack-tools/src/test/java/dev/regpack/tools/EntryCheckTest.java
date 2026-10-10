package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntryCheckTest {
  private static final String WIRING =
      "# derived from spec 16 Entry clauses\n"
          + "\n"
          + "P2a\tSP-R\tREPORT_EXISTS\tgrammar freeze\n"
          + "P2a\tSP-G\tREPORT_EXISTS\tgrammar freeze\n"
          + "P3-schema-sizing\tSP-L\tSTATUS_PASS\tsizing\n"
          + "SP-E\tG3\tGATE_CLEARED\tno XSD before G3\n";

  @TempDir Path root;

  private void report(String id, String status, String evidence) throws IOException {
    write(
        "docs/spikes/" + id + ".md",
        "# "
            + id
            + " report\nspike: "
            + id
            + "\nstatus: "
            + status
            + "\nscope: full\nevidence_level: "
            + evidence
            + "\n\nbody\n\n---\n\nhand written\n");
  }

  private void write(String relative, String content) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private EntryCheck.Outcome check(String target) {
    return EntryCheck.check(target, EntryCheck.parse(WIRING), root);
  }

  private int gates(PrintStream out, PrintStream err, String... args) {
    return Gates.run(args, out, err);
  }

  @Test
  void parseSkipsCommentsAndBlankLines() {
    List<EntryCheck.Requirement> all = EntryCheck.parse(WIRING);
    assertThat(all).hasSize(4);
    assertThat(all.get(0))
        .isEqualTo(new EntryCheck.Requirement("P2a", "SP-R", "REPORT_EXISTS", "grammar freeze"));
  }

  @Test
  void parseRejectsMalformedRowsAndUnsafeIds() {
    assertThatThrownBy(() -> EntryCheck.parse("P2a\tSP-R\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EntryCheck.parse("P2a\tSP-R\tNOPE\tn\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EntryCheck.parse("P2a\t../x\tREPORT_EXISTS\tn\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EntryCheck.parse("P2a\tSP-R\tGATE_CLEARED\tn\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void p2aNeedsSpRAndSpG() throws IOException {
    EntryCheck.Outcome none = check("P2a");
    assertThat(none.satisfied()).isFalse();
    assertThat(none.lines())
        .containsExactly(
            "entry: P2a: SP-R REPORT_EXISTS MISSING", "entry: P2a: SP-G REPORT_EXISTS MISSING");
    report("SP-R", "PASS", "RAW");
    EntryCheck.Outcome half = check("P2a");
    assertThat(half.satisfied()).isFalse();
    assertThat(half.lines())
        .containsExactly(
            "entry: P2a: SP-R REPORT_EXISTS OK", "entry: P2a: SP-G REPORT_EXISTS MISSING");
    report("SP-G", "FAIL", "RAW");
    EntryCheck.Outcome both = check("P2a");
    assertThat(both.satisfied()).isTrue();
    assertThat(both.lines())
        .containsExactly("entry: P2a: SP-R REPORT_EXISTS OK", "entry: P2a: SP-G REPORT_EXISTS OK");
  }

  @Test
  void reportExistsAcceptsFailAndInconclusiveButNotPending() throws IOException {
    for (String status : List.of("PASS", "FAIL", "INCONCLUSIVE")) {
      report("SP-R", status, "RAW");
      assertThat(check("P2a").lines().get(0)).endsWith("SP-R REPORT_EXISTS OK");
    }
    report("SP-R", "PENDING", "RAW");
    assertThat(check("P2a").lines().get(0)).endsWith("SP-R REPORT_EXISTS NOT_PASS");
    report("SP-R", "NOT_RUN", "RAW");
    assertThat(check("P2a").lines().get(0)).endsWith("SP-R REPORT_EXISTS NOT_PASS");
  }

  @Test
  void summaryOnlyReportDoesNotSatisfy() throws IOException {
    report("SP-R", "PENDING", "SUMMARY");
    assertThat(check("P2a").lines().get(0)).endsWith("SP-R REPORT_EXISTS SUMMARY_ONLY");
    report("SP-R", "PASS", "SUMMARY");
    assertThat(check("P2a").lines().get(0)).endsWith("SP-R REPORT_EXISTS SUMMARY_ONLY");
    assertThat(check("P2a").satisfied()).isFalse();
  }

  @Test
  void aStatusLineInTheHandWrittenPartDoesNotCount() throws IOException {
    write(
        "docs/spikes/SP-R.md",
        "# SP-R report\nstatus: PENDING\nevidence_level: RAW\n\nbody\n\n---\n\nstatus: PASS\n");
    assertThat(check("P2a").lines().get(0)).endsWith("SP-R REPORT_EXISTS NOT_PASS");
  }

  @Test
  void statusPassNeedsPass() throws IOException {
    report("SP-L", "FAIL", "RAW");
    assertThat(check("P3-schema-sizing").lines())
        .containsExactly("entry: P3-schema-sizing: SP-L STATUS_PASS NOT_PASS");
    report("SP-L", "PASS", "SUMMARY");
    assertThat(check("P3-schema-sizing").lines())
        .containsExactly("entry: P3-schema-sizing: SP-L STATUS_PASS SUMMARY_ONLY");
    report("SP-L", "PASS", "RAW");
    EntryCheck.Outcome ok = check("P3-schema-sizing");
    assertThat(ok.satisfied()).isTrue();
    assertThat(ok.lines()).containsExactly("entry: P3-schema-sizing: SP-L STATUS_PASS OK");
  }

  @Test
  void speBlockedUntilG3Cleared() throws IOException {
    assertThat(check("SP-E").lines()).containsExactly("entry: SP-E: G3 GATE_CLEARED MISSING");
    write("docs/gates/G3.md", "# Gate G3\n\nstatus: PENDING\n");
    EntryCheck.Outcome pending = check("SP-E");
    assertThat(pending.satisfied()).isFalse();
    assertThat(pending.lines()).containsExactly("entry: SP-E: G3 GATE_CLEARED NOT_CLEARED");
    write(
        "docs/gates/G3.md",
        "# Gate G3\n\nstatus: CLEARED\ncleared_by: x\ncleared_on: 2026-01-01\n");
    EntryCheck.Outcome cleared = check("SP-E");
    assertThat(cleared.satisfied()).isTrue();
    assertThat(cleared.lines()).containsExactly("entry: SP-E: G3 GATE_CLEARED OK");
  }

  @Test
  void aClearedLineInTheGateHistoryDoesNotCount() throws IOException {
    write(
        "docs/gates/G3.md",
        "# Gate G3\n\nstatus: PENDING\n\n## History\n\n- note\nstatus: CLEARED\n");
    assertThat(check("SP-E").lines()).containsExactly("entry: SP-E: G3 GATE_CLEARED NOT_CLEARED");
  }

  @Test
  void unknownTargetExitsTwo() throws IOException {
    assertThatThrownBy(() -> check("P99")).isInstanceOf(IllegalArgumentException.class);
    write("docs/spikes/entry-wiring.tsv", WIRING);
    var outBytes = new ByteArrayOutputStream();
    var errBytes = new ByteArrayOutputStream();
    int rc =
        gates(
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8),
            "entry",
            "--target",
            "P99",
            "--root",
            root.toString());
    assertThat(rc).isEqualTo(2);
    assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("unknown target: P99");
    assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  @Test
  void theCommittedWiringCoversEveryTargetOfTheSpec() throws IOException {
    Path repo = Path.of(System.getProperty("regpack.repo.root"));
    List<EntryCheck.Requirement> all =
        EntryCheck.parse(Files.readString(repo.resolve("docs/spikes/entry-wiring.tsv")));
    for (String target :
        List.of(
            "P2a",
            "P3-schema-sizing",
            "P5-xsd-or-derived-content",
            "P7a-microbatch",
            "P8",
            "P12",
            "SP-E",
            "spec-freeze")) {
      assertThat(all).anyMatch(r -> r.target().equals(target));
    }
    assertThat(
            all.stream()
                .filter(r -> r.target().equals("spec-freeze"))
                .map(EntryCheck.Requirement::requires))
        .containsExactly("SP-R", "SP-G", "SP-L", "SP-X");
    assertThat(
            all.stream().filter(r -> r.target().equals("P8")).map(EntryCheck.Requirement::requires))
        .containsExactly("SP-S", "SP-E", "SP-X", "G3");
    assertThat(
            all.stream()
                .filter(r -> r.target().equals("P7a-microbatch"))
                .map(EntryCheck.Requirement::requires))
        .containsExactly("SP-L", "SP-M", "SP-X");
  }

  @Test
  void gatesEntryPrintsOneLinePerRequirementAndExitsOneUntilSatisfied() throws IOException {
    write("docs/spikes/entry-wiring.tsv", WIRING);
    report("SP-R", "PASS", "RAW");
    var outBytes = new ByteArrayOutputStream();
    var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    int rc =
        gates(
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            sink,
            "entry",
            "--target",
            "P2a",
            "--root",
            root.toString());
    assertThat(rc).isEqualTo(1);
    assertThat(outBytes.toString(StandardCharsets.UTF_8).lines().toList())
        .containsExactly(
            "entry: P2a: SP-R REPORT_EXISTS OK", "entry: P2a: SP-G REPORT_EXISTS MISSING");
    report("SP-G", "INCONCLUSIVE", "RAW");
    int okRc =
        gates(
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            sink,
            "entry",
            "--target",
            "P2a",
            "--root",
            root.toString());
    assertThat(okRc).isZero();
  }
}
