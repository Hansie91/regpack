package dev.regpack.model.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** FP2: the supported range of the asAt of a decision, [2000-01-01, 2200-01-01) UTC. */
class AsAtRangeTest {
  @Test
  void theFirstInstantAndTheLastNanosecondAreInside() {
    Instant first = Instant.parse("2000-01-01T00:00:00Z");
    Instant last = Instant.parse("2199-12-31T23:59:59.999999999Z");
    assertThat(AsAtRange.contains(first)).isTrue();
    assertThat(AsAtRange.contains(last)).isTrue();
    assertThat(AsAtRange.dayOf(first)).isEqualTo(LocalDate.of(2000, 1, 1));
    assertThat(AsAtRange.dayOf(last)).isEqualTo(LocalDate.of(2199, 12, 31));
  }

  @Test
  void anythingBeforeOrFromTheEndDayIsOutside() {
    for (String outside :
        new String[] {
          "1999-12-31T23:59:59.999999999Z",
          "1970-01-01T00:00:00Z",
          "0001-01-01T00:00:00Z",
          "2200-01-01T00:00:00Z",
          "9999-12-31T23:59:59Z"
        }) {
      Instant asAt = Instant.parse(outside);
      assertThat(AsAtRange.contains(asAt)).as(outside).isFalse();
      assertThatThrownBy(() -> AsAtRange.dayOf(asAt))
          .as(outside)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("asAt is outside the supported range 2000 to 2199 (UTC)");
    }
    assertThatThrownBy(() -> AsAtRange.dayOf(Instant.MIN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AsAtRange.dayOf(Instant.MAX))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theDayIsTheUtcDate() {
    assertThat(AsAtRange.dayOf(Instant.parse("2026-10-09T23:59:59.999999Z")))
        .isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(AsAtRange.dayOf(Instant.parse("2026-10-10T00:00:00Z")))
        .isEqualTo(LocalDate.of(2026, 10, 10));
  }

  @Test
  void aNullIsRefused() {
    assertThatThrownBy(() -> AsAtRange.contains(null)).isInstanceOf(NullPointerException.class);
  }
}
