package dev.regpack.pack.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.CitationRole;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.yaml.StrictYaml;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The derive table file: the typed model and the reader (plan W1a, task T5, decision D-W5). */
class DeriveTableTest {
  private static final String PATH = "derive/report.yaml";
  private static final String CITE =
      "cite: [{source_id: fixture-law, provision_path: fixture/1, role: BASIS}]";

  private static String row(String id, String field, String rest) {
    return "  - {id: "
        + id
        + ", field: "
        + field
        + ", value: \"firm.lei\", rule_ref: DR-"
        + id
        + ", "
        + CITE
        + rest
        + "}\n";
  }

  private static String file(String rows) {
    return "table: {id: derive.fixture.report}\nrows:\n" + rows;
  }

  private static List<PackProblem> problemsOf(String text) {
    ProblemSink sink = ProblemSink.forFile();
    Optional<DeriveTable> table = DeriveTable.parse(PATH, StrictYaml.parse(text), sink);
    assertThat(table.isPresent())
        .as("a table exactly when there is no problem")
        .isEqualTo(sink.isEmpty());
    return sink.sorted(PATH);
  }

  private static List<String> codes(String text) {
    return problemsOf(text).stream().map(PackProblem::code).toList();
  }

  @Test
  void parsesRowsAndRefusesUnknownKeysDuplicateFieldsAndBadIds() {
    ProblemSink sink = ProblemSink.forFile();
    String good =
        file(
            row("D1", "executing_lei", "") + row("D2", "buyer", ", when: \"event.kind == 'NEW'\""));

    DeriveTable table = DeriveTable.parse(PATH, StrictYaml.parse(good), sink).orElseThrow();

    assertThat(sink.isEmpty()).isTrue();
    assertThat(table.path()).isEqualTo(PATH);
    assertThat(table.id()).isEqualTo("derive.fixture.report");
    assertThat(table.rows()).extracting(DeriveTable.Row::id).containsExactly("D1", "D2");
    DeriveTable.Row first = table.rows().get(0);
    assertThat(first.field()).isEqualTo("executing_lei");
    assertThat(first.when()).as("when defaults to true").isEqualTo("true");
    assertThat(first.value()).isEqualTo("firm.lei");
    assertThat(first.ruleRef()).isEqualTo("DR-D1");
    assertThat(first.cite())
        .containsExactly(new CitationRef("fixture-law", "fixture/1", CitationRole.BASIS));
    assertThat(table.rows().get(1).when()).isEqualTo("event.kind == 'NEW'");

    // an unknown key, in a row and in the file
    assertThat(codes(file(row("D1", "a_field", ", extra: x"))))
        .containsExactly("DERIVE_KEY_UNKNOWN");
    assertThat(codes(good + "other: 1\n")).containsExactly("DERIVE_KEY_UNKNOWN");
    // a missing key
    assertThat(codes("table: {id: derive.fixture.report}\nrows:\n  - {id: D1, field: a_field}\n"))
        .contains("DERIVE_KEY_MISSING");
    // a duplicate field and a duplicate row id
    assertThat(codes(file(row("D1", "same_field", "") + row("D2", "same_field", ""))))
        .containsExactly("DERIVE_FIELD_DUPLICATE");
    assertThat(codes(file(row("D1", "one", "") + row("D1", "two", ""))))
        .contains("DERIVE_ROW_ID_DUPLICATE");
    // a bad field name, row id, table id and rule_ref
    assertThat(codes(file(row("D1", "Upper", "")))).containsExactly("DERIVE_FIELD_INVALID");
    assertThat(codes(file(row("D1", "1abc", "")))).containsExactly("DERIVE_FIELD_INVALID");
    assertThat(codes(file(row("D1", "a".repeat(65), "")))).containsExactly("DERIVE_FIELD_INVALID");
    assertThat(codes(file(row("1D", "a_field", "")))).containsExactly("DERIVE_ROW_ID_INVALID");
    assertThat(codes(good.replace("derive.fixture.report", "9bad")))
        .containsExactly("DERIVE_TABLE_ID_INVALID");
    assertThat(
            codes(
                file(
                    "  - {id: D1, field: a_field, value: \"firm.lei\", rule_ref: \"bad ref\", "
                        + CITE
                        + "}\n")))
        .containsExactly("DERIVE_RULE_REF_INVALID");
  }

  @Test
  void refusesEmptyTextsMissingCitationsAndBadShapes() {
    assertThat(codes(file(row("D1", "a_field", ", when: \"  \""))))
        .containsExactly("DERIVE_WHEN_EMPTY");
    assertThat(
            codes(
                file("  - {id: D1, field: a_field, value: \"\", rule_ref: DR-1, " + CITE + "}\n")))
        .containsExactly("DERIVE_VALUE_EMPTY");
    assertThat(
            codes(
                file(
                    "  - {id: D1, field: a_field, value: \"firm.lei\", rule_ref: DR-1, cite: []}\n")))
        .containsExactly("DERIVE_CITE_EMPTY");
    assertThat(
            codes(
                file(
                    "  - {id: D1, field: a_field, value: \"firm.lei\", rule_ref: DR-1, cite: [{source_id: x, provision_path: p, role: NOPE}]}\n")))
        .containsExactly("DERIVE_CITE_INVALID");
    assertThat(codes("table: {id: derive.fixture.report}\nrows: []\n"))
        .containsExactly("DERIVE_ROWS_EMPTY");
    assertThat(codes("- a\n- b\n")).containsExactly("DERIVE_SHAPE");
    assertThat(codes("table: x\nrows:\n  - nothing\n")).contains("DERIVE_SHAPE");
    assertThat(
            codes(
                file(
                    "  - {id: D1, field: a_field, value: \"firm\\n.lei\", rule_ref: DR-1, "
                        + CITE
                        + "}\n")))
        .containsExactly("DERIVE_BAD_CHARACTER");
  }

  @Test
  void aProblemMessageNamesTheRowAndNeverEchoesAValueBeyondTheExcerpt() {
    List<PackProblem> problems = problemsOf(file(row("D1", "Upper" + "x".repeat(200), "")));

    assertThat(problems).hasSize(1);
    assertThat(problems.get(0).path()).isEqualTo(PATH);
    assertThat(problems.get(0).line()).isPositive();
    assertThat(problems.get(0).message().length())
        .isLessThanOrEqualTo(PackProblem.MAX_MESSAGE_CHARS);
    assertThat(problems.get(0).message()).doesNotContain("x".repeat(30));
  }
}
