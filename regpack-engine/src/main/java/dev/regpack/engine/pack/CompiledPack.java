package dev.regpack.engine.pack;

import dev.regpack.engine.expr.InHouseExpression;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Regime;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLimits;
import dev.regpack.pack.model.PackManifest;
import dev.regpack.pack.model.ReportDefinition;
import dev.regpack.pack.model.TableRow;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The scope tables of one report of a {@link LoadedPack}, every {@code when} compiled once against
 * the pack's declared canonical variables, with the per-record {@link PackBudget} (plan T15).
 *
 * <p>The loader has already checked the tables as data (catch-all, priorities, citations, unique
 * row ids); this class adds what needs the expression layer and reports nothing the loader reports.
 * Problem codes of {@link #compile}: {@code REPORT_UNKNOWN}, {@code REPORT_TABLE_MISSING}, {@code
 * WHEN_DOES_NOT_COMPILE} (a syntax error, a path the canonical input does not declare, or a rule
 * far beyond the ceiling: the parse cap is 16 times {@code limits.rule_nodes}), {@code
 * RULE_OVER_CEILING} (the node count exceeds {@code limits.rule_nodes}), {@code
 * TABLE_INPUT_UNDECLARED} (the table header lists a path the canonical input does not declare),
 * {@code INPUT_UNDECLARED} (a rule reads a path the header does not list) and {@code INPUT_UNUSED}
 * (the header lists a declared path no rule reads). The header checks are skipped for a table that
 * has a rule that does not compile, because its set of read paths is then incomplete.
 */
public final class CompiledPack {
  /** Parse-work cap as a multiple of the rule ceiling (the cap is not the ceiling). */
  private static final int PARSE_CAP_FACTOR = 16;

  /** One compiled row. */
  public record Row(TableRow source, InHouseExpression.Compiled when, boolean catchAll) {
    public Row {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(when, "when");
    }

    public String id() {
      return source.id();
    }
  }

  /** One compiled table; the rows are ascending by priority. */
  public record Table(String id, List<Row> rows) {
    public Table {
      Objects.requireNonNull(id, "id");
      rows = List.copyOf(rows);
    }
  }

  /** A compiled pack exactly when there are no problems (fail closed). */
  public record CompileResult(Optional<CompiledPack> pack, List<PackProblem> problems) {
    public CompileResult {
      Objects.requireNonNull(pack, "pack");
      problems = List.copyOf(problems);
      if (pack.isPresent() != problems.isEmpty()) {
        throw new IllegalArgumentException("a pack is returned exactly when there are no problems");
      }
    }
  }

  private final String reportId;
  private final Regime regime;
  private final String asAtField;
  private final List<Table> tables;
  private final PackBudget budget;

  private CompiledPack(
      String reportId, Regime regime, String asAtField, List<Table> tables, PackBudget budget) {
    this.reportId = reportId;
    this.regime = regime;
    this.asAtField = asAtField;
    this.tables = List.copyOf(tables);
    this.budget = budget;
  }

  /**
   * Compiles the scope tables of the report with the given id, in the order the report lists them.
   *
   * @return the compiled pack, or the problems sorted by path, line and code
   */
  public static CompileResult compile(LoadedPack pack, String reportId, Rulings rulings) {
    Objects.requireNonNull(pack, "pack");
    Objects.requireNonNull(reportId, "reportId");
    Objects.requireNonNull(rulings, "rulings");
    PackManifest manifest = pack.manifest();
    Optional<ReportDefinition> report =
        manifest.reports().stream().filter(r -> r.id().equals(reportId)).findFirst();
    if (report.isEmpty()) {
      return failed(
          List.of(
              new PackProblem(
                  "REPORT_UNKNOWN",
                  PackManifest.FILE,
                  0,
                  "the pack has no report '" + PackProblem.excerpt(reportId) + "'")));
    }
    PackLimits limits = manifest.limits();
    Map<String, ValueType> variables = declaredTypes(pack.input());
    InHouseExpression expression =
        new InHouseExpression(
            new InHouseExpression.Limits(
                Math.multiplyExact(PARSE_CAP_FACTOR, limits.ruleNodes()),
                limits.listItems(),
                limits.stringCodePoints()),
            rulings);

    List<PackProblem> problems = new ArrayList<>();
    List<Table> tables = new ArrayList<>();
    for (String path : report.get().scopeTables()) {
      DecisionTable table = pack.scopeTables().get(path);
      if (table == null) {
        problems.add(
            new PackProblem(
                "REPORT_TABLE_MISSING",
                path,
                0,
                "the report '" + PackProblem.excerpt(reportId) + "' lists a table the pack lacks"));
        continue;
      }
      tables.add(compileTable(table, expression, variables, limits, problems));
    }
    if (!problems.isEmpty()) {
      return failed(problems);
    }
    PackBudget budget = PackBudget.of(tables, limits);
    return new CompileResult(
        Optional.of(
            new CompiledPack(reportId, new Regime(reportId), manifest.asAtField(), tables, budget)),
        List.of());
  }

