package dev.regpack.spikes.r.bench;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.expr.Expression;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.table.RuleTableProgram;
import dev.regpack.spikes.r.table.TableDecision;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The hot-swap measurement (spec 6.5 step 6, R-5). Evaluator threads pull batches of 1,000 records,
 * pin the active pack once per batch and evaluate the whole batch under it; the measuring thread
 * swaps signed packs in and out. Two invariants are counted, never assumed: a batch that saw two
 * packs (every row id carries the label of its pack, and a batch with two labels is mixed) and
 * records lost (claimed by a batch but not evaluated). The activation time of a swap is the time
 * from "signed pack bytes in memory" to the first record evaluated under the new pack.
 */
public final class HotSwap {
  /** Records of one batch: the evaluator pins the active pack once per batch. */
  public static final int BATCH_SIZE = 1_000;

  private static final int RING = 4_096;
  private static final long WAIT_LIMIT_NANOS = 300_000_000_000L;
  private static final CostBudget UNLIMITED = new CostBudget(Long.MAX_VALUE);

  /** How the evaluators pin the pack; only PER_BATCH is the design, PER_RECORD tests the audit. */
  enum Pinning {
    PER_BATCH,
    PER_RECORD
  }

  /**
   * The measurement.
   *
   * @param activationNanos per swap: signed bytes in memory to the first record under the new pack
   * @param mixedPackBatches batches that saw two packs
   * @param lostRecords records claimed by a batch and not evaluated
   */
  public record Run(long[] activationNanos, long mixedPackBatches, long lostRecords) {
    public Run {
      activationNanos = activationNanos.clone();
    }

    @Override
    public long[] activationNanos() {
      return activationNanos.clone();
    }
  }

  /** The run with the evidence behind it. */
  record Detail(Run run, long submitted, long evaluated, int generationsEvaluated) {}

  /**
   * Decides one record under the pinned program. An empty result means the record was not evaluated
   * (the production decider never returns empty; a test double does, to prove that the lost-records
   * count can become positive).
   */
  @FunctionalInterface
  interface Decider {
    Optional<ExecutionResult<TableDecision>> decide(
        MeteredExecutor executor, RuleTableProgram program, TradeRecord record);
  }

  private static final Decider METERED =
      (executor, program, record) -> Optional.of(executor.execute(program, record, UNLIMITED));

  private HotSwap() {}

  /**
   * Swaps {@code swaps} times between the packs {@code a} and {@code b} (the first swap installs
   * {@code b}) while {@code evaluatorThreads} threads evaluate.
   */
  public static Run measure(
      Expression layer, GeneratedPack a, GeneratedPack b, int evaluatorThreads, int swaps) {
    return measureDetailed(layer, a, b, evaluatorThreads, swaps, Pinning.PER_BATCH, BATCH_SIZE)
        .run();
  }

  /** Counts the records of one batch that were decided under more than one pack. */
  static final class BatchAudit {
    private final Set<String> labels = new HashSet<>();

    void record(String label) {
      labels.add(label);
    }

    /** True when the batch that ends here saw two packs; starts the next batch afresh. */
    boolean endBatch() {
      boolean mixed = labels.size() > 1;
      labels.clear();
      return mixed;
    }
  }

  static Detail measureDetailed(
      Expression layer,
      GeneratedPack a,
      GeneratedPack b,
      int evaluatorThreads,
      int swaps,
      Pinning pinning,
      int batchSize) {
    return measureDetailed(layer, a, b, evaluatorThreads, swaps, pinning, batchSize, METERED);
  }

