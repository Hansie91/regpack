package dev.regpack.flow.enrich;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.ingest.CsvException;
import dev.regpack.inmemory.InMemoryReferenceData;
import dev.regpack.model.ident.Isin;
import dev.regpack.model.ident.Lei;
import dev.regpack.ports.refdata.ReferenceData;
import dev.regpack.ports.refdata.ReferenceData.AsOf;
import dev.regpack.ports.refdata.ReferenceData.Entry;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.ident.SyntheticIds;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The reference seed reader (plan W1a, task T8): the intervals, the refusals, and the closed-world
 * rule that every identifier of a committed seed is synthetic (SF15).
 */
class RefDataSeedTest {
  private static final String SAMPLES = "/dev/regpack/flow/samples/";

  private static String sample(String name) throws IOException {
    try (InputStream in = RefDataSeedTest.class.getResourceAsStream(SAMPLES + name)) {
      assertThat(in).as("the sample resource " + name).isNotNull();
      return new String(in.readAllBytes(), UTF_8);
    }
  }

  @Test
  void parsesIntervalsAndRefusesOverlap() {
    String csv =
        "key,status,valid_from,valid_to\n"
            + "K1,ACTIVE,2020-01-01,2021-01-01\n"
            + "K1,LAPSED,2021-01-01,\n"
            + "K2,ACTIVE,2020-01-01,\n";

    List<Entry> entries = RefDataSeed.parse(csv);

    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).key()).isEqualTo("K1");
    assertThat(entries.get(0).attributes()).isEqualTo(Map.of("status", "ACTIVE"));
    assertThat(entries.get(0).validFrom()).isEqualTo(LocalDate.parse("2020-01-01"));
    assertThat(entries.get(0).validTo()).contains(LocalDate.parse("2021-01-01"));
    assertThat(entries.get(1).attributes()).isEqualTo(Map.of("status", "LAPSED"));
    assertThat(entries.get(1).validTo()).isEqualTo(Optional.empty());
    assertThat(entries.get(2).key()).isEqualTo("K2");

    // the half-open intervals touch at 2021-01-01 and do not overlap: the store takes them
    InMemoryReferenceData store = new InMemoryReferenceData(new ManualClock(Instant.EPOCH));
    ReferenceData.DatasetId dataset = new ReferenceData.DatasetId("lei-status");
    ReferenceData.SnapshotId snapshot = store.load(dataset, entries, Duration.ofDays(1));
    AsOf day = new AsOf(LocalDate.parse("2020-12-31"), LocalDate.parse("2020-12-31"));
    assertThat(
            store
                .lookup(dataset, snapshot, "K1", day, ReferenceData.Anchor.TRADE_DATE)
                .orElseThrow()
                .attributes())
        .containsEntry("status", "ACTIVE");
    AsOf later = new AsOf(LocalDate.parse("2021-01-01"), LocalDate.parse("2021-01-01"));
    assertThat(
            store
                .lookup(dataset, snapshot, "K1", later, ReferenceData.Anchor.TRADE_DATE)
                .orElseThrow()
                .attributes())
        .containsEntry("status", "LAPSED");

    // overlap: a closed interval that runs into the next, and an open one before a later row
    assertThatThrownBy(
            () ->
                RefDataSeed.parse(
                    "key,status,valid_from,valid_to\n"
                        + "K1,ACTIVE,2020-01-01,2021-06-01\n"
                        + "K1,LAPSED,2021-01-01,\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlap")
        .hasMessageNotContaining("K1");
    assertThatThrownBy(
            () ->
                RefDataSeed.parse(
                    "key,status,valid_from,valid_to\n"
                        + "K1,ACTIVE,2020-01-01,\n"
                        + "K1,LAPSED,2021-01-01,\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlap");
    // the order of the rows does not hide an overlap
    assertThatThrownBy(
            () ->
                RefDataSeed.parse(
                    "key,status,valid_from,valid_to\n"
                        + "K1,LAPSED,2021-01-01,\n"
                        + "K1,ACTIVE,2020-01-01,2021-06-01\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsSeveralAttributeColumnsAndLeavesAnEmptyCellOut() {
    List<Entry> entries =
        RefDataSeed.parse(
            "key,status,segment,valid_from,valid_to\n"
                + "K1,LISTED,MAIN,2020-01-01,\n"
                + "K2,LISTED,,2020-01-01,\n");

    assertThat(entries.get(0).attributes())
        .isEqualTo(Map.of("status", "LISTED", "segment", "MAIN"));
    assertThat(entries.get(1).attributes()).isEqualTo(Map.of("status", "LISTED"));
  }

  @Test
  void refusesAWrongHeaderABadDateAndAnEmptyIntervalWithoutEchoingAValue() {
    // the header: key first, valid_from and valid_to last, attribute names in lower case
    for (String header :
        List.of(
            "id,status,valid_from,valid_to",
            "key,status,valid_to,valid_from",
            "key,valid_from,valid_to",
            "key,status,valid_from",
            "key,Status,valid_from,valid_to",
            "key,status,valid_from,valid_to,extra")) {
      assertThatThrownBy(() -> RefDataSeed.parse(header + "\n"))
          .as("header %s", header)
          .isInstanceOf(IllegalArgumentException.class);
    }
    String head = "key,status,valid_from,valid_to\n";
    // a bad date, an interval that ends where it starts, a blank key, a missing start
    for (String row :
        List.of(
            "SECRETKEY,ACTIVE,2020-13-01,",
            "SECRETKEY,ACTIVE,2020-01-01,2020-01-01",
            "SECRETKEY,ACTIVE,2020-01-01,2019-01-01",
            ",ACTIVE,2020-01-01,",
            "SECRETKEY,ACTIVE,,",
            "SECRETKEY,ACTIVE,2020-01-01,not-a-date")) {
      assertThatThrownBy(() -> RefDataSeed.parse(head + row + "\n"))
          .as("row %s", row)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("line 2")
          .hasMessageNotContaining("SECRETKEY")
          .hasMessageNotContaining("2020");
    }
    // a malformed CSV is the CSV reader's refusal, which names a code and a line and no value
    assertThatThrownBy(() -> RefDataSeed.parse(head + "K1,ACTIVE,2020-01-01\n"))
        .isInstanceOf(CsvException.class);
    assertThatThrownBy(() -> RefDataSeed.parse("")).isInstanceOf(CsvException.class);
  }

  @Test
  void everySeedIdentifierIsSynthetic() throws IOException {
    Set<String> leis = Set.copyOf(SyntheticIds.LEIS);
    Set<String> isins = Set.copyOf(SyntheticIds.ISINS);
    Set<String> mics = Set.of("XPAR", "XAMS");

    List<Entry> leiStatus = RefDataSeed.parse(sample("lei-status.csv"));
    assertThat(leiStatus).hasSize(5);
    for (Entry entry : leiStatus) {
      assertThat(leis).as("a LEI of lei-status.csv").contains(entry.key());
      assertThat(Lei.isValid(entry.key())).isTrue();
      assertThat(entry.attributes()).containsKey("status");
    }

    List<Entry> instruments = RefDataSeed.parse(sample("isin-mic.csv"));
    assertThat(instruments).hasSize(4);
    for (Entry entry : instruments) {
      String[] parts = entry.key().split("\\|", -1);
      assertThat(parts).hasSize(2);
      assertThat(isins).as("an ISIN of isin-mic.csv").contains(parts[0]);
      assertThat(Isin.isValid(parts[0])).isTrue();
      assertThat(mics).contains(parts[1]);
    }

    List<Entry> venues = RefDataSeed.parse(sample("mic.csv"));
    assertThat(venues).extracting(Entry::key).containsExactlyInAnyOrder("XPAR", "XAMS");

    // the account seed is read by the static data source: its values are synthetic LEIs too
    String[] lines = sample("static-data.csv").split("\n");
    assertThat(lines[0]).isEqualTo("key,lei");
    for (int i = 1; i < lines.length; i++) {
      assertThat(leis).contains(lines[i].split(",", -1)[1].strip());
    }
  }
}
