package dev.regpack.model.canonical;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Converts a {@link CanonicalExecution} to and from the map form of canonical JSON. */
public final class CanonicalExecutionCodec {
  private CanonicalExecutionCodec() {}

  /** Keys are the record component names; {@code clientLei} is omitted when absent. */
  public static Map<String, Object> toMap(CanonicalExecution execution) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("firmLei", execution.firmLei());
    map.put("venueMic", execution.venueMic());
    map.put("venueExecutionId", execution.venueExecutionId());
    map.put("eventKind", execution.eventKind().name());
    map.put("tradedAt", execution.tradedAt());
    map.put("assetClass", execution.assetClass().name());
    map.put("isin", execution.isin());
    map.put("tradedOnVenue", execution.tradedOnVenue());
    map.put("clientKind", execution.clientKind().name());
    execution.clientLei().ifPresent(lei -> map.put("clientLei", lei));
    map.put("quantity", execution.quantity());
    map.put("price", execution.price());
    map.put("currency", execution.currency());
    return map;
  }

  /** Reads the map form; an {@link IllegalArgumentException} names the offending field. */
  public static CanonicalExecution fromMap(Map<String, Object> map) {
    return new CanonicalExecution(
        text(map, "firmLei"),
        text(map, "venueMic"),
        text(map, "venueExecutionId"),
        enumValue(map, "eventKind", LifecycleKind.class),
        instant(map, "tradedAt"),
        enumValue(map, "assetClass", AssetClass.class),
        text(map, "isin"),
        bool(map, "tradedOnVenue"),
        enumValue(map, "clientKind", PartyKind.class),
        map.containsKey("clientLei") ? Optional.of(text(map, "clientLei")) : Optional.empty(),
        decimal(map, "quantity"),
        decimal(map, "price"),
        text(map, "currency"));
  }

  private static Object required(Map<String, Object> map, String field) {
    Object value = map.get(field);
    if (value == null) {
      throw new IllegalArgumentException("missing field " + field);
    }
    return value;
  }

  private static String text(Map<String, Object> map, String field) {
    if (required(map, field) instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("field " + field + " must be a string");
  }

  private static boolean bool(Map<String, Object> map, String field) {
    if (required(map, field) instanceof Boolean b) {
      return b;
    }
    throw new IllegalArgumentException("field " + field + " must be a boolean");
  }

  private static BigDecimal decimal(Map<String, Object> map, String field) {
    return switch (required(map, field)) {
      case Long l -> BigDecimal.valueOf(l);
      case BigDecimal d -> d;
      default -> throw new IllegalArgumentException("field " + field + " must be a number");
    };
  }

  private static Instant instant(Map<String, Object> map, String field) {
    return switch (required(map, field)) {
      case Instant i -> i;
      case String s -> {
        try {
          yield Instant.parse(s);
        } catch (DateTimeParseException e) {
          throw new IllegalArgumentException("field " + field + " is not an instant: " + s, e);
        }
      }
      default -> throw new IllegalArgumentException("field " + field + " must be an instant");
    };
  }

  private static <E extends Enum<E>> E enumValue(
      Map<String, Object> map, String field, Class<E> type) {
    String name = text(map, field);
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "field " + field + " has unknown value " + name + " for " + type.getSimpleName(), e);
    }
  }
}