  static Detail measureDetailed(
      Expression layer,
      GeneratedPack a,
      GeneratedPack b,
      int evaluatorThreads,
      int swaps,
      Pinning pinning,
      int batchSize,
      Decider decider) {
    if (evaluatorThreads < 1 || swaps < 1 || batchSize < 1) {
      throw new IllegalArgumentException("threads, swaps and batch size must be at least 1");
    }
    GeneratedPack labelledA = relabel(a, "A");
    GeneratedPack labelledB = relabel(b, "B");
    KeyPair keys = newKeys();
    byte[] irA = labelledA.irJson().getBytes(StandardCharsets.UTF_8);
    byte[] irB = labelledB.irJson().getBytes(StandardCharsets.UTF_8);
    byte[] signatureA = sign(keys, irA);
    byte[] signatureB = sign(keys, irB);

    PackHolder holder = new PackHolder(CompiledPack.compile(labelledA, layer));
    Shared shared =
        new Shared(holder, ThroughputBench.ring(2L, RING), swaps + 1, pinning, batchSize);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < evaluatorThreads; i++) {
      Thread thread = new Thread(new EvaluatorLoop(shared, decider), "spike-r-swap-eval-" + i);
      thread.setDaemon(true);
      threads.add(thread);
      thread.start();
    }
    long[] activation = new long[swaps];
    try {
      waitSeen(shared, 0);
      SplitMix64 delays = new SplitMix64(42L);
      pause(delays);
      for (int i = 1; i <= swaps; i++) {
        boolean toB = i % 2 == 1;
        byte[] bytes = toB ? irB : irA;
        byte[] signature = toB ? signatureB : signatureA;
        long start = System.nanoTime();
        if (!holder.activate(bytes, signature, keys.getPublic(), layer)) {
          throw new IllegalStateException("swap " + i + " was refused");
        }
        int generation = (int) holder.current().generation();
        waitSeen(shared, generation);
        activation[i - 1] = Math.max(1, shared.firstSeen.get(generation) - start);
        pause(delays);
      }
    } finally {
      shared.stop = true;
      for (Thread thread : threads) {
        join(thread);
      }
    }
    Throwable failure = shared.failure.get();
    if (failure != null) {
      throw new IllegalStateException("an evaluator thread failed: " + failure, failure);
    }
    int seen = 0;
    for (int g = 0; g < shared.firstSeen.length(); g++) {
      if (shared.firstSeen.get(g) != 0) {
        seen++;
      }
    }
    long submitted = shared.submitted.get();
    long evaluated = shared.evaluated.get();
    return new Detail(
        new Run(activation, shared.mixed.get(), submitted - evaluated), submitted, evaluated, seen);
  }

  /** State shared by the evaluators and the measuring thread. */
  private static final class Shared {
    final PackHolder holder;
    final TradeRecord[] ring;
    final AtomicLongArray firstSeen;
    final Pinning pinning;
    final int batchSize;
    final AtomicLong submitted = new AtomicLong();
    final AtomicLong evaluated = new AtomicLong();
    final AtomicLong mixed = new AtomicLong();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    volatile boolean stop;

    Shared(PackHolder holder, TradeRecord[] ring, int generations, Pinning pinning, int batch) {
      this.holder = holder;
      this.ring = ring;
      this.firstSeen = new AtomicLongArray(generations);
      this.pinning = pinning;
      this.batchSize = batch;
    }

    void markSeen(long generation) {
      int g = (int) generation;
      if (g < firstSeen.length() && firstSeen.get(g) == 0) {
        firstSeen.compareAndSet(g, 0, Math.max(1, System.nanoTime()));
      }
    }
  }

  private static final class EvaluatorLoop implements Runnable {
    private final Shared shared;
    private final MeteredExecutor executor = new MeteredExecutor();
    private final BatchAudit audit = new BatchAudit();
    private final Decider decider;
    private long cachedGeneration = -1;
    private RuleTableProgram program;

    EvaluatorLoop(Shared shared, Decider decider) {
      this.shared = shared;
      this.decider = decider;
    }

    @Override
    public void run() {
      try {
        while (!shared.stop) {
          long first = shared.submitted.getAndAdd(shared.batchSize);
          PackHolder.Active pinned = shared.holder.current();
          long done = 0;
          for (int k = 0; k < shared.batchSize; k++) {
            PackHolder.Active active =
                shared.pinning == Pinning.PER_RECORD ? shared.holder.current() : pinned;
            RuleTableProgram current = programFor(active);
            TradeRecord record = shared.ring[(int) ((first + k) % shared.ring.length)];
            // one decision per record, whichever of the three results it is; a record without a
            // decision stays in "submitted" only, and that difference is the lost-records count
            Optional<ExecutionResult<TableDecision>> decision =
                decider.decide(executor, current, record);
            if (decision.isPresent()) {
              audit.record(label(ruleId(decision.get())));
              done++;
            }
            if (k == 0 || shared.pinning == Pinning.PER_RECORD) {
              shared.markSeen(active.generation());
            }
          }
          shared.evaluated.addAndGet(done);
          if (audit.endBatch()) {
            shared.mixed.incrementAndGet();
          }
        }
      } catch (RuntimeException | Error e) {
        shared.failure.compareAndSet(null, e);
      }
    }

    private RuleTableProgram programFor(PackHolder.Active active) {
      if (active.generation() != cachedGeneration) {
        program = new RuleTableProgram(active.pack(), false);
        cachedGeneration = active.generation();
      }
      return program;
    }
  }

  private static String ruleId(ExecutionResult<TableDecision> result) {
    return switch (result) {
      case ExecutionResult.Completed<TableDecision> done -> done.value().ruleId();
      case ExecutionResult.Failed<TableDecision> failed -> failed.ruleId();
      case ExecutionResult.BudgetExhausted<TableDecision> out -> out.ruleId();
    };
  }

  private static String label(String ruleId) {
    int colon = ruleId.indexOf(':');
    return colon < 0 ? "?" : ruleId.substring(0, colon);
  }

  /** The same pack with every rule id prefixed by {@code label:}, so a decision names its pack. */
  private static GeneratedPack relabel(GeneratedPack pack, String label) {
    List<List<GeneratedRule>> tables = new ArrayList<>();
    for (List<GeneratedRule> table : pack.tables()) {
      List<GeneratedRule> rows = new ArrayList<>();
      for (GeneratedRule r : table) {
        rows.add(
            new GeneratedRule(
                label + ":" + r.id(), r.table(), r.priority(), r.when(), r.outcome(), r.reason()));
      }
      tables.add(rows);
    }
    return new GeneratedPack(
        pack.id() + "-" + label, pack.seed(), pack.shape(), pack.ruleCount(), tables);
  }

  private static void waitSeen(Shared shared, int generation) {
    long start = System.nanoTime();
    while (shared.firstSeen.get(generation) == 0) {
      if (shared.failure.get() != null) {
        throw new IllegalStateException("an evaluator thread failed: " + shared.failure.get());
      }
      if (System.nanoTime() - start > WAIT_LIMIT_NANOS) {
        throw new IllegalStateException("no evaluator picked up generation " + generation);
      }
      LockSupport.parkNanos(20_000);
    }
  }

  /** A short pause of 0 to 3 ms, so that swaps land at different phases of a batch. */
  private static void pause(SplitMix64 delays) {
    LockSupport.parkNanos(delays.nextInt(3_000) * 1_000L);
  }

  private static void join(Thread thread) {
    try {
      thread.join(WAIT_LIMIT_NANOS / 1_000_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static KeyPair newKeys() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("no Ed25519 key pair", e);
    }
  }

  private static byte[] sign(KeyPair keys, byte[] data) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(keys.getPrivate());
      signature.update(data);
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot sign the pack", e);
    }
  }
}
