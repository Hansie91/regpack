package dev.regpack.pack.model;

import dev.regpack.model.CitationRole;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.pack.PackText;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a scope decision table from a parsed {@code PackYaml/1} tree into the typed model
 * (companion contracts section 2 and 5.3). Every scalar of the tree is a string; this class types
 * each one explicitly and by an exact form: numbers and dates in ASCII only, enumerations by exact
 * name, identifiers by an explicit character class.
 *
 * <p>Every string cell is one line of clean text ({@link PackText}, LF not allowed): the parser
 * works on the tree, so a table that came from a CSV import goes through the same cell rules as a
 * table read from YAML. A cell with a bad character is reported once ({@code CELL_BAD_CHARACTER})
 * and its shape is not checked further.
 *
 * <p>Problem codes: {@code TABLE_SHAPE}, {@code TABLE_KEY_UNKNOWN}, {@code TABLE_KEY_MISSING},
 * {@code CELL_BAD_CHARACTER}, {@code TABLE_ID_INVALID}, {@code HIT_POLICY_INVALID}, {@code
 * PRIORITY_MODE_INVALID}, {@code INPUT_PATH_INVALID}, {@code CATCH_ALL_INVALID}, {@code
 * ROW_ID_INVALID}, {@code ROW_NODE_INVALID}, {@code PRIORITY_INVALID}, {@code WHEN_EMPTY}, {@code
 * OUTCOME_INVALID}, {@code REASON_INVALID}, {@code RULE_REF_INVALID}, {@code SOURCE_ID_INVALID},
 * {@code PROVISION_PATH_INVALID}, {@code CITE_ROLE_INVALID}, {@code RATIONALE_INVALID}, {@code
 * DATE_INVALID}, {@code VALIDITY_WINDOW_RESERVED}, {@code SET_INVALID}, {@code
 * LEGS_ON_NON_REPORT_ROW}, {@code LEG_VOCABULARY_RESERVED}, {@code LEG_DUPLICATE} and {@code
 * ROW_LEGS_EMPTY}. Whether the table is sound (catch-all, unique priorities, citations, validity)
 * is the business of {@code TableChecks}.
 */
public final class TableParser {
  private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
  private static final Pattern NODE = Pattern.compile("[A-Z][A-Z0-9]{0,7}");
  private static final Pattern PRIORITY = Pattern.compile("[1-9][0-9]{0,8}");
  private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Pattern PROVISION_PATH = Pattern.compile("[A-Za-z0-9_./:-]+");
  private static final Pattern SET_NAME = Pattern.compile("[a-z][A-Za-z0-9_.]*");
  private static final Pattern SET_VALUE = Pattern.compile("[A-Za-z0-9_.:/-]+");
  private static final Pattern INPUT_PATH =
      Pattern.compile("[a-z][A-Za-z0-9_]*(\\.[a-z][A-Za-z0-9_]*)*");
  private static final int MAX_REASON_CHARS = 128;
  private static final int MAX_INPUT_PATH_CHARS = 128;

  private TableParser() {}

  /**
   * Parses one table file. The problems found are appended to {@code problems} (sorted among
   * themselves, each naming {@code path}); the result is empty when this call found any problem.
   *
   * @param path the pack path of the file, kept in the table and in every problem
   * @param root the parsed file
   */
  public static Optional<DecisionTable> parse(
      String path, YamlNode root, List<PackProblem> problems) {
    Reader reader = new Reader(path);
    Optional<DecisionTable> table = reader.read(root);
    Collections.sort(reader.found);
    problems.addAll(reader.found);
    return reader.found.isEmpty() ? table : Optional.empty();
  }

  private static final class Reader {
    private final String path;
    final List<PackProblem> found = new ArrayList<>();

    Reader(String path) {
      this.path = path;
    }

    private void problem(String code, int line, String message) {
      found.add(new PackProblem(code, path, line, message));
    }

