package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.flow.ingest.Mapper.Mapped;
import dev.regpack.flow.ingest.Mapper.Quarantined;
import dev.regpack.flow.ingest.Mapper.Result;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.canonical.Side;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MapperTest {
  private static final List<String> HEADER =
      List.of(
          "record_id",
          "version",
          "event",
          "exec_id",
          "venue_mic",
          "traded_at",
          "isin",
          "on_venue",
          "account",
          "client_lei",
          "client_type",
          "side",
          "capacity",
          "quantity",
          "price",
          "currency",
          "counterparty",
          "exec_within");

  /** The cells of T-001 of the day-1 sample. */
  private static Map<String, String> base() {
    Map<String, String> cells = new LinkedHashMap<>();
    cells.put("record_id", "T-001");
    cells.put("version", "1");
    cells.put("event", "NEW");
    cells.put("exec_id", "EX-001");
    cells.put("venue_mic", "XPAR");
    cells.put("traded_at", "2026-10-30T09:30:00.123456Z");
    cells.put("isin", "ZZ0000000016");
    cells.put("on_venue", "true");
    cells.put("account", "ACC-1");
    cells.put("client_lei", "SYNT00CLIENT00000167");
    cells.put("client_type", "LEGAL_ENTITY");
    cells.put("side", "BUY");
    cells.put("capacity", "AOTC");
    cells.put("quantity", "100");
    cells.put("price", "12.25");
    cells.put("currency", "EUR");
    cells.put("counterparty", "SYNT00CCP00000000194");
    cells.put("exec_within", "ALGO-1");
    return cells;
  }

  private static Result map(String column, String value) throws IOException {
    Map<String, String> cells = base();
    assertThat(cells).containsKey(column);
    cells.put(column, value);
    return mapper().map(7, HEADER, new ArrayList<>(cells.values()));
  }

  private static Mapper mapper() throws IOException {
    return new Mapper(MappingPackTest.sample());
  }

  private static Quarantined quarantined(String column, String value) throws IOException {
    Result result = map(column, value);
    assertThat(result).as(column + " bad value").isInstanceOf(Quarantined.class);
    return (Quarantined) result;
  }

  private static Mapped mapped(String column, String value) throws IOException {
    Result result = map(column, value);
    assertThat(result).as(column + " good value").isInstanceOf(Mapped.class);
    return (Mapped) result;
  }

  private static void assertQuarantined(String column, String value, String code)
      throws IOException {
    Quarantined q = quarantined(column, value);
    assertThat(q.column()).as(column + " column").isEqualTo(column);
    assertThat(q.code()).as(column + " code").isEqualTo(code);
    assertThat(q.line()).isEqualTo(7);
    // never the value
    assertThat(q.toString()).doesNotContain(value.isEmpty() ? "\u0000" : value);
  }

  @Test
  void mapsTheDay1Rows() throws IOException {
    byte[] bytes;
    try (InputStream in =
        MapperTest.class.getResourceAsStream("/dev/regpack/flow/samples/trades-day1.csv")) {
      bytes = in.readAllBytes();
    }
    CsvReader.Table table = CsvReader.read(bytes, 256, 10_000, 64);
    Mapper mapper = mapper();
    List<Mapped> all = new ArrayList<>();
    for (CsvReader.Row row : table.rows()) {
      Result result = mapper.map(row.line(), table.header(), row.cells());
      assertThat(result).as("line " + row.line()).isInstanceOf(Mapped.class);
      all.add((Mapped) result);
    }
    assertThat(all).hasSize(8);
    // T-001
    MappedRecord first = all.get(0).record();
    assertThat(first.sourceRecordId()).isEqualTo("T-001");
    assertThat(first.sourceVersion()).isEqualTo(1L);
    assertThat(first.attributes()).containsExactly(Map.entry("account", "ACC-1"));
    CanonicalExecution e = first.execution();
    assertThat(e.firmLei()).isEqualTo("SYNT00FIRM0000000152");
    assertThat(e.venueMic()).isEqualTo("XPAR");
    assertThat(e.venueExecutionId()).isEqualTo("EX-001");
    assertThat(e.eventKind()).isEqualTo(LifecycleKind.NEW);
    assertThat(e.tradedAt()).isEqualTo(Instant.parse("2026-10-30T09:30:00.123456Z"));
    assertThat(e.assetClass()).isEqualTo(AssetClass.EQUITY);
    assertThat(e.isin()).isEqualTo("ZZ0000000016");
    assertThat(e.tradedOnVenue()).isTrue();
    assertThat(e.clientKind()).isEqualTo(PartyKind.LEGAL_ENTITY);
    assertThat(e.clientLei()).contains("SYNT00CLIENT00000167");
    assertThat(e.quantity()).isEqualByComparingTo("100");
    assertThat(e.price()).isEqualByComparingTo("12.25");
    assertThat(e.currency()).isEqualTo("EUR");
    assertThat(e.side()).contains(Side.BUY);
    assertThat(e.capacity()).contains("AOTC");
    assertThat(e.counterparty()).contains("SYNT00CCP00000000194");
    assertThat(e.executionWithinFirm()).contains("ALGO-1");
    assertThat(all.get(0).quantity()).isEqualByComparingTo("100");
    // T-002 has no client LEI: absent, not empty
    CanonicalExecution second = all.get(1).record().execution();
    assertThat(second.clientLei()).isEmpty();
    assertThat(second.side()).contains(Side.SELL);
    assertThat(second.capacity()).contains("MTCH");
    assertThat(all.get(1).record().attributes()).containsEntry("account", "ACC-2");
    // T-006 is a natural person and T-007 a novation: both map, the pack decides later
    assertThat(all.get(5).record().execution().clientKind()).isEqualTo(PartyKind.NATURAL_PERSON);
    assertThat(all.get(6).record().execution().eventKind()).isEqualTo(LifecycleKind.NOVATION);
    // the quantities add up to the control total of the marker
    BigDecimal sum = BigDecimal.ZERO;
    for (Mapped m : all) {
      sum = sum.add(m.quantity());
    }
    assertThat(sum).isEqualByComparingTo("540.5");
  }

  @Test
  void aVersionAndARecordIdComeFromTheirColumns() throws IOException {
    assertThat(mapped("version", "9223372036854775807").record().sourceVersion())
        .isEqualTo(Long.MAX_VALUE);
    assertThat(mapped("record_id", "T-ÄÖ").record().sourceRecordId()).isEqualTo("T-ÄÖ");
  }

  @Test
  void aBadDecimalQuarantinesNamingTheColumn() throws IOException {
    for (String bad : List.of("abc", "1e5", "-", "1.", ".5", "1,5", "1 000", "+1", "0x10", "١٢")) {
      assertQuarantined("quantity", bad, "DECIMAL");
      assertQuarantined("price", bad, "DECIMAL");
    }
    assertQuarantined("quantity", "", "MISSING");
    assertThat(mapped("quantity", "-3.5").quantity()).isEqualByComparingTo("-3.5");
    assertThat(mapped("price", "0007.50").record().execution().price()).isEqualByComparingTo("7.5");
  }

  @Test
  void aVeryLongDecimalIsRefusedBeforeItIsParsed() throws IOException {
    assertQuarantined("quantity", "9".repeat(5_000), "DECIMAL");
    assertQuarantined("quantity", "1".repeat(300) + ".5", "DECIMAL");
    // 20 digits in total are accepted, 21 are not; 10 fraction digits are accepted, 11 are not
    assertThat(mapped("quantity", "9".repeat(20)).quantity())
        .isEqualTo(new BigDecimal("9".repeat(20)));
    assertQuarantined("quantity", "9".repeat(21), "DECIMAL");
    assertThat(mapped("price", "1." + "5".repeat(10)).record().execution().price().scale())
        .isEqualTo(10);
    assertQuarantined("price", "1." + "5".repeat(11), "DECIMAL");
    assertQuarantined("price", "9".repeat(11) + "." + "5".repeat(10), "DECIMAL");
  }

  @Test
  void aControlCharacterInTheExecutionIdQuarantinesKeyText() throws IOException {
    assertQuarantined("exec_id", "EX-\u0001", "KEY_TEXT");
    assertQuarantined("exec_id", "EX-\u0000", "KEY_TEXT");
    assertQuarantined("exec_id", "EX-\n1", "KEY_TEXT");
    assertQuarantined("exec_id", "EX-\uD800", "KEY_TEXT");
    assertQuarantined("exec_id", "EX|1", "KEY_TEXT");
    assertQuarantined("exec_id", "E".repeat(257), "KEY_TEXT");
    assertQuarantined("exec_id", "   ", "KEY_TEXT");
    assertQuarantined("venue_mic", "X\u007F", "KEY_TEXT");
    assertQuarantined("record_id", "T-\u0001", "KEY_TEXT");
    assertThat(mapped("exec_id", "E".repeat(256)).record().execution().venueExecutionId())
        .hasSize(256);
    // each part is within its bound and the whole identity is not
    Result whole =
        new Mapper(MappingPackTest.sample())
            .map(
                7,
                HEADER,
                cellsWith(Map.of("exec_id", "E".repeat(256), "venue_mic", "M".repeat(256))));
    assertThat(whole).isInstanceOf(Quarantined.class);
    assertThat(((Quarantined) whole).code()).isEqualTo("KEY_TEXT");
  }

  private static List<String> cellsWith(Map<String, String> overrides) {
    Map<String, String> cells = base();
    cells.putAll(overrides);
    return new ArrayList<>(cells.values());
  }

  @Test
  void anAsAtOutside2000To2200Quarantines() throws IOException {
    assertQuarantined("traded_at", "1999-12-31T23:59:59Z", "AS_AT_RANGE");
    assertQuarantined("traded_at", "2200-01-01T00:00:00Z", "AS_AT_RANGE");
    assertQuarantined("traded_at", "0001-01-01T00:00:00Z", "AS_AT_RANGE");
    assertQuarantined("traded_at", "9999-12-31T23:59:59.999999Z", "AS_AT_RANGE");
    assertThat(mapped("traded_at", "2000-01-01T00:00:00Z").record().execution().tradedAt())
        .isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
    assertThat(mapped("traded_at", "2199-12-31T23:59:59.999999Z").record().execution().tradedAt())
        .isEqualTo(Instant.parse("2199-12-31T23:59:59.999999Z"));
  }

  @Test
  void anUnknownEnumQuarantines() throws IOException {
    assertQuarantined("event", "SPLIT", "ENUM");
    assertQuarantined("event", "new", "ENUM");
    assertQuarantined("side", "buy", "ENUM");
    assertQuarantined("client_type", "COMPANY", "ENUM");
    assertQuarantined("capacity", "OTHER", "ENUM");
    assertQuarantined("exec_within", "ALGO-9", "ENUM");
    // a person-like value in a column that takes a closed set: quarantined, never mapped, never
    // echoed
    assertQuarantined("exec_within", "Jane Doe", "ENUM");
    assertQuarantined("exec_within", "J. Smith (trader)", "ENUM");
  }

  @Test
  void sixFractionalSecondDigitsOfTradedAtAreKeptAndSevenAreQuarantined() throws IOException {
    assertThat(mapped("traded_at", "2026-10-30T09:30:00.123456Z").record().execution().tradedAt())
        .isEqualTo(Instant.parse("2026-10-30T09:30:00.123456Z"));
    assertThat(mapped("traded_at", "2026-10-30T09:30:00.1Z").record().execution().tradedAt())
        .isEqualTo(Instant.parse("2026-10-30T09:30:00.100Z"));
    assertThat(mapped("traded_at", "2026-10-30T09:30:00Z").record().execution().tradedAt())
        .isEqualTo(Instant.parse("2026-10-30T09:30:00Z"));
    assertQuarantined("traded_at", "2026-10-30T09:30:00.1234567Z", "INSTANT_FORMAT");
  }

  @Test
  void anOffsetALowerCaseDesignatorOrSevenFractionDigitsQuarantineNamingTheColumn()
      throws IOException {
    for (String bad :
        List.of(
            "2026-10-30T10:30:00+01:00",
            "2026-10-30T09:30:00+00:00",
            "2026-10-30t09:30:00Z",
            "2026-10-30T09:30:00z",
            "2026-10-30T09:30:00.1234567Z",
            "2026-10-30T09:30:00.Z",
            "2026-10-30 09:30:00Z",
            "2026-10-30T09:30Z",
            "2026-13-30T09:30:00Z",
            "2026-02-30T09:30:00Z",
            "2026-10-30T24:00:00Z",
            "2026-10-30T09:30:00",
            "+2026-10-30T09:30:00Z",
            "٢٠٢٦-10-30T09:30:00Z")) {
      assertQuarantined("traded_at", bad, "INSTANT_FORMAT");
    }
    assertQuarantined("traded_at", "", "MISSING");
  }

  @Test
  void aBadOrNegativeVersionQuarantines() throws IOException {
    for (String bad : List.of("abc", "-1", "9223372036854775808", "1.0", " 1", "1 ", "+1", "1e3")) {
      assertQuarantined("version", bad, "VERSION");
    }
    assertQuarantined("version", "9".repeat(5_000), "VERSION");
    assertQuarantined("version", "", "MISSING");
    assertThat(mapped("version", "0").record().sourceVersion()).isZero();
  }

  @Test
  void anEmptyOptionalCellIsAbsentAndAnEmptyRequiredCellIsMissing() throws IOException {
    CanonicalExecution e = mapped("client_lei", "").record().execution();
    assertThat(e.clientLei()).isEmpty();
    Map<String, String> none = new LinkedHashMap<>();
    none.put("side", "");
    none.put("capacity", "");
    none.put("counterparty", "");
    none.put("exec_within", "");
    none.put("client_lei", "");
    none.put("account", "");
    Result result = mapper().map(7, HEADER, cellsWith(none));
    assertThat(result).isInstanceOf(Mapped.class);
    CanonicalExecution bare = ((Mapped) result).record().execution();
    assertThat(bare.side()).isEmpty();
    assertThat(bare.capacity()).isEmpty();
    assertThat(bare.counterparty()).isEmpty();
    assertThat(bare.executionWithinFirm()).isEmpty();
    assertThat(((Mapped) result).record().attributes()).isEmpty();
    for (String required :
        List.of(
            "venue_mic",
            "exec_id",
            "isin",
            "currency",
            "event",
            "on_venue",
            "client_type",
            "price",
            "record_id")) {
      assertQuarantined(required, "", "MISSING");
    }
  }

  @Test
  void aBooleanIsExactlyTrueOrFalse() throws IOException {
    assertThat(mapped("on_venue", "false").record().execution().tradedOnVenue()).isFalse();
    for (String bad : List.of("TRUE", "yes", "1", "True", "false ")) {
      assertQuarantined("on_venue", bad, "BOOLEAN");
    }
  }

  @Test
  void theFirstBadColumnInMappingOrderIsReportedAndTheRowIsQuarantinedWhole() throws IOException {
    Result result =
        mapper().map(9, HEADER, cellsWith(Map.of("quantity", "abc", "side", "nope", "event", "X")));
    assertThat(result).isInstanceOf(Quarantined.class);
    Quarantined q = (Quarantined) result;
    assertThat(q.line()).isEqualTo(9);
    assertThat(q.column()).isEqualTo("event");
  }

  @Test
  void aMissingHeaderColumnQuarantinesAndNamesIt() throws IOException {
    List<String> header = new ArrayList<>(HEADER);
    int at = header.indexOf("price");
    header.remove(at);
    List<String> cells = new ArrayList<>(cellsWith(Map.of()));
    cells.remove(at);
    assertThat(mapper().missingColumns(header)).containsExactly("price");
    assertThat(mapper().missingColumns(HEADER)).isEmpty();
    Result result = mapper().map(2, header, cells);
    assertThat(result).isInstanceOf(Quarantined.class);
    assertThat(((Quarantined) result).column()).isEqualTo("price");
    assertThat(((Quarantined) result).code()).isEqualTo("MISSING");
    // a row shorter than its header is a quarantine, not an exception
    assertThat(mapper().map(2, HEADER, List.of("T-001", "1"))).isInstanceOf(Quarantined.class);
  }

  @Test
  void noResultAndNoRecordPrintsAFieldValue() throws IOException {
    Mapped m = mapped("account", "ACC-SECRET");
    assertThat(m.toString())
        .doesNotContain("SECRET")
        .doesNotContain("T-001")
        .doesNotContain("XPAR");
    assertThat(m.record().toString()).doesNotContain("SECRET").doesNotContain("T-001");
    assertThat(quarantined("event", "SECRET-EVENT").toString()).doesNotContain("SECRET");
    assertThat("ACC-SECRET".getBytes(UTF_8)).isNotEmpty();
    assertThat(Optional.of(m.record().attributes())).isPresent();
  }
}
