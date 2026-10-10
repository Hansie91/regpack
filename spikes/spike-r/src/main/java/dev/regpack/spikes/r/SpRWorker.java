package dev.regpack.spikes.r;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.proto.ForkResult;
import dev.regpack.bench.proto.ForkRunner;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.proto.Protocol;
import dev.regpack.bench.proto.StealSource;
import dev.regpack.bench.proto.Workload;
import dev.regpack.bench.stats.Stats;
import dev.regpack.ports.expr.Expression;
import dev.regpack.spikes.r.bench.ExplainBench;
import dev.regpack.spikes.r.bench.ParallelWorkload;
import dev.regpack.spikes.r.bench.ThroughputBench;
import dev.regpack.spikes.r.cel.CelJavaExpression;
import dev.regpack.spikes.r.cel.CelJavaLayer;
import dev.regpack.spikes.r.defaults.BudgetDefaults;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import dev.regpack.spikes.r.inhouse.InHouseLayer;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.vectors.ExpressionLayer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fork child of the SP-R benchmarks: one fresh JVM, one measurement under the fixed protocol,
 * one {@code FORK-RESULT} line on standard output (and nothing else that starts with that marker).
 * Two benchmarks: {@code throughput} (a generated pack over a ring of records, one or more threads)
 * and {@code explain} (the Tier-1 render, reported as the p99 of the last batch of renders).
 */
final class SpRWorker {
  /** The seed of the generated packs and of the record ring. */
  static final long SEED = 1L;

  /** Samples of one explain fork, and its batches: three of warm-up, one measured. */
  static final int EXPLAIN_SAMPLES = 5_000;

  private static final Set<String> OPTIONS =
      Set.of("--bench", "--protocol", "--layer", "--shape", "--rules", "--threads", "--ring");
  private static final int BUDGET_LIST_ITEMS = 256;
  private static final int BUDGET_STRING_CODE_POINTS = 4_096;

  private SpRWorker() {}

  /** The protocol of a profile name: smoke, informative or full. */
  static Protocol protocol(String name) {
    return switch (name) {
      case "smoke" -> Protocol.smoke();
      case "informative" -> Protocol.informative();
      case "full" -> Protocol.full();
      default -> throw new IllegalArgumentException("unknown protocol " + name);
    };
  }

  /** The expression layer behind the port: {@code inhouse} or {@code cel}. */
  static Expression expression(String layer) {
    return switch (layer) {
      case "inhouse" -> new InHouseExpression();
      case "cel" -> new CelJavaExpression();
      default -> throw new IllegalArgumentException("unknown layer " + layer);
    };
  }

  /** The same layer as the conformance harness sees it. */
  static ExpressionLayer vectorLayer(String layer) {
    return switch (layer) {
      case "inhouse" -> new InHouseLayer();
      case "cel" -> new CelJavaLayer();
      default -> throw new IllegalArgumentException("unknown layer " + layer);
    };
  }

  static PackShape shape(String name) {
    return switch (name) {
      case "S1" -> PackShape.S1_FIRST_HIT_REALISTIC;
      case "S2" -> PackShape.S2_FULL_SCAN_WORST_CASE;
      default -> throw new IllegalArgumentException("unknown shape " + name);
    };
  }

  /** Runs the worker command {@code args} (without the word {@code worker}) and prints a line. */
  static int run(List<String> args) {
    Map<String, String> options = parse(args);
    Protocol protocol = protocol(required(options, "--protocol"));
    ForkResult result =
        switch (options.getOrDefault("--bench", "throughput")) {
          case "throughput" -> throughput(options, protocol);
          case "explain" -> explain(protocol);
          default -> throw new IllegalArgumentException("unknown benchmark");
        };
    System.out.println(result.toLine());
    return 0;
  }

  private static ForkResult throughput(Map<String, String> options, Protocol protocol) {
    String layer = required(options, "--layer");
    PackShape shape = shape(required(options, "--shape"));
    int rules = Integer.parseInt(required(options, "--rules"));
    int threads = Integer.parseInt(required(options, "--threads"));
    int ring = Integer.parseInt(options.getOrDefault("--ring", "" + ThroughputBench.RING_SIZE));
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be at least 1");
    }
    TradeRecord[] records = ThroughputBench.ring(SEED, ring);
    GeneratedPack pack = RulePackGen.generate(SEED, rules, shape);
    CompiledPack compiled = CompiledPack.compile(pack, expression(layer));
    long budget =
        BudgetDefaults.derive(compiled, List.of(), BUDGET_LIST_ITEMS, BUDGET_STRING_CODE_POINTS)
            .perRecordBudgetUnits();
    List<ThroughputBench> benches = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      benches.add(new ThroughputBench(compiled, records, t, budget));
    }
    if (threads == 1) {
      return measure(protocol, benches.get(0));
    }
    try (ParallelWorkload parallel = new ParallelWorkload(benches)) {
      return measure(protocol, parallel);
    }
  }

  private static ForkResult measure(Protocol protocol, Workload workload) {
    return ForkRunner.runInProcess(protocol, workload, 0, NanoClock.system(), StealSource.system());
  }

  /**
   * The fork statistic of an explain fork is the p99 of the render time, in milliseconds, of the
   * last batch of {@link #EXPLAIN_SAMPLES} renders; the runner's cycle mode runs one untimed and
   * three timed batches, so the batch that counts follows three batches of warm-up.
   */
  private static ForkResult explain(Protocol protocol) {
    ExplainBench.RenderCycle cycle = new ExplainBench.RenderCycle(EXPLAIN_SAMPLES, SEED);
    ForkResult base = measure(protocol, cycle);
    long[] nanos = cycle.lastSamples();
    double[] millis = new double[nanos.length];
    for (int i = 0; i < nanos.length; i++) {
      millis[i] = nanos[i] / 1_000_000.0;
    }
    double p99 = Stats.percentile(millis, 99);
    return new ForkResult(
        base.index(),
        base.unstable(),
        base.discarded(),
        base.discardReason(),
        List.of(p99),
        base.canaryCpu(),
        base.canaryMemory(),
        base.canaryDisk(),
        base.stealFraction(),
        base.checksum());
  }

  private static Map<String, String> parse(List<String> args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i < args.size(); i += 2) {
      String name = args.get(i);
      if (!OPTIONS.contains(name) || i + 1 >= args.size()) {
        throw new IllegalArgumentException("bad worker option " + name);
      }
      if (options.put(name, args.get(i + 1)) != null) {
        throw new IllegalArgumentException(name + " is given more than once");
      }
    }
    return options;
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
