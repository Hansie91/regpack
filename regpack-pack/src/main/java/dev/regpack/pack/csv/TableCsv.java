package dev.regpack.pack.csv;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.TableChecks;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableParser;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CSV export and import of a decision table (companion contracts section 5.7; the AT-8 round trip;
 * provisional until a G-S3b pass).
 *
 * <p>Format: UTF-8, LF, no BOM, RFC 4180 quoting. A field is quoted iff it contains a comma, a
 * double quote, LF or CR, or begins or ends with a space; a quote inside is doubled. One header row
 * with exactly the 13 columns {@link #COLUMNS}; rows ascend by priority; an empty cell is an absent
 * value; {@code cite} is {@code source_id#provision_path#ROLE} items joined by {@code ;}, {@code
 * set} is {@code name=value} items joined by {@code ;} and sorted by name, {@code legs} are names
 * joined by {@code ;}. The charsets of the table parser keep every separator unambiguous.
 *
 * <p>Import accepts CRLF, one leading BOM, a missing final newline and any column order, but
 * exactly the expected column set. The table header ({@code id}, {@code inputs}, {@code catch_all},
 * the path) comes from the existing table. Each CSV row becomes the same node tree that the YAML
 * parser produces, and that tree goes through {@link TableParser} and {@link TableChecks}, so an
 * import cannot bypass a typing rule, the one-line clean-text rule of a cell (LF, CR, TAB and
 * invisible characters are {@code CELL_BAD_CHARACTER}, whatever the quoting) or a table check. No
 * cell is trimmed or otherwise changed on the way.
 *
 * <p>A blank line is a record of one empty cell, so it is refused with {@code CSV_CELL_COUNT} (also
 * a second newline at the end of the text); only a missing final newline is tolerated. A stage-one
 * problem ({@code CSV_COLUMNS}, {@code CSV_QUOTE}, {@code CSV_CELL_COUNT}, {@code SET_INVALID})
 * stops the later stages: the parser and the table checks do not run until the CSV itself is well
 * formed. The size of the text is not limited here; the caller reads the file with the size cap of
 * the pack format.
 *
 * <p>{@link #export} does not escape spreadsheet formula characters: the guarantee that no cell
 * begins with {@code = + - @} (PF16) rests on the table having passed {@link TableChecks} ({@code
 * WHEN_FORMULA_CHARACTER}); every other column is shape-limited to start with a letter or a digit.
 *
 * <p>Extra problem codes: {@code CSV_COLUMNS}, {@code CSV_QUOTE} and {@code CSV_CELL_COUNT}. A
 * problem of the parser carries the line of the CSV row it concerns (the header is line 1); the
 * table checks have no source lines, so their problems carry line 0.
 */
public final class TableCsv {
  /** The columns, in the order of the export. */
  public static final List<String> COLUMNS =
      List.of(
          "id",
          "node",
          "priority",
          "when",
          "outcome",
          "reason",
          "rule_ref",
          "cite",
          "rationale",
          "valid_from",
          "valid_to",
          "set",
          "legs");

  private static final char BOM = '\uFEFF';

  private TableCsv() {}

  // -- export -----------------------------------------------------------------------------------

  /** The CSV text of a table: the header row, then one row per table row in priority order. */
  public static String export(DecisionTable table) {
    StringBuilder out = new StringBuilder();
    line(out, COLUMNS);
    for (TableRow row : table.rows()) {
      List<String> cells = new ArrayList<>(COLUMNS.size());
      cells.add(row.id());
      cells.add(row.node());
      cells.add(Integer.toString(row.priority()));
      cells.add(row.when());
      cells.add(row.outcome().name());
      cells.add(row.reason().value());
      cells.add(row.ruleRef().orElse(""));
      cells.add(cite(row.cite()));
      cells.add(row.rationale());
      Optional<Validity> valid = row.valid();
      cells.add(valid.map(v -> v.from().toString()).orElse(""));
      cells.add(valid.flatMap(Validity::to).map(Object::toString).orElse(""));
      cells.add(set(row.set()));
      cells.add(String.join(";", row.legs().stream().map(Enum::name).toList()));
      line(out, cells);
    }
    return out.toString();
  }

  private static String cite(List<CitationRef> cite) {
    List<String> items = new ArrayList<>();
    for (CitationRef c : cite) {
      items.add(c.sourceId() + "#" + c.provisionPath() + "#" + c.role().name());
    }
    return String.join(";", items);
  }

  private static String set(Map<String, String> set) {
    List<String> items = new ArrayList<>();
    for (Map.Entry<String, String> e : set.entrySet()) {
      items.add(e.getKey() + "=" + e.getValue());
    }
    return String.join(";", items);
  }

  private static void line(StringBuilder out, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      field(out, cells.get(i));
    }
    out.append('\n');
  }

  private static void field(StringBuilder out, String cell) {
    boolean quote =
        (!cell.isEmpty() && (cell.charAt(0) == ' ' || cell.charAt(cell.length() - 1) == ' '))
            || cell.indexOf(',') >= 0
            || cell.indexOf('"') >= 0
            || cell.indexOf('\n') >= 0
            || cell.indexOf('\r') >= 0;
    if (!quote) {
      out.append(cell);
      return;
    }
    out.append('"');
    for (int i = 0; i < cell.length(); i++) {
      char c = cell.charAt(i);
      if (c == '"') {
        out.append('"');
      }
      out.append(c);
    }
    out.append('"');
  }

  // -- import -----------------------------------------------------------------------------------

  /** One CSV record: the cells and the 1-based line on which the record starts. */
  private record Rec(int line, List<String> cells) {}

  /**
   * Reads the rows of a CSV text into a table with the header of {@code header}. The problems found
   * are appended to {@code problems} (sorted among themselves); the result is empty when this call
   * found any problem, so a table that fails a typing rule or a table check is never returned.
   *
   * @param csv the CSV text
   * @param header the table whose path, id, inputs and catch-all row id the result takes
   */
  public static Optional<DecisionTable> importRows(
      String csv, DecisionTable header, List<PackProblem> problems) {
    String path = header.path();
    List<PackProblem> found = new ArrayList<>();
    Optional<DecisionTable> result = read(csv, header, path, found);
    Collections.sort(found);
    problems.addAll(found);
    return found.isEmpty() ? result : Optional.empty();
  }

  private static Optional<DecisionTable> read(
      String csv, DecisionTable header, String path, List<PackProblem> found) {
    Optional<List<Rec>> read = records(csv, path, found);
    if (read.isEmpty()) {
      return Optional.empty();
    }
    List<Rec> records = read.get();
    if (records.isEmpty()) {
      found.add(new PackProblem("CSV_COLUMNS", path, 1, "the CSV has no header row"));
      return Optional.empty();
    }
    Optional<int[]> columns = columnIndex(records.get(0), path, found);
    if (columns.isEmpty()) {
      return Optional.empty();
    }
    int[] index = columns.get();
    List<YamlNode> rows = new ArrayList<>();
    for (Rec rec : records.subList(1, records.size())) {
      if (rec.cells().size() != COLUMNS.size()) {
        found.add(
            new PackProblem(
                "CSV_CELL_COUNT",
                path,
                rec.line(),
                "the row has " + rec.cells().size() + " cells, expected " + COLUMNS.size()));
        continue;
      }
      rows.add(rowNode(rec, index, path, found));
    }
    if (!found.isEmpty()) {
      return Optional.empty();
    }

    Map<String, YamlNode> table = new LinkedHashMap<>();
    table.put("id", scalar(header.id(), 0));
    table.put("hit_policy", scalar("FIRST", 0));
    table.put("priority", scalar("explicit", 0));
    List<YamlNode> inputs = new ArrayList<>();
    header.inputs().forEach(i -> inputs.add(scalar(i, 0)));
    table.put("inputs", new YamlList(inputs, 0));
    table.put("catch_all", scalar(header.catchAllRowId(), 0));
    Map<String, YamlNode> root = new LinkedHashMap<>();
    root.put("table", new YamlMap(table, 0));
    root.put("rows", new YamlList(rows, 0));

    Optional<DecisionTable> parsed = TableParser.parse(path, new YamlMap(root, 0), found);
    if (parsed.isEmpty() || !found.isEmpty()) {
      return Optional.empty();
    }
    found.addAll(TableChecks.check(parsed.get()));
    return parsed;
  }

  /** For each expected column, its position in the file; empty (with problems) on a mismatch. */
  private static Optional<int[]> columnIndex(Rec headerRec, String path, List<PackProblem> found) {
    int before = found.size();
    int[] index = new int[COLUMNS.size()];
    Arrays.fill(index, -1);
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < headerRec.cells().size(); i++) {
      String name = headerRec.cells().get(i);
      int column = COLUMNS.indexOf(name);
      if (column < 0) {
        found.add(
            new PackProblem(
                "CSV_COLUMNS",
                path,
                headerRec.line(),
                "unknown column '" + PackProblem.excerpt(name) + "'"));
      } else if (!seen.add(name)) {
        found.add(
            new PackProblem("CSV_COLUMNS", path, headerRec.line(), "column '" + name + "' twice"));
      } else {
        index[column] = i;
      }
    }
    for (int c = 0; c < index.length; c++) {
      if (index[c] < 0 && !seen.contains(COLUMNS.get(c))) {
        found.add(
            new PackProblem(
                "CSV_COLUMNS", path, headerRec.line(), "missing column '" + COLUMNS.get(c) + "'"));
      }
    }
    return found.size() > before ? Optional.empty() : Optional.of(index);
  }

  private static YamlScalar scalar(String value, int line) {
    return new YamlScalar(value, line);
  }

  /** The tree of one row: absent keys for empty cells, nothing trimmed. */
  private static YamlNode rowNode(Rec rec, int[] index, String path, List<PackProblem> found) {
    int line = rec.line();
    String[] cell = new String[COLUMNS.size()];
    for (int c = 0; c < cell.length; c++) {
      cell[c] = rec.cells().get(index[c]);
    }
    Map<String, YamlNode> row = new LinkedHashMap<>();
    for (int c = 0; c <= 6; c++) {
      if (!cell[c].isEmpty()) {
        row.put(COLUMNS.get(c), scalar(cell[c], line));
      }
    }
    if (!cell[7].isEmpty()) {
      row.put("cite", cite(cell[7], line));
    }
    if (!cell[8].isEmpty()) {
      row.put("rationale", scalar(cell[8], line));
    }
    if (!cell[9].isEmpty() || !cell[10].isEmpty()) {
      Map<String, YamlNode> legal = new LinkedHashMap<>();
      if (!cell[9].isEmpty()) {
        legal.put("from", scalar(cell[9], line));
      }
      if (!cell[10].isEmpty()) {
        legal.put("to", scalar(cell[10], line));
      }
      row.put("valid", new YamlMap(Map.of("legal", new YamlMap(legal, line)), line));
    }
    if (!cell[11].isEmpty()) {
      row.put("set", set(cell[11], line, path, found));
    }
    if (!cell[12].isEmpty()) {
      List<YamlNode> legs = new ArrayList<>();
      for (String leg : cell[12].split(";", -1)) {
        legs.add(scalar(leg, line));
      }
      row.put("legs", new YamlList(legs, line));
    }
    return new YamlMap(row, line);
  }

  private static YamlNode cite(String cell, int line) {
    List<YamlNode> items = new ArrayList<>();
    for (String item : cell.split(";", -1)) {
      Map<String, YamlNode> m = new LinkedHashMap<>();
      int first = item.indexOf('#');
      int second = first < 0 ? -1 : item.indexOf('#', first + 1);
      m.put("source_id", scalar(first < 0 ? item : item.substring(0, first), line));
      if (first >= 0) {
        m.put(
            "provision_path",
            scalar(
                second < 0 ? item.substring(first + 1) : item.substring(first + 1, second), line));
      }
      if (second >= 0) {
        m.put("role", scalar(item.substring(second + 1), line));
      }
      items.add(new YamlMap(m, line));
    }
    return new YamlList(items, line);
  }

  private static YamlNode set(String cell, int line, String path, List<PackProblem> found) {
    Map<String, YamlNode> m = new LinkedHashMap<>();
    for (String item : cell.split(";", -1)) {
      int eq = item.indexOf('=');
      String name = eq < 0 ? item : item.substring(0, eq);
      String value = eq < 0 ? "" : item.substring(eq + 1);
      if (m.putIfAbsent(name, scalar(value, line)) != null) {
        found.add(
            new PackProblem(
                "SET_INVALID",
                path,
                line,
                "set name '" + PackProblem.excerpt(name) + "' is listed twice"));
      }
    }
    return new YamlMap(m, line);
  }

  // -- the CSV reader ---------------------------------------------------------------------------

  /**
   * Splits the text into records (RFC 4180). Returns empty after a {@code CSV_QUOTE} problem. A CR
   * outside quotes ends a record only together with the LF that follows it; any other CR stays in
   * the cell, where the table parser refuses it. A quoted cell may hold LF (the parser refuses it
   * too), so line breaks never hide in a cell.
   */
  private static Optional<List<Rec>> records(String text, String path, List<PackProblem> found) {
    int n = text.length();
    int pos = n > 0 && text.charAt(0) == BOM ? 1 : 0;
    int line = 1;
    List<Rec> records = new ArrayList<>();
    while (pos < n) {
      int startLine = line;
      List<String> cells = new ArrayList<>();
      while (true) {
        StringBuilder cell = new StringBuilder();
        if (pos < n && text.charAt(pos) == '"') {
          pos++;
          boolean closed = false;
          while (pos < n) {
            char c = text.charAt(pos);
            if (c == '"') {
              if (pos + 1 < n && text.charAt(pos + 1) == '"') {
                cell.append('"');
                pos += 2;
              } else {
                pos++;
                closed = true;
                break;
              }
            } else {
              if (c == '\n') {
                line++;
              }
              cell.append(c);
              pos++;
            }
          }
          if (!closed) {
            found.add(
                new PackProblem(
                    "CSV_QUOTE", path, startLine, "a quoted cell is not closed before the end"));
            return Optional.empty();
          }
          cells.add(cell.toString());
          if (pos < n
              && text.charAt(pos) != ','
              && text.charAt(pos) != '\n'
              && !(text.charAt(pos) == '\r' && pos + 1 < n && text.charAt(pos + 1) == '\n')) {
            found.add(
                new PackProblem(
                    "CSV_QUOTE", path, line, "text follows the closing quote of a cell"));
            return Optional.empty();
          }
        } else {
          while (pos < n) {
            char c = text.charAt(pos);
            if (c == ','
                || c == '\n'
                || (c == '\r' && pos + 1 < n && text.charAt(pos + 1) == '\n')) {
              break;
            }
            if (c == '"') {
              found.add(
                  new PackProblem(
                      "CSV_QUOTE", path, line, "a quote inside a cell that is not quoted"));
              return Optional.empty();
            }
            cell.append(c);
            pos++;
          }
          cells.add(cell.toString());
        }
        if (pos >= n) {
          break;
        }
        char c = text.charAt(pos);
        if (c == ',') {
          pos++;
          if (pos >= n) { // a comma at the very end: one more, empty cell
            cells.add("");
            break;
          }
          continue;
        }
        pos += c == '\r' ? 2 : 1; // CRLF or LF ends the record
        line++;
        break;
      }
      records.add(new Rec(startLine, cells));
    }
    return Optional.of(records);
  }
}
