package dev.regpack.engine.pack;

import dev.regpack.engine.expr.ErrorKind;
import dev.regpack.engine.expr.ExprException;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.exec.RuleEvaluationException;
import dev.regpack.ports.exec.RuleProgram;
import dev.regpack.ports.exec.Trace;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The reference interpreter of the scope tables of one report (spec 6.6: it evaluates the YAML
 * tables directly; plan T16, interpretation rules in companion contracts section 5.6, provisional
 * until gate G-S3b).
 *
 * <p>The program reads the as-at timestamp from the bindings and takes its UTC date. It visits the
 * report's tables in order. In a table it walks the rows ascending by priority, skips a row whose
 * validity window does not contain the date (no charge, no path step), and for every other row
 * enters the row, charges 1, evaluates the {@code when} (a result that is not a bool is an error,
 * never a quiet miss) and records a trace step. The first matching row decides the table. If that
 * row is not the catch-all it is the decision; if it is, the next table is tried, and when every
 * table ends in its catch-all the decision is the last table's catch-all.
 *
 * <p>An expression error is rethrown as a {@link RuleEvaluationException} naming the row, after the
 * kind has been remembered for {@link #lastErrorKind()}. A budget overrun is the port's {@code
 * BudgetExceededException} and passes through untouched.
 *
 * <p>One instance serves one evaluation and is not thread-safe, because it remembers the last error
 * kind and, with a full trace, the rows it evaluated.
 */
public final class PackProgram implements RuleProgram<Bindings, PackProgram.Decision> {
  /** The id under which a failure to read the as-at field is reported (it is not a row). */
  public static final String AS_AT_RULE_ID = "as_at_field";

  /** The row that decided, and the id of the table it belongs to. */
  public record Decision(TableRow row, String tableId) {
    public Decision {
      Objects.requireNonNull(row, "row");
      Objects.requireNonNull(tableId, "tableId");
    }
  }

  /**
   * One evaluated row of the full trace.
   *
   * @param ruleId the row id
   * @param when the expression text
   * @param matched whether the {@code when} was true; false when it failed
   * @param units the cost units the row charged, its own unit included
   * @param error the kind of the evaluation error, if the row failed
   */
  public record RowTrace(
      String ruleId, String when, boolean matched, long units, Optional<ErrorKind> error) {
    public RowTrace {
      Objects.requireNonNull(ruleId, "ruleId");
      Objects.requireNonNull(when, "when");
      Objects.requireNonNull(error, "error");
    }
  }

  /**
   * Thrown, wrapped in a {@link RuleEvaluationException}, when a record reaches a row whose poison
   * circuit is open (spec 7.3). The executor reports it as a failed evaluation of the row, and
   * {@link PackRegime} maps it to {@code EXEC_CIRCUIT_OPEN} through {@link #circuitOpenRule()}, not
   * through the name of the error class. It carries no stack trace: a poisoned rule meets every
   * record.
   */
  public static final class CircuitOpenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String ruleId;

    public CircuitOpenException(String ruleId) {
      super(
          "the circuit of rule " + Objects.requireNonNull(ruleId, "ruleId") + " is open",
          null,
          false,
          false);
      this.ruleId = ruleId;
    }

