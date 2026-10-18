package dev.regpack.postgres.capacity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * What the workflow can set on the capacity probe (T21): environment variables, each optional, an
 * empty value meaning unset. The defaults are the SP-L shape (3,470 records/s for 1,200 s, four
 * writers, micro-batches of 5,000 and then 10,000). A bad value is refused with the name of its
 * variable, so a typo in the workflow stops the run before it starts.
 *
 * @param variant a label for the files and the read-out (the workflow uses A, B and C)
 * @param batchSizes the micro-batch sizes to run one after the other
 * @param writers the writer threads, each with a source id of its own
 * @param ratePerSecond the offered records a second
 * @param seconds the length of the slice
 * @param memoryLimitBytes the memory limit of the server container, when there is one
 * @param forceSynchronousCommit whether the adapter sets {@code synchronous_commit = on}; only the
 *     diagnostic variant C turns it off, so that the server setting applies
 */
record ProbeSettings(
    String variant,
    List<Integer> batchSizes,
    int writers,
    long ratePerSecond,
    long seconds,
    OptionalLong memoryLimitBytes,
    boolean forceSynchronousCommit) {
  static final String VARIANT = "REGPACK_CAPACITY_VARIANT";
  static final String BATCH_SIZES = "REGPACK_CAPACITY_BATCH_SIZES";
  static final String SECONDS = "REGPACK_CAPACITY_SECONDS";
  static final String RATE = "REGPACK_CAPACITY_RATE";
  static final String WRITERS = "REGPACK_CAPACITY_WRITERS";
  static final String MEMORY_LIMIT_MB = "REGPACK_CAPACITY_MEMORY_LIMIT_MB";
  static final String SYNCHRONOUS_COMMIT = "REGPACK_CAPACITY_SYNCHRONOUS_COMMIT";

  private static final Pattern VARIANT_SHAPE = Pattern.compile("[A-Za-z0-9_-]{1,16}");

  ProbeSettings {
    batchSizes = List.copyOf(batchSizes);
  }

  /** Reads the settings from an environment map (the process environment in the real run). */
  static ProbeSettings parse(Map<String, String> environment) {
    String variant = text(environment, VARIANT, "local");
    if (!VARIANT_SHAPE.matcher(variant).matches()) {
      throw refused(VARIANT, "letters, digits, underscore and hyphen, 1 to 16 characters");
    }
    List<Integer> sizes = new ArrayList<>();
    for (String part : text(environment, BATCH_SIZES, "5000,10000").split(",", -1)) {
      sizes.add((int) number(BATCH_SIZES, part.strip(), 1, 100_000));
    }
    long mb = optionalNumber(environment, MEMORY_LIMIT_MB, 64, 1_000_000, -1);
    String commit = text(environment, SYNCHRONOUS_COMMIT, "on");
    if (!commit.equals("on") && !commit.equals("off")) {
      throw refused(SYNCHRONOUS_COMMIT, "on or off");
    }
    return new ProbeSettings(
        variant,
        sizes,
        (int) optionalNumber(environment, WRITERS, 1, 16, 4),
        optionalNumber(environment, RATE, 1, 1_000_000, 3_470),
        optionalNumber(environment, SECONDS, 1, 7_200, 1_200),
        mb < 0 ? OptionalLong.empty() : OptionalLong.of(mb * 1024L * 1024L),
        commit.equals("on"));
  }

  private static String text(Map<String, String> environment, String name, String fallback) {
    String value = environment.get(name);
    return value == null || value.isBlank() ? fallback : value.strip();
  }

  private static long optionalNumber(
      Map<String, String> environment, String name, long min, long max, long fallback) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return number(name, value.strip(), min, max);
  }

  private static long number(String name, String text, long min, long max) {
    long value;
    try {
      value = Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw refused(name, "a whole number from " + min + " to " + max);
    }
    if (value < min || value > max) {
      throw refused(name, "a whole number from " + min + " to " + max);
    }
    return value;
  }

  private static IllegalArgumentException refused(String name, String expected) {
    return new IllegalArgumentException(name + " must be " + expected);
  }
}
