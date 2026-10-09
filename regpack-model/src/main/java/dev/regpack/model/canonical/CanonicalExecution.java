package dev.regpack.model.canonical;

import dev.regpack.model.EventIdentity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A flattened execution event of the walking skeleton. */
public record CanonicalExecution(
    String firmLei,
    String venueMic,
    String venueExecutionId,
    LifecycleKind eventKind,
    Instant tradedAt,
    AssetClass assetClass,
    String isin,
    boolean tradedOnVenue,
    PartyKind clientKind,
    Optional<String> clientLei,
    BigDecimal quantity,
    BigDecimal price,
    String currency)
    implements CanonicalEvent {

  public CanonicalExecution {
    requireText(firmLei, "firmLei");
    requireText(venueMic, "venueMic");
    requireText(venueExecutionId, "venueExecutionId");
    Objects.requireNonNull(eventKind, "eventKind");
    Objects.requireNonNull(tradedAt, "tradedAt");
    Objects.requireNonNull(assetClass, "assetClass");
    requireText(isin, "isin");
    Objects.requireNonNull(clientKind, "clientKind");
    Objects.requireNonNull(clientLei, "clientLei");
    Objects.requireNonNull(quantity, "quantity");
    Objects.requireNonNull(price, "price");
    requireText(currency, "currency");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CanonicalExecution " + field + " must not be blank");
    }
  }

  @Override
  public EventIdentity eventIdentity() {
    return EventIdentity.of(firmLei, venueMic, venueExecutionId);
  }

  @Override
  public Instant occurredAt() {
    return tradedAt;
  }
}
