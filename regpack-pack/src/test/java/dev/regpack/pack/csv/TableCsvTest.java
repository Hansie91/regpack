package dev.regpack.pack.csv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.TableChecks;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableParser;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.yaml.StrictYaml;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** CSV export and import of decision tables (companion section 5.7; plan task T21). */
class TableCsvTest {
  private static final String HEADER =
      "id,node,priority,when,outcome,reason,rule_ref,cite,rationale,valid_from,valid_to,set,legs";
  private static final String[] COLUMNS = HEADER.split(",", -1);

  /** The table header an import takes (the rows come from the CSV). */
  private static DecisionTable header() {
    return new DecisionTable("scope/t.yaml", "t.scope", List.of("event.kind"), "S-CA", List.of());
  }

  private static String[] scopeRow() {
    return new String[] {
      "S-1",
      "N01",
      "100",
      "event.kind == 'NEW'",
      "REPORT",
      "T.N01.X",
      "",
      "s#p#BASIS",
      "RAT-0001",
      "2018-01-03",
      "",
      "",
      "MARKET"
    };
  }

  private static String[] catchAllRow() {
    return new String[] {
      "S-CA",
      "DEF",
      "900",
      "true",
      "UNDETERMINED",
      "T.DEF.X",
      "",
      "s#p#BASIS",
      "RAT-0002",
      "",
      "",
      "",
      ""
    };
  }

