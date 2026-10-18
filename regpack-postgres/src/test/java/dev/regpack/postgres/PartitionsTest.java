package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * D-S1, Convention 7, review focus LF17: partition dates and SQL text are zone- and locale-free.
 */
class PartitionsTest {
  @Test
  void instantsNearUtcMidnightLandInTheUtcPartitions() {
    TimeZone before = TimeZone.getDefault();
    try {
      for (String zone : List.of("Pacific/Kiritimati", "America/Los_Angeles", "UTC")) {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));
        assertThat(Partitions.dateOf(Instant.parse("2026-10-09T23:59:59.999999Z")))
            .as(zone)
            .isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(Partitions.dateOf(Instant.parse("2026-10-10T00:00:00Z")))
            .as(zone)
            .isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(Partitions.dateOf(Instant.parse("2026-10-09T00:00:00Z")))
            .as(zone)
            .isEqualTo(LocalDate.of(2026, 10, 9));
      }
    } finally {
      TimeZone.setDefault(before);
    }
  }

  @Test
  void partitionNamesAndStatementsAreFixedFormat() {
    Locale before = Locale.getDefault();
    try {
      for (Locale locale : List.of(Locale.forLanguageTag("tr-TR"), Locale.ROOT, Locale.GERMANY)) {
        Locale.setDefault(locale);
        LocalDate day = LocalDate.of(2026, 10, 9);
        assertThat(Partitions.partitionName(day)).isEqualTo("decision_p20261009");
        assertThat(Partitions.partitionName(LocalDate.of(2000, 1, 1)))
            .isEqualTo("decision_p20000101");
        assertThat(Partitions.partitionName(LocalDate.of(2199, 12, 31)))
            .isEqualTo("decision_p21991231");
        assertThat(Partitions.createStatements("ledger_t1", day))
            .containsExactly(
                "CREATE TABLE ledger_t1.decision_p20261009 (LIKE ledger_t1.decision INCLUDING"
                    + " DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES)",
                "ALTER TABLE ledger_t1.decision ATTACH PARTITION ledger_t1.decision_p20261009"
                    + " FOR VALUES FROM ('2026-10-09') TO ('2026-10-10')");
      }
    } finally {
      Locale.setDefault(before);
    }
  }

  @Test
  void boundsCrossMonthYearAndLeapDayCorrectly() {
    assertThat(Partitions.createStatements("s", LocalDate.of(2026, 12, 31)).get(1))
        .endsWith("FOR VALUES FROM ('2026-12-31') TO ('2027-01-01')");
    assertThat(Partitions.createStatements("s", LocalDate.of(2028, 2, 28)).get(1))
        .endsWith("FOR VALUES FROM ('2028-02-28') TO ('2028-02-29')");
    assertThat(Partitions.createStatements("s", LocalDate.of(2199, 12, 31)).get(1))
        .endsWith("FOR VALUES FROM ('2199-12-31') TO ('2200-01-01')");
  }

  @Test
  void statementEscapesNothingFromInput() {
    LocalDate day = LocalDate.of(2026, 10, 9);
    for (String bad :
        List.of("", "Ledger", "a;drop table x", "a b", "1abc", "a\"b", "a.b", "x".repeat(64))) {
      assertThatThrownBy(() -> Partitions.createStatements(bad, day))
          .as("schema %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> Partitions.createStatements(null, day))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Partitions.createStatements("ledger", null))
        .isInstanceOf(NullPointerException.class);
    assertThat(Partitions.createStatements("x".repeat(63), day)).hasSize(2);
  }

  @Test
  void datesOutsideTheSupportedRangeAreRejected() {
    assertThat(Partitions.dateOf(Instant.parse("2000-01-01T00:00:00Z")))
        .isEqualTo(LocalDate.of(2000, 1, 1));
    assertThat(Partitions.dateOf(Instant.parse("2199-12-31T23:59:59.999999Z")))
        .isEqualTo(LocalDate.of(2199, 12, 31));
    for (String text :
        List.of(
            "1999-12-31T23:59:59.999999Z",
            "2200-01-01T00:00:00Z",
            "1970-01-01T00:00:00Z",
            "2999-01-01T00:00:00Z")) {
      assertThatThrownBy(() -> Partitions.dateOf(Instant.parse(text)))
          .as(text)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> Partitions.dateOf(Instant.MIN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Partitions.dateOf(Instant.MAX))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Partitions.partitionName(LocalDate.of(1999, 12, 31)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Partitions.partitionName(LocalDate.of(2200, 1, 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Partitions.createStatements("s", LocalDate.of(2200, 1, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
