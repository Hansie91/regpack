package dev.regpack.spikes.r;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * The number of physical cores of this machine, for the per-physical-core reading of the executor
 * gate. On Linux it is the number of distinct (physical id, core id) pairs of {@code
 * /proc/cpuinfo}; where that is unavailable or unreadable it is the logical processor count, which
 * makes the per-core reading equal to the per-thread reading (a hyper-thread is not discounted).
 */
public final class PhysicalCores {
  private PhysicalCores() {}

  /** The count of this machine. */
  public static int detect() {
    int logical = Math.max(1, Runtime.getRuntime().availableProcessors());
    Path file = Path.of("/proc/cpuinfo");
    try {
      if (Files.isReadable(file)) {
        return parse(Files.readString(file, StandardCharsets.UTF_8), logical);
      }
    } catch (IOException | RuntimeException e) {
      // fall through: an unreadable file means the logical count
    }
    return logical;
  }

  /**
   * The distinct (physical id, core id) pairs of a {@code /proc/cpuinfo} text, never more than
   * {@code logical} and never less than 1; {@code logical} when the text names no core.
   */
  public static int parse(String cpuinfo, int logical) {
    Set<String> cores = new HashSet<>();
    String physical = "0";
    String core = null;
    for (String line : (cpuinfo + "\n\n").split("\n", -1)) {
      if (line.isBlank()) {
        if (core != null) {
          cores.add(physical + "/" + core);
        }
        physical = "0";
        core = null;
        continue;
      }
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = line.substring(0, colon).trim();
      String value = line.substring(colon + 1).trim();
      if (key.equals("physical id")) {
        physical = value;
      } else if (key.equals("core id")) {
        core = value;
      }
    }
    if (cores.isEmpty()) {
      return Math.max(1, logical);
    }
    return Math.max(1, Math.min(cores.size(), logical));
  }
}
