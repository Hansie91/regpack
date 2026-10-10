package dev.regpack.spikes.r.bench;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.Workload;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.expr.Expression;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.table.RuleTableProgram;
import dev.regpack.spikes.r.table.TableDecision;
import java.util.Objects;

/**
 * One thread of the throughput benchmark: it runs {@link MeteredExecutor#execute} over a shared,
 * read-only ring of records, trace off, and folds the outcome and the rule id of every decision
 * into a checksum, so that the JIT cannot discard the work. One unit is one record. The thread
 * starts at a position of the ring that depends on its index, so the threads of one run do not walk
 * the same records in step.
 *
 * <p>Not thread-safe: use one instance per thread. The compiled pack may be shared.
 */
public final class ThroughputBench implements Workload {
  /** Records in the ring of a full run (spec: at least 200,000 distinct records). */
  public static final int RING_SIZE = 262_144;

  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final RuleTableProgram program;
  private final TradeRecord[] ring;
  private final CostBudget budget;
  private final MeteredExecutor executor = new MeteredExecutor();
  private final int start;
  private int position;
  private long checksum;

  /** Compiles the pack with the layer; use the other constructor to share one compiled pack. */
  public ThroughputBench(
      Expression layer, GeneratedPack pack, TradeRecord[] ring, int threadIndex, long budget) {
    this(CompiledPack.compile(pack, layer), ring, threadIndex, budget);
  }

  public ThroughputBench(CompiledPack pack, TradeRecord[] ring, int threadIndex, long budget) {
    Objects.requireNonNull(pack, "pack");
    Objects.requireNonNull(ring, "ring");
    if (ring.length == 0) {
      throw new IllegalArgumentException("the ring has no record");
    }
    if (threadIndex < 0) {
      throw new IllegalArgumentException("threadIndex must not be negative: " + threadIndex);
    }
    this.program = new RuleTableProgram(pack, false);
    this.ring = ring;
    this.budget = new CostBudget(budget);
    this.start = (int) Long.remainderUnsigned(threadIndex * GOLDEN, ring.length);
    this.position = start;
  }

  /** A ring of {@code size} consecutive records of the generator with the seed. */
  public static TradeRecord[] ring(long seed, int size) {
    if (size < 1) {
      throw new IllegalArgumentException("size must be at least 1: " + size);
    }
    TradeRecordGen generator = new TradeRecordGen(seed);
    TradeRecord[] ring = new TradeRecord[size];
    for (int i = 0; i < size; i++) {
      ring[i] = generator.next();
    }
    return ring;
  }

  /** Where in the ring this thread began. */
  public int startPosition() {
    return start;
  }

  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    long folded = checksum;
    int at = position;
    for (long i = 0; i < maxUnits; i++) {
      ExecutionResult<TableDecision> result = executor.execute(program, ring[at], budget);
      folded = fold(folded, result);
      if (++at == ring.length) {
        at = 0;
      }
    }
    position = at;
    checksum = folded;
    return maxUnits;
  }

  @Override
  public long checksum() {
    return checksum;
  }

  private static long fold(long folded, ExecutionResult<TableDecision> result) {
    long tag;
    String ruleId;
    switch (result) {
      case ExecutionResult.Completed<TableDecision> done -> {
        tag = 1 + outcomeOrdinal(done.value().outcome());
        ruleId = done.value().ruleId();
      }
      case ExecutionResult.Failed<TableDecision> failed -> {
        tag = 8;
        ruleId = failed.ruleId();
      }
      case ExecutionResult.BudgetExhausted<TableDecision> out -> {
        tag = 9;
        ruleId = out.ruleId();
      }
    }
    return Long.rotateLeft(folded, 7) * 31 + tag * 1_000_003L + ruleId.hashCode();
  }

  private static int outcomeOrdinal(String outcome) {
    return switch (outcome) {
      case "REPORT" -> 0;
      case "DO_NOT_REPORT" -> 1;
      case "REPORT_BY_OTHER" -> 2;
      case "UNDETERMINED" -> 3;
      default -> throw new IllegalStateException("unknown outcome " + outcome);
    };
  }
}
