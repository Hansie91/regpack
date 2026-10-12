package dev.regpack.bench.imp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.imp.TimeLedger.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimeLedgerTest {
  private static Instant t(String time) {
    return Instant.parse("2026-10-10T" + time + "Z");
  }

  @Test
  void usedCountsActiveTimeOnly() {
    TimeLedger stopped =
        TimeLedger.empty()
            .append(t("09:00:00"), Event.START, "")
            .append(t("10:30:00"), Event.STOP, "report");
    // a stopped ledger does not grow with the clock
    assertThat(stopped.used(t("10:30:00"))).isEqualTo(Duration.ofMinutes(90));
    assertThat(stopped.used(t("23:00:00"))).isEqualTo(Duration.ofMinutes(90));

    TimeLedger running = TimeLedger.empty().append(t("09:00:00"), Event.START, "");
    assertThat(running.used(t("09:45:00"))).isEqualTo(Duration.ofMinutes(45));
    assertThat(running.used(t("09:00:00"))).isEqualTo(Duration.ZERO);
    // a clock before the start never gives a negative time
    assertThat(running.used(t("08:00:00"))).isEqualTo(Duration.ZERO);
    assertThat(TimeLedger.empty().used(t("09:00:00"))).isEqualTo(Duration.ZERO);
  }

  @Test
  void lastEventAtIsTheInstantOfTheLastEvent() {
    assertThat(TimeLedger.empty().lastEventAt()).isEmpty();
    TimeLedger ledger =
        TimeLedger.empty()
            .append(t("09:00:00"), Event.START, "")
            .append(t("10:00:00"), Event.PAUSE, "approval");
    assertThat(ledger.lastEventAt()).contains(t("10:00:00"));
    assertThat(TimeLedger.parse(ledger.toTsv()).lastEventAt()).contains(t("10:00:00"));
  }

  @Test
  void pausedWaitsAreNotCounted() {
    TimeLedger ledger =
        TimeLedger.empty()
            .append(t("09:00:00"), Event.START, "")
            .append(t("10:00:00"), Event.PAUSE, "waiting for the owner")
            .append(t("12:00:00"), Event.RESUME, "")
            .append(t("12:30:00"), Event.STOP, "");
    assertThat(ledger.used(t("20:00:00"))).isEqualTo(Duration.ofMinutes(90));

    TimeLedger paused =
        TimeLedger.empty()
            .append(t("09:00:00"), Event.START, "")
            .append(t("10:00:00"), Event.PAUSE, "approval");
    assertThat(paused.used(t("13:00:00"))).isEqualTo(Duration.ofMinutes(60));
    assertThat(paused.isRunning()).isFalse();
  }

  @Test
  void overBoxIsReported() {
    TimeLedger ledger = TimeLedger.empty().append(t("09:00:00"), Event.START, "");
    Duration box = Duration.ofHours(8);
    assertThat(ledger.overBox(box, t("16:59:00"))).isFalse();
    // the box is reached at exactly eight hours: the report says "hit: yes" there too
    assertThat(ledger.overBox(box, t("17:00:00"))).isTrue();
    assertThat(ledger.overBox(box, t("17:01:00"))).isTrue();
  }

  @Test
  void overBoxAgreesWithTheHitRuleOfTheReport() {
    TimeLedger ledger = TimeLedger.empty().append(t("09:00:00"), Event.START, "");
    Duration box = Duration.ofHours(8);
    for (String now :
        new String[] {"16:58:59", "16:59:00", "16:59:59", "17:00:00", "17:00:30", "18:00:00"}) {
      Map<String, Object> timeBox = new LinkedHashMap<>();
      timeBox.put("usedMinutes", ledger.used(t(now)).toMinutes());
      timeBox.put("boxMinutes", box.toMinutes());
      assertThat(ledger.overBox(box, t(now))).as(now).isEqualTo(SpikeResult.boxHit(timeBox));
    }
  }

  @Test
  void parseRejectsOutOfOrderEvents() {
    // time goes back
    assertThatThrownBy(
            () -> TimeLedger.parse("2026-10-10T10:00:00Z\tSTART\t\n2026-10-10T09:00:00Z\tSTOP\t\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 2");
    // the first event is not START
    assertThatThrownBy(() -> TimeLedger.parse("2026-10-10T10:00:00Z\tPAUSE\t\n"))
        .isInstanceOf(IllegalArgumentException.class);
    // RESUME without PAUSE
    assertThatThrownBy(
            () ->
                TimeLedger.parse("2026-10-10T09:00:00Z\tSTART\t\n2026-10-10T10:00:00Z\tRESUME\t\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 2");
    // anything after STOP
    assertThatThrownBy(
            () ->
                TimeLedger.parse(
                    "2026-10-10T09:00:00Z\tSTART\t\n"
                        + "2026-10-10T10:00:00Z\tSTOP\t\n"
                        + "2026-10-10T11:00:00Z\tRESUME\t\n"))
        .isInstanceOf(IllegalArgumentException.class);
    // a second START
    assertThatThrownBy(
            () ->
                TimeLedger.parse("2026-10-10T09:00:00Z\tSTART\t\n2026-10-10T10:00:00Z\tSTART\t\n"))
        .isInstanceOf(IllegalArgumentException.class);
    // garbage
    assertThatThrownBy(() -> TimeLedger.parse("yesterday\tSTART\t\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TimeLedger.parse("2026-10-10T09:00:00Z\tBEGIN\t\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void appendRefusesAnEventThatDoesNotFollowAndANoteThatBreaksTheFile() {
    TimeLedger started = TimeLedger.empty().append(t("09:00:00"), Event.START, "");
    assertThatThrownBy(() -> started.append(t("08:00:00"), Event.PAUSE, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> started.append(t("10:00:00"), Event.RESUME, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> started.append(t("10:00:00"), Event.PAUSE, "two\nlines"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> started.append(t("10:00:00"), Event.PAUSE, "a\ttab"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsThroughTheTsvText() {
    TimeLedger ledger =
        TimeLedger.empty()
            .append(t("16:57:00"), Event.START, "")
            .append(t("18:00:00"), Event.PAUSE, "waiting for the criteria lock");
    String tsv = ledger.toTsv();
    assertThat(tsv)
        .isEqualTo(
            "2026-10-10T16:57:00Z\tSTART\t\n"
                + "2026-10-10T18:00:00Z\tPAUSE\twaiting for the criteria lock\n");
    assertThat(TimeLedger.parse(tsv).toTsv()).isEqualTo(tsv);
    assertThat(TimeLedger.parse(tsv).used(t("23:00:00"))).isEqualTo(Duration.ofMinutes(63));
  }
}
