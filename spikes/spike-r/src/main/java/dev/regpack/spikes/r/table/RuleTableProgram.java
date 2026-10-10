package dev.regpack.spikes.r.table;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.exec.RuleEvaluationException;
import dev.regpack.ports.exec.RuleProgram;
import dev.regpack.ports.exec.Trace;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value.BoolValue;
import java.util.Objects;

/**
 * The disposable table interpreter of SP-R, run by the P0 executor. A record visits the tables in
 * order; in each table the first row by ascending priority whose {@code when} is true decides that
 * table. If the deciding row is the catch-all the record goes on to the next table, otherwise the
 * decision is that row's outcome. If every table ends in its catch-all the decision is UNDETERMINED
 * from the last catch-all. Each row evaluated charges one unit and then whatever its expression
 * charges; the meter is told the row first, so a failure names it.
 *
 * <p>An expression error fails closed: it is rethrown as a {@link RuleEvaluationException} and the
 * executor reports {@code Failed}; an exhausted budget propagates and is reported as {@code
 * BudgetExhausted}. Nothing here allocates a map per record. The instance owns a {@link
 * TradeBindings}: use one instance per thread (the {@link CompiledPack} may be shared).
 */
public final class RuleTableProgram implements RuleProgram<TradeRecord, TableDecision> {
  private final CompiledPack pack;
  private final CompiledPack.Row[][] tables;
  private final boolean traceOn;
  private final TradeBindings bindings = new TradeBindings();

  /** A program with the trace off (the throughput configuration). */
  public RuleTableProgram(CompiledPack pack) {
    this(pack, false);
  }

  public RuleTableProgram(CompiledPack pack, boolean traceOn) {
    this.pack = Objects.requireNonNull(pack, "pack");
    this.traceOn = traceOn;
    this.tables = new CompiledPack.Row[pack.tables().size()][];
    for (int t = 0; t < tables.length; t++) {
      tables[t] = pack.tables().get(t).toArray(new CompiledPack.Row[0]);
    }
  }

  @Override
  public String id() {
    return "rule-table-" + pack.id();
  }

  @Override
  public TableDecision run(TradeRecord input, CostMeter meter, Trace trace) {
    bindings.reset(input);
    int tablesVisited = 0;
    int rowsEvaluated = 0;
    String lastCatchAll = "";
    for (CompiledPack.Row[] table : tables) {
      tablesVisited++;
      boolean decided = false;
      for (CompiledPack.Row row : table) {
        rowsEvaluated++;
        meter.enter(row.id());
        long before = meter.used();
        meter.charge(1);
        boolean matched = matches(row, meter);
        if (traceOn) {
          trace.step(row.id(), matched, meter.used() - before);
        }
        if (matched) {
          if (!row.catchAll()) {
            return new TableDecision(row.outcome(), row.id(), tablesVisited, rowsEvaluated);
          }
          lastCatchAll = row.id();
          decided = true;
          break;
        }
      }
      if (!decided) {
        throw new IllegalStateException("table ended without its catch-all hitting");
      }
    }
    return new TableDecision("UNDETERMINED", lastCatchAll, tablesVisited, rowsEvaluated);
  }

  private boolean matches(CompiledPack.Row row, CostMeter meter) {
    try {
      // Fail closed (I5): a non-bool result is an error, never a silent skip to the next row.
      if (row.when().evaluate(bindings, meter) instanceof BoolValue result) {
        return result.value();
      }
      throw new ExpressionException("when must be bool: " + row.id());
    } catch (ExpressionException e) {
      throw new RuleEvaluationException(row.id(), e);
    }
  }
}
