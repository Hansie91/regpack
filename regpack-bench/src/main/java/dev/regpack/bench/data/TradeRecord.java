package dev.regpack.bench.data;

import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One synthetic trade event. Nothing in it is regulatory content or a real identifier. */
public record TradeRecord(
    long seq,
    String executionId,
    long sourceVersion,
    String firmLei,
    String venueMic,
    String eventKind,
    Instant tradedAt,
    LocalDate tradeDate,
    LocalDate settlementDate,
    String assetClass,
    String isin,
    String cfi,
    boolean tradedOnVenue,
    String clientKind,
    Optional<String> clientLei,
    BigDecimal quantity,
    BigDecimal price,
    String currency,
    String side,
    String capacity,
    Optional<String> traderId,
    List<String> underlyings) {

  private static final List<String> FIELD_PATHS =
      List.of(
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

  public TradeRecord {
    underlyings = List.copyOf(underlyings);
  }

  /** The paths a rule expression may read, in a fixed order. */
  public static List<String> fieldPaths() {
    return FIELD_PATHS;
  }

  /**
   * The value at a path: String, Long, Boolean, BigDecimal, Instant, LocalDate or List of String.
   * Empty means explicit absence. {@code instrument.underlyings} is also readable here (empty for
   * an empty list) although it is not one of {@link #fieldPaths()}.
   *
   * @throws IllegalArgumentException for a path that does not exist
   */
  public Optional<Object> valueAt(String path) {
    return switch (path) {
      case "event.kind" -> Optional.of(eventKind);
      case "event.tradedAt" -> Optional.of(tradedAt);
      case "event.sourceVersion" -> Optional.of(sourceVersion);
      case "venue.mic" -> Optional.of(venueMic);
      case "instrument.isin" -> Optional.of(isin);
      case "instrument.cfi" -> Optional.of(cfi);
      case "instrument.assetClass" -> Optional.of(assetClass);
      case "instrument.tradedOnVenue" -> Optional.of(tradedOnVenue);
      case "instrument.underlyings" ->
          underlyings.isEmpty() ? Optional.empty() : Optional.of(underlyings);
      case "trade.price" -> Optional.of(price);
      case "trade.quantity" -> Optional.of(quantity);
      case "trade.currency" -> Optional.of(currency);
      case "trade.side" -> Optional.of(side);
      case "trade.capacity" -> Optional.of(capacity);
      case "trade.tradeDate" -> Optional.of(tradeDate);
      case "trade.settlementDate" -> Optional.of(settlementDate);
      case "client.kind" -> Optional.of(clientKind);
      case "client.lei" -> clientLei.map(v -> (Object) v);
      case "firm.lei" -> Optional.of(firmLei);
      case "trader.id" -> traderId.map(v -> (Object) v);
      default -> throw new IllegalArgumentException("unknown field path: " + path);
    };
  }

  /** The canonical JSON of the record as a nested map; absent optionals and lists are omitted. */
  public String toNdjsonLine() {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("kind", eventKind);
    event.put("tradedAt", tradedAt);
    event.put("sourceVersion", sourceVersion);

    Map<String, Object> instrument = new LinkedHashMap<>();
    instrument.put("isin", isin);
    instrument.put("cfi", cfi);
    instrument.put("assetClass", assetClass);
    instrument.put("tradedOnVenue", tradedOnVenue);
    if (!underlyings.isEmpty()) {
      instrument.put("underlyings", underlyings);
    }

    Map<String, Object> trade = new LinkedHashMap<>();
    trade.put("price", price);
    trade.put("quantity", quantity);
    trade.put("currency", currency);
    trade.put("side", side);
    trade.put("capacity", capacity);
    trade.put("tradeDate", tradeDate);
    trade.put("settlementDate", settlementDate);

    Map<String, Object> client = new LinkedHashMap<>();
    client.put("kind", clientKind);
    clientLei.ifPresent(v -> client.put("lei", v));

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("seq", seq);
    record.put("executionId", executionId);
    record.put("event", event);
    record.put("venue", Map.of("mic", venueMic));
    record.put("instrument", instrument);
    record.put("trade", trade);
    record.put("client", client);
    record.put("firm", Map.of("lei", firmLei));
    traderId.ifPresent(v -> record.put("trader", Map.of("id", v)));
    return Json.canonical(record);
  }
}