    Optional<DecisionTable> read(YamlNode root) {
      YamlMap top = map(root, "a decision table file");
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(top, "the table file", Set.of("table", "rows"), Set.of());

      YamlMap header =
          top.entries().containsKey("table") ? map(top.entries().get("table"), "table") : null;
      String id = null;
      List<String> inputs = null;
      String catchAll = null;
      if (header != null) {
        checkKeys(
            header,
            "table",
            Set.of("id", "hit_policy", "priority", "inputs", "catch_all"),
            Set.of());
        id = cell(header.entries().get("id"), "table id", ID, "TABLE_ID_INVALID");
        exact(header.entries().get("hit_policy"), "hit_policy", "FIRST", "HIT_POLICY_INVALID");
        exact(header.entries().get("priority"), "priority", "explicit", "PRIORITY_MODE_INVALID");
        inputs = inputs(header.entries().get("inputs"));
        catchAll = cell(header.entries().get("catch_all"), "catch_all", ID, "CATCH_ALL_INVALID");
      }

      List<TableRow> rows = new ArrayList<>();
      YamlNode rowsNode = top.entries().get("rows");
      if (rowsNode != null) {
        YamlList list = list(rowsNode, "rows");
        if (list != null) {
          for (YamlNode item : list.items()) {
            YamlMap m = map(item, "a row");
            if (m != null) {
              TableRow row = row(m);
              if (row != null) {
                rows.add(row);
              }
            }
          }
        }
      }
      if (!found.isEmpty() || id == null || inputs == null || catchAll == null) {
        return Optional.empty();
      }
      return Optional.of(new DecisionTable(path, id, inputs, catchAll, rows));
    }

    // -- generic helpers --------------------------------------------------------------------

    private YamlMap map(YamlNode node, String what) {
      if (node instanceof YamlMap m) {
        return m;
      }
      problem("TABLE_SHAPE", node.line(), what + " must be a mapping");
      return null;
    }

    private YamlList list(YamlNode node, String what) {
      if (node instanceof YamlList l) {
        return l;
      }
      problem("TABLE_SHAPE", node.line(), what + " must be a list");
      return null;
    }

    private void checkKeys(YamlMap m, String where, Set<String> required, Set<String> optional) {
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        String key = e.getKey();
        if (!required.contains(key) && !optional.contains(key)) {
          problem(
              "TABLE_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(key) + "' in " + where);
        }
      }
      List<String> missing = new ArrayList<>(required);
      Collections.sort(missing);
      for (String key : missing) {
        if (!m.entries().containsKey(key)) {
          problem("TABLE_KEY_MISSING", m.line(), "missing key '" + key + "' in " + where);
        }
      }
    }

    /**
     * The text of one cell, or null (with a problem reported unless the node is absent, which
     * {@code checkKeys} reported). A bad character is reported as {@code CELL_BAD_CHARACTER}; a
     * text that does not match {@code shape} as {@code code}.
     */
    private String cell(YamlNode node, String what, Pattern shape, String code) {
      String value = text(node, what);
      if (value == null) {
        return null;
      }
      if (!clean(value, what, node.line())) {
        return null;
      }
      if (!shape.matcher(value).matches()) {
        problem(
            code, node.line(), what + " has an invalid form: '" + PackProblem.excerpt(value) + "'");
        return null;
      }
      return value;
    }

    /** The scalar text of a node (null if absent, or a problem if it is not a scalar). */
    private String text(YamlNode node, String what) {
      if (node == null) {
        return null;
      }
      if (node instanceof YamlScalar s) {
        return s.value();
      }
      problem("TABLE_SHAPE", node.line(), what + " must be a single value");
      return null;
    }

    /** Whether a cell is one line of clean text; reports {@code CELL_BAD_CHARACTER} if not. */
    private boolean clean(String value, String what, int line) {
      Optional<PackText.Bad> bad = PackText.firstBad(value, false);
      if (bad.isPresent()) {
        problem(
            "CELL_BAD_CHARACTER",
            line,
            what
                + " must be one line of clean text; it has U+"
                + hex(bad.get().codePoint())
                + " at index "
                + bad.get().index());
        return false;
      }
      return true;
    }

    private static String hex(int codePoint) {
      String h = Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT);
      return "0".repeat(Math.max(0, 4 - h.length())) + h;
    }

    private void exact(YamlNode node, String what, String expected, String code) {
      String value = text(node, what);
      if (value == null) {
        return;
      }
      if (clean(value, what, node.line()) && !value.equals(expected)) {
        problem(
            code,
            node.line(),
            what + " must be " + expected + ", got '" + PackProblem.excerpt(value) + "'");
      }
    }

    // -- header ------------------------------------------------------------------------------

    private List<String> inputs(YamlNode node) {
      if (node == null) {
        return null;
      }
      YamlList list = list(node, "inputs");
      if (list == null) {
        return null;
      }
      List<String> out = new ArrayList<>();
      boolean ok = true;
      for (YamlNode item : list.items()) {
        String value = cell(item, "an input path", INPUT_PATH, "INPUT_PATH_INVALID");
        if (value != null && value.length() > MAX_INPUT_PATH_CHARS) {
          problem("INPUT_PATH_INVALID", item.line(), "an input path is longer than 128 characters");
          value = null;
        }
        if (value == null) {
          ok = false;
        } else {
          out.add(value);
        }
      }
      return ok ? out : null;
    }

