package dev.regpack.engine.pack;

import dev.regpack.engine.expr.ErrorKind;
import dev.regpack.engine.expr.ExprException;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.exec.RuleEvaluationException;
import dev.regpack.ports.exec.RuleProgram;
import dev.regpack.ports.exec.Trace;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.StringValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The derive step of one report (plan W1a, task T5, decision D-W5): it walks the derive rows in
 * order and, for every row whose {@code when} is true, evaluates the {@code value} and keeps it as
 * text. A row whose {@code when} is false gives no field at all: the field is absent from the
 * result, not empty.
 *
 * <p>The text is the one the {@code string} function of the expression language gives (a decimal as
 * plain text without trailing zeros, an instant with six fraction digits, an enumeration by its
 * name, a bool as {@code true} or {@code false}); {@link CompiledPack} compiles every {@code value}
 * as {@code string(value)}, so no second rendering exists to drift from it. A {@code when} that is
 * not a bool, a read of an absent optional path that no {@code has} guards and a value that has no
 * string form are errors of the row, never a quiet miss. Every row charges the meter before the
 * work it pays for; the steps of the trace are the rows considered.
 *
 * <p>One instance serves one evaluation and is not thread-safe, because it remembers the kind of
 * the last error.
 */
public final class DeriveProgram implements RuleProgram<Bindings, List<DeriveProgram.Derived>> {
  /** One derived value: the row that gave it, the field, the text and the rule reference. */
  public record Derived(String rowId, String field, String value, String ruleRef) {
    public Derived {
      Objects.requireNonNull(rowId, "rowId");
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(ruleRef, "ruleRef");
    }
  }

  private final List<CompiledPack.DeriveRow> rows;
  private Optional<ErrorKind> lastErrorKind = Optional.empty();

  /** The derive program of the compiled report; a report without derive tables derives nothing. */
  public DeriveProgram(CompiledPack pack) {
    this.rows = Objects.requireNonNull(pack, "pack").deriveRows();
  }

  @Override
  public String id() {
    return "pack-derive-tables-v1";
  }

  @Override
  public List<Derived> run(Bindings input, CostMeter meter, Trace trace) {
    Objects.requireNonNull(input, "input");
    List<Derived> out = new ArrayList<>();
    for (CompiledPack.DeriveRow row : rows) {
      meter.enter(row.id());
      long before = meter.used();
      meter.charge(1);
      boolean applies;
      try {
        // fail closed (I5): a non-bool result is an error, never a silent skip
        if (!(row.when().evaluate(input, meter) instanceof BoolValue result)) {
          throw new ExprException(ErrorKind.TYPE, "when must be bool");
        }
        applies = result.value();
        if (applies) {
          Value value = row.value().evaluate(input, meter);
          if (!(value instanceof StringValue text)) {
            throw new ExprException(ErrorKind.TYPE, "the value has no string form");
          }
          out.add(
              new Derived(row.id(), row.source().field(), text.value(), row.source().ruleRef()));
        }
      } catch (ExprException e) {
        lastErrorKind = Optional.of(e.kind());
        throw new RuleEvaluationException(row.id(), e);
      }
      trace.step(row.id(), applies, meter.used() - before);
    }
    return List.copyOf(out);
  }

  /** The kind of the last expression error of this evaluation, if there was one. */
  public Optional<ErrorKind> lastErrorKind() {
    return lastErrorKind;
  }
}
