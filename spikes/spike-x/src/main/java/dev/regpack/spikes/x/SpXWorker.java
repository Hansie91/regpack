package dev.regpack.spikes.x;

import dev.regpack.bench.proto.ForkResult;
import dev.regpack.bench.proto.ForkRunner;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.proto.Protocol;
import dev.regpack.bench.proto.StealSource;
import dev.regpack.bench.proto.Workload;
import dev.regpack.spikes.x.SpXRunner.Profile;
import dev.regpack.spikes.x.SpXRunner.Sizes;
import dev.regpack.spikes.x.bench.AssembleStage;
import dev.regpack.spikes.x.bench.BisectBench;
import dev.regpack.spikes.x.bench.FileGateBench;
import dev.regpack.spikes.x.bench.FileGateCycle;
import dev.regpack.spikes.x.bench.ParallelWorkload;
import dev.regpack.spikes.x.bench.RecordXsdBench;
import dev.regpack.spikes.x.bench.WriterBench;
import dev.regpack.spikes.x.schema.SyntheticSchema;
import dev.regpack.spikes.x.validate.PolicyModel;
import dev.regpack.spikes.x.validate.PolicyModel.Policy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.validation.Schema;

/**
 * The fork child of the SP-X benchmarks: one fresh JVM, one measurement under the fixed protocol,
 * one {@code FORK-RESULT} line on standard output. The benchmarks: {@code writer} (the StAX
 * writer), {@code xsd} (the per-record validation), {@code gate} (the whole-file validation of a
 * file of the profile's size), {@code bisect} (finding one defective record), {@code assemble} (the
 * integrated stage under a policy) and {@code latency} (the stage latency per file size, one file
 * at a time, reported as milliseconds per size instead of rates).
 *
 * <p>Every file that a benchmark writes goes to a new directory under {@code java.io.tmpdir} and is
 * deleted by the benchmark that made it, whether it succeeds or fails.
 */
final class SpXWorker {
  /** The seed of the generated records. */
  static final long SEED = 1L;

  private static final Set<String> OPTIONS =
      Set.of("--bench", "--protocol", "--profile", "--threads", "--policy");

  private SpXWorker() {}

  /** The protocol of a profile name: smoke, informative or full. */
  static Protocol protocol(String name) {
    return switch (name) {
      case "smoke" -> Protocol.smoke();
      case "informative" -> Protocol.informative();
      case "full" -> Protocol.full();
      default -> throw new IllegalArgumentException("unknown protocol " + name);
    };
  }

  static Policy policy(String name) {
    return switch (name) {
      case "ALL" -> PolicyModel.ALL;
      case "SAMPLED_10" -> PolicyModel.SAMPLED_10;
      default -> throw new IllegalArgumentException("unknown policy " + name);
    };
  }

  /** Runs the worker command {@code args} (without the word {@code worker}) and prints a line. */
  static int run(List<String> args) {
    Map<String, String> options = parse(args);
    Protocol protocol = protocol(required(options, "--protocol"));
    Sizes sizes = Profile.of(options.getOrDefault("--profile", "full")).sizes();
    int threads = Integer.parseInt(options.getOrDefault("--threads", "1"));
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be at least 1");
    }
    ForkResult result =
        switch (required(options, "--bench")) {
          case "writer" -> writer(protocol, sizes, threads);
          case "xsd" -> xsd(protocol, sizes, threads);
          case "gate" -> gate(protocol, sizes, threads);
          case "bisect" -> bisect(protocol, sizes);
          case "assemble" ->
              assemble(protocol, sizes, threads, policy(required(options, "--policy")));
          case "latency" -> latency(sizes, policy(required(options, "--policy")));
          default -> throw new IllegalArgumentException("unknown benchmark");
        };
    System.out.println(result.toLine());
    return 0;
  }

  private static ForkResult measure(Protocol protocol, Workload workload) {
    return ForkRunner.runInProcess(protocol, workload, 0, NanoClock.system(), StealSource.system());
  }

  private static ForkResult writer(Protocol protocol, Sizes sizes, int threads) {
    List<WriterBench> benches = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      benches.add(new WriterBench(SEED + t, sizes.writerRing()));
    }
    return measureThreads(protocol, benches);
  }

  private static ForkResult xsd(Protocol protocol, Sizes sizes, int threads) {
    Schema schema = SyntheticSchema.txn();
    List<RecordXsdBench> benches = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      benches.add(new RecordXsdBench(schema, SEED + t, sizes.xsdRing()));
    }
    return measureThreads(protocol, benches);
  }

  private static ForkResult gate(Protocol protocol, Sizes sizes, int threads) {
    Schema schema = SyntheticSchema.txn();
    Path dir = tempDirectory();
    try {
      Path file = FileGateBench.generate(dir, sizes.gateRecords(), SEED);
      List<FileGateCycle> benches = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        benches.add(new FileGateCycle(file, schema, sizes.gateRecords()));
      }
      return measureThreads(protocol, benches);
    } finally {
      deleteTree(dir);
    }
  }

  private static ForkResult bisect(Protocol protocol, Sizes sizes) {
    return measure(protocol, new BisectBench(SyntheticSchema.txn(), sizes.bisectRecords(), SEED));
  }

  private static ForkResult assemble(Protocol protocol, Sizes sizes, int threads, Policy policy) {
    Path dir = tempDirectory();
    try (AssembleStage stage =
        new AssembleStage(
            SyntheticSchema.txn(), policy, threads, sizes.recordsPerFile(), SEED, dir)) {
      return measure(protocol, stage);
    } finally {
      deleteTree(dir);
    }
  }

  /**
   * The stage latency of one file at a time, for every size of the profile, in milliseconds: the
   * "window rates" of the result are the latencies in the order of the sizes.
   */
  private static ForkResult latency(Sizes sizes, Policy policy) {
    int[] list = sizes.latencySizes().stream().mapToInt(Integer::intValue).toArray();
    StealSource steal = StealSource.system();
    long[] before = steal.cpuJiffies();
    long[] nanos = AssembleStage.latencyPerFileSize(list, SyntheticSchema.txn(), policy, SEED);
    long[] after = steal.cpuJiffies();
    List<Double> millis = new ArrayList<>();
    long checksum = 0;
    for (long n : nanos) {
      millis.add(n / 1_000_000.0);
      checksum += n > 0 ? 1 : 0;
    }
    long total = after[0] - before[0];
    long stolen = after[1] - before[1];
    double fraction = total > 0 && stolen > 0 ? (double) stolen / total : 0.0;
    boolean discard = fraction > Protocol.informative().maxStealFraction();
    return new ForkResult(
        0,
        false,
        discard,
        discard ? "STEAL: fraction " + fraction + " is above the limit" : "",
        millis,
        List.of(),
        List.of(),
        List.of(),
        fraction,
        checksum);
  }

  private static ForkResult measureThreads(Protocol protocol, List<? extends Workload> benches) {
    if (benches.size() == 1) {
      return measure(protocol, benches.get(0));
    }
    try (ParallelWorkload parallel = new ParallelWorkload(benches)) {
      return measure(protocol, parallel);
    }
  }

  private static Path tempDirectory() {
    try {
      return Files.createTempDirectory("sp-x-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Deletes a directory and what is in it; a file that cannot be deleted is reported. */
  static void deleteTree(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
