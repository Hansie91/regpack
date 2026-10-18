package dev.regpack.bench.proto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A source of cumulative CPU jiffies; tests script it. */
@FunctionalInterface
public interface StealSource {
  /** {@code {total, steal}} since boot, or {@code {0, 0}} where this is unavailable. */
  long[] cpuJiffies();

  /** Reads the first line of {@code /proc/stat}; {@code {0, 0}} off Linux or when unreadable. */
  static StealSource system() {
    return fromFile(Path.of("/proc/stat"));
  }

  /**
   * Reads the first line of a file in the {@code /proc/stat} format. A missing, empty or malformed
   * file gives {@code {0, 0}}; nothing is thrown, so a bad file can only switch the steal rule off,
   * and the test of this class pins the columns that the rule depends on.
   */
  static StealSource fromFile(Path stat) {
    return () -> {
      try {
        if (!Files.isReadable(stat)) {
          return new long[] {0, 0};
        }
        List<String> lines = Files.readAllLines(stat, StandardCharsets.US_ASCII);
        return lines.isEmpty() ? new long[] {0, 0} : parseCpuLine(lines.get(0));
      } catch (IOException | RuntimeException e) {
        return new long[] {0, 0};
      }
    };
  }

  /**
   * Parses {@code cpu user nice system idle iowait irq softirq steal ...}: the total is the sum of
   * the first eight counters (guest time is already inside user time), the steal the eighth.
   */
  static long[] parseCpuLine(String line) {
    String[] parts = line.trim().split("\\s+");
    if (parts.length < 9 || !parts[0].equals("cpu")) {
      return new long[] {0, 0};
    }
    long total = 0;
    for (int i = 1; i <= 8; i++) {
      total += Long.parseLong(parts[i]);
    }
    return new long[] {total, Long.parseLong(parts[8])};
  }
}
