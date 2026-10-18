package dev.regpack.spikes.l.restore;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides a landed record (the parsed NDJSON map of a {@code TradeRecord}) under a generated rule
 * pack, for the rebuild from evidence. It is a deliberately narrow interpreter: it compiles exactly
 * the conditions that {@code RulePackGen} writes (equality on a string, a bare boolean path,
 * membership of a list, decimal comparison, {@code startsWith}, {@code size}, a date against a
 * date, {@code has}, {@code true}, joined by {@code &&}) and refuses anything else.
 *
 * <p><b>Why not the in-house expression layer of SP-R.</b> That layer lives in the {@code spike-r}
 * module; depending on it would put cel-java and its dependencies into the tree of {@code spike-l},
 * whose resolved tree is the JDBC driver and checker-qual only. The rebuild therefore decides
 * through this class, and the report must say that its CPU cost is a lower bound of the in-house
 * layer's. The semantics are those of SP-R's {@code RuleTableProgram}: a record visits the tables
 * in order; in each table the first row by ascending priority whose condition holds decides that
 * table; a deciding row that is not the catch-all is the decision; if every table ends in its
 * catch-all the decision is UNDETERMINED from the last catch-all. Reading an absent field outside
 * {@code has} fails closed (I5): it is an error, not a silent false.
 */
public final class PackDecider {
  /**
   * The decision of one record.
   *
   * @param outcome the name of a model outcome
   * @param ruleId the deciding row (the last catch-all when every table ended in its catch-all)
   * @param tablesVisited tables entered
   * @param rowsEvaluated rows evaluated over all tables
   * @param decidingRow the 1-based position of the deciding row in its table
   */
  public record Decision(
      String outcome, String ruleId, int tablesVisited, int rowsEvaluated, int decidingRow) {}

  /** One compiled condition. */
  @FunctionalInterface
  private interface Atom {
    boolean test(Map<String, Object> record);
  }

  private record Row(String id, String outcome, boolean catchAll, Atom[] atoms) {}

  private static final String PATH = "[A-Za-z][A-Za-z0-9.]*";
  private static final Pattern TRUE = Pattern.compile("true");
  private static final Pattern EQUALS = Pattern.compile("(" + PATH + ") == '([^']*)'");
  private static final Pattern IN = Pattern.compile("(" + PATH + ") in \\[(.*)]");
  private static final Pattern DECIMAL =
      Pattern.compile("(" + PATH + ") (>|>=) dec\\('([0-9.]+)'\\)");
  private static final Pattern STARTS_WITH =
      Pattern.compile("(" + PATH + ")\\.startsWith\\('([^']*)'\\)");
  private static final Pattern SIZE = Pattern.compile("size\\((" + PATH + ")\\) == ([0-9]+)");
  private static final Pattern HAS = Pattern.compile("has\\((" + PATH + ")\\)");
  private static final Pattern PATH_AFTER = Pattern.compile("(" + PATH + ") > (" + PATH + ")");
  private static final Pattern BARE = Pattern.compile(PATH);

  private final Row[][] tables;

  /**
   * Compiles the pack.
   *
   * @throws IllegalArgumentException for a condition outside the grammar of the generator
   */
  public PackDecider(GeneratedPack pack) {
    List<Row[]> compiled = new ArrayList<>();
    for (List<GeneratedRule> table : pack.tables()) {
      Row[] rows = new Row[table.size()];
      for (int i = 0; i < rows.length; i++) {
        GeneratedRule rule = table.get(i);
        rows[i] =
            new Row(rule.id(), rule.outcome(), "true".equals(rule.when()), compile(rule.when()));
      }
      compiled.add(rows);
    }
    this.tables = compiled.toArray(new Row[0][]);
  }

  /**
   * Decides one record; safe to call from several threads.
   *
   * @throws IllegalStateException if a condition reads a field that the record does not have
   */
  public Decision decide(Map<String, Object> record) {
    int visited = 0;
    int evaluated = 0;
    String lastCatchAll = "";
    int lastCatchAllRow = 0;
    for (Row[] table : tables) {
      visited++;
      boolean decided = false;
      for (int r = 0; r < table.length; r++) {
        Row row = table[r];
        evaluated++;
        if (matches(row, record)) {
          if (!row.catchAll()) {
            return new Decision(row.outcome(), row.id(), visited, evaluated, r + 1);
          }
          lastCatchAll = row.id();
          lastCatchAllRow = r + 1;
          decided = true;
          break;
        }
      }
      if (!decided) {
        throw new IllegalStateException("table ended without its catch-all hitting");
      }
    }
    return new Decision("UNDETERMINED", lastCatchAll, visited, evaluated, lastCatchAllRow);
  }

