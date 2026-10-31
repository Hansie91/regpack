package dev.regpack.engine.pack;

import dev.regpack.engine.expr.InHouseExpression;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Regime;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLimits;
import dev.regpack.pack.model.PackManifest;
import dev.regpack.pack.model.ReportDefinition;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.report.DeriveTable;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
 *
 * <p>The derive tables of the report (decision D-W5) are read and compiled with the same layer
 * against the same variables. Their problem codes are those of {@link DeriveTable#parse} and, from
 * {@link #compile}: {@code DERIVE_TABLE_MISSING} (the report lists a derive file the loaded pack
 * lacks), {@code DERIVE_DOES_NOT_COMPILE} (a {@code when} or a {@code value} that does not compile,
 * an undeclared path included), {@code DERIVE_OVER_CEILING} (more than {@code limits.rule_nodes}
 * nodes), {@code DERIVE_ROW_ID_DUPLICATE}, {@code DERIVE_RULE_REF_DUPLICATE} and {@code
 * DERIVE_FIELD_DUPLICATE} (an id, a rule reference or a field used twice among the scope and derive
 * rows of the report) and {@code DERIVE_CITE_UNRESOLVED} (a citation names a source or a provision
 * the pack lacks). A {@code value} is compiled twice: as written, which proves that it is one
 * expression and gives its node count, and as {@code string(value)}, which is what runs, so that a
 * derived field has the text of the {@code string} function and no other.
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

  /**
   * One compiled derive row: the row as read, its {@code when} and the {@code value} as {@code
   * string(value)}.
   */
  public record DeriveRow(
      DeriveTable.Row source, InHouseExpression.Compiled when, InHouseExpression.Compiled value) {
    public DeriveRow {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(when, "when");
      Objects.requireNonNull(value, "value");
    }

    public String id() {
      return source.id();
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
  private final List<DeriveRow> deriveRows;
  private final long recordUnits;

  private CompiledPack(
      String reportId,
      Regime regime,
      String asAtField,
      List<Table> tables,
      PackBudget budget,
      List<DeriveRow> deriveRows,
      long deriveUnits) {
    this.reportId = reportId;
    this.regime = regime;
    this.asAtField = asAtField;
    this.tables = List.copyOf(tables);
    this.budget = budget;
    this.deriveRows = List.copyOf(deriveRows);
    this.recordUnits = Math.max(budget.perRecordUnits(), deriveUnits);
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
    List<DeriveRow> deriveRows =
        compileDerive(pack, report.get(), tables, expression, variables, limits, problems);
    if (!problems.isEmpty()) {
      return failed(problems);
    }
    PackBudget budget = PackBudget.of(tables, limits);
    return new CompileResult(
        Optional.of(
            new CompiledPack(
                reportId,
                new Regime(reportId),
                manifest.asAtField(),
                tables,
                budget,
                deriveRows,
                deriveUnits(deriveRows, limits))),
        List.of());
  }

  /**
   * The budget of the derive step of one record: {@code limits.record_budget} if the pack declares
   * one, else 2 times the sum over the rows of 1 plus the static worst case of the {@code when} and
   * of the {@code value} (the rule of {@link PackBudget#of}); 0 without derive rows.
   */
  private static long deriveUnits(List<DeriveRow> rows, PackLimits limits) {
    if (rows.isEmpty()) {
      return 0;
    }
    long sum = 0;
    for (DeriveRow row : rows) {
      long own =
          Math.addExact(
              1L,
              Math.addExact(row.when().staticWorstCaseUnits(), row.value().staticWorstCaseUnits()));
      sum = Math.addExact(sum, own);
    }
    return limits.recordBudget().isPresent()
        ? limits.recordBudget().getAsLong()
        : Math.multiplyExact(2L, sum);
  }

  /** Reads and compiles the derive tables of the report; every problem is added to the list. */
  private static List<DeriveRow> compileDerive(
      LoadedPack pack,
      ReportDefinition report,
      List<Table> scope,
      InHouseExpression expression,
      Map<String, ValueType> variables,
      PackLimits limits,
      List<PackProblem> problems) {
    Set<String> ids = new HashSet<>();
    Set<String> refs = new HashSet<>();
    Set<String> fields = new HashSet<>();
    for (Table table : scope) {
      for (Row row : table.rows()) {
        ids.add(row.id());
        row.source().ruleRef().ifPresent(refs::add);
      }
    }
    Seen seen = new Seen(ids, refs, fields);
    List<DeriveRow> rows = new ArrayList<>();
    for (String path : report.deriveTables()) {
      YamlNode node = pack.opaque().get(path);
      if (node == null) {
        problems.add(
            new PackProblem(
                "DERIVE_TABLE_MISSING",
                path,
                0,
                "the report '"
                    + PackProblem.excerpt(report.id())
                    + "' lists a derive table the pack lacks"));
        continue;
      }
      ProblemSink found = ProblemSink.forFile();
      Optional<DeriveTable> table = DeriveTable.parse(path, node, found);
      problems.addAll(found.sorted(path));
      if (table.isEmpty()) {
        continue;
      }
      for (DeriveTable.Row row : table.get().rows()) {
        compileDeriveRow(pack, path, row, expression, variables, limits, seen, rows, problems);
      }
    }
    return rows;
  }

  /** The ids, rule references and fields of the rows compiled so far. */
  private record Seen(Set<String> ids, Set<String> refs, Set<String> fields) {}

  private static void compileDeriveRow(
      LoadedPack pack,
      String path,
      DeriveTable.Row row,
      InHouseExpression expression,
      Map<String, ValueType> variables,
      PackLimits limits,
      Seen seen,
      List<DeriveRow> rows,
      List<PackProblem> problems) {
    String who = "row " + PackProblem.excerpt(row.id());
    if (!seen.ids().add(row.id())) {
      problems.add(
          new PackProblem(
              "DERIVE_ROW_ID_DUPLICATE", path, 0, who + " reuses the id of a row of the report"));
    }
    if (!seen.refs().add(row.ruleRef())) {
      problems.add(
          new PackProblem(
              "DERIVE_RULE_REF_DUPLICATE",
              path,
              0,
              who + " reuses the rule_ref of a row of the report"));
    }
    if (!seen.fields().add(row.field())) {
      problems.add(
          new PackProblem(
              "DERIVE_FIELD_DUPLICATE",
              path,
              0,
              who + " gives a field that another derive row of the report gives"));
    }
    for (CitationRef cite : row.cite()) {
      if (pack.sources().resolve(cite.sourceId(), cite.provisionPath()).isEmpty()) {
        problems.add(
            new PackProblem(
                "DERIVE_CITE_UNRESOLVED",
                path,
                0,
                who
                    + " cites "
                    + PackProblem.excerpt(cite.provisionPath())
                    + " of "
                    + PackProblem.excerpt(cite.sourceId())
                    + ", which the pack lacks"));
      }
    }
    InHouseExpression.Compiled when =
        compileDeriveText(row.when(), who, path, expression, variables, limits, problems);
    InHouseExpression.Compiled single =
        compileDeriveText(row.value(), who, path, expression, variables, limits, problems);
    if (when == null || single == null) {
      return;
    }
    try {
      rows.add(
          new DeriveRow(row, when, expression.compile("string(" + row.value() + ")", variables)));
    } catch (ExpressionException e) {
      problems.add(
          new PackProblem(
              "DERIVE_DOES_NOT_COMPILE", path, 0, who + ": " + String.valueOf(e.getMessage())));
    }
  }

  /** Compiles one text as written; null (with a problem) when it does not compile or is too big. */
  private static InHouseExpression.Compiled compileDeriveText(
      String text,
      String who,
      String path,
      InHouseExpression expression,
      Map<String, ValueType> variables,
      PackLimits limits,
      List<PackProblem> problems) {
    InHouseExpression.Compiled compiled;
    try {
      compiled = expression.compile(text, variables);
    } catch (ExpressionException e) {
      problems.add(
          new PackProblem(
              "DERIVE_DOES_NOT_COMPILE", path, 0, who + ": " + String.valueOf(e.getMessage())));
      return null;
    }
    if (compiled.nodeCount() > limits.ruleNodes()) {
      problems.add(
          new PackProblem(
              "DERIVE_OVER_CEILING",
              path,
              0,
              who
                  + " has "
                  + compiled.nodeCount()
                  + " nodes, the ceiling is "
                  + limits.ruleNodes()));
      return null;
    }
    return compiled;
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

  /** The compiled derive rows of the report in file order; empty when the report has none. */
  public List<DeriveRow> deriveRows() {
    return deriveRows;
  }

  /**
   * The units one record may charge in either step: the larger of the budget of the scope tables
   * and the budget of the derive step. It equals {@code budget().perRecordUnits()} when the report
   * has no derive rows.
   */
  public long recordUnits() {
    return recordUnits;
  }
}
