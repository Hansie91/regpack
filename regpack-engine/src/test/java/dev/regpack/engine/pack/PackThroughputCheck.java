package dev.regpack.engine.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.ForkResult;
import dev.regpack.bench.proto.ForkRunner;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.proto.Protocol;
import dev.regpack.bench.proto.StealSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The production throughput check (plan T22): information, not a verdict. SP-R measured a
 * disposable interpreter; this measures the production one ({@code MeteredExecutor} and {@link
 * PackProgram}) on the same shape, a generated pack of 200 rules in 10 tables of 20 rows in which
 * every row is evaluated for every record (shape S2).
 *
 * <p>The class is not named {@code *Test}, so Surefire skips it in {@code verify}. Run it with
 * {@code -Dtest=PackThroughputCheck}; it writes {@code pack-throughput.txt} into the build
 * directory. Nothing asserts on a rate: a slow machine must not fail a build, and the placeholder
 * gate of 10,000 records/s is printed beside the figure, never enforced (PF20). The guards against
 * a vacuous benchmark are in {@link PackThroughputGuardTest}.
 *
 * <p>The figure covers one in-process fork in one VM and the interpreter only: record assembly,
 * {@code input_digest}, path encoding and real pack sizes are not in it.
 */
class PackThroughputCheck {
  static final int RING_SIZE = 262_144;
  static final long GATE_PER_THREAD = 10_000L;
  private static final int RULES = 200;
  private static final int BUSY_THREADS = 4;
  private static final String OUTPUT_FILE = "pack-throughput.txt";

  /** One fork, 2 s windows, 3 stable windows within 3%, 10 measured windows, no canary. */
  private static Protocol protocol() {
    return new Protocol(
        1, Duration.ofSeconds(60), Duration.ofSeconds(2), 3, 0.03, 10, Duration.ZERO, 0.05, 3, 1);
  }

  /** What one run measured. */
  private record Run(int threads, ForkResult fork, long records, long execOutcomes, long checksum) {
    /** Records per second of one thread, if the fork was kept. */
    String perThread() {
      if (fork.discarded()) {
        return "not measured (" + fork.discardReason() + ")";
      }
      return count(Math.round(fork.statistic() / threads));
    }

    boolean aboveGate() {
      return Math.round(fork.statistic() / threads) >= GATE_PER_THREAD;
    }
  }

  @Test
  void measure() throws IOException {
    GeneratedPack generated = RulePackGen.generate(1, RULES, PackShape.S2_FULL_SCAN_WORST_CASE);
    long start = System.nanoTime();
    CompiledPack pack = GeneratedPackSource.compile(generated);
    long loadAndCompileMillis = (System.nanoTime() - start) / 1_000_000L;

    TradeRecord[] ring = ring(1, RING_SIZE);
    Run one = run(pack, ring, 1);
    Run four = run(pack, ring, BUSY_THREADS);

    assertThat(one.fork().discarded() && four.fork().discarded())
        .as("at least one run was measured")
        .isFalse();

    String verdict;
    if (four.fork().discarded()) {
      verdict = "NOT MEASURED";
    } else {
      verdict = four.aboveGate() ? "ABOVE" : "BELOW";
    }
    List<String> lines = new ArrayList<>();
    lines.add(
        "pack-throughput (information, not a verdict; one in-process fork, one VM, interpreter"
            + " only, worst-case shape S2)");
    lines.add(
        "rules "
            + count(RULES)
            + ", tables "
            + pack.tables().size()
            + ", ring "
            + count(RING_SIZE)
            + " records, budget "
            + count(pack.budget().perRecordUnits())
            + " units per record");
    lines.add("load plus compile: " + count(loadAndCompileMillis) + " ms (one cold run)");
    lines.add(
        "interpreter, records/s per thread: "
            + one.perThread()
            + " (1 thread), "
            + four.perThread()
            + " ("
            + BUSY_THREADS
            + " threads busy)");
    lines.add(
        "SP-R reference, spike interpreter: 55,860 (1 thread), 27,796 (4 threads busy, per thread);"
            + " placeholder gate "
            + count(GATE_PER_THREAD)
            + " (spec: per core; shown per thread, the conservative SP-R reading): "
            + verdict);
    lines.add(
        "EXEC_* outcomes on the clean corpus: "
            + count(four.execOutcomes())
            + " in "
            + count(four.records())
            + " records ("
            + BUSY_THREADS
            + " threads); checksum "
            + Long.toHexString(four.checksum()));
    String text = String.join("\n", lines) + "\n";

    Path directory = Path.of(System.getProperty("regpack.build.dir"));
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(OUTPUT_FILE), text, UTF_8);
    System.out.print(text);
    assertThat(lines).hasSize(6);
  }

  private static Run run(CompiledPack pack, TradeRecord[] ring, int threads) {
    List<PackWorkload> workloads = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      workloads.add(new PackWorkload(pack, ring, i));
    }
    try (ParallelWorkload parallel = new ParallelWorkload(workloads)) {
      ForkResult fork =
          ForkRunner.runInProcess(
              protocol(), parallel, 0, NanoClock.system(), StealSource.system());
      long records = 0;
      long exec = 0;
      for (PackWorkload workload : workloads) {
        records += workload.records();
        exec += workload.execOutcomes();
      }
      return new Run(threads, fork, records, exec, parallel.checksum());
    }
  }

  private static TradeRecord[] ring(long seed, int size) {
    TradeRecordGen generator = new TradeRecordGen(seed);
    TradeRecord[] ring = new TradeRecord[size];
    for (int i = 0; i < size; i++) {
      ring[i] = generator.next();
    }
    return ring;
  }

  /** A number with comma grouping, independent of the default locale. */
  private static String count(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }
}
