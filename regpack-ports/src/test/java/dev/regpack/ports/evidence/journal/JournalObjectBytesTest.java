package dev.regpack.ports.evidence.journal;

import static dev.regpack.ports.evidence.journal.JournalFixtures.AT;
import static dev.regpack.ports.evidence.journal.JournalFixtures.SCOPE;
import static dev.regpack.ports.evidence.journal.JournalFixtures.SCOPE_TEXT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JournalObjectBytesTest {
  private static final String MARKER = "SECRET MARKER 7";

  // the canonical payload text of the golden objects, written by hand: sorted keys, no whitespace;
  // the hashes are those of the seeds "logical", "artifact", "context-1" and "context-2"
  private static final String INTENT_PAYLOAD =
      "{\"action\":\"NEWT\","
          + "\"artifact_sha256\":\"c7c5c1d70c5dec4416ab6158afd0b223ef40c29b1dc1f97ed9428b94d4cadb1c\","
          + "\"attempt_id\":42,\"business_key\":\"TRADE-1\",\"context_ids\":["
          + "\"9bf558cf47fd57b0ad02a89e8b135c1ef619ddf5b842ac1746bc8a8a271cd460\","
          + "\"e1b5609251224d45c0b3a0d5b5cb2b02506881bfebb37aaf40101767a63652ca\"],"
          + "\"delivery_id\":17,\"file_name\":\"report-1.xml\","
          + "\"logical_hash\":\"ac7f39a1e0b7bcd03a4d2c83b773ae28ba3befa34bbfd0341e3fef15dfdfad84\","
          + "\"trn\":\"TRN0000000000000000001\",\"version_seq\":2}";
  private static final String RESULT_PAYLOAD =
      "{\"attempt_id\":42,\"detail\":\"http 202\","
          + "\"observed_at\":\"2026-10-19T12:00:01.500000Z\",\"status\":\"ACCEPTED\"}";

  private static JournalObject intent() {
    return JournalFixtures.object(JournalType.DELIVERY_INTENT);
  }

  private static JournalObject transportResult() {
    return JournalFixtures.object(JournalType.TRANSPORT_RESULT);
  }

  private static String text(JournalObject object) {
    return new String(object.toBytes(), UTF_8);
  }

  @Test
  void classAEnvelopeHasTheDocumentedFields() {
    // the payload hash is the sha256 of INTENT_PAYLOAD, computed outside the code (sha256sum)
    assertThat(text(intent()))
        .isEqualTo(
            "{\"envelope\":{\"clock_instant\":\"2026-10-19T12:00:00.123456Z\","
                + "\"endpoint\":\""
                + SCOPE_TEXT
                + "\",\"ordinal\":1,"
                + "\"payload_sha256\":\"78facdebbceaf2a512fa1c77235dbbc6608bc4054383f3e3a305612d357b6bb9\","
                + "\"restore_epoch\":3,\"schema_version\":1,\"subject_id\":\"42\","
                + "\"type\":\"DeliveryIntent\"},\"payload\":"
                + INTENT_PAYLOAD
                + "}");
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
                + "\"payload_sha256\":\"c16a69090065d1af87884de1e62d723f12b73549ae8396b535457f4e8af534bb\","
                + "\"restore_epoch\":3,\"schema_version\":1,\"seq\":10001,"
                + "\"type\":\"TransportResult\"},"
                + "\"payload\":"
                + RESULT_PAYLOAD
                + "}");
    assertThat(text).doesNotContain("subject_id").doesNotContain("ordinal");
  }

  @Test
  void bytesRoundTrip() {
    for (JournalType type : JournalType.values()) {
      JournalObject object = JournalFixtures.object(type);
      JournalObject back = JournalObject.fromBytes(object.key(), object.toBytes());
      assertThat(back).as(type.wireName()).isEqualTo(object);
      assertThat(back.toBytes()).as(type.wireName()).isEqualTo(object.toBytes());
    }
    // both instant-typed fields come back as Instant, not as text
    assertThat(
            JournalObject.fromBytes(transportResult().key(), transportResult().toBytes())
                .payload()
                .get("observed_at"))
        .isEqualTo(JournalFixtures.OBSERVED);
    assertThat(
            JournalObject.fromBytes(
                    JournalFixtures.object(JournalType.FEEDBACK_RECEIVED).key(),
                    JournalFixtures.object(JournalType.FEEDBACK_RECEIVED).toBytes())
                .payload()
                .get("received_at"))
        .isEqualTo(JournalFixtures.OBSERVED);
  }

  @Test
  void textWithSpecialCharactersRoundTrips() {
    Map<String, Object> payload =
        new LinkedHashMap<>(JournalFixtures.payload(JournalType.OPERATOR_ACTION, JournalClass.A));
    payload.put("detail", "café 😀 \"quoted\" \\ \n tab\t");
    JournalObject object =
        new JournalObject(
            new JournalKey.ClassA(SCOPE, 3, JournalType.OPERATOR_ACTION, "op-1", 2),
            JournalType.OPERATOR_ACTION,
            1,
            AT,
            payload);
    JournalObject back = JournalObject.fromBytes(object.key(), object.toBytes());
    assertThat(back).isEqualTo(object);
    assertThat(back.payload().get("detail")).isEqualTo("café 😀 \"quoted\" \\ \n tab\t");
  }

  @Test
  void payloadSha256IsTheHashOfTheCanonicalPayload() {
    assertThat(intent().payloadSha256()).isEqualTo(Sha256.ofUtf8(INTENT_PAYLOAD));
    // key order does not matter: the canonical form sorts
    List<Map.Entry<String, Object>> entries = new ArrayList<>(intent().payload().entrySet());
    java.util.Collections.reverse(entries);
    Map<String, Object> reordered = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : entries) {
      reordered.put(entry.getKey(), entry.getValue());
    }
    JournalObject other =
        new JournalObject(intent().key(), JournalType.DELIVERY_INTENT, 1, AT, reordered);
    assertThat(other.payloadSha256()).isEqualTo(intent().payloadSha256());
    assertThat(other.toBytes()).isEqualTo(intent().toBytes());
    Map<String, Object> changed = new LinkedHashMap<>(reordered);
    changed.put("action", "CANC");
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
    // the dedicated check names the field (a text that is only not canonical does not)
    assertThatThrownBy(
            () ->
                JournalObject.fromBytes(
                    intent().key(), good.replace(hash, otherHash).getBytes(UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload_sha256");
    bad.add(good.replace("\"payload\":{", "\"extra\":{},\"payload\":{").getBytes(UTF_8));
    bad.add(good.replace("\"subject_id\":\"42\"", "\"subject_id\":\"042\"").getBytes(UTF_8));
    bad.add(good.replace("\"schema_version\":1", "\"schema_version\":0").getBytes(UTF_8));
    bad.add(good.replace("\"schema_version\":1", "\"schema_version\":2").getBytes(UTF_8));
    bad.add(good.replace("\"restore_epoch\":3", "\"restore_epoch\":-3").getBytes(UTF_8));
    bad.add(good.replace("\"restore_epoch\":3", "\"restore_epoch\":\"3\"").getBytes(UTF_8));
    bad.add(good.replace("\"DeliveryIntent\"", "\"Nope\"").getBytes(UTF_8));
    bad.add(good.replace(".123456Z", ".123Z").getBytes(UTF_8)); // not the canonical rendering
    bad.add(good.replace("T12:00:00.123456Z", "T12:00:00.123456+00:00").getBytes(UTF_8));
    bad.add(good.replace("\"action\":\"NEWT\",", "").getBytes(UTF_8)); // missing payload key
    bad.add(good.replace("\"attempt_id\":42", "\"attempt_id\":\"42\"").getBytes(UTF_8));
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
      assertThatThrownBy(() -> JournalObject.fromBytes(intent().key(), bytes))
          .as(new String(bytes, UTF_8))
          .isInstanceOf(IllegalArgumentException.class);
    }
    // an instant that is not microsecond text, a number, or sub-microsecond is refused too
    String result = text(transportResult());
    for (String instant :
        List.of(
            "\"2026-10-19T12:00:01.5Z\"",
            "\"2026-10-19T12:00:01.500000+00:00\"",
            "\"2026-10-19T12:00:01.5000001Z\"",
            "\"nonsense\"",
            "5")) {
      byte[] bytes = result.replace("\"2026-10-19T12:00:01.500000Z\"", instant).getBytes(UTF_8);
      assertThatThrownBy(() -> JournalObject.fromBytes(transportResult().key(), bytes))
          .as(instant)
          .isInstanceOf(IllegalArgumentException.class);
    }
    // the control: the untouched text is accepted
    assertThat(JournalObject.fromBytes(intent().key(), good.getBytes(UTF_8))).isEqualTo(intent());
  }

  @Test
  void theClockInstantIsRenderedWithSixDigitsOfMicroseconds() {
    JournalKey key = intent().key();
    Map<String, Object> payload = intent().payload();
    String[][] cases = {
      {"2026-10-19T12:00:00Z", "2026-10-19T12:00:00.000000Z"},
      {"2026-10-19T12:00:00.120Z", "2026-10-19T12:00:00.120000Z"},
      {"2026-10-19T12:00:00.100001Z", "2026-10-19T12:00:00.100001Z"},
      {"2026-10-19T12:00:00.123456Z", "2026-10-19T12:00:00.123456Z"},
      {"0000-01-01T00:00:00Z", "0000-01-01T00:00:00.000000Z"},
      {"9999-12-31T23:59:59.999999Z", "9999-12-31T23:59:59.999999Z"},
    };
    for (String[] c : cases) {
      JournalObject object =
          new JournalObject(key, JournalType.DELIVERY_INTENT, 1, Instant.parse(c[0]), payload);
      String text = text(object);
      assertThat(text).contains("\"clock_instant\":\"" + c[1] + "\"");
      assertThat(JournalObject.fromBytes(object.key(), object.toBytes())).isEqualTo(object);
      // the shortened form (what Instant.toString gives) is not the canonical text
      String shortened = text.replace(c[1], c[0]);
      if (!shortened.equals(text)) {
        assertThatThrownBy(() -> JournalObject.fromBytes(object.key(), shortened.getBytes(UTF_8)))
            .as(c[0])
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void aPayloadThatToBytesCouldNeverWriteCannotBeConstructed() {
    JournalKey key = intent().key();
    Map<String, Object> good = intent().payload();
    String lone = "a" + (char) 0xD800 + "b";
    String loneLow = "a" + (char) 0xDC00;
    String reversed = "" + (char) 0xDC00 + (char) 0xD800;
    for (String text : List.of(lone, loneLow, reversed, "tail" + (char) 0xD83D)) {
      Map<String, Object> asValue = new LinkedHashMap<>(good);
      asValue.put("file_name", text);
      assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, asValue))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("surrogate");
      Map<String, Object> asKey = new LinkedHashMap<>(good);
      asKey.put(text, 1L);
      assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, asKey))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("surrogate");
      Map<String, Object> inList = new LinkedHashMap<>(good);
      inList.put("context_ids", List.of(text));
      assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, inList))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("surrogate");
    }
    // a pair is fine
    Map<String, Object> pair = new LinkedHashMap<>(good);
    pair.put("file_name", "ok " + new String(Character.toChars(0x1F600)));
    assertThat(new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, pair).toBytes())
        .isNotEmpty();
    // an instant the canonical writer cannot render: nanoseconds, or a year outside 0000 to 9999
    for (String instant :
        List.of(
            "2026-10-19T12:00:00.123456789Z",
            "2026-10-19T12:00:00.000000001Z",
            "+10000-01-01T00:00:00Z",
            "-0001-12-31T23:59:59Z",
            "MIN",
            "MAX")) {
      Map<String, Object> payload = new LinkedHashMap<>(good);
      payload.put(
          "file_name",
          instant.equals("MIN")
              ? Instant.MIN
              : instant.equals("MAX") ? Instant.MAX : Instant.parse(instant));
      assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, payload))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("instant")
          .satisfies(
              e -> assertThat(e.getMessage()).doesNotContain("2026").doesNotContain("10000"));
    }
    // and an instant it can render is not refused by the shape check (the schema refuses the
    // field's type here, not the instant)
    Map<String, Object> fine = new LinkedHashMap<>(good);
    fine.put("file_name", Instant.parse("2026-10-19T12:00:00.123456Z"));
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, fine))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("instant has");
  }

  @Test
  void aReShipIsByteIdentical() {
    byte[] first = intent().toBytes();
    JournalObject read = JournalObject.fromBytes(intent().key(), first);
    assertThat(read.toBytes()).isEqualTo(first);
    // the same ledger event rebuilt later gives the same bytes: the clock instant is the event time
    List<Map.Entry<String, Object>> entries = new ArrayList<>(intent().payload().entrySet());
    java.util.Collections.reverse(entries);
    Map<String, Object> reordered = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : entries) {
      reordered.put(entry.getKey(), entry.getValue());
    }
    JournalObject rebuilt =
        new JournalObject(
            new JournalKey.ClassA(SCOPE, 3, JournalType.DELIVERY_INTENT, "42", 1),
            JournalType.DELIVERY_INTENT,
            1,
            Instant.parse("2026-10-19T12:00:00.123456Z"),
            reordered);
    assertThat(rebuilt.toBytes()).isEqualTo(first);
  }

  @Test
  void parseErrorsNeverEchoPayloadText() {
    String good = text(intent());
    String result = text(transportResult());
    List<String> bad =
        List.of(
            // truncated
            "{\"payload\":{\"note\":\"" + MARKER,
            // duplicate key
            "{\"payload\":{\"" + MARKER + "\":1,\"" + MARKER + "\":2},\"envelope\":{}}",
            // marker as a payload value, wrong payload hash
            good.replace("\"business_key\":\"TRADE-1\"", "\"business_key\":\"" + MARKER + "\""),
            // marker as a mistyped payload value and as an unknown payload key
            good.replace("\"attempt_id\":42", "\"attempt_id\":\"" + MARKER + "\""),
            good.replace("\"action\"", "\"" + MARKER + "\":1,\"action\""),
            // marker as a hash
            good.replace(
                "\"logical_hash\":\"ac7f39a1e0b7bcd03a4d2c83b773ae28ba3befa34bbfd0341e3fef15dfdfad84\"",
                "\"logical_hash\":\"" + MARKER + "\""),
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
            // marker in an instant-typed payload field
            result.replace("2026-10-19T12:00:01.500000Z", MARKER),
            "{\"payload\":{\"a\":" + MARKER + "}}",
            good + MARKER);
    for (String text : bad) {
      assertThatThrownBy(() -> JournalObject.fromBytes(intent().key(), text.getBytes(UTF_8)))
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
    Map<String, Object> decision =
        JournalFixtures.payload(JournalType.RECOVERY_DECISION, JournalClass.A);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    new JournalKey.ClassA(SCOPE, 3, JournalType.DELIVERY_INTENT, "42", 1),
                    JournalType.RECOVERY_DECISION,
                    1,
                    AT,
                    decision))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    new JournalKey.ClassB(SCOPE, 3, 1),
                    JournalType.DELIVERY_INTENT,
                    1,
                    AT,
                    intent().payload()))
        .isInstanceOf(IllegalArgumentException.class);
    // a type that both classes allow is accepted in a class b key
    assertThat(
            new JournalObject(
                    new JournalKey.ClassB(SCOPE, 3, 1),
                    JournalType.RECOVERY_DECISION,
                    1,
                    AT,
                    JournalFixtures.payload(JournalType.RECOVERY_DECISION, JournalClass.B))
                .key()
                .journalClass())
        .isEqualTo(JournalClass.B);
  }

  @Test
  void theSchemaVersionAndTheClockInstantAreChecked() {
    JournalKey key = intent().key();
    Map<String, Object> payload = intent().payload();
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 0, AT, payload))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, -1, AT, payload))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    key,
                    JournalType.DELIVERY_INTENT,
                    1,
                    Instant.parse("2026-10-19T12:00:00.123456789Z"),
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    key,
                    JournalType.DELIVERY_INTENT,
                    1,
                    Instant.parse("+10000-01-01T00:00:00Z"),
                    payload))
        .isInstanceOf(IllegalArgumentException.class);
    // Instant.MIN has no calendar year: still an IllegalArgumentException, not a DateTimeException
    assertThatThrownBy(
            () -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, Instant.MIN, payload))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, Instant.MAX, payload))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thePayloadIsCopiedDeeplyAndImmutable() {
    List<Object> list = new ArrayList<>(List.of(JournalFixtures.hash("a")));
    Map<String, Object> source =
        new LinkedHashMap<>(JournalFixtures.payload(JournalType.DELIVERY_INTENT, JournalClass.A));
    source.put("context_ids", list);
    source.put("attempt_id", 5); // an Integer widens to Long
    JournalObject object =
        new JournalObject(intent().key(), JournalType.DELIVERY_INTENT, 1, AT, source);
    byte[] before = object.toBytes();
    list.add("more");
    source.put("late", 1L);
    assertThat(object.toBytes()).isEqualTo(before);
    assertThat(object.payload()).containsEntry("attempt_id", 5L).doesNotContainKey("late");
    assertThatThrownBy(() -> object.payload().put("x", 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<?>) object.payload().get("context_ids")).clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aPayloadValueThatIsNotCanonicalJsonIsRefused() {
    JournalKey key = intent().key();
    Map<String, Object> good = intent().payload();
    for (Object bad : List.of(new Object(), 1.5d, List.of(new Object()), Map.of(1, "x"))) {
      Map<String, Object> payload = new LinkedHashMap<>(good);
      payload.put("file_name", bad);
      assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, payload))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Map<String, Object> withNull = new LinkedHashMap<>(good);
    withNull.put("file_name", null);
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, withNull))
        .isInstanceOf(IllegalArgumentException.class);
    List<Object> withNullElement = new ArrayList<>();
    withNullElement.add(null);
    Map<String, Object> inList = new LinkedHashMap<>(good);
    inList.put("context_ids", withNullElement);
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, AT, inList))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
