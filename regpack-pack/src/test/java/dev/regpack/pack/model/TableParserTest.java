package dev.regpack.pack.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.CitationRole;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Decision tables: the typed model and the parser (companion sections 2 and 5.3; plan task T7). */
class TableParserTest {
  private static final String PATH = "scope/scope.yaml";

  private static final String HEADER =
      "table: {id: sample.s0.scope, hit_policy: FIRST, priority: explicit, inputs: [event.kind],"
          + " catch_all: S-DEFAULT}";

  /** A complete scope row; a value of {@code null} in an override removes the key. */
  private static Map<String, String> row() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("id", "S-N03-novation");
    m.put("node", "N03");
    m.put("priority", "300");
    m.put("when", "\"event.kind in ['NOVATION','ASSIGNMENT']\"");
    m.put("outcome", "DO_NOT_REPORT");
    m.put("reason", "SAMPLE.N03.EXCLUDED");
    m.put("rule_ref", "RULE-0001");
    m.put("cite", "[{source_id: sample-law, provision_path: sample/1, role: BASIS}]");
    m.put("rationale", "RAT-0001");
    m.put("valid", "{legal: {from: 2018-01-03}}");
    m.put("set", "{action: NEWT}");
    return m;
  }

  private static Map<String, String> catchAll() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("id", "S-DEFAULT");
    m.put("node", "DEF");
    m.put("priority", "900");
    m.put("when", "\"true\"");
    m.put("outcome", "UNDETERMINED");
    m.put("reason", "SAMPLE.DEF.UNDETERMINED");
    m.put("cite", "[{source_id: sample-law, provision_path: sample/1, role: BASIS}]");
    m.put("rationale", "RAT-0009");
    return m;
  }

  private static Map<String, String> with(Map<String, String> base, String key, String value) {
    Map<String, String> m = new LinkedHashMap<>(base);
    if (value == null) {
      m.remove(key);
    } else {
      m.put(key, value);
    }
    return m;
  }

  private static String rowYaml(Map<String, String> row) {
    StringBuilder out = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> e : row.entrySet()) {
      out.append(first ? "  - " : "    ").append(e.getKey()).append(": ").append(e.getValue());
      out.append('\n');
      first = false;
    }
    return out.toString();
  }

  private static String document(String header, List<Map<String, String>> rows) {
    StringBuilder out = new StringBuilder(header).append("\nrows:\n");
    for (Map<String, String> r : rows) {
      out.append(rowYaml(r));
    }
    return out.toString();
  }

  private static final class Result {
    final Optional<DecisionTable> table;
    final List<PackProblem> problems = new ArrayList<>();

    Result(YamlNode root) {
      this.table = TableParser.parse(PATH, root, problems);
    }

    List<String> codes() {
      return problems.stream().map(PackProblem::code).toList();
    }
  }

  private static Result parse(String yaml) {
    return new Result(StrictYaml.parse(yaml));
  }

  private static Result parseRows(List<Map<String, String>> rows) {
    return parse(document(HEADER, rows));
  }

  /** The document of one scope row and the catch-all, with the first row changed. */
  private static Result parseWith(String key, String value) {
    return parseRows(List.of(with(row(), key, value), catchAll()));
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void parsesTheSampleTableShape() {
    Result r = parseRows(List.of(row(), catchAll()));

    assertThat(r.problems).isEmpty();
    DecisionTable t = r.table.orElseThrow();
    assertThat(t.path()).isEqualTo(PATH);
    assertThat(t.id()).isEqualTo("sample.s0.scope");
    assertThat(t.inputs()).containsExactly("event.kind");
    assertThat(t.catchAllRowId()).isEqualTo("S-DEFAULT");
    assertThat(t.rows()).hasSize(2);

    TableRow n03 = t.rows().get(0);
    assertThat(n03.id()).isEqualTo("S-N03-novation");
    assertThat(n03.node()).isEqualTo("N03");
    assertThat(n03.priority()).isEqualTo(300);
    assertThat(n03.when()).isEqualTo("event.kind in ['NOVATION','ASSIGNMENT']");
    assertThat(n03.outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(n03.reason()).isEqualTo(new ReasonCode("SAMPLE.N03.EXCLUDED"));
    assertThat(n03.ruleRef()).contains("RULE-0001");
    assertThat(n03.cite())
        .containsExactly(new CitationRef("sample-law", "sample/1", CitationRole.BASIS));
    assertThat(n03.rationale()).isEqualTo("RAT-0001");
    assertThat(n03.valid()).contains(new Validity(LocalDate.of(2018, 1, 3), Optional.empty()));
    assertThat(n03.set()).isEqualTo(Map.of("action", "NEWT"));
    assertThat(n03.legs()).isEmpty();

    TableRow last = t.rows().get(1);
    assertThat(last.id()).isEqualTo("S-DEFAULT");
    assertThat(last.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(last.ruleRef()).isEmpty();
    assertThat(last.valid()).isEmpty();
    assertThat(last.set()).isEmpty();
    assertThat(last.legs()).isEmpty();
  }

  @Test
  void aValidityWindowMayHaveAnEnd() {
    Result r = parseWith("valid", "{legal: {from: 2018-01-03, to: 2024-12-31}}");

    assertThat(r.problems).isEmpty();
    assertThat(r.table.orElseThrow().rows().get(0).valid())
        .contains(new Validity(LocalDate.of(2018, 1, 3), Optional.of(LocalDate.of(2024, 12, 31))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0300", "+5", "3e2", "\" 5\"", "99999999999", "0", "-1", "", "1_0"})
  void numbersAndDatesNeedTheirExactFormPriority(String priority) {
    String value = priority.isEmpty() ? "''" : priority;
    Result r = parseWith("priority", value);

    assertThat(r.codes()).as(priority).containsExactly("PRIORITY_INVALID");
    assertThat(r.table).isEmpty();
  }

  @Test
  void numbersAndDatesNeedTheirExactForm() {
    // a priority in Arabic-Indic digits is not an ASCII number
    Result arabic = parseWith("priority", "\"\u0663\u0660\u0660\"");
    assertThat(arabic.codes()).containsExactly("PRIORITY_INVALID");

    for (String ok : List.of("1", "300", "999999999")) {
      Result r = parseWith("priority", ok);
      assertThat(r.problems).as(ok).isEmpty();
      assertThat(
              r.table.orElseThrow().rows().stream()
                  .filter(x -> x.id().equals("S-N03-novation"))
                  .findFirst()
                  .orElseThrow()
                  .priority())
          .isEqualTo(Integer.parseInt(ok));
    }

    for (String date :
        List.of(
            "2018-1-3",
            "2018-02-30",
            "18-01-03",
            "2018/01/03",
            "\u0662\u0660\u0661\u0668-\u0660\u0661-\u0660\u0663",
            "2018-01-03T00:00:00Z")) {
      Result r = parseWith("valid", "{legal: {from: \"" + date + "\"}}");
      assertThat(r.codes()).as(date).containsExactly("DATE_INVALID");
      Result to = parseWith("valid", "{legal: {from: 2018-01-03, to: \"" + date + "\"}}");
      assertThat(to.codes()).as("to " + date).containsExactly("DATE_INVALID");
    }
    // the scalar null is not a date
    assertThat(parseWith("valid", "{legal: {from: null}}").codes()).containsExactly("DATE_INVALID");
  }

  @Test
  void sourceIdAndRuleRefShapes() {
    for (String bad : List.of("=cmd", "+1", "-1", "@x", "a;b", "a,b", "a#b", "1abc", "a b", "")) {
      String quoted = "\"" + bad + "\"";
      assertThat(parseWith("rule_ref", quoted).codes())
          .as("rule_ref " + bad)
          .containsExactly("RULE_REF_INVALID");
      assertThat(
              parseWith(
                      "cite",
                      "[{source_id: " + quoted + ", provision_path: sample/1, role: BASIS}]")
                  .codes())
          .as("source_id " + bad)
          .containsExactly("SOURCE_ID_INVALID");
      assertThat(parseWith("id", quoted).codes()).as("id " + bad).containsExactly("ROW_ID_INVALID");
    }
    // 64 characters pass, 65 do not
    String edge = "R" + "a".repeat(63);
    assertThat(parseWith("rule_ref", edge).problems).isEmpty();
    assertThat(parseWith("rule_ref", edge + "a").codes()).containsExactly("RULE_REF_INVALID");
    assertThat(parseWith("rule_ref", "A-b_c.9").problems).isEmpty();
  }

  @Test
  void supervisoryAndEventWindowsAreReserved() {
    for (String window : List.of("supervisory", "event", "other")) {
      Result alone = parseWith("valid", "{" + window + ": {from: 2018-01-03}}");
      assertThat(alone.codes()).as(window).contains("VALIDITY_WINDOW_RESERVED");
      assertThat(alone.table).isEmpty();

      Result beside =
          parseWith("valid", "{legal: {from: 2018-01-03}, " + window + ": {from: 2018-01-03}}");
      assertThat(beside.codes())
          .as(window + " beside legal")
          .containsExactly("VALIDITY_WINDOW_RESERVED");
    }
    assertThat(parseWith("valid", "{legal: {from: 2018-01-03, extra: x}}").codes())
        .containsExactly("TABLE_KEY_UNKNOWN");
    assertThat(parseWith("valid", "{legal: {to: 2018-01-03}}").codes())
        .containsExactly("TABLE_KEY_MISSING");
  }

  // -- cells ------------------------------------------------------------------------------------

  /** Replaces every occurrence of {@code token} in keys and values of a tree. */
  private static YamlNode substitute(YamlNode node, String token, String replacement) {
    return switch (node) {
      case YamlScalar s -> new YamlScalar(s.value().replace(token, replacement), s.line());
      case YamlList l ->
          new YamlList(
              l.items().stream().map(i -> substitute(i, token, replacement)).toList(), l.line());
      case YamlMap m -> {
        Map<String, YamlNode> entries = new LinkedHashMap<>();
        m.entries()
            .forEach(
                (k, v) ->
                    entries.put(k.replace(token, replacement), substitute(v, token, replacement)));
        yield new YamlMap(entries, m.line());
      }
    };
  }

  @Test
  void everyCellMustBeOneLineOfCleanText() {
    // each cell, with "ZZ" marking where a hostile character is put
    Map<String, Map<String, String>> cells = new LinkedHashMap<>();
    cells.put("id", with(row(), "id", "SZZ"));
    cells.put("node", with(row(), "node", "NZZ"));
    cells.put("when", with(row(), "when", "\"a ZZ b\""));
    cells.put("reason", with(row(), "reason", "SAMPLE.NZZ"));
    cells.put("rule_ref", with(row(), "rule_ref", "RZZ"));
    cells.put("rationale", with(row(), "rationale", "RZZ"));
    cells.put("priority", with(row(), "priority", "3ZZ"));
    cells.put("outcome", with(row(), "outcome", "REPORTZZ"));
    cells.put(
        "source_id",
        with(row(), "cite", "[{source_id: sZZ, provision_path: sample/1, role: BASIS}]"));
    cells.put(
        "provision_path",
        with(row(), "cite", "[{source_id: sample-law, provision_path: sZZ, role: BASIS}]"));
    cells.put(
        "role", with(row(), "cite", "[{source_id: sample-law, provision_path: p, role: BASISZZ}]"));
    cells.put("set name", with(row(), "set", "{actionZZ: NEWT}"));
    cells.put("set value", with(row(), "set", "{action: NEWTZZ}"));
    cells.put("valid", with(row(), "valid", "{legal: {from: 2018-01-0ZZ}}"));
    cells.put("legs", with(with(row(), "outcome", "REPORT"), "legs", "[MARKETZZ]"));

    List<String> hostile =
        List.of(
            "\n", "\r", "\t", "\u200b", "\u202e", "\u0000", "\u2060", "\ufe0f", "\ue000", "\u00ad");
    for (Map.Entry<String, Map<String, String>> cell : cells.entrySet()) {
      YamlNode template = StrictYaml.parse(document(HEADER, List.of(cell.getValue(), catchAll())));
      for (String bad : hostile) {
        Result r = new Result(substitute(template, "ZZ", bad));
        assertThat(r.codes())
            .as("cell " + cell.getKey() + " with U+" + Integer.toHexString(bad.codePointAt(0)))
            .containsExactly("CELL_BAD_CHARACTER");
        assertThat(r.table).isEmpty();
        assertThat(r.problems.get(0).path()).isEqualTo(PATH);
        assertThat(r.problems.get(0).line()).isPositive();
      }
    }

    // a line feed written as a YAML escape reaches the parser the same way
    Result escaped = parseWith("when", "\"a\\nb\"");
    assertThat(escaped.codes()).containsExactly("CELL_BAD_CHARACTER");
    // a clean cell with non-ASCII letters and symbols is fine
    assertThat(parseWith("when", "\"event.kind == 'caf\u00e9 \u20ac'\"").problems).isEmpty();
  }

  @Test
  void aHostileCellDoesNotEchoItselfUnbounded() {
    Result r = parseWith("when", "\"" + "x".repeat(5_000) + "\\n\"");

    assertThat(r.codes()).containsExactly("CELL_BAD_CHARACTER");
    assertThat(r.problems.get(0).message()).hasSizeLessThanOrEqualTo(160);
  }

  // -- keys and shape ---------------------------------------------------------------------------

  @Test
  void unknownRowKeyIsRejected() {
    Result r = parseWith("colour", "red");
    assertThat(r.codes()).containsExactly("TABLE_KEY_UNKNOWN");
    assertThat(r.table).isEmpty();

    Result missing = parseWith("reason", null);
    assertThat(missing.codes()).containsExactly("TABLE_KEY_MISSING");

    // unknown and missing keys of the header and of the document
    assertThat(
            parse(
                    document(
                        "table: {id: t, hit_policy: FIRST, priority: explicit, inputs: [],"
                            + " catch_all: S-DEFAULT, extra: x}",
                        List.of(row(), catchAll())))
                .codes())
        .containsExactly("TABLE_KEY_UNKNOWN");
    assertThat(
            parse(
                    document(
                        "table: {id: t, hit_policy: FIRST, priority: explicit, inputs: []}",
                        List.of(row(), catchAll())))
                .codes())
        .containsExactly("TABLE_KEY_MISSING");
    assertThat(parse("rows: []\n").codes()).containsExactly("TABLE_KEY_MISSING");
    assertThat(parse("").codes()).containsExactly("TABLE_KEY_MISSING", "TABLE_KEY_MISSING");
    assertThat(parse(HEADER + "\nrows: []\nextra: x\n").codes())
        .containsExactly("TABLE_KEY_UNKNOWN");
  }

  @Test
  void shapesAreChecked() {
    assertThat(parse("table: x\nrows: []\n").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parse(HEADER + "\nrows: x\n").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parse(HEADER + "\nrows:\n  - x\n").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parse(HEADER.replace("[event.kind]", "event.kind") + "\nrows: []\n").codes())
        .containsExactly("TABLE_SHAPE");
    assertThat(parseWith("cite", "x").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parseWith("cite", "[x]").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parseWith("set", "x").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parseWith("set", "{action: [x]}").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parseWith("valid", "x").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parseWith("when", "[a]").codes()).containsExactly("TABLE_SHAPE");
    assertThat(parseWith("when", "''").codes()).containsExactly("WHEN_EMPTY");
    assertThat(parse(HEADER.replace("[event.kind]", "[[a]]") + "\nrows: []\n").codes())
        .containsExactly("TABLE_SHAPE");
    assertThat(parse(HEADER.replace("[event.kind]", "[Event.Kind]") + "\nrows: []\n").codes())
        .containsExactly("INPUT_PATH_INVALID");
  }

  @Test
  void hitPolicyMustBeFirstAndPriorityExplicit() {
    for (String hit : List.of("ANY", "first", "UNIQUE", "COLLECT", "''")) {
      Result r =
          parse(
              document(
                  HEADER.replace("hit_policy: FIRST", "hit_policy: " + hit),
                  List.of(row(), catchAll())));
      assertThat(r.codes()).as(hit).containsExactly("HIT_POLICY_INVALID");
    }
    for (String mode : List.of("implicit", "EXPLICIT", "file_order", "''")) {
      Result r =
          parse(
              document(
                  HEADER.replace("priority: explicit", "priority: " + mode),
                  List.of(row(), catchAll())));
      assertThat(r.codes()).as(mode).containsExactly("PRIORITY_MODE_INVALID");
    }
  }

  @Test
  void outcomeMustBeAnExactName() {
    for (String outcome :
        List.of("report", "Report", "do_not_report", "REPORT ", "REPORTED", "''")) {
      assertThat(parseWith("outcome", "\"" + outcome + "\"").codes())
          .as(outcome)
          .containsExactly("OUTCOME_INVALID");
    }
    // the Turkish dotless i cannot make a name valid
    assertThat(parseWith("outcome", "\"DO_NOT_REPORT\u0131\"").codes())
        .containsExactly("OUTCOME_INVALID");
    for (Outcome o : Outcome.values()) {
      Result r = parseWith("outcome", o.name());
      assertThat(r.problems).as(o.name()).isEmpty();
      assertThat(r.table.orElseThrow().rows().get(0).outcome()).isEqualTo(o);
    }
  }

  @Test
  void legsOnlyOnReportRowsAndDefaultToMarket() {
    // a REPORT row without legs defaults to MARKET
    Result def = parseWith("outcome", "REPORT");
    assertThat(def.problems).isEmpty();
    assertThat(def.table.orElseThrow().rows().get(0).legs()).containsExactly(Leg.MARKET);

    Result listed =
        parseRows(
            List.of(
                with(with(row(), "outcome", "REPORT"), "legs", "[MARKET, CLIENT]"), catchAll()));
    assertThat(listed.problems).isEmpty();
    assertThat(listed.table.orElseThrow().rows().get(0).legs())
        .containsExactly(Leg.MARKET, Leg.CLIENT);

    // other outcomes have no legs, and the key is refused on them
    for (Outcome o :
        List.of(Outcome.DO_NOT_REPORT, Outcome.REPORT_BY_OTHER, Outcome.UNDETERMINED)) {
      Result r =
          parseRows(
              List.of(with(with(row(), "outcome", o.name()), "legs", "[MARKET]"), catchAll()));
      assertThat(r.codes()).as(o.name()).containsExactly("LEGS_ON_NON_REPORT_ROW");
      Result none = parseWith("outcome", o.name());
      assertThat(none.table.orElseThrow().rows().get(0).legs()).isEmpty();
    }

    // the vocabulary is closed, duplicates and an empty list are refused
    Map<String, String> report = with(row(), "outcome", "REPORT");
    assertThat(parseRows(List.of(with(report, "legs", "[SIDE]"), catchAll())).codes())
        .containsExactly("LEG_VOCABULARY_RESERVED");
    assertThat(parseRows(List.of(with(report, "legs", "[market]"), catchAll())).codes())
        .containsExactly("LEG_VOCABULARY_RESERVED");
    assertThat(parseRows(List.of(with(report, "legs", "[MARKET, MARKET]"), catchAll())).codes())
        .containsExactly("LEG_DUPLICATE");
    assertThat(parseRows(List.of(with(report, "legs", "[]"), catchAll())).codes())
        .containsExactly("ROW_LEGS_EMPTY");
  }

  @Test
  void setNamesAndValuesAreRestricted() {
    for (String name : List.of("Action", "1a", "a-b", "a b", "_a", "a#", "=a")) {
      assertThat(parseWith("set", "{\"" + name + "\": NEWT}").codes())
          .as("name " + name)
          .containsExactly("SET_INVALID");
    }
    for (String value : List.of("a b", "a;b", "a,b", "a=b", "a#b", "=1", "", "a\\\\b", "\u00e9")) {
      assertThat(parseWith("set", "{action: \"" + value + "\"}").codes())
          .as("value " + value)
          .containsExactly("SET_INVALID");
    }
    Result ok = parseWith("set", "{zeta.x_1: A-b_c.d:e/f, action: -5}");
    assertThat(ok.problems).isEmpty();
    // the model keeps the names sorted whatever the file order was
    assertThat(ok.table.orElseThrow().rows().get(0).set().keySet())
        .containsExactly("action", "zeta.x_1");
    assertThat(ok.table.orElseThrow().rows().get(0).set().get("action")).isEqualTo("-5");
    assertThat(parseWith("set", "{}").table.orElseThrow().rows().get(0).set()).isEmpty();
  }

  @Test
  void provisionPathCharset() {
    for (String bad : List.of("a b", "a;b", "a,b", "a#b", "a?b", "", "a\\\\b", "\u00e9", "a=b")) {
      String cite = "[{source_id: sample-law, provision_path: \"" + bad + "\", role: BASIS}]";
      assertThat(parseWith("cite", cite).codes())
          .as("path " + bad)
          .containsExactly("PROVISION_PATH_INVALID");
    }
    String ok = "[{source_id: sample-law, provision_path: \"A-9_./:z\", role: EXCEPTION}]";
    Result r = parseWith("cite", ok);
    assertThat(r.problems).isEmpty();
    assertThat(r.table.orElseThrow().rows().get(0).cite())
        .containsExactly(new CitationRef("sample-law", "A-9_./:z", CitationRole.EXCEPTION));

    // the role is an exact name; the three keys are all required and no others are known
    String badRole = "[{source_id: sample-law, provision_path: p, role: basis}]";
    assertThat(parseWith("cite", badRole).codes()).containsExactly("CITE_ROLE_INVALID");
    assertThat(parseWith("cite", "[{source_id: sample-law, provision_path: p}]").codes())
        .containsExactly("TABLE_KEY_MISSING");
    assertThat(
            parseWith("cite", "[{source_id: s, provision_path: p, role: BASIS, quote: q}]").codes())
        .containsExactly("TABLE_KEY_UNKNOWN");
  }

  @Test
  void aRowMayCiteSeveralProvisionsAndNoneIsLeftToTheChecks() {
    Result two =
        parseWith(
            "cite",
            "[{source_id: a, provision_path: p, role: BASIS}, {source_id: b, provision_path: q,"
                + " role: GUIDANCE}]");
    assertThat(two.table.orElseThrow().rows().get(0).cite())
        .containsExactly(
            new CitationRef("a", "p", CitationRole.BASIS),
            new CitationRef("b", "q", CitationRole.GUIDANCE));

    // an empty or absent list parses; TableChecks reports ROW_NO_CITATION and ROW_NO_VALIDITY
    Result empty = parseRows(List.of(with(with(row(), "cite", "[]"), "valid", null), catchAll()));
    assertThat(empty.problems).isEmpty();
    assertThat(empty.table.orElseThrow().rows().get(0).cite()).isEmpty();
    assertThat(empty.table.orElseThrow().rows().get(0).valid()).isEmpty();
    assertThat(parseWith("cite", null).table.orElseThrow().rows().get(0).cite()).isEmpty();
  }

  @Test
  void rowsAreSortedByPriorityNotFileOrder() {
    Map<String, String> ten = with(with(row(), "id", "R-10"), "priority", "10");
    Map<String, String> nine = with(with(row(), "id", "R-9"), "priority", "9");
    Map<String, String> hundred = with(with(row(), "id", "R-100"), "priority", "100");

    Result r = parseRows(List.of(catchAll(), hundred, ten, nine));

    assertThat(r.problems).isEmpty();
    // numeric order: 9, 10, 100, 900 (a text order would put 10 and 100 before 9)
    assertThat(r.table.orElseThrow().rows().stream().map(TableRow::id).toList())
        .containsExactly("R-9", "R-10", "R-100", "S-DEFAULT");
  }

  @Test
  void reasonCodeShapeIsChecked() {
    for (String bad :
        List.of("sample.n03", "SAMPLE..X", "SAMPLE.A.B.C.D", "1ABC", "A B", "=A", "A#B", "''")) {
      String value = bad.equals("''") ? bad : "\"" + bad + "\"";
      assertThat(parseWith("reason", value).codes()).as(bad).containsExactly("REASON_INVALID");
    }
    assertThat(parseWith("reason", "\"A" + "B".repeat(200) + "\"").codes())
        .containsExactly("REASON_INVALID");
    assertThat(parseWith("reason", "EXEC_BUDGET").problems).isEmpty();
  }

  @Test
  void nodeAndRationaleShapes() {
    for (String bad : List.of("n03", "N0000000X", "03", "N-3", "N 3", "")) {
      assertThat(parseWith("node", "\"" + bad + "\"").codes())
          .as("node " + bad)
          .containsExactly("ROW_NODE_INVALID");
    }
    assertThat(parseWith("node", "N0000007").problems).isEmpty();
    for (String bad : List.of("=RAT", "+RAT", "-RAT", "@RAT", "RAT 1", "RAT,1", "")) {
      assertThat(parseWith("rationale", "\"" + bad + "\"").codes())
          .as("rationale " + bad)
          .containsExactly("RATIONALE_INVALID");
    }
  }

  @Test
  void aProblemAlwaysNamesTheTablePathAndTheLine() {
    Result r = parseWith("priority", "0300");

    assertThat(r.problems).hasSize(1);
    PackProblem p = r.problems.get(0);
    assertThat(p.path()).isEqualTo(PATH);
    assertThat(p.line()).isEqualTo(5); // the priority line of the first row
    assertThat(r.table).isEmpty();
  }

  @Test
  void severalProblemsAreAllReportedInOrder() {
    Map<String, String> bad = with(with(row(), "priority", "0"), "outcome", "nope");
    Result r = parseRows(List.of(bad, catchAll()));

    assertThat(r.codes()).containsExactly("PRIORITY_INVALID", "OUTCOME_INVALID");
    List<PackProblem> sorted = new ArrayList<>(r.problems);
    java.util.Collections.sort(sorted);
    assertThat(r.problems).isEqualTo(sorted);
  }

  @Test
  void theModelIsImmutableAndCopiesItsInput() {
    DecisionTable t = parseRows(List.of(row(), catchAll())).table.orElseThrow();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> t.rows().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> t.inputs().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> t.rows().get(0).set().put("a", "b"))
        .isInstanceOf(UnsupportedOperationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> t.rows().get(0).cite().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