    // -- rows --------------------------------------------------------------------------------

    private TableRow row(YamlMap m) {
      int before = found.size();
      checkKeys(
          m,
          "a row",
          Set.of("id", "node", "priority", "when", "outcome", "reason", "rationale"),
          Set.of("rule_ref", "cite", "valid", "set", "legs"));
      Map<String, YamlNode> e = m.entries();

      String id = cell(e.get("id"), "row id", ID, "ROW_ID_INVALID");
      String node = cell(e.get("node"), "node", NODE, "ROW_NODE_INVALID");
      Integer priority = priority(e.get("priority"));
      String when = when(e.get("when"));
      Outcome outcome = outcome(e.get("outcome"));
      ReasonCode reason = reason(e.get("reason"));
      String ruleRef =
          e.containsKey("rule_ref")
              ? cell(e.get("rule_ref"), "rule_ref", ID, "RULE_REF_INVALID")
              : null;
      List<CitationRef> cite = e.containsKey("cite") ? cite(e.get("cite")) : List.of();
      String rationale = cell(e.get("rationale"), "rationale", ID, "RATIONALE_INVALID");
      Validity valid = e.containsKey("valid") ? validity(e.get("valid")) : null;
      Map<String, String> set = e.containsKey("set") ? set(e.get("set")) : Map.of();
      List<Leg> legs = legs(e.get("legs"), outcome);

      if (found.size() > before
          || id == null
          || node == null
          || priority == null
          || when == null
          || outcome == null
          || reason == null
          || cite == null
          || rationale == null
          || set == null
          || legs == null) {
        return null;
      }
      return new TableRow(
          id,
          node,
          priority,
          when,
          outcome,
          reason,
          Optional.ofNullable(ruleRef),
          cite,
          rationale,
          Optional.ofNullable(valid),
          set,
          legs);
    }

    private Integer priority(YamlNode node) {
      String value = cell(node, "priority", PRIORITY, "PRIORITY_INVALID");
      return value == null ? null : Integer.valueOf(value);
    }

    private String when(YamlNode node) {
      String value = text(node, "when");
      if (value == null || !clean(value, "when", node.line())) {
        return null;
      }
      if (value.isBlank()) {
        problem("WHEN_EMPTY", node.line(), "when must not be empty");
        return null;
      }
      return value;
    }

