package dev.regpack.pack.report;

import dev.regpack.model.CitationRole;
import dev.regpack.pack.PackText;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A derive table: the rows that give the fields of a report from the canonical input (plan W1a,
 * task T5; the file {@code derive/report.yaml} of a pack). A row has an id, the name of the field
 * it gives, a {@code when} (an expression, default {@code true}), a {@code value} (an expression),
 * a rule reference and at least one citation. The reader checks the shape only; the expressions are
 * compiled against the canonical input by the engine, which also checks the cross-table rules
 * (unique ids, rule references and fields, and that the citations resolve).
 *
 * <pre>
 * table: {id: example.report}
 * rows:
 *   - {id: D-ONE, field: executing_lei, when: "event.kind == 'NEW'", value: "firm.lei",
 *      rule_ref: DR-0001, cite: [{source_id: some-law, provision_path: p/1, role: BASIS}]}
 * </pre>
 *
 * <p>Problem codes of {@link #parse}: {@code DERIVE_SHAPE}, {@code DERIVE_KEY_UNKNOWN}, {@code
 * DERIVE_KEY_MISSING}, {@code DERIVE_TABLE_ID_INVALID}, {@code DERIVE_ROWS_EMPTY}, {@code
 * DERIVE_ROW_ID_INVALID}, {@code DERIVE_ROW_ID_DUPLICATE}, {@code DERIVE_FIELD_INVALID}, {@code
 * DERIVE_FIELD_DUPLICATE}, {@code DERIVE_WHEN_EMPTY}, {@code DERIVE_VALUE_EMPTY}, {@code
 * DERIVE_RULE_REF_INVALID}, {@code DERIVE_CITE_EMPTY}, {@code DERIVE_CITE_INVALID} and {@code
 * DERIVE_BAD_CHARACTER}.
 *
 * @param path the pack path of the file, kept in every problem
 * @param id the id of the table
 * @param rows the rows in file order, at least one
 */
public record DeriveTable(String path, String id, List<Row> rows) {
  /**
   * One row.
   *
   * @param id the id of the row, the name under which its cost and its failures are reported
   * @param field the name of the field the row gives, unique in the table
   * @param when the expression that selects the records the row applies to; {@code true} if the
   *     file gives none
   * @param value the expression of the field's value
   * @param ruleRef the reference of the rule, shown on the derived field
   * @param cite the provisions the row cites, at least one
   */
  public record Row(
      String id, String field, String when, String value, String ruleRef, List<CitationRef> cite) {
    public Row {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(when, "when");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(ruleRef, "ruleRef");
      cite = List.copyOf(cite);
    }
  }

  public DeriveTable {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(id, "id");
    rows = List.copyOf(rows);
  }

  private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
  private static final Pattern FIELD = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final Pattern PROVISION_PATH = Pattern.compile("[A-Za-z0-9_./:-]+");

