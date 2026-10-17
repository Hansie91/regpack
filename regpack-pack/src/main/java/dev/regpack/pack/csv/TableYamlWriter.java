package dev.regpack.pack.csv;

import dev.regpack.pack.PackFormat;
import dev.regpack.pack.PackText;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Writes a decision table as {@code PackYaml/1} text in one canonical layout (companion contracts
 * section 5.7; provisional until a G-S3b pass), so that {@code TableParser} reads the text back to
 * an equal table and writing that table again gives the same bytes.
 *
 * <p>Layout: the table header as one flow mapping, then one block mapping per row with the keys in
 * contract order ({@code id node priority when outcome reason rule_ref cite rationale valid set
 * legs}); absent optionals are left out; {@code inputs}, {@code cite}, {@code set} and {@code legs}
 * are one-line flow collections; every value is written plain when it is plain-safe, else in double
 * quotes, and {@code when} is always in double quotes. Only the escapes {@code \\} and {@code \"}
 * are ever needed, because a cell is one line of clean text. Comments of an original file are not
 * preserved: the parser drops them.
 *
 * <p>The writer refuses ({@link IllegalArgumentException}) a table it cannot write faithfully: a
 * cell with a character the {@link PackText} policy refuses (LF, CR and TAB included), or a line
 * longer than {@link PackFormat#MAX_LINE_CHARS}. A table that came from {@code TableParser} or from
 * {@code TableCsv.importRows} never trips either.
 */
public final class TableYamlWriter {
  /** A value that the parser reads back unchanged without quotes, in block and in flow context. */
  private static final Pattern PLAIN_SAFE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]*");

  private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

  private TableYamlWriter() {}

  /**
   * The canonical YAML text of a table (UTF-8 text with LF ends, a final LF).
   *
   * @throws IllegalArgumentException if the table cannot be written faithfully
   */
  public static String write(DecisionTable table) {
    StringBuilder out = new StringBuilder();
    out.append("table: {id: ")
        .append(value(table.id()))
        .append(", hit_policy: FIRST, priority: explicit, inputs: ")
        .append(flowList(table.inputs()))
        .append(", catch_all: ")
        .append(value(table.catchAllRowId()))
        .append("}\n");
    if (table.rows().isEmpty()) {
      out.append("rows: []\n");
    } else {
      out.append("rows:\n");
      for (TableRow row : table.rows()) {
        row(out, row);
      }
    }
    String text = out.toString();
    for (String line : text.split("\n", -1)) {
      if (line.length() > PackFormat.MAX_LINE_CHARS) {
        throw new IllegalArgumentException(
            "a line of the table is longer than " + PackFormat.MAX_LINE_CHARS + " characters");
      }
    }
    return text;
  }

  private static void row(StringBuilder out, TableRow row) {
    out.append("  - id: ").append(value(row.id())).append('\n');
    out.append("    node: ").append(value(row.node())).append('\n');
    out.append("    priority: ").append(row.priority()).append('\n');
    out.append("    when: ").append(quoted(row.when())).append('\n');
    out.append("    outcome: ").append(value(row.outcome().name())).append('\n');
    out.append("    reason: ").append(value(row.reason().value())).append('\n');
    Optional<String> ref = row.ruleRef();
    if (ref.isPresent()) {
      out.append("    rule_ref: ").append(value(ref.get())).append('\n');
    }
    if (!row.cite().isEmpty()) {
      out.append("    cite: ").append(cite(row.cite())).append('\n');
    }
    out.append("    rationale: ").append(value(row.rationale())).append('\n');
    if (row.valid().isPresent()) {
      Validity v = row.valid().get();
      out.append("    valid: {legal: {from: ").append(value(v.from().toString()));
      if (v.to().isPresent()) {
        out.append(", to: ").append(value(v.to().get().toString()));
      }
      out.append("}}\n");
    }
    if (!row.set().isEmpty()) {
      out.append("    set: {");
      boolean first = true;
      for (Map.Entry<String, String> e : row.set().entrySet()) {
        out.append(first ? "" : ", ")
            .append(key(e.getKey()))
            .append(": ")
            .append(value(e.getValue()));
        first = false;
      }
      out.append("}\n");
    }
    if (!row.legs().isEmpty()) {
      out.append("    legs: ")
          .append(flowList(row.legs().stream().map(Enum::name).toList()))
          .append('\n');
    }
  }

  private static String cite(List<CitationRef> cite) {
    StringBuilder out = new StringBuilder("[");
    boolean first = true;
    for (CitationRef c : cite) {
      out.append(first ? "" : ", ")
          .append("{source_id: ")
          .append(value(c.sourceId()))
          .append(", provision_path: ")
          .append(value(c.provisionPath()))
          .append(", role: ")
          .append(value(c.role().name()))
          .append('}');
      first = false;
    }
    return out.append(']').toString();
  }

  private static String flowList(List<String> items) {
    StringBuilder out = new StringBuilder("[");
    boolean first = true;
    for (String item : items) {
      out.append(first ? "" : ", ").append(value(item));
      first = false;
    }
    return out.append(']').toString();
  }

  /** A value: plain when plain-safe, else double-quoted. */
  private static String value(String text) {
    check(text);
    return PLAIN_SAFE.matcher(text).matches() ? text : quoted(text);
  }

  private static String key(String text) {
    check(text);
    return PLAIN_KEY.matcher(text).matches() ? text : quoted(text);
  }

  private static String quoted(String text) {
    check(text);
    StringBuilder out = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\' || c == '"') {
        out.append('\\');
      }
      out.append(c);
    }
    return out.append('"').toString();
  }

  private static void check(String text) {
    if (text.isEmpty() || PackText.firstBad(text, false).isPresent()) {
      throw new IllegalArgumentException(
          "a cell is empty or has a character the pack text policy refuses");
    }
  }
}
