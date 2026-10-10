package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.refdata.ReferenceData.Anchor;
import dev.regpack.ports.refdata.ReferenceData.AsOf;
import dev.regpack.ports.refdata.ReferenceData.DatasetId;
import dev.regpack.ports.refdata.ReferenceData.Entry;
import dev.regpack.ports.refdata.ReferenceData.SnapshotId;
import dev.regpack.ports.refdata.ReferenceData.SnapshotInfo;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InMemoryReferenceDataTest {
  private static final DatasetId LEI = new DatasetId("lei-status");
  private static final DatasetId MIC = new DatasetId("mic");
  private static final Duration MAX_AGE = Duration.ofDays(1);

  private final ManualClock clock = new ManualClock(Instant.parse("2026-03-01T10:00:00Z"));
  private final InMemoryReferenceData data = new InMemoryReferenceData(clock);

  private static Entry entry(String key, String value, LocalDate from, LocalDate to) {
    return new Entry(key, Map.of("v", value), from, Optional.ofNullable(to));
  }

  private static AsOf asOf(String trade, String submission) {
    return new AsOf(LocalDate.parse(trade), LocalDate.parse(submission));
  }

  @Test
  void snapshotIdsAreMonotonicPerDataset() {
    assertThat(data.load(LEI, List.of(), MAX_AGE)).isEqualTo(new SnapshotId(1));
    assertThat(data.load(LEI, List.of(), MAX_AGE)).isEqualTo(new SnapshotId(2));
    assertThat(data.load(MIC, List.of(), MAX_AGE)).isEqualTo(new SnapshotId(1));
  }

  @Test
  void lookupHonoursHalfOpenValidityAndAnchor() {
    SnapshotId snapshot =
        data.load(
            LEI,
            List.of(entry("k", "x", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"))),
            MAX_AGE);
    assertThat(data.lookup(LEI, snapshot, "k", asOf("2026-01-01", "2026-12-31"), Anchor.TRADE_DATE))
        .isPresent();
    assertThat(data.lookup(LEI, snapshot, "k", asOf("2026-06-01", "2026-01-01"), Anchor.TRADE_DATE))
        .isEmpty();
    assertThat(
            data.lookup(
                LEI, snapshot, "k", asOf("2026-06-01", "2026-03-01"), Anchor.SUBMISSION_DATE))
        .isPresent();
    assertThat(
            data.lookup(
                LEI, snapshot, "k", asOf("2026-03-01", "2026-06-01"), Anchor.SUBMISSION_DATE))
        .isEmpty();
    assertThat(
            data.lookup(
                LEI, snapshot, "k", asOf("2026-03-01", "2025-12-31"), Anchor.SUBMISSION_DATE))
        .isEmpty();
    assertThat(
            data.lookup(
                LEI, snapshot, "other", asOf("2026-03-01", "2026-03-01"), Anchor.TRADE_DATE))
        .isEmpty();
  }

  @Test
  void olderSnapshotKeepsAnsweringAsSealed() {
    LocalDate from = LocalDate.parse("2026-01-01");
    SnapshotId first = data.load(LEI, List.of(entry("a", "1", from, null)), MAX_AGE);
    SnapshotId second = data.load(LEI, List.of(entry("a", "2", from, null)), MAX_AGE);
    AsOf when = asOf("2026-02-01", "2026-02-01");
    assertThat(data.lookup(LEI, first, "a", when, Anchor.TRADE_DATE))
        .hasValueSatisfying(e -> assertThat(e.attributes()).containsEntry("v", "1"));
    assertThat(data.lookup(LEI, second, "a", when, Anchor.TRADE_DATE))
        .hasValueSatisfying(e -> assertThat(e.attributes()).containsEntry("v", "2"));
    assertThat(data.currentSnapshot(LEI).id()).isEqualTo(second);
  }

  @Test
  void overlappingValidityIsRejectedAtLoad() {
    SnapshotId first =
        data.load(LEI, List.of(entry("a", "1", LocalDate.parse("2026-01-01"), null)), MAX_AGE);
    List<Entry> overlapping =
        List.of(
            entry("a", "1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01")),
            entry("a", "2", LocalDate.parse("2026-05-31"), LocalDate.parse("2026-12-01")));
    assertThatThrownBy(() -> data.load(LEI, overlapping, MAX_AGE))
        .isInstanceOf(IllegalArgumentException.class);
    List<Entry> openEnded =
        List.of(
            entry("a", "1", LocalDate.parse("2026-01-01"), null),
            entry("a", "2", LocalDate.parse("2030-01-01"), null));
    assertThatThrownBy(() -> data.load(LEI, openEnded, MAX_AGE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(data.currentSnapshot(LEI).id()).isEqualTo(first);
    assertThat(data.load(LEI, List.of(), MAX_AGE)).isEqualTo(new SnapshotId(2));
    // the same validity on different keys is not an overlap
    assertThat(
            data.load(
                MIC,
                List.of(
                    entry("a", "1", LocalDate.parse("2026-01-01"), null),
                    entry("b", "2", LocalDate.parse("2026-01-01"), null)),
                MAX_AGE))
        .isEqualTo(new SnapshotId(1));
  }

  @Test
  void entryRejectsEmptyOrInvertedIntervalAndCopiesAttributes() {
    LocalDate d = LocalDate.parse("2026-01-01");
    assertThatThrownBy(() -> new Entry("k", Map.of(), d, Optional.of(d)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Entry("k", Map.of(), d, Optional.of(d.minusDays(1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Entry(null, Map.of(), d, Optional.empty()))
        .isInstanceOf(NullPointerException.class);
    Map<String, String> source = new HashMap<>(Map.of("v", "1"));
    Entry entry = new Entry("k", source, d, Optional.empty());
    source.put("v", "2");
    assertThat(entry.attributes()).containsEntry("v", "1");
  }

  @Test
  void adjacentIntervalsAreAccepted() {
    LocalDate boundary = LocalDate.parse("2026-06-01");
    SnapshotId snapshot =
        data.load(
            LEI,
            List.of(
                entry("a", "1", LocalDate.parse("2026-01-01"), boundary),
                entry("a", "2", boundary, null),
                entry("b", "3", LocalDate.parse("2026-01-01"), null)),
            MAX_AGE);
    assertThat(data.lookup(LEI, snapshot, "a", asOf("2026-05-31", "2026-05-31"), Anchor.TRADE_DATE))
        .hasValueSatisfying(e -> assertThat(e.attributes()).containsEntry("v", "1"));
    assertThat(data.lookup(LEI, snapshot, "a", asOf("2026-06-01", "2026-06-01"), Anchor.TRADE_DATE))
        .hasValueSatisfying(e -> assertThat(e.attributes()).containsEntry("v", "2"));
    // another key with an equal interval is not an overlap: the rule is per key
    assertThat(data.lookup(LEI, snapshot, "b", asOf("2026-05-31", "2026-05-31"), Anchor.TRADE_DATE))
        .hasValueSatisfying(e -> assertThat(e.attributes()).containsEntry("v", "3"));
    assertThat(data.lookup(LEI, snapshot, "b", asOf("2026-06-01", "2026-06-01"), Anchor.TRADE_DATE))
        .hasValueSatisfying(e -> assertThat(e.attributes()).containsEntry("v", "3"));
  }

  @Test
  void unknownDatasetOrSnapshotFails() {
    AsOf when = asOf("2026-02-01", "2026-02-01");
    assertThatThrownBy(() -> data.currentSnapshot(LEI))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.lookup(LEI, new SnapshotId(1), "a", when, Anchor.TRADE_DATE))
        .isInstanceOf(IllegalArgumentException.class);
    data.load(LEI, List.of(), MAX_AGE);
    assertThatThrownBy(() -> data.lookup(LEI, new SnapshotId(2), "a", when, Anchor.TRADE_DATE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.lookup(LEI, new SnapshotId(0), "a", when, Anchor.TRADE_DATE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.lookup(LEI, new SnapshotId(-1), "a", when, Anchor.TRADE_DATE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.lookup(MIC, new SnapshotId(1), "a", when, Anchor.TRADE_DATE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotInfoCarriesLoadTimeAndMaxAge() {
    Instant loadTime = clock.now();
    SnapshotId id = data.load(LEI, List.of(), MAX_AGE);
    clock.advance(Duration.ofHours(5));
    SnapshotInfo info = data.currentSnapshot(LEI);
    assertThat(info.dataset()).isEqualTo(LEI);
    assertThat(info.id()).isEqualTo(id);
    assertThat(info.loadedAt()).isEqualTo(loadTime);
    assertThat(info.maxAge()).isEqualTo(MAX_AGE);
  }
}
