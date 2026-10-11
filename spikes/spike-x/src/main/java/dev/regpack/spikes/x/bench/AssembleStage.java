package dev.regpack.spikes.x.bench;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.Workload;
import dev.regpack.spikes.x.validate.PerRecordValidator;
import dev.regpack.spikes.x.validate.PolicyModel;
import dev.regpack.spikes.x.validate.PolicyModel.Policy;
import dev.regpack.spikes.x.xml.StaxRecordWriter;
import dev.regpack.spikes.x.xml.TxModel;
import dev.regpack.spikes.x.xml.XmlRecordGen;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.xml.validation.Schema;

/**
 * The integrated assembly stage: every thread (a lane) loops whole file cycles. One cycle writes
 * the records of a file through the StAX writer, validates each record against the schema when the
 * policy samples it, writes the file, seals it with SHA-256 (the file is read back) and runs the
 * whole-file gate. One unit is one record, counted when its file's gate and hash have completed, so
 * the rate over whole cycles has no phase outside it. The records are generated before the
 * measurement; each lane has its own ring, which is walked on across cycles.
 *
 * <p>A call of {@link #runUnits} runs whole cycles on all lanes at the same time and returns when
 * the slowest lane is done; {@link #cycleUnits()} is {@code threads x recordsPerFile}, so the
 * protocol rule for cycles applies: one untimed warm-up cycle, then the measured ones. Each lane
 * writes one file, overwritten by its next cycle; {@link #close} deletes the files and stops the
 * threads.
 */
public final class AssembleStage implements Workload, AutoCloseable {
  /** The whole-file gate of a sealed file; it throws when the file is not valid. */
  @FunctionalInterface
  public interface Gate {
    void check(Path file);
  }

  /**
   * One sealed file, handed to the observer before the next cycle overwrites it.
   *
   * @param validated how many records were validated one by one
   */
  public record Sealed(
      int lane, Path file, String sha256, long bytes, int records, int validated) {}

  private static final long GOLDEN = 0x9E3779B97F4A7C15L;
  private static final long SAMPLER_SALT = 0x1B873593L;
  private static final int RING_EXTRA = 997;

  private final Policy policy;
  private final List<Lane> lanes;
  private final long cycleUnits;
  private final ExecutorService pool;

  /** A stage with the real whole-file gate and no observer. */
  public AssembleStage(
      Schema schema, Policy policy, int threads, int recordsPerFile, long seed, Path dir) {
    this(schema, policy, threads, recordsPerFile, seed, dir, gateOf(schema), ignored -> {});
  }

  public AssembleStage(
      Schema schema,
      Policy policy,
      int threads,
      int recordsPerFile,
      long seed,
      Path dir,
      Gate gate,
      Consumer<Sealed> observer) {
    this(
        schema,
        policy,
        threads,
        recordsPerFile,
        seed,
        dir,
        gate,
        observer,
        lane -> generateRing(laneSeed(seed, lane), recordsPerFile));
  }

