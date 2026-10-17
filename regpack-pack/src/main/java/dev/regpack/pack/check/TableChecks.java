package dev.regpack.pack.check;

import dev.regpack.model.Outcome;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The static checks of one decision table (spec 6.1 and 6.2; companion contracts section 5.3): a
 * mandatory catch-all row that is last, always true, UNDETERMINED (I5) and has no validity window;
 * no second always-true row; unique priorities and row ids; a citation on every row, the catch-all
 * too (I5); a legal validity window on every other row, not inverted; and no {@code when} text that
 * a spreadsheet would read as a formula.
 *
 * <p>Full static analysis (gaps, overlaps, unreachable and shadowed rows) is plan P2b. Problem
 * codes: {@code TABLE_EMPTY}, {@code CATCH_ALL_MISSING}, {@code CATCH_ALL_NOT_LAST}, {@code
 * CATCH_ALL_NOT_TRUE}, {@code CATCH_ALL_NOT_UNDETERMINED}, {@code CATCH_ALL_HAS_VALIDITY}, {@code
 * CATCH_ALL_DUPLICATE}, {@code PRIORITY_DUPLICATE}, {@code ROW_ID_DUPLICATE}, {@code
 * ROW_NO_CITATION}, {@code ROW_NO_VALIDITY}, {@code VALIDITY_INVERTED} and {@code
 * WHEN_FORMULA_CHARACTER}. The model carries no source lines, so every problem has line 0.
 */
public final class TableChecks {
  private static final String TRUE = "true";

  private TableChecks() {}

  /** The problems of a table, sorted; empty when the table is sound. */
  public static List<PackProblem> check(DecisionTable table) {
    ProblemSink found = ProblemSink.forFile();
    new Checker(table, found).run();
    return found.sorted(table.path());
  }

  private static final class Checker {
    private final DecisionTable table;
    private final ProblemSink found;

    Checker(DecisionTable table, ProblemSink found) {
      this.table = table;
      this.found = found;
    }

    private void problem(String code, String message) {
      found.add(new PackProblem(code, table.path(), 0, message));
    }

    void run() {
      List<TableRow> rows = table.rows();
      if (rows.isEmpty()) {
        problem("TABLE_EMPTY", "the table has no rows, not even the mandatory catch-all");
        return;
      }
      uniqueness(rows);
      int catchAllIndex = catchAllIndex(rows);
      if (catchAllIndex < 0) {
        problem(
            "CATCH_ALL_MISSING",
            "no row has the catch-all id '" + PackProblem.excerpt(table.catchAllRowId()) + "'");
      } else {
        catchAll(rows, catchAllIndex);
      }
      for (int i = 0; i < rows.size(); i++) {
        if (found.truncated()) {
          break;
        }
        TableRow row = rows.get(i);
        boolean isCatchAll = i == catchAllIndex;
        if (row.cite().isEmpty()) {
          problem(
              "ROW_NO_CITATION",
              "row " + PackProblem.excerpt(row.id()) + " has no citation (I5: every row cites)");
        }
        if (!isCatchAll) {
          validity(row);
          if (isTrue(row.when())) {
            problem(
                "CATCH_ALL_DUPLICATE",
                "row "
                    + PackProblem.excerpt(row.id())
                    + " is always true but is not the catch-all row");
          }
        }
        if (formulaStart(row.when())) {
          problem(
              "WHEN_FORMULA_CHARACTER",
              "when of row "
                  + PackProblem.excerpt(row.id())
                  + " starts with a spreadsheet formula character (= + - @)");
        }
      }
    }

    private void uniqueness(List<TableRow> rows) {
      Map<String, Integer> idCount = new LinkedHashMap<>();
      Map<Integer, List<String>> byPriority = new LinkedHashMap<>();
      for (TableRow row : rows) {
        idCount.merge(row.id(), 1, Integer::sum);
        byPriority.computeIfAbsent(row.priority(), p -> new ArrayList<>()).add(row.id());
      }
      for (Map.Entry<String, Integer> e : idCount.entrySet()) {
        if (found.truncated()) {
          break;
        }
        if (e.getValue() > 1) {
          problem(
              "ROW_ID_DUPLICATE",
              "row id " + PackProblem.excerpt(e.getKey()) + " is used " + e.getValue() + " times");
        }
      }
      for (Map.Entry<Integer, List<String>> e : byPriority.entrySet()) {
        if (found.truncated()) {
          break;
        }
        if (e.getValue().size() > 1) {
          Set<String> ids = new LinkedHashSet<>();
          e.getValue().forEach(id -> ids.add(PackProblem.excerpt(id)));
          problem(
              "PRIORITY_DUPLICATE",
              "priority " + e.getKey() + " is used by rows " + String.join(", ", ids));
        }
      }
    }

    /** The index of the first row with the catch-all id, or -1. */
    private int catchAllIndex(List<TableRow> rows) {
      for (int i = 0; i < rows.size(); i++) {
        if (rows.get(i).id().equals(table.catchAllRowId())) {
          return i;
        }
      }
      return -1;
    }

    private void catchAll(List<TableRow> rows, int index) {
      TableRow row = rows.get(index);
      String id = PackProblem.excerpt(row.id());
      for (int i = 0; i < rows.size(); i++) {
        if (i != index && rows.get(i).priority() >= row.priority()) {
          problem("CATCH_ALL_NOT_LAST", "the catch-all row " + id + " is not the last row");
          break;
        }
      }
      if (!isTrue(row.when())) {
        problem("CATCH_ALL_NOT_TRUE", "the catch-all row " + id + " must have when: true");
      }
      if (row.outcome() != Outcome.UNDETERMINED) {
        problem(
            "CATCH_ALL_NOT_UNDETERMINED",
            "the catch-all row "
                + id
                + " must have outcome UNDETERMINED (I5), not "
                + row.outcome());
      }
      if (row.valid().isPresent()) {
        problem("CATCH_ALL_HAS_VALIDITY", "the catch-all row " + id + " must have no validity");
      }
    }

    private void validity(TableRow row) {
      String id = PackProblem.excerpt(row.id());
      if (row.valid().isEmpty()) {
        problem("ROW_NO_VALIDITY", "row " + id + " has no legal validity window");
        return;
      }
      Validity v = row.valid().get();
      if (v.to().isPresent() && !v.to().get().isAfter(v.from())) {
        problem(
            "VALIDITY_INVERTED",
            "row " + id + " has a validity window whose end is not after its start");
      }
    }

    /**
     * Whether the text is {@code true} between spaces (the expression lexer's blanks are not
     * Unicode's).
     */
    private static boolean isTrue(String when) {
      int from = 0;
      int to = when.length();
      while (from < to && when.charAt(from) == ' ') {
        from++;
      }
      while (to > from && when.charAt(to - 1) == ' ') {
        to--;
      }
      return TRUE.equals(when.substring(from, to));
    }

    /** Whether the text, after leading spaces, starts with {@code = + - @}. */
    private static boolean formulaStart(String when) {
      int i = 0;
      while (i < when.length() && when.charAt(i) == ' ') {
        i++;
      }
      if (i == when.length()) {
        return false;
      }
      char c = when.charAt(i);
      return c == '=' || c == '+' || c == '-' || c == '@';
    }
  }
}
