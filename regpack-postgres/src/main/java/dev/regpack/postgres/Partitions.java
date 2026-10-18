package dev.regpack.postgres;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Partition dates and statements for the {@code decision} table (D-S1, Convention 7): one partition
 * per UTC day of the decision's asAt instant. Everything here is fixed format: the dates come from
 * the UTC offset, the names and bounds are built from digits, and nothing consults the default zone
 * or locale.
 */
public final class Partitions {
  /** The first supported day (inclusive). */
  static final LocalDate FIRST_DAY = LocalDate.of(2000, 1, 1);

  /** The first day that is no longer supported (exclusive). */
  static final LocalDate END_DAY = LocalDate.of(2200, 1, 1);

  private static final String PREFIX = "decision_p";

  private Partitions() {}

  /**
   * The UTC date of the instant, the partition key.
   *
   * @throws IllegalArgumentException outside [2000-01-01, 2200-01-01) UTC; the caller has written
   *     nothing yet
   */
  public static LocalDate dateOf(Instant asAt) {
    Objects.requireNonNull(asAt, "asAt");
    if (asAt.isBefore(FIRST_DAY.atStartOfDay().toInstant(ZoneOffset.UTC))
        || !asAt.isBefore(END_DAY.atStartOfDay().toInstant(ZoneOffset.UTC))) {
      throw new IllegalArgumentException("asAt is outside the supported range 2000 to 2199 (UTC)");
    }
    return asAt.atOffset(ZoneOffset.UTC).toLocalDate();
  }

  /** {@code decision_p20261009} for 2026-10-09. */
  public static String partitionName(LocalDate day) {
    requireSupported(day);
    return PREFIX
        + digits(day.getYear(), 4)
        + digits(day.getMonthValue(), 2)
        + digits(day.getDayOfMonth(), 2);
  }

  /**
   * The two statements that create a partition and attach it, in this order and in one transaction:
   * a table like {@code decision} with its defaults, constraints and indexes, then the attach for
   * the half-open day range. The caller holds the partition advisory lock (Convention 12, purpose
   * 2) and skips both when the partition already exists (T16).
   *
   * @throws IllegalArgumentException if the schema is not a valid identifier or the day is outside
   *     the supported range
   */
  public static List<String> createStatements(String schema, LocalDate day) {
    String qualifier = SqlIdentifier.require(schema);
    requireSupported(day);
    String partition = qualifier + "." + partitionName(day);
    String parent = qualifier + ".decision";
    return List.of(
        "CREATE TABLE "
            + partition
            + " (LIKE "
            + parent
            + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES)",
        "ALTER TABLE "
            + parent
            + " ATTACH PARTITION "
            + partition
            + " FOR VALUES FROM ('"
            + literal(day)
            + "') TO ('"
            + literal(day.plusDays(1))
            + "')");
  }

  private static void requireSupported(LocalDate day) {
    Objects.requireNonNull(day, "day");
    if (day.isBefore(FIRST_DAY) || !day.isBefore(END_DAY)) {
      throw new IllegalArgumentException("day is outside the supported range 2000 to 2199");
    }
  }

  private static String literal(LocalDate day) {
    return digits(day.getYear(), 4)
        + "-"
        + digits(day.getMonthValue(), 2)
        + "-"
        + digits(day.getDayOfMonth(), 2);
  }

  /** Zero-padded decimal digits without any locale-dependent formatting. */
  private static String digits(int value, int width) {
    String plain = Integer.toString(value);
    return "0".repeat(Math.max(0, width - plain.length())) + plain;
  }
}
