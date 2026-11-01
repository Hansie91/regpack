package dev.regpack.model.canonical;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.KeyText;
import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Converts a {@link CanonicalExecution} to and from the map form of canonical JSON. */
public final class CanonicalExecutionCodec {
  private CanonicalExecutionCodec() {}

  /**
   * Keys are the record component names; {@code clientLei}, {@code side}, {@code capacity}, {@code
   * counterparty} and {@code executionWithinFirm} are omitted when absent, so an execution without
   * them has the canonical JSON (and the input digest) it had before they existed.
   */
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
    execution.side().ifPresent(side -> map.put("side", side.name()));
    execution.capacity().ifPresent(v -> map.put("capacity", v));
    execution.counterparty().ifPresent(v -> map.put("counterparty", v));
    execution.executionWithinFirm().ifPresent(v -> map.put("executionWithinFirm", v));
    return map;
  }

  /** Reads the map form; an {@link IllegalArgumentException} names the offending field. */
  public static CanonicalExecution fromMap(Map<String, Object> map) {
    CanonicalExecution execution = decode(map);
    requireRepresentable(execution);
    return execution;
  }

  /**
   * An execution that decodes but could not be given an identity or canonical JSON (a '|' in an
   * identity part, a control character such as U+0000 or an unpaired surrogate in one, an identity
   * over {@value KeyText#MAX_EVENT_IDENTITY_CHARS} characters, a {@code tradedAt} finer than a
   * microsecond or outside the years 0000..9999) is refused here, early, with a bounded message
   * that names the field and never echoes it. The range of {@code asAt} that a ledger supports is
   * not checked here: it is the rule of the ledger ({@code AsAtRange}).
   */
  private static void requireRepresentable(CanonicalExecution e) {
    requireIdentityPart("firmLei", e.firmLei());
    requireIdentityPart("venueMic", e.venueMic());
    requireIdentityPart("venueExecutionId", e.venueExecutionId());
    try {
      e.eventIdentity();
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "the identity of firmLei, venueMic and venueExecutionId is longer than "
              + KeyText.MAX_EVENT_IDENTITY_CHARS
              + " characters",
          ex);
    }
    try {
      Json.canonical(Map.of("tradedAt", e.tradedAt()));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "field tradedAt must have at most microsecond precision and a year in 0000..9999", ex);
    }
    try {
      Json.canonical(toMap(e));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("the execution has no canonical JSON form", ex);
    }
  }

  private static void requireIdentityPart(String field, String value) {
    try {
      EventIdentity.of(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "field "
              + field
              + " must be non-blank, free of '|' and free of control characters and unpaired"
              + " surrogates, and at most "
              + KeyText.MAX_EVENT_IDENTITY_CHARS
              + " characters",
          ex);
    }
  }

  private static CanonicalExecution decode(Map<String, Object> map) {
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
        text(map, "currency"),
        map.containsKey("side")
            ? Optional.of(enumValue(map, "side", Side.class))
            : Optional.empty(),
        optionalText(map, "capacity"),
        optionalText(map, "counterparty"),
        optionalText(map, "executionWithinFirm"));
  }

  /**
   * The text of an optional field; the constructor of the record applies the {@link KeyText} rule.
   */
  private static Optional<String> optionalText(Map<String, Object> map, String field) {
    return map.containsKey(field) ? Optional.of(text(map, field)) : Optional.empty();
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
          // no value and no library exception: the text is the caller's data
          throw new IllegalArgumentException("field " + field + " is not an instant");
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
      // no value and no library exception: the text is the caller's data
      throw new IllegalArgumentException("field " + field + " has an unknown value");
    }
  }
}
