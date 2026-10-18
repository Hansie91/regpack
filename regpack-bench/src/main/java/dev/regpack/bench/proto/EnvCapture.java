package dev.regpack.bench.proto;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Records the machine a measurement ran on, for the result file. Only environment variables of a
 * fixed allow list are copied: the runner environment also holds tokens, and a variable outside the
 * list never appears. Values are strings and integers only (a result file holds no {@code double}).
 */
public final class EnvCapture {
  /** The only environment variables that may be recorded. */
  static final List<String> ALLOWED_VARIABLES =
      List.of(
          "ImageOS",
          "ImageVersion",
          "RUNNER_OS",
          "RUNNER_ARCH",
          "GITHUB_RUN_ID",
          "GITHUB_RUN_ATTEMPT",
          "GITHUB_SHA",
          "GITHUB_REPOSITORY",
          "GITHUB_WORKFLOW",
          "GITHUB_REF_NAME");

  private static final String UNKNOWN = "unknown";

  private EnvCapture() {}

  /**
   * The live capture: JVM properties, the CPU and memory of this host, the allow-listed variables.
   */
  public static Map<String, Object> capture() {
    return capture(System.getenv(), readOrEmpty("/proc/cpuinfo"), readOrEmpty("/proc/meminfo"));
  }

  static Map<String, Object> capture(
      Map<String, String> environment, String cpuinfo, String meminfo) {
    Map<String, Object> out = new TreeMap<>();
    out.put("os.name", property("os.name"));
    out.put("os.arch", property("os.arch"));
    out.put("os.version", property("os.version"));
    out.put("java.version", property("java.version"));
    out.put("java.vendor", property("java.vendor"));
    out.put("java.vm.name", property("java.vm.name"));
    out.put("cpu.count", (long) Runtime.getRuntime().availableProcessors());
    out.put("cpu.model", parseCpuModel(cpuinfo));
    long memory = parseMemTotalBytes(meminfo);
    out.put("mem.totalBytes", memory > 0 ? memory : jvmTotalMemoryBytes());
    Map<String, Object> variables = new TreeMap<>();
    for (String name : ALLOWED_VARIABLES) {
      String value = environment.get(name);
      if (value != null) {
        variables.put(name, value);
      }
    }
    out.put("env", variables);
    return out;
  }

  /** The text after the colon of the first {@code model name} line, or {@code unknown}. */
  public static String parseCpuModel(String cpuinfo) {
    for (String line : cpuinfo.split("\n")) {
      if (line.startsWith("model name")) {
        int colon = line.indexOf(':');
        if (colon >= 0 && !line.substring(colon + 1).isBlank()) {
          return line.substring(colon + 1).trim();
        }
      }
    }
    return UNKNOWN;
  }

  /** {@code MemTotal} of {@code /proc/meminfo} in bytes, or 0 when absent or unreadable. */
  public static long parseMemTotalBytes(String meminfo) {
    for (String line : meminfo.split("\n")) {
      if (line.startsWith("MemTotal:")) {
        String[] parts = line.substring("MemTotal:".length()).trim().split("\\s+");
        if (parts.length == 2 && parts[1].equals("kB")) {
          try {
            return Long.parseLong(parts[0]) * 1024L;
          } catch (NumberFormatException e) {
            return 0;
          }
        }
      }
    }
    return 0;
  }

  private static String property(String name) {
    String value = System.getProperty(name);
    return value == null ? UNKNOWN : value;
  }

  private static String readOrEmpty(String file) {
    Path path = Path.of(file);
    try {
      return Files.isReadable(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }

  private static long jvmTotalMemoryBytes() {
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean os) {
      return os.getTotalMemorySize();
    }
    return 0;
  }
}
