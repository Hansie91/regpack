package dev.regpack.model.canonical;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.KeyText;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A flattened execution event of the walking skeleton.
 *
 * <p>The last four components are optional: {@code side}, {@code capacity}, {@code counterparty}
 * and {@code executionWithinFirm}. Each text is a {@link KeyText} (at most {@value
 * KeyText#MAX_CHARS} characters, no control character, no unpaired surrogate); a refusal names the
 * field and never echoes the value, and a present text is not blank. The 13-argument constructor
 * leaves the four empty.
 */
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
    String currency,
    Optional<Side> side,
    Optional<String> capacity,
    Optional<String> counterparty,
    Optional<String> executionWithinFirm)
    implements CanonicalEvent {

  /** An execution with none of the four optional components. */
  public CanonicalExecution(
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
      String currency) {
    this(
        firmLei,
        venueMic,
        venueExecutionId,
        eventKind,
        tradedAt,
        assetClass,
        isin,
        tradedOnVenue,
        clientKind,
        clientLei,
        quantity,
        price,
        currency,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

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
    clientLei.ifPresent(lei -> requireText(lei, "clientLei"));
    Objects.requireNonNull(quantity, "quantity");
    Objects.requireNonNull(price, "price");
    requireText(currency, "currency");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(capacity, "capacity");
    Objects.requireNonNull(counterparty, "counterparty");
    Objects.requireNonNull(executionWithinFirm, "executionWithinFirm");
    capacity.ifPresent(v -> optionalText("capacity", v));
    counterparty.ifPresent(v -> optionalText("counterparty", v));
    executionWithinFirm.ifPresent(v -> optionalText("executionWithinFirm", v));
  }

  /** A present optional text is a key text and not blank: a blank is not the same as absent. */
  private static void optionalText(String field, String value) {
    requireText(value, field);
    KeyText.require(field, value);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CanonicalExecution " + field + " must not be blank");
    }
  }

  /**
   * Only the event kind: the identifiers, the price and the quantity of an execution are never
   * printed (a message, a log line or an assertion failure may show this text).
   */
  @Override
  public String toString() {
    return "CanonicalExecution[eventKind=" + eventKind + "]";
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