  private static CompileResult failed(List<PackProblem> problems) {
    List<PackProblem> sorted = new ArrayList<>(problems);
    Collections.sort(sorted);
    return new CompileResult(Optional.empty(), sorted);
  }

  private static Map<String, ValueType> declaredTypes(CanonicalInputSpec input) {
    Map<String, ValueType> types = new TreeMap<>();
    input.variables().forEach((path, variable) -> types.put(path, variable.type()));
    return types;
  }

  private static Table compileTable(
      DecisionTable table,
      InHouseExpression expression,
      Map<String, ValueType> variables,
      PackLimits limits,
      List<PackProblem> problems) {
    List<Row> rows = new ArrayList<>();
    Set<String> read = new TreeSet<>();
    boolean everyRowCompiled = true;
    for (TableRow row : table.rows()) {
      InHouseExpression.Compiled when;
      try {
        when = expression.compile(row.when(), variables);
      } catch (ExpressionException e) {
        everyRowCompiled = false;
        problems.add(
            new PackProblem(
                "WHEN_DOES_NOT_COMPILE",
                table.path(),
                0,
                "row " + PackProblem.excerpt(row.id()) + ": " + String.valueOf(e.getMessage())));
        continue;
      }
      if (when.nodeCount() > limits.ruleNodes()) {
        problems.add(
            new PackProblem(
                "RULE_OVER_CEILING",
                table.path(),
                0,
                "row "
                    + PackProblem.excerpt(row.id())
                    + " has "
                    + when.nodeCount()
                    + " nodes, the ceiling is "
                    + limits.ruleNodes()));
      }
      read.addAll(when.paths());
      rows.add(new Row(row, when, row.id().equals(table.catchAllRowId())));
    }
    checkHeader(table, variables, read, everyRowCompiled, problems);
    return new Table(table.id(), rows);
  }

  /** The header {@code inputs} must be declared variables and equal the set the rows read. */
  private static void checkHeader(
      DecisionTable table,
      Map<String, ValueType> variables,
      Set<String> read,
      boolean everyRowCompiled,
      List<PackProblem> problems) {
    Set<String> header = new TreeSet<>(table.inputs());
    for (String input : header) {
      if (!variables.containsKey(input)) {
        problems.add(
            new PackProblem(
                "TABLE_INPUT_UNDECLARED",
                table.path(),
                0,
                "the table header lists '"
                    + PackProblem.excerpt(input)
                    + "', which the canonical input does not declare"));
      }
    }
    if (!everyRowCompiled) {
      return;
    }
    for (String path : read) {
      if (!header.contains(path)) {
        problems.add(
            new PackProblem(
                "INPUT_UNDECLARED",
                table.path(),
                0,
                "a rule reads '" + PackProblem.excerpt(path) + "', which the table header omits"));
      }
    }
    for (String input : header) {
      if (variables.containsKey(input) && !read.contains(input)) {
        problems.add(
            new PackProblem(
                "INPUT_UNUSED",
                table.path(),
                0,
                "the table header lists '" + PackProblem.excerpt(input) + "', no rule reads it"));
      }
    }
  }

  /** The id of the report; it is also the id of the {@link Regime}. */
  public String reportId() {
    return reportId;
  }

  /** The regime of the decisions (BRK-14: the id of the report definition). */
  public Regime regime() {
    return regime;
  }

  /** The path of the input timestamp that selects the validity window of the rows. */
  public String asAtField() {
    return asAtField;
  }

  /** The compiled scope tables in the order the report consults them. */
  public List<Table> tables() {
    return tables;
  }

  /** The table the report consults last; its catch-all is the default of the whole report. */
  public Table lastTable() {
    return tables.get(tables.size() - 1);
  }

  public PackBudget budget() {
    return budget;
  }
}
