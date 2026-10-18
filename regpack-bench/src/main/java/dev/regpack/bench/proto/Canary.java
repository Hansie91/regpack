package dev.regpack.bench.proto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Set;

/**
 * Three short probes of the machine itself: CPU, memory and disk. Run before warm-up and after the
 * last window of every fork, they show whether a neighbour slowed the host down during the fork
 * (P-4). Each probe returns a rate. A "megabyte" here is 2^20 bytes.
 */
public final class Canary {
  private static final int MIB = 1 << 20;
  private static final int MEMORY_BYTES = 64 * MIB;
  private static final int CACHE_LINE = 64;
  private static final int BLOCK = 4096;
  private static final int MAX_DISK_BLOCKS = 16_384; // the file stops growing at 64 MiB

  /**
   * How the disk probe opens its file; a test pins {@code DSYNC}, which makes it measure the disk.
   */
  static final Set<StandardOpenOption> DISK_OPEN_OPTIONS =
      Set.of(
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.DSYNC);

  /** Keeps the probes' results alive so the JIT cannot discard the work. */
  private static volatile long sink;

  private Canary() {}

  private static long limitNanos(Duration d) {
    if (d.isNegative() || d.isZero()) {
      throw new IllegalArgumentException("a probe needs a positive duration: " + d);
    }
    return d.toNanos();
  }

  private static double perSecond(double amount, long elapsedNanos) {
    return amount * 1_000_000_000.0 / Math.max(1L, elapsedNanos);
  }

  /** SHA-256 over one 1 MiB buffer in a loop, for at least {@code d}; MB hashed per second. */
  public static double cpuMbPerSec(NanoClock c, Duration d) {
    long limit = limitNanos(d);
    byte[] buffer = new byte[MIB];
    for (int i = 0; i < buffer.length; i++) {
      buffer[i] = (byte) (i * 31 + 7);
    }
    MessageDigest sha = sha256();
    long passes = 0;
    long elapsed;
    long start = c.nanoTime();
    do {
      sha.update(buffer);
      passes++;
      elapsed = c.nanoTime() - start;
    } while (elapsed < limit);
    sink ^= sha.digest()[0];
    return perSecond(passes, elapsed);
  }

  /**
   * A strided read (one byte per cache line) over a 64 MiB buffer, repeated for at least {@code d};
   * each pass counts as 64 MB.
   */
  public static double memoryMbPerSec(NanoClock c, Duration d) {
    long limit = limitNanos(d);
    byte[] buffer = new byte[MEMORY_BYTES];
    for (int i = 0; i < buffer.length; i += CACHE_LINE) {
      buffer[i] = (byte) (i >>> 6);
    }
    long accumulator = 0;
    long passes = 0;
    long elapsed;
    long start = c.nanoTime();
    do {
      for (int i = 0; i < buffer.length; i += CACHE_LINE) {
        accumulator += buffer[i];
      }
      passes++;
      elapsed = c.nanoTime() - start;
    } while (elapsed < limit);
    sink ^= accumulator;
    return perSecond((double) passes * (MEMORY_BYTES / MIB), elapsed);
  }

  /**
   * Sequential 4 KiB writes to {@code scratch} opened with {@code DSYNC}, for at least {@code d};
   * synchronous writes per second. The file is created or truncated and left in place: it holds one
   * block per operation, up to 64 MiB, after which the writes wrap around. The caller deletes it.
   */
  public static double diskSyncOpsPerSec(NanoClock c, Duration d, Path scratch) {
    long limit = limitNanos(d);
    ByteBuffer block = ByteBuffer.allocate(BLOCK);
    for (int i = 0; i < BLOCK; i++) {
      block.put(i, (byte) (i * 13 + 5));
    }
    try (FileChannel channel = FileChannel.open(scratch, DISK_OPEN_OPTIONS)) {
      long operations = 0;
      long elapsed;
      long start = c.nanoTime();
      do {
        block.clear();
        long position = (operations % MAX_DISK_BLOCKS) * BLOCK;
        while (block.hasRemaining()) {
          channel.write(block, position + block.position());
        }
        operations++;
        elapsed = c.nanoTime() - start;
      } while (elapsed < limit);
      sink ^= operations;
      return perSecond(operations, elapsed);
    } catch (IOException e) {
      throw new UncheckedIOException("disk canary failed on " + scratch, e);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK has SHA-256", e);
    }
  }
}