    /** The row whose circuit is open. */
    public String ruleId() {
      return ruleId;
    }
  }

  private final CompiledPack pack;
  private final boolean fullTrace;
  private final Optional<RuleCircuit> circuit;
  private final List<RowTrace> rowTraces = new ArrayList<>();
  private Optional<ErrorKind> lastErrorKind = Optional.empty();
  private Optional<String> circuitOpenRule = Optional.empty();

  /** A program without a poison circuit. */
  public PackProgram(CompiledPack pack, boolean fullTrace) {
    this(pack, fullTrace, Optional.empty());
  }

  /**
   * @param pack the compiled report
   * @param fullTrace whether to keep a {@link RowTrace} for every evaluated row (for {@code
   *     explain}); the executor's own path is recorded either way
   * @param circuit the poison circuit shared by the evaluations of this report: every row that is
   *     evaluated is recorded in it, and a row whose circuit is open is not evaluated
   */
  public PackProgram(CompiledPack pack, boolean fullTrace, Optional<RuleCircuit> circuit) {
    this.pack = Objects.requireNonNull(pack, "pack");
    this.fullTrace = fullTrace;
    this.circuit = Objects.requireNonNull(circuit, "circuit");
  }

  @Override
  public String id() {
    return "pack-scope-tables-v1";
  }

  @Override
  public Decision run(Bindings input, CostMeter meter, Trace trace) {
    Objects.requireNonNull(input, "input");
    LocalDate date = asAt(input, meter).atZone(ZoneOffset.UTC).toLocalDate();
    Decision fallback = null;
    for (CompiledPack.Table table : pack.tables()) {
      Decision tableCatchAll = null;
      boolean decided = false;
      for (CompiledPack.Row row : table.rows()) {
        if (!inWindow(row.source().valid(), date)) {
          continue;
        }
        if (matches(row, input, meter, trace)) {
          Decision decision = new Decision(row.source(), table.id());
          if (!row.catchAll()) {
            return decision;
          }
          tableCatchAll = decision;
          decided = true;
          break;
        }
      }
      if (!decided) {
        // fail closed: a table whose rows all miss (a catch-all that is not true) decides nothing
        throw new IllegalStateException("no row of table " + table.id() + " matched");
      }
      fallback = tableCatchAll;
    }
    return Objects.requireNonNull(fallback, "the report has no table");
  }

  /** The kind of the last expression error of this evaluation, if there was one. */
  public Optional<ErrorKind> lastErrorKind() {
    return lastErrorKind;
  }

  /** The row at which this evaluation stopped because its circuit is open, if it did. */
  public Optional<String> circuitOpenRule() {
    return circuitOpenRule;
  }

  /** The rows evaluated so far, in order; empty unless the program was built with a full trace. */
  public List<RowTrace> rowTraces() {
    return List.copyOf(rowTraces);
  }

  private Instant asAt(Bindings input, CostMeter meter) {
    meter.enter(AS_AT_RULE_ID);
    Optional<Value> value = input.get(pack.asAtField());
    if (value.isEmpty()) {
      throw failed(
          AS_AT_RULE_ID, new ExprException(ErrorKind.ABSENT, "the as-at field is not bound"));
    }
    if (!(value.get() instanceof TimestampValue timestamp)) {
      throw failed(
          AS_AT_RULE_ID, new ExprException(ErrorKind.TYPE, "the as-at field is not a timestamp"));
    }
    return timestamp.value();
  }

  /** The window is {@code [from, to)} in UTC dates; a row without a window is always valid. */
  private static boolean inWindow(Optional<Validity> window, LocalDate date) {
    if (window.isEmpty()) {
      return true;
    }
    Validity validity = window.get();
    return !date.isBefore(validity.from())
        && validity.to().map(to -> date.isBefore(to)).orElse(true);
  }

  private boolean matches(CompiledPack.Row row, Bindings input, CostMeter meter, Trace trace) {
    meter.enter(row.id());
    if (circuit.isPresent() && circuit.get().isOpen(row.id())) {
      // no charge and no path step: the executor names this row, not the previous one
      circuitOpenRule = Optional.of(row.id());
      throw new RuleEvaluationException(row.id(), new CircuitOpenException(row.id()));
    }
    long before = meter.used();
    boolean matched;
    try {
      meter.charge(1);
      // Fail closed (I5): a non-bool result is an error, never a silent skip to the next row.
      if (!(row.when().evaluate(input, meter) instanceof BoolValue result)) {
        throw new ExprException(ErrorKind.TYPE, "when must be bool");
      }
      matched = result.value();
    } catch (BudgetExceededException e) {
      // blamed on the row that was running when the budget ran out
      circuit.ifPresent(c -> c.record(row.id(), true));
      throw e;
    } catch (ExprException e) {
      circuit.ifPresent(c -> c.record(row.id(), true));
      if (fullTrace) {
        rowTraces.add(
            new RowTrace(
                row.id(),
                row.source().when(),
                false,
                meter.used() - before,
                Optional.of(e.kind())));
      }
      throw failed(row.id(), e);
    }
    circuit.ifPresent(c -> c.record(row.id(), false));
    long units = meter.used() - before;
    trace.step(row.id(), matched, units);
    if (fullTrace) {
      rowTraces.add(new RowTrace(row.id(), row.source().when(), matched, units, Optional.empty()));
    }
    return matched;
  }

  private RuleEvaluationException failed(String ruleId, ExprException e) {
    lastErrorKind = Optional.of(e.kind());
    return new RuleEvaluationException(ruleId, e);
  }
}