    private Outcome outcome(YamlNode node) {
      String value = text(node, "outcome");
      if (value == null || !clean(value, "outcome", node.line())) {
        return null;
      }
      for (Outcome o : Outcome.values()) {
        if (o.name().equals(value)) {
          return o;
        }
      }
      problem(
          "OUTCOME_INVALID",
          node.line(),
          "outcome must be an exact outcome name, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    private ReasonCode reason(YamlNode node) {
      String value = text(node, "reason");
      if (value == null || !clean(value, "reason", node.line())) {
        return null;
      }
      if (value.length() <= MAX_REASON_CHARS) {
        try {
          return new ReasonCode(value);
        } catch (IllegalArgumentException e) {
          // reported below
        }
      }
      problem(
          "REASON_INVALID",
          node.line(),
          "reason is not a reason code: '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    /** The citations, or null on a problem. An empty list is returned as is (checked later). */
    private List<CitationRef> cite(YamlNode node) {
      YamlList list = list(node, "cite");
      if (list == null) {
        return null;
      }
      List<CitationRef> out = new ArrayList<>();
      boolean ok = true;
      for (YamlNode item : list.items()) {
        YamlMap m = map(item, "a citation");
        if (m == null) {
          ok = false;
          continue;
        }
        int before = found.size();
        checkKeys(m, "a citation", Set.of("source_id", "provision_path", "role"), Set.of());
        String source = cell(m.entries().get("source_id"), "source_id", ID, "SOURCE_ID_INVALID");
        String provision =
            cell(
                m.entries().get("provision_path"),
                "provision_path",
                PROVISION_PATH,
                "PROVISION_PATH_INVALID");
        CitationRole role = role(m.entries().get("role"));
        if (found.size() > before || source == null || provision == null || role == null) {
          ok = false;
        } else {
          out.add(new CitationRef(source, provision, role));
        }
      }
      return ok ? out : null;
    }

    private CitationRole role(YamlNode node) {
      String value = text(node, "role");
      if (value == null || !clean(value, "role", node.line())) {
        return null;
      }
      for (CitationRole r : CitationRole.values()) {
        if (r.name().equals(value)) {
          return r;
        }
      }
      problem(
          "CITE_ROLE_INVALID",
          node.line(),
          "role must be an exact citation role, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    private Validity validity(YamlNode node) {
      YamlMap valid = map(node, "valid");
      if (valid == null) {
        return null;
      }
      boolean reserved = false;
      for (Map.Entry<String, YamlNode> e : valid.entries().entrySet()) {
        if (!e.getKey().equals("legal")) {
          reserved = true;
          problem(
              "VALIDITY_WINDOW_RESERVED",
              e.getValue().line(),
              "validity window '"
                  + PackProblem.excerpt(e.getKey())
                  + "' is reserved; release 1 implements 'legal' only (RSV-6)");
        }
      }
      YamlNode legalNode = valid.entries().get("legal");
      if (legalNode == null) {
        if (!reserved) {
          problem("TABLE_KEY_MISSING", valid.line(), "missing key 'legal' in valid");
        }
        return null;
      }
      YamlMap legal = map(legalNode, "valid.legal");
      if (legal == null) {
        return null;
      }
      int before = found.size();
      checkKeys(legal, "valid.legal", Set.of("from"), Set.of("to"));
      LocalDate from = date(legal.entries().get("from"), "valid.legal.from");
      LocalDate to =
          legal.entries().containsKey("to")
              ? date(legal.entries().get("to"), "valid.legal.to")
              : null;
      if (found.size() > before
          || from == null
          || (legal.entries().containsKey("to") && to == null)) {
        return null;
      }
      return new Validity(from, Optional.ofNullable(to));
    }

    private LocalDate date(YamlNode node, String what) {
      String value = text(node, what);
      if (value == null || !clean(value, what, node.line())) {
        return null;
      }
      if (DATE.matcher(value).matches()) {
        try {
          return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
          // reported below
        }
      }
      problem(
          "DATE_INVALID",
          node.line(),
          what + " must be a date yyyy-mm-dd, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    /** The attributes, or null on a problem. */
    private Map<String, String> set(YamlNode node) {
      YamlMap m = map(node, "set");
      if (m == null) {
        return null;
      }
      Map<String, String> out = new LinkedHashMap<>();
      boolean ok = true;
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        YamlNode valueNode = e.getValue();
        String name = e.getKey();
        boolean nameOk = clean(name, "a set name", valueNode.line());
        if (nameOk && !SET_NAME.matcher(name).matches()) {
          problem(
              "SET_INVALID",
              valueNode.line(),
              "set name has an invalid form: '" + PackProblem.excerpt(name) + "'");
          nameOk = false;
        }
        String value = cell(valueNode, "a set value", SET_VALUE, "SET_INVALID");
        if (nameOk && value != null) {
          out.put(name, value);
        } else {
          ok = false;
        }
      }
      return ok ? out : null;
    }

    /** The legs of a row: null on a problem; empty for a non-REPORT row. */
    private List<Leg> legs(YamlNode node, Outcome outcome) {
      if (node == null) {
        return outcome == Outcome.REPORT ? List.of(Leg.MARKET) : List.of();
      }
      if (outcome != null && outcome != Outcome.REPORT) {
        problem(
            "LEGS_ON_NON_REPORT_ROW",
            node.line(),
            "legs are allowed only on REPORT rows, not on " + outcome);
        return null;
      }
      YamlList list = list(node, "legs");
      if (list == null) {
        return null;
      }
      if (list.items().isEmpty()) {
        problem("ROW_LEGS_EMPTY", node.line(), "legs must not be empty; omit the key for MARKET");
        return null;
      }
      EnumSet<Leg> seen = EnumSet.noneOf(Leg.class);
      List<Leg> out = new ArrayList<>();
      boolean ok = true;
      for (YamlNode item : list.items()) {
        String value = text(item, "a leg");
        if (value == null || !clean(value, "a leg", item.line())) {
          ok = false;
          continue;
        }
        Leg leg = null;
        for (Leg l : Leg.values()) {
          if (l.name().equals(value)) {
            leg = l;
          }
        }
        if (leg == null) {
          problem(
              "LEG_VOCABULARY_RESERVED",
              item.line(),
              "leg '"
                  + PackProblem.excerpt(value)
                  + "' is outside the closed vocabulary MARKET, CLIENT, ALLOCATION (BRK-12)");
          ok = false;
        } else if (!seen.add(leg)) {
          problem("LEG_DUPLICATE", item.line(), "leg " + leg + " is listed twice");
          ok = false;
        } else {
          out.add(leg);
        }
      }
      return ok ? out : null;
    }
  }
}