  /** One CSV record with every cell quoted (so a cell may hold anything). */
  private static String record(String... cells) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append('"').append(cells[i].replace("\"", "\"\"")).append('"');
    }
    return out.append('\n').toString();
  }

  private static String csv(String[]... rows) {
    StringBuilder out = new StringBuilder(HEADER).append('\n');
    for (String[] row : rows) {
      out.append(record(row));
    }
    return out.toString();
  }

  private static List<String> codes(List<PackProblem> problems) {
    return problems.stream().map(PackProblem::code).toList();
  }

  private static Optional<DecisionTable> imported(String csv, List<PackProblem> problems) {
    return TableCsv.importRows(csv, header(), problems);
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void exportHasTheDocumentedHeaderAndAscendingRows() {
    String[] lines = TableCsv.export(RandomTables.sample()).split("\n", -1);

    assertThat(lines[0]).isEqualTo(HEADER);
    assertThat(lines).hasSize(5); // header, three rows, and the empty text after the last LF
    assertThat(lines[1])
        .isEqualTo(
            "S-N03-novation,N03,300,\"event.kind in ['NOVATION','ASSIGNMENT']\","
                + "DO_NOT_REPORT,SAMPLE.N03.EXCLUDED,RULE-0001,"
                + "sample-law#sample/1#BASIS;regpack-spec#D14#INTERPRETATION,"
                + "RAT-0001,2018-01-03,2030-01-01,a.b=x;action=NEWT,");
    assertThat(lines[2])
        .isEqualTo(
            "S-N08-equity-on-venue,N08,800,instrument.assetClass == 'EQUITY' && "
                + "instrument.tradedOnVenue,REPORT,SAMPLE.N08.IN_SCOPE,,sample-law#sample/2#BASIS,"
                + "RAT-0003,2018-01-03,,,MARKET;CLIENT");
    assertThat(lines[3])
        .isEqualTo(
            "S-DEFAULT-catch-all,DEFAULT,9999,true,UNDETERMINED,SAMPLE.DEFAULT.NO_RULE_HIT,,"
                + "regpack-spec#I5#GUIDANCE,RAT-0004,,,,");
    assertThat(lines[4]).isEmpty();
  }

  @Test
  void exportQuotesOnlyWhenNeeded() {
    DecisionTable base = RandomTables.sample();
    TableRow first = base.rows().get(0);
    List<TableRow> rows = new ArrayList<>(base.rows());
    String[] whens = {
      "plain ' # : [ ] { } text", // nothing here needs quotes
      "a,b",
      "say \"hi\"",
      " leading",
      "trailing ",
      "tab-free é日"
    };
    List<String> expected =
        List.of(
            "plain ' # : [ ] { } text",
            "\"a,b\"",
            "\"say \"\"hi\"\"\"",
            "\" leading\"",
            "\"trailing \"",
            "tab-free é日");
    for (int i = 0; i < whens.length; i++) {
      rows.set(0, withWhen(first, whens[i]));
      DecisionTable t =
          new DecisionTable(base.path(), base.id(), base.inputs(), base.catchAllRowId(), rows);
      String line = TableCsv.export(t).split("\n", -1)[1];
      String cells = line.substring(line.indexOf(",N03,300,") + ",N03,300,".length());
      assertThat(cells).startsWith(expected.get(i) + ",DO_NOT_REPORT,");
    }
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

  @Test
  void exportIsLfUtf8WithoutBom() {
    DecisionTable base = RandomTables.sample();
    List<TableRow> rows = new ArrayList<>(base.rows());
    rows.set(0, withWhen(base.rows().get(0), "café 日本"));
    String text =
        TableCsv.export(
            new DecisionTable(base.path(), base.id(), base.inputs(), base.catchAllRowId(), rows));

    byte[] bytes = text.getBytes(UTF_8);
    assertThat(text).doesNotContain("\r").doesNotContain("\uFEFF").endsWith("\n");
    assertThat(bytes[0]).isEqualTo((byte) 'i'); // the header, no BOM bytes EF BB BF
    assertThat(new String(bytes, UTF_8)).isEqualTo(text);
    assertThat(text).contains("café 日本");
  }

  @Test
  void importAcceptsCrlfBomAndAMissingFinalNewline() {
    DecisionTable table = RandomTables.sample();
    String text = "\uFEFF" + TableCsv.export(table).replace("\n", "\r\n");
    text = text.substring(0, text.length() - 2);
    assertThat(text).doesNotEndWith("\n");

    List<PackProblem> problems = new ArrayList<>();
    Optional<DecisionTable> result = TableCsv.importRows(text, asHeader(table), problems);

    assertThat(problems).isEmpty();
    assertThat(result).contains(table);
  }

  private static DecisionTable asHeader(DecisionTable table) {
    return new DecisionTable(
        table.path(), table.id(), table.inputs(), table.catchAllRowId(), List.of());
  }

  @Test
  void importAcceptsReorderedColumns() {
    List<PackProblem> normal = new ArrayList<>();
    Optional<DecisionTable> expected = imported(csv(scopeRow(), catchAllRow()), normal);
    assertThat(normal).isEmpty();
    assertThat(expected).isPresent();

    List<String> reversedHeader = new ArrayList<>(List.of(COLUMNS));
    Collections.reverse(reversedHeader);
    StringBuilder reversed = new StringBuilder(String.join(",", reversedHeader)).append('\n');
    for (String[] row : new String[][] {scopeRow(), catchAllRow()}) {
      List<String> cells = new ArrayList<>(List.of(row));
      Collections.reverse(cells);
      reversed.append(record(cells.toArray(String[]::new)));
    }

    List<PackProblem> problems = new ArrayList<>();
    assertThat(imported(reversed.toString(), problems)).isEqualTo(expected);
    assertThat(problems).isEmpty();
  }

  @Test
  void importRejectsUnknownMissingAndDuplicateColumns() {
    String unknown = HEADER + ",extra\n";
    String missing = HEADER.substring(0, HEADER.lastIndexOf(',')) + "\n";
    String duplicate = HEADER.substring(0, HEADER.lastIndexOf(',')) + ",id\n";
    String empty = "";

    for (String text : List.of(unknown, missing, duplicate, empty)) {
      List<PackProblem> problems = new ArrayList<>();
      assertThat(imported(text, problems)).isEmpty();
      assertThat(codes(problems)).as(text).contains("CSV_COLUMNS");
    }
  }

  @Test
  void importRejectsAnUnterminatedQuote() {
    String[] texts = {
      HEADER + "\n\"S-1,N01\n", // never closed
      HEADER + "\nS-1,N0\"1,100\n", // a quote inside an unquoted cell
      HEADER + "\n\"S-1\"x,N01\n" // text after the closing quote
    };
    for (String text : texts) {
      List<PackProblem> problems = new ArrayList<>();
      assertThat(imported(text, problems)).isEmpty();
      assertThat(codes(problems)).as(text).contains("CSV_QUOTE");
    }
  }

  @Test
  void importRejectsARowWithTheWrongNumberOfCells() {
    String[] fewer = Arrays.copyOf(scopeRow(), 12);
    String[] more = Arrays.copyOf(scopeRow(), 14);
    more[13] = "";
    for (String[] row : new String[][] {fewer, more}) {
      List<PackProblem> problems = new ArrayList<>();
      assertThat(imported(csv(row, catchAllRow()), problems)).isEmpty();
      assertThat(codes(problems)).contains("CSV_CELL_COUNT");
      assertThat(problems.get(0).line()).isEqualTo(2);
    }
  }

  @Test
  void importRefusesABlankLine() {
    String[] texts = {
      csv(scopeRow(), catchAllRow()) + "\n", // a second newline at the end
      csv(scopeRow()) + "\n" + record(catchAllRow()) // a blank line in the middle
    };
    int[] blankLines = {4, 3};
    for (int i = 0; i < texts.length; i++) {
      List<PackProblem> problems = new ArrayList<>();
      assertThat(imported(texts[i], problems)).as(texts[i]).isEmpty();
      assertThat(codes(problems)).as(texts[i]).containsExactly("CSV_CELL_COUNT");
      assertThat(problems.get(0).line()).as(texts[i]).isEqualTo(blankLines[i]);
    }
  }

  @Test
  void aStageOneProblemStopsTheLaterStages() {
    String[] wrongCount = Arrays.copyOf(scopeRow(), 12);
    String[] badNode = scopeRow().clone();
    badNode[0] = "S-2";
    badNode[1] = "n01"; // would be ROW_NODE_INVALID from the parser
    badNode[2] = "200";
    List<PackProblem> problems = new ArrayList<>();

    assertThat(imported(csv(wrongCount, badNode, catchAllRow()), problems)).isEmpty();

    assertThat(codes(problems)).containsExactly("CSV_CELL_COUNT");
  }

  @Test
  void exportedCellsNeverBeginWithAFormulaCharacter() {
    String[] lines = TableCsv.export(RandomTables.sample()).split("\n", -1);
    for (int i = 1; i < lines.length - 1; i++) {
      for (String cell : lines[i].split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1)) {
        String content = cell.startsWith("\"") ? cell.substring(1) : cell;
        assertThat(content)
            .as(cell)
            .doesNotStartWith("=")
            .doesNotStartWith("+")
            .doesNotStartWith("-")
            .doesNotStartWith("@");
      }
    }
  }

  @Test
  void importRunsTheSameTypingAndTableChecks() {
    DecisionTable table = RandomTables.sample();
    String exported = TableCsv.export(table);
    String yaml = TableYamlWriter.write(table);

    // a duplicate priority: found by the table checks
    List<PackProblem> csvProblems = new ArrayList<>();
    assertThat(
            TableCsv.importRows(
                exported.replace("N03,300,", "N03,800,"), asHeader(table), csvProblems))
        .isEmpty();
    List<PackProblem> yamlProblems = new ArrayList<>();
    DecisionTable fromYaml =
        TableParser.parse(
                table.path(),
                StrictYaml.parse(yaml.replace("priority: 300", "priority: 800")),
                yamlProblems)
            .orElseThrow();
    yamlProblems.addAll(TableChecks.check(fromYaml));
    assertThat(codes(csvProblems))
        .isEqualTo(codes(yamlProblems))
        .containsExactly("PRIORITY_DUPLICATE");

    // an unknown outcome: found by the typing of the parser
    csvProblems = new ArrayList<>();
    assertThat(
            TableCsv.importRows(
                exported.replace(",REPORT,", ",report,"), asHeader(table), csvProblems))
        .isEmpty();
    yamlProblems = new ArrayList<>();
    assertThat(
            TableParser.parse(
                table.path(),
                StrictYaml.parse(yaml.replace("outcome: REPORT", "outcome: report")),
                yamlProblems))
        .isEmpty();
    assertThat(codes(csvProblems))
        .isEqualTo(codes(yamlProblems))
        .containsExactly("OUTCOME_INVALID");
  }

  @Test
  void importReportsADuplicateRowIdOnce() {
    String[] second = scopeRow().clone();
    second[2] = "200";
    List<PackProblem> problems = new ArrayList<>();

    assertThat(imported(csv(scopeRow(), second, catchAllRow()), problems)).isEmpty();

    assertThat(codes(problems)).containsExactly("ROW_ID_DUPLICATE");
  }

  @Test
  void importProblemsCarryTheCsvLineOfTheRow() {
    String[] bad = scopeRow().clone();
    bad[1] = "n01"; // node must be upper case
    bad[2] = "200";
    bad[0] = "S-2";
    List<PackProblem> problems = new ArrayList<>();

    assertThat(imported(csv(scopeRow(), bad, catchAllRow()), problems)).isEmpty();

    assertThat(problems).hasSize(1);
    assertThat(problems.get(0).code()).isEqualTo("ROW_NODE_INVALID");
    assertThat(problems.get(0).line()).isEqualTo(3);
    assertThat(problems.get(0).path()).isEqualTo("scope/t.yaml");
  }

  @Test
  void importFlagsASpreadsheetFormulaInWhen() {
    for (String when : List.of("=1+1", "+x", "-x == 1", "@SUM(A1)", "  =x")) {
      String[] row = scopeRow().clone();
      row[3] = when;
      List<PackProblem> problems = new ArrayList<>();
      assertThat(imported(csv(row, catchAllRow()), problems)).isEmpty();
      assertThat(codes(problems)).as(when).containsExactly("WHEN_FORMULA_CHARACTER");
    }
  }

  @Test
  void importRefusesLfCrTabAndInvisibleCharactersInAnyCell() {
    String[] bad = {"\n", "\r", "\t", "\u202E", "\u200B", "\0"};
    String[] suffixed = {
      "S-1",
      "N01",
      "100",
      "x == 1",
      "REPORT",
      "T.N01.X",
      "R1",
      "s#p#BASIS",
      "RAT-0001",
      "2018-01-03",
      "2030-01-01",
      "a=b",
      "MARKET"
    };
    for (int column = 0; column < COLUMNS.length; column++) {
      for (String b : bad) {
        String[] row = scopeRow().clone();
        row[column] = suffixed[column] + b;
        // the other columns must be filled so that only the bad cell is wrong
        for (int c = 0; c < row.length; c++) {
          if (c != column && row[c].isEmpty()) {
            row[c] = suffixed[c];
          }
        }
        List<PackProblem> problems = new ArrayList<>();
        Optional<DecisionTable> result = imported(csv(row, catchAllRow()), problems);
        String what = COLUMNS[column] + " + U+" + Integer.toHexString(b.codePointAt(0));
        assertThat(result).as(what).isEmpty();
        assertThat(codes(problems)).as(what).contains("CELL_BAD_CHARACTER");
      }
    }
  }

  @Test
  void anEditedCellChangesOnlyThatRow() {
    DecisionTable table = RandomTables.sample();
    String edited =
        TableCsv.export(table).replace("instrument.tradedOnVenue", "instrument.tradedOnVenue2");
    List<PackProblem> problems = new ArrayList<>();

    DecisionTable after = TableCsv.importRows(edited, asHeader(table), problems).orElseThrow();

    assertThat(problems).isEmpty();
    String[] before = TableYamlWriter.write(table).split("\n", -1);
    String[] now = TableYamlWriter.write(after).split("\n", -1);
    assertThat(now).hasSameSizeAs(before);
    List<Integer> differing = new ArrayList<>();
    for (int i = 0; i < before.length; i++) {
      if (!before[i].equals(now[i])) {
        differing.add(i);
      }
    }
    assertThat(differing).hasSize(1);
    assertThat(now[differing.get(0)]).startsWith("    when: ").contains("tradedOnVenue2");
  }
}
