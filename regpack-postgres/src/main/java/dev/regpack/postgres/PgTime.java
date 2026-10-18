package dev.regpack.postgres;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * How instants cross to and from {@code timestamptz} (Convention 7, review focus LF18): always as
 * an {@link OffsetDateTime} at {@link ZoneOffset#UTC}, in whole microseconds, the precision of both
 * the injected {@code Clock} and PostgreSQL. Nothing here consults the default zone.
 */
final class PgTime {
  private PgTime() {}

  /**
   * The value to bind for an instant.
   *
   * @throws IllegalArgumentException if the instant is finer than a microsecond: it is refused
   *     rather than silently truncated
   */
  static OffsetDateTime timestamp(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    if (instant.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("an instant must be whole microseconds");
    }
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** The instant of a {@code timestamptz} value read with {@code getObject(i, OffsetDateTime)}. */
  static Instant instant(OffsetDateTime value) {
    return Objects.requireNonNull(value, "value").toInstant();
  }
}
