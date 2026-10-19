package dev.regpack.model.decision;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * The supported range of the {@code asAt} of a decision: [2000-01-01, 2200-01-01) UTC. The ledger
 * of the real store keeps one partition per UTC day of {@code asAt} between these bounds (D-S1), so
 * every adapter refuses an instant outside them with the same {@link IllegalArgumentException},
 * before it writes anything; the in-memory adapter must not accept what the real store cannot hold.
 * A pipeline that must not fail a whole batch for one bad record checks the instant first.
 */
public final class AsAtRange {
  /** The first supported day (inclusive). */
  public static final LocalDate FIRST_DAY = LocalDate.of(2000, 1, 1);

  /** The first day that is no longer supported (exclusive). */
  public static final LocalDate END_DAY = LocalDate.of(2200, 1, 1);

  private static final Instant FIRST = FIRST_DAY.atStartOfDay().toInstant(ZoneOffset.UTC);
  private static final Instant END = END_DAY.atStartOfDay().toInstant(ZoneOffset.UTC);

  private AsAtRange() {}

  /** True when {@code asAt} lies in [2000-01-01, 2200-01-01) UTC. */
  public static boolean contains(Instant asAt) {
    Objects.requireNonNull(asAt, "asAt");
    return !asAt.isBefore(FIRST) && asAt.isBefore(END);
  }

  /**
   * The UTC day of the instant.
   *
   * @throws IllegalArgumentException if the instant lies outside [2000-01-01, 2200-01-01) UTC
   */
  public static LocalDate dayOf(Instant asAt) {
    if (!contains(asAt)) {
      throw new IllegalArgumentException("asAt is outside the supported range 2000 to 2199 (UTC)");
    }
    return asAt.atOffset(ZoneOffset.UTC).toLocalDate();
  }
}
