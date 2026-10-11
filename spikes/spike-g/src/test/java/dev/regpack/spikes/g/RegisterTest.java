package dev.regpack.spikes.g;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RegisterTest {
  private static final String HEADER =
      "| id | hypothesis | location | symptom | emir_need | proposed_fix | impact | severity |"
          + " status | owner_note |\n"
          + "|---|---|---|---|---|---|---|---|---|---|\n";

  private static String row(String id, String hypothesis, String severity, String status) {
    return row(id, hypothesis, severity, status, "-");
  }

  private static String row(
      String id, String hypothesis, String severity, String status, String fix) {
    return "| "
        + id
        + " | "
        + hypothesis
        + " | loc | symptom | need | "
        + fix
        + " | impact | "
        + severity
        + " | "
        + status
        + " | note |\n";
  }

  @Test
  void parsesColumnsAndRejectsUnknownStatus() {
    String markdown =
        "# Register\n\nSome prose.\n\n"
            + HEADER
            + row("BRK-01", "H1", "FIX_BEFORE_FREEZE", "OPEN")
            + row("BRK-02", "new", "P12_ONLY", "ACCEPTED")
            + "\nTrailing prose.\n";
    List<Register.Row> rows = Register.parse(markdown);

    assertThat(rows)
        .containsExactly(
            new Register.Row("BRK-01", "H1", "OPEN", "FIX_BEFORE_FREEZE", "-"),
            new Register.Row("BRK-02", "new", "ACCEPTED", "P12_ONLY", "-"));

    assertThatThrownBy(
            () -> Register.parse(HEADER + row("BRK-01", "H1", "FIX_BEFORE_FREEZE", "DONE")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status");
    assertThatThrownBy(() -> Register.parse(HEADER + row("BRK-01", "H1", "SERIOUS", "OPEN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("severity");
    assertThatThrownBy(
            () -> Register.parse(HEADER + row("BRK-01", "H11", "FIX_BEFORE_FREEZE", "OPEN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hypothesis");
    assertThatThrownBy(() -> Register.parse("| id | hypothesis |\n|---|---|\n| BRK-01 | H1 |\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("columns");
    assertThatThrownBy(() -> Register.parse(HEADER + "| BRK-01 | H1 | loc | symptom |\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cells");
    assertThatThrownBy(() -> Register.parse("no table here\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("table");
  }

  @Test
  void idsAreSequentialAndUnique() {
    assertThatThrownBy(
            () ->
                Register.parse(
                    HEADER
                        + row("BRK-01", "H1", "P12_ONLY", "OPEN")
                        + row("BRK-03", "H2", "P12_ONLY", "OPEN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BRK-02");
    assertThatThrownBy(
            () ->
                Register.parse(
                    HEADER
                        + row("BRK-01", "H1", "P12_ONLY", "OPEN")
                        + row("BRK-01", "H2", "P12_ONLY", "OPEN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
    assertThatThrownBy(() -> Register.parse(HEADER + row("BRK-02", "H1", "P12_ONLY", "OPEN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BRK-01");
    assertThatThrownBy(() -> Register.parse(HEADER + row("brk-1", "H1", "P12_ONLY", "OPEN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BRK-01");
  }

  @Test
  void idsDoNotDependOnTheDefaultLocale() {
    Locale saved = Locale.getDefault();
    try {
      // a locale whose default numbering system is not ASCII digits
      Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
      List<Register.Row> rows =
          Register.parse(
              HEADER
                  + row("BRK-01", "H1", "P12_ONLY", "OPEN")
                  + row("BRK-02", "H2", "P12_ONLY", "OPEN"));
      assertThat(rows).extracting(Register.Row::id).containsExactly("BRK-01", "BRK-02");
    } finally {
      Locale.setDefault(saved);
    }
  }

  @Test
  void openAndProposedCountAsOpen() {
    List<Register.Row> rows =
        Register.parse(
            HEADER
                + row("BRK-01", "H1", "P12_ONLY", "OPEN")
                + row("BRK-02", "H2", "P12_ONLY", "PROPOSED", "OLD<<a>> NEW<<b>>")
                + row("BRK-03", "H3", "P12_ONLY", "ACCEPTED")
                + row("BRK-04", "H4", "P12_ONLY", "REJECTED_NOT_NEEDED")
                + row("BRK-05", "H5", "P12_ONLY", "UNFIXABLE"));

    assertThat(Register.open(rows)).isEqualTo(2);
    assertThat(Register.open(List.of())).isZero();
  }

  @Test
  void unfixableIsCounted() {
    List<Register.Row> rows =
        Register.parse(
            HEADER
                + row("BRK-01", "H1", "BLOCKS_FREEZE", "UNFIXABLE")
                + row("BRK-02", "H2", "P12_ONLY", "OPEN")
                + row("BRK-03", "new", "P12_ONLY", "UNFIXABLE"));

    assertThat(Register.unfixable(rows)).isEqualTo(2);
    assertThat(Register.unfixable(List.of())).isZero();
  }

  @Test
  void escapedPipeStaysInTheCellAndAProposedRowNeedsFixText() {
    List<Register.Row> rows =
        Register.parse(
            HEADER
                + row(
                    "BRK-01",
                    "H1",
                    "FIX_BEFORE_FREEZE",
                    "PROPOSED",
                    "OLD<<REPORT \\| DO_NOT_REPORT>> NEW<<x>>"));
    assertThat(rows.get(0).proposedFix()).isEqualTo("OLD<<REPORT | DO_NOT_REPORT>> NEW<<x>>");

    assertThatThrownBy(
            () ->
                Register.parse(HEADER + row("BRK-01", "H1", "FIX_BEFORE_FREEZE", "PROPOSED", "-")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("proposed_fix");
  }
}
