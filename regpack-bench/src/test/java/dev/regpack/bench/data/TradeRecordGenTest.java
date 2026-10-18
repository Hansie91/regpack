package dev.regpack.bench.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TradeRecordGenTest {
  private static final Set<String> KINDS =
      Set.of("NEW", "AMEND", "CANCEL", "CORRECTION", "NOVATION", "ASSIGNMENT");
  private static final Set<String> CLASSES = Set.of("EQUITY", "DEBT", "DERIVATIVE", "OTHER");
  private static final Pattern ISIN = Pattern.compile("ZZ[0-9A-Z]{10}");

  @Test
  void fieldDomainsAreRespected() {
    TradeRecordGen gen = new TradeRecordGen(3);
    for (int i = 0; i < 20_000; i++) {
      TradeRecord r = gen.next();
      assertThat(KINDS).contains(r.eventKind());
      assertThat(CLASSES).contains(r.assetClass());
      assertThat(TradeRecordGen.currencies()).contains(r.currency());
      assertThat(r.isin()).matches(ISIN);
      assertThat(r.price().scale()).isBetween(2, 6);
      assertThat(r.quantity().scale()).isBetween(2, 6);
      assertThat(r.price().signum()).isPositive();
      assertThat(r.quantity().signum()).isPositive();
      assertThat(r.tradedAt().getNano() % 1000).isZero();
      assertThat(r.tradeDate()).isEqualTo(r.tradedAt().atZone(ZoneOffset.UTC).toLocalDate());
      assertThat(r.settlementDate()).isAfterOrEqualTo(r.tradeDate());
      assertThat(r.side()).isIn("BUY", "SELL");
      assertThat(r.firmLei()).hasSize(20);
      assertThat(r.venueMic()).hasSize(4);
      assertThat(r.executionId()).startsWith("EX-");
      if (r.assetClass().equals("DERIVATIVE")) {
        assertThat(r.underlyings()).isNotEmpty();
        assertThat(r.underlyings()).allSatisfy(u -> assertThat(u).matches(ISIN));
      } else {
        assertThat(r.underlyings()).isEmpty();
      }
    }
    assertThat(TradeRecordGen.currencies()).hasSize(8).doesNotContain("ZZZ");
  }

  @Test
  void executionIdsAreUnique() {
    TradeRecordGen gen = new TradeRecordGen(4);
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 200_000; i++) {
      assertThat(ids.add(gen.next().executionId())).isTrue();
    }
  }

  @Test
  void contentRepeatRateBelowOnePercentIn200k() {
    TradeRecordGen gen = new TradeRecordGen(5);
    Set<String> tuples = new HashSet<>();
    int repeats = 0;
    for (int i = 0; i < 200_000; i++) {
      TradeRecord r = gen.next();
      String tuple =
          String.join(
              "|",
              r.venueMic(),
              r.isin(),
              r.clientLei().orElse("NP"),
              r.price().toPlainString(),
              r.quantity().toPlainString(),
              r.tradedAt().toString());
      if (!tuples.add(tuple)) {
        repeats++;
      }
    }
    assertThat(repeats).isLessThan(2_000);
  }

  @Test
  void naturalPersonShareIsAboutThreePercent() {
    TradeRecordGen gen = new TradeRecordGen(6);
    int natural = 0;
    int n = 200_000;
    for (int i = 0; i < n; i++) {
      if (gen.next().clientLei().isEmpty()) {
        natural++;
      }
    }
    assertThat(natural).isBetween(n * 25 / 1000, n * 35 / 1000);
  }

  @Test
  void absentClientLeiOnlyForNaturalPersons() {
    TradeRecordGen gen = new TradeRecordGen(8);
    int natural = 0;
    for (int i = 0; i < 50_000; i++) {
      TradeRecord r = gen.next();
      assertThat(r.clientLei().isEmpty()).isEqualTo(r.clientKind().equals("NATURAL_PERSON"));
      r.clientLei().ifPresent(lei -> assertThat(lei).hasSize(20));
      if (r.clientLei().isEmpty()) {
        natural++;
      }
    }
    assertThat(natural).isPositive();
  }

  @Test
  void ndjsonLineIsCanonicalJson() {
    TradeRecordGen gen = new TradeRecordGen(9);
    for (int i = 0; i < 5_000; i++) {
      String line = gen.next().toNdjsonLine();
      assertThat(line).doesNotContain("\n");
      assertThat(Json.canonical(Json.parse(line))).isEqualTo(line);
    }
  }

  @Test
  void valueAtCoversEveryDeclaredPath() {
    List<String> paths = TradeRecord.fieldPaths();
    assertThat(paths)
        .containsExactly(
            "event.kind",
            "event.tradedAt",
            "event.sourceVersion",
            "venue.mic",
            "instrument.isin",
            "instrument.cfi",
            "instrument.assetClass",
            "instrument.tradedOnVenue",
            "trade.price",
            "trade.quantity",
            "trade.currency",
            "trade.side",
            "trade.capacity",
            "trade.tradeDate",
            "trade.settlementDate",
            "client.kind",
            "client.lei",
            "firm.lei",
            "trader.id");
    TradeRecordGen gen = new TradeRecordGen(10);
    Set<String> seenPresent = new HashSet<>();
    Set<String> seenAbsent = new HashSet<>();
    for (int i = 0; i < 5_000; i++) {
      TradeRecord r = gen.next();
      for (String path : paths) {
        Optional<Object> value = r.valueAt(path);
        if (value.isEmpty()) {
          seenAbsent.add(path);
          continue;
        }
        seenPresent.add(path);
        assertThat(value.get())
            .isInstanceOfAny(
                String.class,
                Long.class,
                Boolean.class,
                BigDecimal.class,
                Instant.class,
                LocalDate.class,
                List.class);
      }
      assertThat(r.valueAt("client.lei")).isEqualTo(r.clientLei().map(s -> (Object) s));
      assertThat(r.valueAt("trader.id")).isEqualTo(r.traderId().map(s -> (Object) s));
      assertThat(r.valueAt("event.sourceVersion")).contains(r.sourceVersion());
      assertThat(r.valueAt("trade.price")).contains(r.price());
    }
    assertThat(seenPresent).containsAll(paths);
    assertThat(seenAbsent).containsExactlyInAnyOrder("client.lei", "trader.id");
    assertThatThrownBy(() -> gen.next().valueAt("no.such.path"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
