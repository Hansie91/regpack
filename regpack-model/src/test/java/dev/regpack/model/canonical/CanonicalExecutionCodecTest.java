package dev.regpack.model.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CanonicalExecutionCodecTest {
  private static CanonicalExecution execution(Optional<String> clientLei) {
    return new CanonicalExecution(
        "529900T8BM49AURSDO55",
        "XPAR",
        "EX-1",
        LifecycleKind.NEW,
        Instant.parse("2026-10-09T09:30:00.123456Z"),
        AssetClass.EQUITY,
        "FR0000120271",
        true,
        PartyKind.LEGAL_ENTITY,
        clientLei,
        new BigDecimal("100.5"),
        new BigDecimal("12.25"),
        "EUR");
  }

  @Test
  @SuppressWarnings("unchecked")
  void roundTripsThroughCanonicalJson() {
    CanonicalExecution e = execution(Optional.of("5493001KJTIIGC8Y1R12"));
    String json = Json.canonical(CanonicalExecutionCodec.toMap(e));
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(json);
    CanonicalExecution back = CanonicalExecutionCodec.fromMap(parsed);
    assertThat(Json.canonical(CanonicalExecutionCodec.toMap(back))).isEqualTo(json);
    assertThat(back).isEqualTo(e);
  }

  @Test
  void eventIdentityIsFirmVenueExecution() {
    CanonicalExecution e = execution(Optional.empty());
    assertThat(e.eventIdentity())
        .isEqualTo(EventIdentity.of("529900T8BM49AURSDO55", "XPAR", "EX-1"));
    assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-10-09T09:30:00.123456Z"));
  }

  @Test
  void clientLeiIsOmittedWhenAbsent() {
    assertThat(CanonicalExecutionCodec.toMap(execution(Optional.empty())))
        .doesNotContainKey("clientLei");
    assertThat(CanonicalExecutionCodec.toMap(execution(Optional.of("X1"))))
        .containsKey("clientLei");
  }

  @Test
  void fromMapRejectsMissingFieldAndUnknownEnum() {
    Map<String, Object> good = CanonicalExecutionCodec.toMap(execution(Optional.empty()));
    Map<String, Object> missing = new LinkedHashMap<>(good);
    missing.remove("eventKind");
    assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventKind");
    Map<String, Object> bogus = new LinkedHashMap<>(good);
    bogus.put("eventKind", "BOGUS");
    assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(bogus))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventKind")
        .hasMessageContaining("BOGUS");
  }
}
