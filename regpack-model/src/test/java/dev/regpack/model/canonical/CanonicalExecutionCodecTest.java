package dev.regpack.model.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.decision.InputDigest;
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

  private static Map<String, Object> mapWithExecutionId(String venueExecutionId) {
    Map<String, Object> map =
        new LinkedHashMap<>(CanonicalExecutionCodec.toMap(execution(Optional.empty())));
    map.put("venueExecutionId", venueExecutionId);
    return map;
  }

  @Test
  void anIdentityPartWithAControlCharacterOrALoneSurrogateIsRefusedWithoutEchoingIt() {
    String nul = String.valueOf((char) 0);
    String tab = String.valueOf((char) 9);
    String high = String.valueOf((char) 0xD800);
    String low = String.valueOf((char) 0xDC00);
    for (String bad :
        new String[] {"EX" + nul + "1", "EX" + tab + "1", "EX" + high + "1", "EX" + low}) {
      Map<String, Object> map = mapWithExecutionId(bad);
      assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(map))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("field venueExecutionId must be")
          .message()
          .doesNotContain(bad);
    }
  }

  @Test
  void anIdentityLongerThanTheBoundIsRefusedAsAWhole() {
    // firmLei (20), "|", venueMic (4) and "|" take 26 chars: 486 more is the longest identity
    Map<String, Object> longest = mapWithExecutionId("x".repeat(486));
    assertThat(CanonicalExecutionCodec.fromMap(longest).eventIdentity().value()).hasSize(512);
    Map<String, Object> tooLong = mapWithExecutionId("x".repeat(487));
    assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("512");
  }

  @Test
  void aTradeTimeOutsideTheLedgerRangeIsStillAValidExecution() {
    // the range of the asAt is the rule of the ledger, not of the codec (a 1970 default is valid)
    Map<String, Object> map = mapWithExecutionId("EX-1");
    map.put("tradedAt", Instant.parse("1970-01-01T00:00:00Z"));
    assertThat(CanonicalExecutionCodec.fromMap(map).tradedAt())
        .isEqualTo(Instant.parse("1970-01-01T00:00:00Z"));
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

  private static CanonicalExecution full() {
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
        Optional.of("5493001KJTIIGC8Y1R12"),
        new BigDecimal("100.5"),
        new BigDecimal("12.25"),
        "EUR",
        Optional.of(Side.SELL),
        Optional.of("MTCH"),
        Optional.of("CTP-1"),
        Optional.of("ALGO-1"));
  }

  @Test
  void absentOptionalFieldsAreOmittedSoOldDigestsHold() {
    String head = "{\"assetClass\":\"EQUITY\",\"clientKind\":\"LEGAL_ENTITY\",";
    String tail =
        "\"currency\":\"EUR\",\"eventKind\":\"NEW\",\"firmLei\":\"529900T8BM49AURSDO55\","
            + "\"isin\":\"FR0000120271\",\"price\":12.25,\"quantity\":100.5,"
            + "\"tradedAt\":\"2026-10-09T09:30:00.123456Z\",\"tradedOnVenue\":true,"
            + "\"venueExecutionId\":\"EX-1\",\"venueMic\":\"XPAR\"}";
    assertThat(Json.canonical(CanonicalExecutionCodec.toMap(execution(Optional.empty()))))
        .isEqualTo(head + tail);
    assertThat(
            Json.canonical(
                CanonicalExecutionCodec.toMap(execution(Optional.of("5493001KJTIIGC8Y1R12")))))
        .isEqualTo(head + "\"clientLei\":\"5493001KJTIIGC8Y1R12\"," + tail);
    for (String key : new String[] {"side", "capacity", "counterparty", "executionWithinFirm"}) {
      assertThat(CanonicalExecutionCodec.toMap(execution(Optional.empty()))).doesNotContainKey(key);
    }
    // the fixture of DecisionTypesTest#inputDigestMatchesTheGoldenVector: the pinned digest holds
    CanonicalExecution pinned =
        new CanonicalExecution(
            "L",
            "XPAR",
            "EX-1",
            LifecycleKind.NEW,
            Instant.parse("2026-10-09T09:30:00.123456Z"),
            AssetClass.EQUITY,
            "FR0000120271",
            true,
            PartyKind.LEGAL_ENTITY,
            Optional.empty(),
            new BigDecimal("100.5"),
            new BigDecimal("12.25"),
            "EUR");
    assertThat(InputDigest.of(pinned, new IngestKey("src", "rec-1", 1)).hex())
        .isEqualTo("30ddc5cf01393b67d6c92facf300b21058c9c95392f5d5882b147582c5fe1e7d");
  }

  @Test
  void thirteenArgumentConstructorLeavesTheFourEmpty() {
    CanonicalExecution e = execution(Optional.empty());
    assertThat(e.side()).isEmpty();
    assertThat(e.capacity()).isEmpty();
    assertThat(e.counterparty()).isEmpty();
    assertThat(e.executionWithinFirm()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void roundTripsWithAllFourOptionalFields() {
    CanonicalExecution e = full();
    Map<String, Object> map = CanonicalExecutionCodec.toMap(e);
    assertThat(map)
        .containsEntry("side", "SELL")
        .containsEntry("capacity", "MTCH")
        .containsEntry("counterparty", "CTP-1")
        .containsEntry("executionWithinFirm", "ALGO-1");
    String json = Json.canonical(map);
    CanonicalExecution back =
        CanonicalExecutionCodec.fromMap((Map<String, Object>) Json.parse(json));
    assertThat(back).isEqualTo(e);
    assertThat(Json.canonical(CanonicalExecutionCodec.toMap(back))).isEqualTo(json);
  }

  @Test
  void aSideOutsideTheEnumerationIsRefused() {
    Map<String, Object> map = new LinkedHashMap<>(CanonicalExecutionCodec.toMap(full()));
    map.put("side", "HOLD");
    assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(map))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("side");
  }

  @Test
  void aBadOptionalTextIsRefusedWithoutEchoingIt() {
    String nul = String.valueOf((char) 0);
    String high = String.valueOf((char) 0xD800);
    String[] bad = {"A" + nul + "B", "A" + high + "B", "x".repeat(257)};
    for (String field : new String[] {"capacity", "counterparty", "executionWithinFirm"}) {
      for (String value : bad) {
        Map<String, Object> map = new LinkedHashMap<>(CanonicalExecutionCodec.toMap(full()));
        map.put(field, value);
        assertThatThrownBy(() -> CanonicalExecutionCodec.fromMap(map))
            .as(field)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(field)
            .message()
            .doesNotContain(value);
      }
    }
    // the constructor refuses the same texts
    assertThatThrownBy(
            () ->
                new CanonicalExecution(
                    "F",
                    "XPAR",
                    "EX-1",
                    LifecycleKind.NEW,
                    Instant.parse("2026-10-09T09:30:00Z"),
                    AssetClass.EQUITY,
                    "FR0000120271",
                    true,
                    PartyKind.LEGAL_ENTITY,
                    Optional.empty(),
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    "EUR",
                    Optional.empty(),
                    Optional.of("x".repeat(257)),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacity");
    // 256 characters are allowed
    Map<String, Object> longest = new LinkedHashMap<>(CanonicalExecutionCodec.toMap(full()));
    longest.put("capacity", "x".repeat(256));
    assertThat(CanonicalExecutionCodec.fromMap(longest).capacity()).contains("x".repeat(256));
  }
}