  /**
   * Reads one derive file. The problems found are appended to {@code problems}; reading stops once
   * the sink is full. The result is empty when this call found any problem.
   *
   * @param path the pack path of the file
   * @param root the parsed file
   */
  public static Optional<DeriveTable> parse(String path, YamlNode root, ProblemSink problems) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(problems, "problems");
    int before = problems.count();
    Optional<DeriveTable> table = new Reader(path, problems).read(root);
    return problems.count() == before ? table : Optional.empty();
  }

  private static final class Reader {
    private final String path;
    private final ProblemSink found;

    Reader(String path, ProblemSink found) {
      this.path = path;
      this.found = found;
    }

    private void problem(String code, int line, String message) {
      found.add(new PackProblem(code, path, line, message));
    }

    Optional<DeriveTable> read(YamlNode root) {
      YamlMap top = map(root, "a derive table file");
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(top, "the derive table file", Set.of("table", "rows"), Set.of());
      String id = null;
      YamlNode header = top.entries().get("table");
      if (header != null) {
        YamlMap h = map(header, "table");
        if (h != null) {
          checkKeys(h, "table", Set.of("id"), Set.of());
          id = cell(h.entries().get("id"), "table id", ID, "DERIVE_TABLE_ID_INVALID");
        }
      }
      List<Row> rows = new ArrayList<>();
      YamlNode rowsNode = top.entries().get("rows");
      if (rowsNode != null) {
        YamlList list = list(rowsNode, "rows");
        if (list != null) {
          if (list.items().isEmpty()) {
            problem("DERIVE_ROWS_EMPTY", rowsNode.line(), "a derive table needs at least one row");
          }
          Set<String> ids = new HashSet<>();
          Set<String> fields = new HashSet<>();
          for (YamlNode item : list.items()) {
            if (found.truncated()) {
              break; // the file fails anyway; do not read the rest of a hostile table
            }
            YamlMap m = map(item, "a row");
            if (m != null) {
              Row row = row(m);
              if (row != null) {
                if (!ids.add(row.id())) {
                  problem(
                      "DERIVE_ROW_ID_DUPLICATE",
                      m.line(),
                      "row id " + PackProblem.excerpt(row.id()) + " is used twice in the table");
                }
                if (!fields.add(row.field())) {
                  problem(
                      "DERIVE_FIELD_DUPLICATE",
                      m.line(),
                      "row "
                          + PackProblem.excerpt(row.id())
                          + " gives the field "
                          + PackProblem.excerpt(row.field())
                          + " a second time");
                }
                rows.add(row);
              }
            }
          }
        }
      }
      if (id == null) {
        return Optional.empty();
      }
      return Optional.of(new DeriveTable(path, id, rows));
    }

    // -- generic helpers --------------------------------------------------------------------

    private YamlMap map(YamlNode node, String what) {
      if (node instanceof YamlMap m) {
        return m;
      }
      problem("DERIVE_SHAPE", node.line(), what + " must be a mapping");
      return null;
    }

    private YamlList list(YamlNode node, String what) {
      if (node instanceof YamlList l) {
        return l;
      }
      problem("DERIVE_SHAPE", node.line(), what + " must be a list");
      return null;
    }

    private void checkKeys(YamlMap m, String where, Set<String> required, Set<String> optional) {
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (found.truncated()) {
          return;
        }
        String key = e.getKey();
        if (!required.contains(key) && !optional.contains(key)) {
          problem(
              "DERIVE_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(key) + "' in " + where);
        }
      }
      for (String key : new TreeSet<>(required)) {
        if (!m.entries().containsKey(key)) {
          problem("DERIVE_KEY_MISSING", m.line(), "missing key '" + key + "' in " + where);
        }
      }
    }

    /** The scalar text of a node (null if absent, or a problem if it is not a scalar). */
    private String text(YamlNode node, String what) {
      if (node == null) {
        return null;
      }
      if (node instanceof YamlScalar s) {
        return s.value();
      }
      problem("DERIVE_SHAPE", node.line(), what + " must be a single value");
      return null;
    }

    /** Whether a cell is one line of clean text; reports {@code DERIVE_BAD_CHARACTER} if not. */
    private boolean clean(String value, String what, int line) {
      Optional<PackText.Bad> bad = PackText.firstBad(value, false);
      if (bad.isPresent()) {
        problem(
            "DERIVE_BAD_CHARACTER",
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

    /** A clean cell of the given shape, or null (with a problem unless the node is absent). */
    private String cell(YamlNode node, String what, Pattern shape, String code) {
      String value = text(node, what);
      if (value == null || !clean(value, what, node.line())) {
        return null;
      }
      if (!shape.matcher(value).matches()) {
        problem(
            code, node.line(), what + " has an invalid form: '" + PackProblem.excerpt(value) + "'");
        return null;
      }
      return value;
    }

    /** A clean expression text that is not blank, or null (with a problem). */
    private String expression(YamlNode node, String what, String emptyCode) {
      String value = text(node, what);
      if (value == null || !clean(value, what, node.line())) {
        return null;
      }
      if (value.isBlank()) {
        problem(emptyCode, node.line(), what + " must not be empty");
        return null;
      }
      return value;
    }

    // -- rows -------------------------------------------------------------------------------

    private Row row(YamlMap m) {
      int before = found.count();
      checkKeys(m, "a row", Set.of("id", "field", "value", "rule_ref", "cite"), Set.of("when"));
      Map<String, YamlNode> e = m.entries();
      String id = cell(e.get("id"), "row id", ID, "DERIVE_ROW_ID_INVALID");
      String field = cell(e.get("field"), "field", FIELD, "DERIVE_FIELD_INVALID");
      String when =
          e.containsKey("when") ? expression(e.get("when"), "when", "DERIVE_WHEN_EMPTY") : "true";
      String value = expression(e.get("value"), "value", "DERIVE_VALUE_EMPTY");
      String ruleRef = cell(e.get("rule_ref"), "rule_ref", ID, "DERIVE_RULE_REF_INVALID");
      List<CitationRef> cite = e.containsKey("cite") ? cite(e.get("cite")) : null;
      if (found.count() > before
          || id == null
          || field == null
          || when == null
          || value == null
          || ruleRef == null
          || cite == null) {
        return null;
      }
      return new Row(id, field, when, value, ruleRef, cite);
    }

    /** The citations, or null on a problem. */
    private List<CitationRef> cite(YamlNode node) {
      YamlList list = list(node, "cite");
      if (list == null) {
        return null;
      }
      if (list.items().isEmpty()) {
        problem("DERIVE_CITE_EMPTY", node.line(), "a row cites at least one provision");
        return null;
      }
      List<CitationRef> out = new ArrayList<>();
      boolean ok = true;
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break;
        }
        if (!(item instanceof YamlMap m)) {
          problem("DERIVE_CITE_INVALID", item.line(), "a citation must be a mapping");
          ok = false;
          continue;
        }
        int before = found.count();
        checkKeys(m, "a citation", Set.of("source_id", "provision_path", "role"), Set.of());
        String source = cell(m.entries().get("source_id"), "source_id", ID, "DERIVE_CITE_INVALID");
        String provision =
            cell(
                m.entries().get("provision_path"),
                "provision_path",
                PROVISION_PATH,
                "DERIVE_CITE_INVALID");
        CitationRole role = role(m.entries().get("role"));
        if (found.count() > before || source == null || provision == null || role == null) {
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
          "DERIVE_CITE_INVALID",
          node.line(),
          "role must be an exact citation role, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }
  }
}
