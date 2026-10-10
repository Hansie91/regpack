package dev.regpack.bench.proto;

import dev.regpack.bench.stats.Stats;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Runs the fixed measurement protocol: one fork in this JVM, or a series of forks as child JVMs.
 */
public final class ForkRunner {
  /**
   * A call of the workload aims at this fraction of a window, so a window ends close to its time.
   */
  private static final long CALLS_PER_WINDOW = 50;

  private static final long MAX_CHUNK = Long.MAX_VALUE / 16;
  private static final int MAX_IDLE_CALLS = 100_000;
  private static final int TAIL_LINES = 8;

  private ForkRunner() {}

  // ------------------------------------------------------------------ in process

  /**
   * One fork in this JVM (P-2, P-3, P-4, P-5): canary probes, warm-up, measured windows, canary
   * probes. A protocol with a zero canary runs no probe and creates no scratch file. A workload
   * with {@link Workload#cycleUnits()} above zero gets one untimed warm-up cycle and three timed
   * whole cycles. The fork is discarded only for CPU steal above the limit, never for being fast or
   * slow.
   */
  public static ForkResult runInProcess(
      Protocol p, Workload w, int index, NanoClock clock, StealSource steal) {
    Objects.requireNonNull(p, "p");
    Objects.requireNonNull(w, "w");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(steal, "steal");
    try (Probes probes = Probes.forProtocol(p)) {
      probes.measure(clock);
      long[] before = steal.cpuJiffies();
      boolean unstable = false;
      List<Double> rates = new ArrayList<>();
      WindowRunner windows = new WindowRunner(w, clock);
      if (w.cycleUnits() > 0) {
        rates.add(measureCycles(w, clock));
      } else {
        unstable = warmUp(p, windows);
        for (int i = 0; i < p.measureWindows(); i++) {
          rates.add(windows.run(p.window().toNanos()).rate());
        }
      }
      long[] after = steal.cpuJiffies();
      probes.measure(clock);
      double stolen = stealFraction(before, after);
      boolean discard = stolen > p.maxStealFraction();
      String reason =
          discard
              ? "STEAL: fraction "
                  + Stats.decimal(stolen).toPlainString()
                  + " is above "
                  + p.maxStealFraction()
              : "";
      return new ForkResult(
          index,
          unstable,
          discard,
          reason,
          rates,
          probes.cpu,
          probes.memory,
          probes.disk,
          stolen,
          w.checksum());
    }
  }

  /**
   * The three canary probes of one fork and the scratch file of the disk probe. The scratch file
   * exists only when the protocol has a canary, and is deleted on close. It lives in the temp
   * directory, so the disk probe measures the disk of that directory; the plan's {@code
   * runInProcess} signature has no parameter for another place.
   */
  private static final class Probes implements AutoCloseable {
    private final Duration length;
    private final Optional<Path> scratch;
    private final List<Double> cpu = new ArrayList<>();
    private final List<Double> memory = new ArrayList<>();
    private final List<Double> disk = new ArrayList<>();

    private Probes(Duration length, Optional<Path> scratch) {
      this.length = length;
      this.scratch = scratch;
    }

    static Probes forProtocol(Protocol p) {
      if (p.canary().isZero()) {
        return new Probes(p.canary(), Optional.empty());
      }
      try {
        return new Probes(p.canary(), Optional.of(Files.createTempFile("regpack-canary", ".bin")));
      } catch (IOException e) {
        throw new UncheckedIOException("cannot create the canary scratch file", e);
      }
    }

    /** Runs the three probes once; nothing when the protocol has no canary. */
    void measure(NanoClock clock) {
      if (scratch.isEmpty()) {
        return;
      }
      cpu.add(Canary.cpuMbPerSec(clock, length));
      memory.add(Canary.memoryMbPerSec(clock, length));
      disk.add(Canary.diskSyncOpsPerSec(clock, length, scratch.get()));
    }

    @Override
    public void close() {
      if (scratch.isPresent()) {
        try {
          Files.deleteIfExists(scratch.get());
        } catch (IOException e) {
          // a leftover canary file in the temp directory is harmless
        }
      }
    }
  }

  private static double stealFraction(long[] before, long[] after) {
    long total = after[0] - before[0];
    long stolen = after[1] - before[1];
    if (total <= 0 || stolen <= 0) {
      return 0.0;
    }
    return (double) stolen / total;
  }

  /** P-2: warm-up windows until the last {@code stableWindows} agree; true if it hit the limit. */
  private static boolean warmUp(Protocol p, WindowRunner windows) {
    List<Double> rates = new ArrayList<>();
    long elapsed = 0;
    while (true) {
      WindowResult result = windows.run(p.window().toNanos());
      rates.add(result.rate());
      elapsed += result.nanos();
      if (rates.size() >= p.stableWindows()
          && relativeRange(rates.subList(rates.size() - p.stableWindows(), rates.size()))
              <= p.stabilityTolerance()) {
        return false;
      }
      if (elapsed >= p.warmupMax().toNanos()) {
        return true;
      }
    }
  }

  /** {@code (max - min) / min}; infinite when the smallest rate is not positive. */
  private static double relativeRange(List<Double> rates) {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (double r : rates) {
      min = Math.min(min, r);
      max = Math.max(max, r);
    }
    return min <= 0.0 ? Double.POSITIVE_INFINITY : (max - min) / min;
  }

  /**
   * P-3 for a periodic workload: one untimed cycle, then three timed whole cycles; the statistic is
   * the units of those cycles over their elapsed time, so no phase falls outside the measurement.
   */
  private static double measureCycles(Workload w, NanoClock clock) {
    long cycle = w.cycleUnits();
    runCycle(w, cycle);
    long units = 0;
    long start = clock.nanoTime();
    for (int i = 0; i < 3; i++) {
      units += runCycle(w, cycle);
    }
    long elapsed = clock.nanoTime() - start;
    return new WindowResult(units, elapsed).rate();
  }

