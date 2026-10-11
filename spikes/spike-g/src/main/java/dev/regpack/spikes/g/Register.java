package dev.regpack.spikes.g;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The SP-G breakage register: a Markdown table whose rows record every place where the pack format
 * of spec 6.1 could not say what EMIR needs. {@link #parse} validates the table; the status
 * vocabulary is the one of the plan (only the owner sets ACCEPTED, REJECTED_NOT_NEEDED and
 * UNFIXABLE). Disposable spike code.
 */
public final class Register {
  /** The columns of the table, in order. */
  private static final List<String> COLUMNS =
      List.of(
          "id",
          "hypothesis",
          "location",
          "symptom",
          "emir_need",
          "proposed_fix",
          "impact",
          "severity",
          "status",
          "owner_note");

  private static final Set<String> STATUSES =
      Set.of("OPEN", "PROPOSED", "ACCEPTED", "REJECTED_NOT_NEEDED", "UNFIXABLE");
  private static final Set<String> SEVERITIES =
      Set.of("BLOCKS_FREEZE", "FIX_BEFORE_FREEZE", "P12_ONLY");
  private static final Set<String> HYPOTHESES =
      Set.of("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "H9", "H10", "new");

  private Register() {}

  /** One register row, reduced to the columns that the checks read. */
  public record Row(
      String id, String hypothesis, String status, String severity, String proposedFix) {}

  /**
   * Parses the first table of the document whose header is exactly {@link #COLUMNS}.
   *
   * @param markdown the register document; text around the table is ignored
   * @return the rows in order
   * @throws IllegalArgumentException when there is no such table, a row has the wrong number of
   *     cells, a vocabulary is violated, the ids are not BRK-01, BRK-02, ... without gaps and
   *     duplicates, or a PROPOSED row has no fix text
   */
  public static List<Row> parse(String markdown) {
    String[] lines = markdown.split("\r?\n", -1);
    int header = -1;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.startsWith("|") && cells(line, i + 1).equals(COLUMNS)) {
        header = i;
        break;
      }
    }
    if (header < 0) {
      throw new IllegalArgumentException(
          "no register table found: the header must have the columns " + COLUMNS);
    }
    if (header + 1 >= lines.length || !isSeparator(lines[header + 1].strip(), header + 2)) {
      throw new IllegalArgumentException(
          "line " + (header + 2) + ": the header must be followed by a separator row");
    }
    List<Row> rows = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = header + 2; i < lines.length && lines[i].strip().startsWith("|"); i++) {
      int no = i + 1;
      List<String> cells = cells(lines[i].strip(), no);
      if (cells.size() != COLUMNS.size()) {
        throw new IllegalArgumentException(
            "line " + no + ": expected " + COLUMNS.size() + " cells, found " + cells.size());
      }
      String id = cells.get(0);
      if (!seen.add(id)) {
        throw new IllegalArgumentException("line " + no + ": duplicate id " + id);
      }
      String expected = String.format(Locale.ROOT, "BRK-%02d", rows.size() + 1);
      if (!id.equals(expected)) {
        throw new IllegalArgumentException(
            "line "
                + no
                + ": ids must run BRK-01, BRK-02, ... without gaps; expected "
                + expected
                + " but found "
                + id);
      }
      String hypothesis = cells.get(1);
      if (!HYPOTHESES.contains(hypothesis)) {
        throw new IllegalArgumentException(
            "line " + no + ": unknown hypothesis '" + hypothesis + "' (H1 to H10 or new)");
      }
      String severity = cells.get(7);
      if (!SEVERITIES.contains(severity)) {
        throw new IllegalArgumentException(
            "line " + no + ": unknown severity '" + severity + "' (" + SEVERITIES + ")");
      }
      String status = cells.get(8);
      if (!STATUSES.contains(status)) {
        throw new IllegalArgumentException(
            "line " + no + ": unknown status '" + status + "' (" + STATUSES + ")");
      }
      String fix = cells.get(5);
      if (status.equals("PROPOSED") && (fix.isBlank() || fix.equals("-"))) {
        throw new IllegalArgumentException(
            "line " + no + ": a PROPOSED row needs the exact text in proposed_fix");
      }
      rows.add(new Row(id, hypothesis, status, severity, fix));
    }
    return List.copyOf(rows);
  }

  /** The number of rows that the owner has not dispositioned: OPEN and PROPOSED. */
  public static long open(List<Row> rows) {
    return rows.stream()
        .filter(r -> r.status().equals("OPEN") || r.status().equals("PROPOSED"))
        .count();
  }

  /** The number of rows that the owner declared a redesign instead of a format fix. */
  public static long unfixable(List<Row> rows) {
    return rows.stream().filter(r -> r.status().equals("UNFIXABLE")).count();
  }

  private static boolean isSeparator(String line, int no) {
    if (!line.startsWith("|")) {
      return false;
    }
    List<String> cells = cells(line, no);
    return cells.size() == COLUMNS.size() && cells.stream().allMatch(c -> c.matches(":?-{3,}:?"));
  }

  /** Splits a table line at unescaped pipes; {@code \|} stands for a pipe inside a cell. */
  private static List<String> cells(String line, int no) {
    if (!line.startsWith("|") || !line.endsWith("|") || line.length() < 2) {
      throw new IllegalArgumentException(
          "line " + no + ": a table row must start and end with '|'");
    }
    List<String> out = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    String body = line.substring(1, line.length() - 1);
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
        cell.append('|');
        i++;
      } else if (c == '|') {
        out.add(cell.toString().strip());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    out.add(cell.toString().strip());
    return out;
  }
}
