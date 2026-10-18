package dev.regpack.pack.csv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.CitationRole;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableParser;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.yaml.StrictYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The canonical table YAML (companion section 5.7; plan task T21). */
class TableYamlWriterTest {
  private static DecisionTable parse(String path, String yaml) {
    List<PackProblem> problems = new ArrayList<>();
    DecisionTable table = TableParser.parse(path, StrictYaml.parse(yaml), problems).orElse(null);
    assertThat(problems).isEmpty();
    assertThat(table).isNotNull();
    return table;
  }

  private static TableRow withWhen(TableRow r, String when) {
    return new TableRow(
        r.id(),
        r.node(),
        r.priority(),
        when,
        r.outcome(),
        r.reason(),
        r.ruleRef(),
        r.cite(),
        r.rationale(),
        r.valid(),
        r.set(),
        r.legs());
  }

  private static DecisionTable withFirstWhen(DecisionTable base, String when) {
    List<TableRow> rows = new ArrayList<>(base.rows());
    rows.set(0, withWhen(rows.get(0), when));
    return new DecisionTable(base.path(), base.id(), base.inputs(), base.catchAllRowId(), rows);
  }

  @Test
  void writerOutputParsesToTheSameTable() {
    DecisionTable table = RandomTables.sample();

    String yaml = TableYamlWriter.write(table);

    assertThat(parse(table.path(), yaml)).isEqualTo(table);
    assertThat(yaml)
        .startsWith("table: {id: sample.s0.scope, hit_policy: FIRST, priority: explicit,");
    assertThat(yaml)
        .contains("rows:\n  - id: S-N03-novation\n    node: N03\n    priority: 300\n    when: \"");
    assertThat(yaml).endsWith("\n").doesNotContain("\r");
  }

  @Test
  void writerIsIdempotent() {
    DecisionTable table = RandomTables.sample();
    String once = TableYamlWriter.write(table);

    String twice = TableYamlWriter.write(parse(table.path(), once));

    assertThat(twice.getBytes(UTF_8)).isEqualTo(once.getBytes(UTF_8));
  }

  @Test
  void quotingRules() {
    String[] whens = {
      " say \"hi\" \\ back ", // quote, backslash, leading and trailing space
      "a: b # c",
      "!x == 1",
      "*y",
      "&z",
      "|p",
      ">q",
      "%r",
      "? s",
      "'single' and [1, 2] and {a: b}",
      "- dash",
      "# not a comment",
      "true",
      "é日 , 𠀀"
    };
    DecisionTable base = RandomTables.sample();
    for (String when : whens) {
      DecisionTable table = withFirstWhen(base, when);
      String yaml = TableYamlWriter.write(table);
      assertThat(parse(table.path(), yaml)).as(when).isEqualTo(table);
      assertThat(TableYamlWriter.write(parse(table.path(), yaml))).as(when).isEqualTo(yaml);
    }
    String yaml = TableYamlWriter.write(withFirstWhen(base, " say \"hi\" \\ back "));
    assertThat(yaml).contains("    when: \" say \\\"hi\\\" \\\\ back \"\n");
  }

  @Test
  void valuesThatAreNotPlainSafeAreQuoted() {
    DecisionTable base = RandomTables.sample();
    TableRow r = base.rows().get(0);
    TableRow odd =
        new TableRow(
            r.id(),
            r.node(),
            r.priority(),
            r.when(),
            r.outcome(),
            r.reason(),
            r.ruleRef(),
            List.of(
                new CitationRef("sample-law", "a:b", CitationRole.BASIS),
                new CitationRef("sample-law", "-x", CitationRole.BASIS),
                new CitationRef("sample-law", "p/q.r_s-1", CitationRole.EXCEPTION)),
            r.rationale(),
            r.valid(),
            Map.of("n", "-5", "m", "a:b", "k", "x/y"),
            r.legs());
    List<TableRow> rows = new ArrayList<>(base.rows());
    rows.set(0, odd);
    DecisionTable table =
        new DecisionTable(base.path(), base.id(), base.inputs(), base.catchAllRowId(), rows);

    String yaml = TableYamlWriter.write(table);

    assertThat(yaml).contains("provision_path: \"a:b\"").contains("provision_path: \"-x\"");
    assertThat(yaml).contains("provision_path: p/q.r_s-1");
    assertThat(yaml).contains("set: {k: x/y, m: \"a:b\", n: \"-5\"}");
    assertThat(parse(table.path(), yaml)).isEqualTo(table);
  }

  @Test
  void commentsAreNotPreserved() {
    DecisionTable table = RandomTables.sample();
    String canonical = TableYamlWriter.write(table);
    String commented =
        "# a header comment\n"
            + canonical
                .replace("rows:\n", "rows:   # the rows\n")
                .replace("    node: N03\n", "    node: N03   # the node\n")
            + "# a trailing comment\n";
    assertThat(commented).contains("# the rows").contains("# the node");

    DecisionTable parsed = parse(table.path(), commented);

    assertThat(parsed).isEqualTo(table);
    assertThat(TableYamlWriter.write(parsed)).isEqualTo(canonical).doesNotContain("# ");
  }

  @Test
  void writerRefusesACellItCannotRepresent() {
    DecisionTable base = RandomTables.sample();
    for (String bad : List.of("a\nb", "a\tb", "a\rb", "a\u202Eb")) {
      DecisionTable table = withFirstWhen(base, bad);
      assertThatThrownBy(() -> TableYamlWriter.write(table))
          .isInstanceOf(IllegalArgumentException.class);
    }
    DecisionTable longLine = withFirstWhen(base, "x".repeat(9000));
    assertThatThrownBy(() -> TableYamlWriter.write(longLine))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSamplePackTableIsInTheCanonicalLayout() throws IOException {
    Path file =
        Path.of(System.getProperty("regpack.repo.root"))
            .resolve("packs/sample.s0/scope/sample-scope.yaml");
    String committed = Files.readString(file, UTF_8);

    DecisionTable table = parse("scope/sample-scope.yaml", committed);

    assertThat(TableYamlWriter.write(table)).isEqualTo(committed);
  }
}
