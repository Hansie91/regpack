package dev.regpack.spikes.r.bench;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.proto.Workload;
import dev.regpack.engine.explain.Tier1Explainer;
import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.model.decision.PathStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The render half of the explain gate (R-4): the time the P0 {@link Tier1Explainer} takes to turn a
 * stored {@link DecisionRecord} into text, for evaluation paths of 1 to 200 steps. It measures the
 * render only: reading the decision from the ledger is L-9.
 */
public final class ExplainBench {
  /** The longest evaluation path of the pool (a pack of 200 rules visits at most 200 rows). */
  public static final int MAX_PATH = 200;

  private static final Instant AS_AT = Instant.parse("2026-10-09T10:00:00Z");
  private static volatile long sink;

  private ExplainBench() {}

  /** One decision record per path length from 1 to {@link #MAX_PATH}, in that order. */
  public static List<DecisionRecord> decisionRecords() {
    List<DecisionRecord> records = new ArrayList<>(MAX_PATH);
    for (int length = 1; length <= MAX_PATH; length++) {
      records.add(record(length));
    }
    return List.copyOf(records);
  }

  private static DecisionRecord record(int pathLength) {
    List<PathStep> path = new ArrayList<>(pathLength);
    List<String> hits = new ArrayList<>();
    for (int i = 0; i < pathLength; i++) {
      boolean matched = i == pathLength - 1;
      String ruleId = "SYN.T" + (1 + i / 20) + ".R" + (1 + i % 20);
      path.add(new PathStep(ruleId, matched, 3 + (i % 5)));
      if (matched) {
        hits.add(ruleId);
      }
    }
    DecisionBody body =
        new DecisionBody(
            EventIdentity.of("SYNFIRM0000000000001", "EX-" + pathLength),
            new Regime("mifir.eu.rts22"),
            Sha256.ofUtf8("context-" + pathLength),
            AS_AT,
            AS_AT,
            Outcome.REPORT,
            List.of("SYNTHETIC"),
            new ReasonCode("SYN.T01.R001"),
            "SYN.T01",
            hits,
            path,
            List.of(
                new Citation("SYN-SOURCE", "art.1(2)", CitationRole.BASIS, Optional.empty()),
                new Citation("SYN-SOURCE", "art.3", CitationRole.GUIDANCE, Optional.empty())),
            List.of(Leg.MARKET, Leg.CLIENT),
            Sha256.ofUtf8("input-" + pathLength));
    return new DecisionRecord(new DecisionId(pathLength), 1, Optional.empty(), body);
  }

  /** The pool indexes of {@code samples} renders, drawn uniformly from the pool by the seed. */
  static int[] sampleOrder(int samples, long seed) {
    SplitMix64 rng = new SplitMix64(seed);
    int[] order = new int[samples];
    for (int i = 0; i < samples; i++) {
      order[i] = rng.nextInt(MAX_PATH);
    }
    return order;
  }

  /**
   * The nanoseconds of {@code samples} renders, each of a record whose path length is drawn
   * uniformly from 1 to 200. There is no warm-up here: a caller that wants a warm JVM runs it first
   * (the fork protocol does, see {@link RenderCycle}).
   */
  public static long[] tier1RenderNanos(int samples, long seed) {
    if (samples < 1) {
      throw new IllegalArgumentException("samples must be at least 1: " + samples);
    }
    List<DecisionRecord> pool = decisionRecords();
    return renders(pool, sampleOrder(samples, seed));
  }

  private static long[] renders(List<DecisionRecord> pool, int[] order) {
    long[] nanos = new long[order.length];
    long folded = 0;
    for (int i = 0; i < order.length; i++) {
      DecisionRecord record = pool.get(order[i]);
      long start = System.nanoTime();
      String text = Tier1Explainer.explain(record);
      long elapsed = System.nanoTime() - start;
      nanos[i] = Math.max(1, elapsed);
      folded = folded * 31 + text.length();
    }
    sink = folded;
    return nanos;
  }

  /**
   * One cycle of the fork protocol is one batch of renders. The runner's cycle mode runs an untimed
   * cycle and three timed ones, so a fork warms up on the first three batches and the samples of
   * the last one are the fork's: {@link #lastSamples()}. Every cycle draws a new batch.
   */
  public static final class RenderCycle implements Workload {
    private final int samples;
    private final long seed;
    private final List<DecisionRecord> pool = decisionRecords();
    private long cycles;
    private long[] last = new long[0];
    private long checksum;

    public RenderCycle(int samples, long seed) {
      if (samples < 1) {
        throw new IllegalArgumentException("samples must be at least 1: " + samples);
      }
      this.samples = samples;
      this.seed = seed;
    }

    @Override
    public long runUnits(long maxUnits) {
      if (maxUnits != samples) {
        throw new IllegalArgumentException(
            "a cycle is " + samples + " renders, asked for " + maxUnits);
      }
      last = renders(pool, sampleOrder(samples, seed + cycles++));
      checksum += sink;
      return samples;
    }

    @Override
    public long checksum() {
      return checksum;
    }

    @Override
    public long cycleUnits() {
      return samples;
    }

    /** The nanoseconds of the renders of the latest cycle. */
    public long[] lastSamples() {
      return last.clone();
    }
  }
}
