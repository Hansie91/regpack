package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Convention 7, review focus LF18: instants cross to {@code timestamptz} as UTC microseconds. */
class PgTimeTest {
  @Test
  void instantsRoundTripAtMicrosecondPrecisionAndAreSentAtUtc() {
    for (String text :
        new String[] {
          "2026-10-09T23:59:59.999999Z",
          "2026-10-10T00:00:00.000001Z",
          "2000-01-01T00:00:00.000000Z",
          "2199-12-31T23:59:59.999999Z",
          "1969-12-31T23:59:59.999999Z"
        }) {
      Instant instant = Instant.parse(text);
      OffsetDateTime sent = PgTime.timestamp(instant);
      assertThat(sent.getOffset()).isEqualTo(ZoneOffset.UTC);
      assertThat(sent.toInstant()).isEqualTo(instant);
      assertThat(PgTime.instant(sent)).isEqualTo(instant);
      // the same instant read at another offset is the same instant
      assertThat(PgTime.instant(sent.withOffsetSameInstant(ZoneOffset.ofHours(14))))
          .isEqualTo(instant);
    }
  }

  @Test
  void anInstantFinerThanAMicrosecondIsRefusedNotTruncated() {
    assertThatThrownBy(() -> PgTime.timestamp(Instant.parse("2026-10-09T08:00:00.000000001Z")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