  private static boolean matches(Row row, Map<String, Object> record) {
    for (Atom atom : row.atoms()) {
      if (!atom.test(record)) {
        return false;
      }
    }
    return true;
  }

  // ------------------------------------------------------------------ compiler

  private static Atom[] compile(String when) {
    String[] parts = when.split(" && ", -1);
    Atom[] atoms = new Atom[parts.length];
    for (int i = 0; i < parts.length; i++) {
      atoms[i] = atom(parts[i].strip());
    }
    return atoms;
  }

  private static Atom atom(String text) {
    if (TRUE.matcher(text).matches()) {
      return record -> true;
    }
    Matcher m = EQUALS.matcher(text);
    if (m.matches()) {
      String[] path = split(m.group(1));
      String literal = m.group(2);
      return record -> literal.equals(required(record, path, text));
    }
    m = IN.matcher(text);
    if (m.matches()) {
      String[] path = split(m.group(1));
      List<String> literals = literals(m.group(2), text);
      return record -> literals.contains(required(record, path, text));
    }
    m = DECIMAL.matcher(text);
    if (m.matches()) {
      String[] path = split(m.group(1));
      boolean orEqual = m.group(2).equals(">=");
      BigDecimal bound = new BigDecimal(m.group(3));
      return record -> {
        int c = number(required(record, path, text), text).compareTo(bound);
        return orEqual ? c >= 0 : c > 0;
      };
    }
    m = STARTS_WITH.matcher(text);
    if (m.matches()) {
      String[] path = split(m.group(1));
      String prefix = m.group(2);
      return record -> string(required(record, path, text), text).startsWith(prefix);
    }
    m = SIZE.matcher(text);
    if (m.matches()) {
      String[] path = split(m.group(1));
      int size = Integer.parseInt(m.group(2));
      return record -> string(required(record, path, text), text).length() == size;
    }
    m = HAS.matcher(text);
    if (m.matches()) {
      String[] path = split(m.group(1));
      return record -> lookup(record, path) != null;
    }
    m = PATH_AFTER.matcher(text);
    if (m.matches()) {
      String[] left = split(m.group(1));
      String[] right = split(m.group(2));
      // ISO dates compare as text
      return record ->
          string(required(record, left, text), text)
                  .compareTo(string(required(record, right, text), text))
              > 0;
    }
    if (BARE.matcher(text).matches()) {
      String[] path = split(text);
      return record -> Boolean.TRUE.equals(required(record, path, text));
    }
    throw new IllegalArgumentException("a condition outside the grammar of the generator: " + text);
  }

  private static List<String> literals(String list, String whole) {
    List<String> out = new ArrayList<>();
    for (String item : list.split(",", -1)) {
      String t = item.strip();
      if (t.length() < 2 || t.charAt(0) != '\'' || t.charAt(t.length() - 1) != '\'') {
        throw new IllegalArgumentException(
            "a condition outside the grammar of the generator: " + whole);
      }
      out.add(t.substring(1, t.length() - 1));
    }
    return List.copyOf(out);
  }

  private static String[] split(String path) {
    return path.split("\\.");
  }

  /** The value at a path of nested maps, or null when any step is absent. */
  private static Object lookup(Map<String, Object> record, String[] path) {
    Object current = record;
    for (String step : path) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(step);
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  private static Object required(Map<String, Object> record, String[] path, String condition) {
    Object value = lookup(record, path);
    if (value == null) {
      throw new IllegalStateException(
          "the record has no " + String.join(".", path) + " (in: " + condition + ")");
    }
    return value;
  }

  private static BigDecimal number(Object value, String condition) {
    return switch (value) {
      case Long n -> BigDecimal.valueOf(n);
      case BigDecimal n -> n;
      default ->
          throw new IllegalStateException("not a number: " + value + " (in: " + condition + ")");
    };
  }

  private static String string(Object value, String condition) {
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalStateException("not a string: " + value + " (in: " + condition + ")");
  }
}
