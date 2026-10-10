package dev.regpack.spikes.r.table;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.TradeRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Hand-built records and packs for the rule-table tests. */
public final class TestRecords {
  private TestRecords() {}

  /** A legal-entity equity trade on a venue, with every optional field present. */
  public static TradeRecord record(
      long seq, String eventKind, String assetClass, boolean tradedOnVenue, String clientKind) {
    return record(seq, eventKind, assetClass, tradedOnVenue, clientKind, "XPAR");
  }

  public static TradeRecord record(
      long seq,
      String eventKind,
      String assetClass,
      boolean tradedOnVenue,
      String clientKind,
      String venueMic) {
    return new TradeRecord(
        seq,
        "EX-" + seq,
        1L,
        "SYNFIRM0000000000001",
        venueMic,
        eventKind,
        Instant.parse("2026-10-09T09:30:00.123456Z"),
        LocalDate.of(2026, 10, 9),
        LocalDate.of(2026, 10, 11),
        assetClass,
        "ZZ0000000001",
        "ESVUFR",
        tradedOnVenue,
        clientKind,
        clientKind.equals("LEGAL_ENTITY") ? Optional.of("C0000000000000000001") : Optional.empty(),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR",
        "BUY",
        "PRINCIPAL",
        Optional.of("TRD001"),
        List.of());
  }

  public static GeneratedRule rule(
      String id, int table, int priority, String when, String outcome) {
    return new GeneratedRule(id, table, priority, when, outcome, id);
  }

  public static GeneratedPack pack(String id, List<List<GeneratedRule>> tables) {
    int count = tables.stream().mapToInt(List::size).sum();
    return new GeneratedPack(id, 0L, PackShape.S1_FIRST_HIT_REALISTIC, count, tables);
  }
}