  private static long runCycle(Workload w, long cycle) {
    long done = w.runUnits(cycle);
    if (done != cycle) {
      throw new IllegalStateException("a cycle must run all " + cycle + " units, ran " + done);
    }
    return done;
  }

  /** Runs timed windows, sizing each call of the workload so that a window ends on time. */
  private static final class WindowRunner {
    private final Workload workload;
    private final NanoClock clock;
    private long chunk = 1;
    private double nanosPerUnit;

    WindowRunner(Workload workload, NanoClock clock) {
      this.workload = workload;
      this.clock = clock;
    }

    WindowResult run(long windowNanos) {
      long targetCallNanos = Math.max(1L, windowNanos / CALLS_PER_WINDOW);
      long units = 0;
      int idle = 0;
      long start = clock.nanoTime();
      long now = start;
      while (now - start < windowNanos) {
        long remaining = windowNanos - (now - start);
        long max = chunk;
        if (nanosPerUnit > 0) {
          max = Math.min(max, Math.max(1L, (long) (remaining / nanosPerUnit)));
        }
        long done = workload.runUnits(max);
        if (done < 0 || done > max) {
          throw new IllegalStateException("runUnits(" + max + ") returned " + done);
        }
        long end = clock.nanoTime();
        if (done == 0) {
          if (++idle >= MAX_IDLE_CALLS) {
            throw new IllegalStateException("the workload makes no progress");
          }
        } else {
          idle = 0;
          units += done;
          long call = end - now;
          if (call > 0) {
            nanosPerUnit = (double) call / done;
            long wanted = Math.max(1L, (long) (targetCallNanos / nanosPerUnit));
            chunk = Math.min(Math.min(chunk * 8, MAX_CHUNK), wanted);
          } else {
            chunk = Math.min(chunk * 8, MAX_CHUNK);
          }
        }
        now = end;
      }
      return new WindowResult(units, now - start);
    }
  }

  // ------------------------------------------------------------------ child JVMs

  /**
   * Runs {@code p.forks()} forks, each in a fresh child JVM, one after another (P-1). The child
   * command is this JVM's own executable, a {@code -D} option for every system property of this JVM
   * whose name starts with {@code spike.} (so a run receives, for instance, the PostgreSQL URL),
   * the {@code jvmArgs}, {@code -cp} with this JVM's class path, the main class and the worker
   * arguments. The child prints one line {@code FORK-RESULT <canonical json>}; a non-zero exit, a
   * missing, repeated or malformed line makes a discarded fork with reason {@code HARNESS_ERROR}.
   * No fork starts once the deadline has passed. The deadline is checked only between forks and a
   * child has no time limit of its own: a hung child blocks the run until the CI job timeout, which
   * is the only guard (decisions.md, T3 harness notes).
   */
  public static ForkSeries runForked(
      Protocol p,
      List<String> jvmArgs,
      String mainClass,
      List<String> workerArgs,
      RunDeadline deadline)
      throws IOException, InterruptedException {
    Objects.requireNonNull(p, "p");
    Objects.requireNonNull(mainClass, "mainClass");
    Objects.requireNonNull(deadline, "deadline");
    List<String> jvm = List.copyOf(jvmArgs);
    List<String> worker = List.copyOf(workerArgs);
    String java =
        ProcessHandle.current()
            .info()
            .command()
            .orElseThrow(() -> new IOException("cannot determine the java executable"));
    List<ForkResult> forks = new ArrayList<>();
    for (int i = 0; i < p.forks(); i++) {
      if (deadline.passed()) {
        break;
      }
      forks.add(runChild(java, jvm, mainClass, worker, i));
    }
    return new ForkSeries(forks, p);
  }

  static List<String> childCommand(
      String java, List<String> jvmArgs, String mainClass, List<String> workerArgs) {
    List<String> command = new ArrayList<>();
    command.add(java);
    TreeMap<String, String> spike = new TreeMap<>();
    for (String name : System.getProperties().stringPropertyNames()) {
      if (name.startsWith("spike.")) {
        spike.put(name, System.getProperty(name));
      }
    }
    spike.forEach((name, value) -> command.add("-D" + name + "=" + value));
    command.addAll(jvmArgs);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass);
    command.addAll(workerArgs);
    return command;
  }

  private static ForkResult runChild(
      String java, List<String> jvmArgs, String mainClass, List<String> workerArgs, int index)
      throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(childCommand(java, jvmArgs, mainClass, workerArgs))
            .redirectErrorStream(true);
    Process process = builder.start();
    try {
      String resultLine = "";
      int resultLines = 0;
      Deque<String> tail = new ArrayDeque<>();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith(ForkResult.LINE_PREFIX)) {
            resultLine = line;
            resultLines++;
          } else {
            tail.addLast(line);
            if (tail.size() > TAIL_LINES) {
              tail.removeFirst();
            }
          }
        }
      }
      int exit = process.waitFor();
      if (exit != 0) {
        return ForkResult.harnessError(
            index, "exit code " + exit + "; last output: " + String.join(" | ", tail));
      }
      if (resultLines > 1) {
        return ForkResult.harnessError(
            index,
            "multiple FORK-RESULT lines ("
                + resultLines
                + "); last output: "
                + String.join(" | ", tail));
      }
      if (resultLine.isEmpty()) {
        return ForkResult.harnessError(
            index, "no FORK-RESULT line; last output: " + String.join(" | ", tail));
      }
      try {
        return ForkResult.parseLine(resultLine).withIndex(index);
      } catch (IllegalArgumentException e) {
        return ForkResult.harnessError(index, e.getMessage());
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
