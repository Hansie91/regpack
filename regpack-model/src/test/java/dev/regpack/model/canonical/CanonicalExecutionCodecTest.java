package dev.regpack.model.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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

  @Test
  void fromMapOfToMapIsTheSameExecution() {
    for (Optional<String> lei : List.of(Optional.<String>empty(), Optional.of("X1"))) {
      CanonicalExecution e = execution(lei);
      assertThat(CanonicalExecutionCodec.fromMap(CanonicalExecutionCodec.toMap(e))).isEqualTo(e);
    }
  }

  @Test
  void fromMapRejectsBadInstants() {
    Map<String, Object> good = CanonicalExecutionCodec.toMap(execution(Optional.empty()));
    for (Object bad : new Object[] {"nope", 5L}) {
      Map<String, Object> map = new LinkedHashMap<>(good);
      map.put("tradedAt", bad);
      assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(map))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tradedAt");
    }
    Map<String, Object> asText = new LinkedHashMap<>(good);
    asText.put("tradedAt", "2026-10-09T09:30:00.123456Z");
    assertThat(CanonicalExecutionCodec.fromMap(asText).tradedAt())
        .isEqualTo(Instant.parse("2026-10-09T09:30:00.123456Z"));
  }

  @Test
  void fromMapRejectsAnExecutionThatCannotBeCanonicalised() {
    Map<String, Object> good = CanonicalExecutionCodec.toMap(execution(Optional.empty()));
    Object[][] cases = {
      {"tradedAt", "2026-10-09T09:30:00.123456789Z"},
      {"tradedAt", "+10000-01-01T00:00:00Z"},
      {"tradedAt", "-0001-12-31T23:59:59Z"},
      {"tradedAt", Instant.parse("2026-10-09T09:30:00.000000001Z")},
      {"venueExecutionId", "EX|3"},
      {"firmLei", "A|B"},
      {"venueMic", "XP|R"},
    };
    for (Object[] c : cases) {
      Map<String, Object> map = new LinkedHashMap<>(good);
      map.put((String) c[0], c[1]);
      assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(map))
          .as(c[0] + "=" + c[1])
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining((String) c[0])
          .hasMessageNotContaining("EX|3");
    }
  }

  @Test
  void theMessageForAnUnrepresentableIdentityPartIsBounded() {
    Map<String, Object> map = CanonicalExecutionCodec.toMap(execution(Optional.empty()));
    map = new LinkedHashMap<>(map);
    map.put("venueExecutionId", "x|".repeat(5000));
    Map<String, Object> bad = map;
    assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(200));
  }

  @Test
  void blankClientLeiIsRejected() {
    for (String blank : new String[] {"", "   "}) {
      assertThatThrownBy(() -> execution(Optional.of(blank)))
          .as("constructor")
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("clientLei");
      Map<String, Object> map =
          new LinkedHashMap<>(CanonicalExecutionCodec.toMap(execution(Optional.empty())));
      map.put("clientLei", blank);
      assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(map))
          .as("fromMap")
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("clientLei");
    }
  }
}
