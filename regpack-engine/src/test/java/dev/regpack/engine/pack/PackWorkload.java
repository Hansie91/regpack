package dev.regpack.engine.pack;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.proto.Workload;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import java.util.Objects;

/**
 * One thread of the throughput check: it runs the pack over a shared, read-only ring of records,
 * trace off, under the per-record budget of the pack, and folds the outcome, the deciding row and
 * the cost units of every record into a checksum, so that the JIT cannot discard the work and the
 * checksum depends on the rules. One unit is one record. The thread starts at a ring position that
 * depends on its index, so the threads of one run do not walk the same records in step.
 *
 * <p>Not thread-safe: one instance, with its own program and bindings, per evaluating thread. The
 * compiled pack and the ring may be shared.
 */
final class PackWorkload implements Workload {
  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final TradeRecord[] ring;
  private final PackProgram program;
  private final TradeRecordBindings bindings = new TradeRecordBindings();
  private final MeteredExecutor executor = new MeteredExecutor();
  private final CostBudget budget;
  private int position;
  private long checksum;
  private long records;
  private long execOutcomes;

  PackWorkload(CompiledPack pack, TradeRecord[] ring, int threadIndex) {
    Objects.requireNonNull(pack, "pack");
    this.ring = Objects.requireNonNull(ring, "ring");
    if (ring.length == 0) {
      throw new IllegalArgumentException("the ring has no record");
    }
    if (threadIndex < 0) {
      throw new IllegalArgumentException("threadIndex must not be negative: " + threadIndex);
    }
    this.program = new PackProgram(pack, false);
    this.budget = new CostBudget(pack.budget().perRecordUnits());
    this.position = (int) Long.remainderUnsigned(threadIndex * GOLDEN, ring.length);
  }

  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    long folded = checksum;
    long failures = 0;
    int at = position;
    for (long i = 0; i < maxUnits; i++) {
      bindings.reset(ring[at]);
      ExecutionResult<PackProgram.Decision> result = executor.execute(program, bindings, budget);
      long tag;
      String ruleId;
      long units;
      switch (result) {
        case ExecutionResult.Completed<PackProgram.Decision> done -> {
          tag = 1 + done.value().row().outcome().ordinal();
          ruleId = done.value().row().id();
          units = done.costUnits();
        }
        case ExecutionResult.Failed<PackProgram.Decision> failed -> {
          tag = 100;
          ruleId = failed.ruleId();
          units = failed.costUnits();
          failures++;
        }
        case ExecutionResult.BudgetExhausted<PackProgram.Decision> out -> {
          tag = 200;
          ruleId = out.ruleId();
          units = out.costUnits();
          failures++;
        }
      }
      folded = Long.rotateLeft(folded, 7) * 31 + tag * 1_000_003L + ruleId.hashCode() + units;
      if (++at == ring.length) {
        at = 0;
      }
    }
    position = at;
    checksum = folded;
    records += maxUnits;
    execOutcomes += failures;
    return maxUnits;
  }

  @Override
  public long checksum() {
    return checksum;
  }

  /** The records evaluated so far. */
  long records() {
    return records;
  }

  /** The records so far that ended in {@code EXEC_ERROR} or {@code EXEC_BUDGET}. */
  long execOutcomes() {
    return execOutcomes;
  }
}
