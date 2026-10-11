package dev.regpack.spikes.l.partition;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The range partitions of {@code decision}: contiguous daily or monthly bounds from a first day. A
 * bound is half open, {@code [from, to)}, the way PostgreSQL range partitions are.
 */
public final class PartitionPlan {
  /** The width of a partition. */
  public enum Granularity {
    DAILY,
    MONTHLY
  }

  /**
   * One partition.
   *
   * @param name the table name, {@code decision_YYYYMMDD} (daily) or {@code decision_YYYYMM}
   *     (monthly)
   * @param from the first day it holds
   * @param to the first day it does not hold
   */
  public record Bound(String name, LocalDate from, LocalDate to) {}

  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

  private PartitionPlan() {}

  /**
   * {@code count} contiguous partitions from {@code first}.
   *
   * @param first the first day; for a monthly plan the first day of a month
   * @throws IllegalArgumentException if {@code count} is not positive or a monthly plan does not
   *     start on the first of a month
   */
  public static List<Bound> plan(LocalDate first, int count, Granularity granularity) {
    if (count <= 0) {
      throw new IllegalArgumentException("the number of partitions must be positive: " + count);
    }
    if (granularity == Granularity.MONTHLY && first.getDayOfMonth() != 1) {
      throw new IllegalArgumentException("a monthly plan starts on the first of a month: " + first);
    }
    List<Bound> out = new ArrayList<>(count);
    LocalDate from = first;
    for (int i = 0; i < count; i++) {
      LocalDate to;
      String name;
      if (granularity == Granularity.DAILY) {
        to = from.plusDays(1);
        name = "decision_" + DAY.format(from);
      } else {
        to = from.plusMonths(1);
        name = "decision_" + MONTH.format(from);
      }
      out.add(new Bound(name, from, to));
      from = to;
    }
    return List.copyOf(out);
  }

  /** The statement that creates one partition of {@code decision}. */
  public static String createStatement(Bound bound) {
    return "CREATE TABLE "
        + bound.name()
        + " PARTITION OF decision FOR VALUES FROM ('"
        + bound.from()
        + "') TO ('"
        + bound.to()
        + "')";
  }
}
