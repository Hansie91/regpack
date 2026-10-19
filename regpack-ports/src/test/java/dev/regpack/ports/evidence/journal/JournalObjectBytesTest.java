package dev.regpack.ports.evidence.journal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JournalObjectBytesTest {
  private static final JournalScope SCOPE =
      JournalScope.of(new EndpointKey("PRODUCTION", "ARM-ONE", "5493001KJTIIGC8Y1R12", "FCA"));
  private static final String SCOPE_TEXT = "PRODUCTION,ARM-ONE,5493001KJTIIGC8Y1R12,FCA";
  private static final Instant AT = Instant.parse("2026-10-19T12:00:00.123456Z");
  private static final String MARKER = "SECRET MARKER 7";

  private static JournalObject intent() {
    return new JournalObject(
        new JournalKey.ClassA(SCOPE, 3, JournalType.DELIVERY_INTENT, "42", 1),
        JournalType.DELIVERY_INTENT,
        1,
        AT,
        Map.of("attempt_id", 42L, "note", "x"));
  }

  private static JournalObject transportResult() {
    return new JournalObject(
        new JournalKey.ClassB(SCOPE, 3, 10_001),
        JournalType.TRANSPORT_RESULT,
        1,
        AT,
        Map.of("attempt_id", 42L, "observed_at", "x"));
  }

  private static String text(JournalObject object) {
    return new String(object.toBytes(), UTF_8);
  }

  @Test
  void classAEnvelopeHasTheDocumentedFields() {
    // the payload hash is the sha256 of {"attempt_id":42,"note":"x"}, computed outside the code
    assertThat(text(intent()))
        .isEqualTo(
            "{\"envelope\":{\"clock_instant\":\"2026-10-19T12:00:00.123456Z\","
                + "\"endpoint\":\""
                + SCOPE_TEXT
                + "\",\"ordinal\":1,"
                + "\"payload_sha256\":\"80441ad8cd848f135a3fe248a4beb1d42f9609f652b0f0961bb17cbe459d4bb5\","
                + "\"restore_epoch\":3,\"schema_version\":1,\"subject_id\":\"42\","
                + "\"type\":\"DeliveryIntent\"},\"payload\":{\"attempt_id\":42,\"note\":\"x\"}}");
  }

  @Test
  void classBEnvelopeHasSeqAndNoSubject() {
    String text = text(transportResult());
    assertThat(text)
        .isEqualTo(
            "{\"envelope\":{\"clock_instant\":\"2026-10-19T12:00:00.123456Z\","
                + "\"endpoint\":\""
                + SCOPE_TEXT
                + "\","
                + "\"payload_sha256\":\"40dfa139f28a4f047e3326e996c96844b1abeb6e5cefe23ba498c83c0821b363\","
                + "\"restore_epoch\":3,\"schema_version\":1,\"seq\":10001,"
                + "\"type\":\"TransportResult\"},"
                + "\"payload\":{\"attempt_id\":42,\"observed_at\":\"x\"}}");
    assertThat(text).doesNotContain("subject_id").doesNotContain("ordinal");
  }

  @Test
  void bytesRoundTrip() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("list", List.of("a", 1L, true, Map.of("k", "v")));
    payload.put("nested", Map.of("deep", Map.of("deeper", List.of())));
    payload.put("text", "café 😀 \"quoted\" \\ \n tab\t");
    payload.put("flag", false);
    payload.put("n", 9_007_199_254_740_993L);
    for (JournalObject object :
        List.of(
            intent(),
            transportResult(),
            new JournalObject(
                new JournalKey.ClassA(
                    JournalScope.control(), 0, JournalType.BATCH_ADMITTED, "b:1", 2),
                JournalType.BATCH_ADMITTED,
                1,
                AT,
                payload))) {
      JournalObject back = JournalObject.fromBytes(object.toBytes());
      assertThat(back).isEqualTo(object);
      assertThat(back.toBytes()).isEqualTo(object.toBytes());
    }
  }

  @Test
  void payloadSha256IsTheHashOfTheCanonicalPayload() {
    assertThat(intent().payloadSha256())
        .isEqualTo(Sha256.ofUtf8("{\"attempt_id\":42,\"note\":\"x\"}"));
    // key order does not matter: the canonical form sorts
    Map<String, Object> reordered = new LinkedHashMap<>();
    reordered.put("note", "x");
    reordered.put("attempt_id", 42L);
    JournalObject other =
        new JournalObject(intent().key(), JournalType.DELIVERY_INTENT, 1, AT, reordered);
    assertThat(other.payloadSha256()).isEqualTo(intent().payloadSha256());
    assertThat(other.toBytes()).isEqualTo(intent().toBytes());
    Map<String, Object> changed = new LinkedHashMap<>(reordered);
    changed.put("note", "y");
    assertThat(
            new JournalObject(intent().key(), JournalType.DELIVERY_INTENT, 1, AT, changed)
                .payloadSha256())
        .isNotEqualTo(intent().payloadSha256());
  }

  @Test
  void fromBytesRefusesNonCanonicalTextUnknownKeysAWrongPayloadHashAndTrailingBytes() {
    String good = text(intent());
    String hash = intent().payloadSha256().hex();
    String otherHash = "0".repeat(64);
    List<byte[]> bad = new ArrayList<>();
    bad.add(good.replace("\"payload\":", "\"payload\": ").getBytes(UTF_8)); // whitespace
    bad.add(good.replace(",\"payload\"", ",\n\"payload\"").getBytes(UTF_8));
    int payloadAt = good.indexOf(",\"payload\":");
    String envelope = good.substring("{\"envelope\":".length(), payloadAt);
    String payload = good.substring(payloadAt + ",\"payload\":".length(), good.length() - 1);
    bad.add(
        ("{\"payload\":" + payload + ",\"envelope\":" + envelope + "}")
            .getBytes(UTF_8)); // keys in the wrong order
    bad.add(
        good.replace("\"endpoint\"", "\"extra\":1,\"endpoint\"").getBytes(UTF_8)); // unknown key
    bad.add(good.replace("\"ordinal\":1,", "").getBytes(UTF_8)); // missing key
    bad.add(good.replace("\"schema_version\":1,", "").getBytes(UTF_8));
    bad.add(good.replace("\"subject_id\":\"42\",", "").getBytes(UTF_8));
    bad.add(
        good.replace("\"subject_id\":\"42\",", "\"seq\":1,\"subject_id\":\"42\",").getBytes(UTF_8));
    bad.add(good.replace(hash, otherHash).getBytes(UTF_8)); // wrong payload hash
    bad.add(good.replace("\"payload\":{", "\"extra\":{},\"payload\":{").getBytes(UTF_8));
    bad.add(good.replace("\"subject_id\":\"42\"", "\"subject_id\":\"042\"").getBytes(UTF_8));
    bad.add(good.replace("\"schema_version\":1", "\"schema_version\":0").getBytes(UTF_8));
    bad.add(good.replace("\"restore_epoch\":3", "\"restore_epoch\":-3").getBytes(UTF_8));
    bad.add(good.replace("\"restore_epoch\":3", "\"restore_epoch\":\"3\"").getBytes(UTF_8));
    bad.add(good.replace("\"DeliveryIntent\"", "\"Nope\"").getBytes(UTF_8));
    bad.add(good.replace(".123456Z", ".123Z").getBytes(UTF_8)); // not the canonical rendering
    bad.add(good.replace("T12:00:00.123456Z", "T12:00:00.123456+00:00").getBytes(UTF_8));
    bad.add((good + "\n").getBytes(UTF_8)); // trailing bytes
    bad.add((good + " ").getBytes(UTF_8));
    bad.add((good + "x").getBytes(UTF_8));
    bad.add((good + good).getBytes(UTF_8));
    byte[] withNul = Arrays.copyOf(good.getBytes(UTF_8), good.length() + 1);
    bad.add(withNul);
    bad.add((String.valueOf((char) 0xFEFF) + good).getBytes(UTF_8)); // a byte order mark
    bad.add(new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'}); // not UTF-8
    bad.add(new byte[0]);
    bad.add("[]".getBytes(UTF_8));
    bad.add("{}".getBytes(UTF_8));
    bad.add("null".getBytes(UTF_8));
    bad.add(good.replace("\"envelope\":{", "\"envelope\":[").getBytes(UTF_8));
    for (byte[] bytes : bad) {
      assertThatThrownBy(() -> JournalObject.fromBytes(bytes))
          .as(new String(bytes, UTF_8))
          .isInstanceOf(IllegalArgumentException.class);
    }
    // the control: the untouched text is accepted
    assertThat(JournalObject.fromBytes(good.getBytes(UTF_8))).isEqualTo(intent());
  }

  @Test
  void aReShipIsByteIdentical() {
    byte[] first = intent().toBytes();
    JournalObject read = JournalObject.fromBytes(first);
    assertThat(read.toBytes()).isEqualTo(first);
    // the same ledger event rebuilt later gives the same bytes: the clock instant is the event time
    JournalObject rebuilt =
        new JournalObject(
            new JournalKey.ClassA(SCOPE, 3, JournalType.DELIVERY_INTENT, "42", 1),
            JournalType.DELIVERY_INTENT,
            1,
            Instant.parse("2026-10-19T12:00:00.123456Z"),
            Map.of("note", "x", "attempt_id", 42L));
    assertThat(rebuilt.toBytes()).isEqualTo(first);
  }

  @Test
  void parseErrorsNeverEchoPayloadText() {
    String good = text(intent());
    List<String> bad =
        List.of(
            // truncated
            "{\"payload\":{\"note\":\"" + MARKER,
            // duplicate key
            "{\"payload\":{\"" + MARKER + "\":1,\"" + MARKER + "\":2},\"envelope\":{}}",
            // wrong payload hash, marker in the payload
            good.replace("\"note\":\"x\"", "\"note\":\"" + MARKER + "\""),
            // marker as an unknown envelope key
            good.replace("\"endpoint\"", "\"" + MARKER + "\":1,\"endpoint\""),
            // marker in the type, the subject, the scope, the instant and the hash
            good.replace("\"DeliveryIntent\"", "\"" + MARKER + "\""),
            good.replace("\"subject_id\":\"42\"", "\"subject_id\":\"" + MARKER + "\""),
            good.replace(SCOPE_TEXT, MARKER),
            good.replace("2026-10-19T12:00:00.123456Z", MARKER),
            good.replace(intent().payloadSha256().hex(), MARKER),
            // marker where a number is expected
            good.replace("\"ordinal\":1", "\"ordinal\":\"" + MARKER + "\""),
            "{\"payload\":{\"a\":" + MARKER + "}}",
            good + MARKER);
    for (String text : bad) {
      assertThatThrownBy(() -> JournalObject.fromBytes(text.getBytes(UTF_8)))
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e -> {
                assertThat(e.getMessage()).doesNotContain(MARKER).doesNotContain("SECRET");
                assertThat(e.getCause()).isNull();
              });
    }
  }

  @Test
  void aKeyAndATypeMustAgree() {
    assertThatThrownBy(
            () ->
                new JournalObject(
                    new JournalKey.ClassA(SCOPE, 3, JournalType.DELIVERY_INTENT, "42", 1),
                    JournalType.RECOVERY_DECISION,
                    1,
                    AT,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    new JournalKey.ClassB(SCOPE, 3, 1),
                    JournalType.DELIVERY_INTENT,
                    1,
                    AT,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    // a type that both classes allow is accepted in a class b key
    assertThat(
            new JournalObject(
                    new JournalKey.ClassB(SCOPE, 3, 1),
                    JournalType.RECOVERY_DECISION,
                    1,
                    AT,
                    Map.of())
                .key()
                .journalClass())
        .isEqualTo(JournalClass.B);
  }

  @Test
  void theSchemaVersionAndTheClockInstantAreChecked() {
    JournalKey key = intent().key();
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 0, AT, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, -1, AT, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    key,
                    JournalType.DELIVERY_INTENT,
                    1,
                    Instant.parse("2026-10-19T12:00:00.123456789Z"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    key,
                    JournalType.DELIVERY_INTENT,
                    1,
                    Instant.parse("+10000-01-01T00:00:00Z"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thePayloadIsCopiedDeeplyAndImmutable() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("k", "v");
    List<Object> list = new ArrayList<>(List.of("a", inner));
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("list", list);
    source.put("small", 5);
    JournalObject object =
        new JournalObject(intent().key(), JournalType.DELIVERY_INTENT, 1, AT, source);
    byte[] before = object.toBytes();
    inner.put("k", "changed");
    list.add("more");
    source.put("late", 1L);
    assertThat(object.toBytes()).isEqualTo(before);
    assertThat(object.payload()).containsEntry("small", 5L).doesNotContainKey("late");
    assertThatThrownBy(() -> object.payload().put("x", 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<?>) object.payload().get("list")).clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aPayloadValueThatIsNotCanonicalJsonIsRefused() {
    JournalKey key = intent().key();
    Map<String, Object> withNull = new LinkedHashMap<>();
    withNull.put("a", null);
    Map<Object, Object> numericKey = new LinkedHashMap<>();
    numericKey.put(1, "x");
    List<Object> withNullElement = new ArrayList<>();
    withNullElement.add(null);
    for (Map<String, Object> payload :
        List.<Map<String, Object>>of(
            withNull,
            Map.of("a", new Object()),
            Map.of("a", 1.5d),
            Map.of("a", List.of(new Object())),
            Map.of("a", withNullElement),
            Map.of("a", numericKey))) {
      assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, payload))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