  /**
   * The same with the ring of records of each lane supplied by the caller ({@code ringOf} maps the
   * lane index to its ring), so that a test can put a known record, a defective one for example,
   * where the stage will write and validate it. The ring is walked on across cycles; it must not be
   * empty.
   */
  AssembleStage(
      Schema schema,
      Policy policy,
      int threads,
      int recordsPerFile,
      long seed,
      Path dir,
      Gate gate,
      Consumer<Sealed> observer,
      IntFunction<TxModel[]> ringOf) {
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(ringOf, "ringOf");
    this.policy = Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(dir, "dir");
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(observer, "observer");
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be at least 1: " + threads);
    }
    if (recordsPerFile < 1) {
      throw new IllegalArgumentException("recordsPerFile must be at least 1: " + recordsPerFile);
    }
    this.cycleUnits = Math.multiplyExact((long) threads, (long) recordsPerFile);
    List<Lane> built = new ArrayList<>(threads);
    for (int i = 0; i < threads; i++) {
      TxModel[] ring = Objects.requireNonNull(ringOf.apply(i), "ring");
      if (ring.length < 1) {
        throw new IllegalArgumentException("the ring of lane " + i + " is empty");
      }
      built.add(new Lane(i, schema, laneSeed(seed, i), ring, recordsPerFile, dir, gate, observer));
    }
    this.lanes = List.copyOf(built);
    AtomicInteger counter = new AtomicInteger();
    this.pool =
        Executors.newFixedThreadPool(
            threads,
            task -> {
              Thread thread = new Thread(task, "spike-x-assemble-" + counter.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  private static long laneSeed(long seed, int lane) {
    return seed + (lane + 1) * GOLDEN;
  }

  /** The stream with which lane {@code lane} decides which records to validate. */
  static SplitMix64 samplerOf(long seed, int lane) {
    return new SplitMix64(laneSeed(seed, lane) ^ SAMPLER_SALT);
  }

  private static TxModel[] generateRing(long laneSeed, int recordsPerFile) {
    TradeRecordGen trades = new TradeRecordGen(laneSeed);
    XmlRecordGen gen = new XmlRecordGen(laneSeed);
    TxModel[] ring = new TxModel[recordsPerFile + RING_EXTRA];
    for (int i = 0; i < ring.length; i++) {
      ring[i] = gen.next(trades.next());
    }
    return ring;
  }

  /** The real gate: one streaming validator over the file. */
  public static Gate gateOf(Schema schema) {
    Objects.requireNonNull(schema, "schema");
    return file -> FileGateBench.validateNanos(file, schema);
  }

  @Override
  public long cycleUnits() {
    return cycleUnits;
  }

  /** Runs {@code maxUnits / cycleUnits} whole cycles and returns the records sealed. */
  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    long cycles = maxUnits / cycleUnits;
    for (long c = 0; c < cycles; c++) {
      List<Future<?>> futures = new ArrayList<>(lanes.size());
      for (Lane lane : lanes) {
        futures.add(pool.submit(lane::cycle));
      }
      RuntimeException failure = null;
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while a lane ran", e);
        } catch (ExecutionException e) {
          if (failure == null) {
            Throwable cause = e.getCause();
            failure =
                cause instanceof RuntimeException r
                    ? r
                    : new IllegalStateException("an assemble lane failed", cause);
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
    return cycles * cycleUnits;
  }

  @Override
  public long checksum() {
    long sum = 0;
    for (Lane lane : lanes) {
      sum += lane.checksum;
    }
    return sum;
  }

  /** Records sealed so far, over all lanes. */
  public long sealedRecords() {
    long sum = 0;
    for (Lane lane : lanes) {
      sum += lane.sealed;
    }
    return sum;
  }

  /** Records validated one by one so far, over all lanes. */
  public long validatedRecords() {
    long sum = 0;
    for (Lane lane : lanes) {
      sum += lane.validated;
    }
    return sum;
  }

  /** The policy of this stage. */
  public Policy policy() {
    return policy;
  }

  /** Stops the threads and deletes the files. */
  @Override
  public void close() {
    pool.shutdownNow();
    IOException failure = null;
    for (Lane lane : lanes) {
      try {
        Files.deleteIfExists(lane.file);
      } catch (IOException e) {
        failure = e;
      }
    }
    if (failure != null) {
      throw new UncheckedIOException(failure);
    }
  }

  /**
   * One thread, one file of each size, the stage latency in nanoseconds: from the first record
   * written to the file sealed and passed by the gate, with every record validated (policy ALL).
   */
  public static long[] latencyPerFileSize(int[] sizes, Schema schema, long seed) {
    return latencyPerFileSize(sizes, schema, PolicyModel.ALL, seed);
  }

  /**
   * The same under the given policy; one untimed file of up to 2,000 records warms the JIT first.
   */
  public static long[] latencyPerFileSize(int[] sizes, Schema schema, Policy policy, long seed) {
    Path dir;
    try {
      dir = Files.createTempDirectory("sp-x-latency-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      try (AssembleStage warm = new AssembleStage(schema, policy, 1, 2_000, seed, dir)) {
        warm.runUnits(warm.cycleUnits());
      }
      long[] nanos = new long[sizes.length];
      for (int i = 0; i < sizes.length; i++) {
        try (AssembleStage stage = new AssembleStage(schema, policy, 1, sizes[i], seed, dir)) {
          long start = System.nanoTime();
          stage.runUnits(stage.cycleUnits());
          nanos[i] = System.nanoTime() - start;
        }
      }
      return nanos;
    } finally {
      try {
        Files.deleteIfExists(dir);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /**
   * Collects what the writer emits so that one record's bytes can be read before they are drained.
   */
  private static final class Capture extends OutputStream {
    private byte[] buffer = new byte[16 * 1024];
    private int size;

    @Override
    public void write(int b) {
      ensure(1);
      buffer[size++] = (byte) b;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      ensure(length);
      System.arraycopy(bytes, offset, buffer, size, length);
      size += length;
    }

    /** Forgets what was captured. */
    void reset() {
      size = 0;
    }

    /**
     * The captured bytes of one record, wrapped by the validator in the smallest valid file. The
     * start tag of the set of records stays open until the first record is written, so the first
     * record's bytes begin with its closing '>', which is left out here.
     */
    byte[] wrappedBy(PerRecordValidator validator) {
      int from = size > 0 && buffer[0] == '>' ? 1 : 0;
      return validator.wrap(buffer, from, size - from);
    }

    void drainTo(OutputStream out) throws IOException {
      out.write(buffer, 0, size);
      size = 0;
    }

    private void ensure(int extra) {
      if (size + extra > buffer.length) {
        buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra));
      }
    }
  }

  private final class Lane {
    final int index;
    final Path file;
    final int recordsPerFile;
    final TxModel[] ring;
    final Gate gate;
    final Consumer<Sealed> observer;
    final PerRecordValidator validator;
    final SplitMix64 sampler;
    final MessageDigest digest;
    final byte[] readBuffer = new byte[64 * 1024];
    final Capture capture = new Capture();
    int cursor;
    long sealed;
    long validated;
    long checksum;

    Lane(
        int index,
        Schema schema,
        long laneSeed,
        TxModel[] ring,
        int recordsPerFile,
        Path dir,
        Gate gate,
        Consumer<Sealed> observer) {
      this.index = index;
      this.file = dir.resolve("assemble-" + index + ".xml");
      this.recordsPerFile = recordsPerFile;
      this.gate = gate;
      this.observer = observer;
      this.validator = new PerRecordValidator(schema);
      this.sampler = new SplitMix64(laneSeed ^ SAMPLER_SALT);
      try {
        this.digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("every JDK has SHA-256", e);
      }
      this.ring = ring;
    }

    void cycle() {
      int validatedInFile = 0;
      long bytes;
      try {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
          capture.reset();
          StaxRecordWriter writer = new StaxRecordWriter(capture);
          writer.startDocument("assemble-" + index, 0L);
          writer.flush(); // the header bytes are in the capture
          capture.drainTo(out);
          for (int i = 0; i < recordsPerFile; i++) {
            writer.writeTx(ring[cursor]);
            cursor = cursor + 1 == ring.length ? 0 : cursor + 1;
            writer.flush(); // the record's bytes are in the capture, ready to be validated
            if (policy.sample(sampler)) {
              int errors = validator.validate(capture.wrappedBy(validator));
              if (errors > 0) {
                throw new IllegalStateException(
                    "record "
                        + i
                        + " of lane "
                        + index
                        + " failed the per-record gate: "
                        + validator.firstError());
              }
              validatedInFile++;
            }
            capture.drainTo(out);
          }
          writer.endDocument();
          capture.drainTo(out);
          bytes = writer.bytesWritten();
        }
        digest.reset();
        try (InputStream in = Files.newInputStream(file)) {
          for (int n = in.read(readBuffer); n > 0; n = in.read(readBuffer)) {
            digest.update(readBuffer, 0, n);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      byte[] sum = digest.digest();
      gate.check(file);
      sealed += recordsPerFile;
      validated += validatedInFile;
      long head = 0;
      for (int i = 0; i < 8; i++) {
        head = (head << 8) | (sum[i] & 0xFFL);
      }
      checksum = (checksum ^ head ^ bytes) * GOLDEN + recordsPerFile;
      observer.accept(
          new Sealed(
              index, file, HexFormat.of().formatHex(sum), bytes, recordsPerFile, validatedInFile));
    }
  }
}
