package dev.regpack.ports.evidence.journal;

import static dev.regpack.ports.evidence.journal.JournalFixtures.SCOPE;
import static dev.regpack.ports.evidence.journal.JournalFixtures.hash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JournalSchemasTest {
  private static final String MARKER = "SECRET MARKER 7";

  /** The SHA-256 of the canonical bytes of the golden object of each type (Pins created). */
  private static final Map<JournalType, String> GOLDEN = new EnumMap<>(JournalType.class);

  static {
    GOLDEN.put(
        JournalType.DELIVERY_INTENT,
        "e8b202cf003e8281381d5fb89d1824c4aec9226aaef1b23c4275c71ae9ad8ffb");
    GOLDEN.put(
        JournalType.BATCH_ADMITTED,
        "49f9437babbe399c54ae2d3f6146049ad85ad1d4aebafe8b2d48d79207d5cf56");
    GOLDEN.put(
        JournalType.TRANSPORT_RESULT,
        "bd6603bf329581c4a7ee3d430b5624a86b4ecfa45ade67869d331641fa25df2a");
    GOLDEN.put(
        JournalType.FEEDBACK_RECEIVED,
        "47aab80e8e15e9b5739ce7b2f90eba115f7ab1d406fdcda179f568c02c07c7cc");
    GOLDEN.put(
        JournalType.RECOVERY_DECISION,
        "f64cb143571dfb7103ead9567546bba9b7394a5855c2299080c3d9157e7ae175");
    GOLDEN.put(
        JournalType.OPERATOR_ACTION,
        "4508aad90e7412d0a03ca4436acd1618296ad95ce52df0cf20d3ff5618c827e3");
    GOLDEN.put(
        JournalType.VERSION_ASSIGNED,
        "16284feead81cb5889da8f64142017b3aefa0978d74fa41eb1d950149924ef48");
  }

  private static JournalClass classOf(JournalType type) {
    return type.allows(JournalClass.A) ? JournalClass.A : JournalClass.B;
  }

  private static JournalScope scopeOf(JournalType type) {
    return type == JournalType.BATCH_ADMITTED ? JournalScope.control() : SCOPE;
  }

  private static void validate(JournalType type, Map<String, Object> payload) {
    JournalSchemas.validate(type, JournalSchemas.V1, classOf(type), scopeOf(type), payload);
  }

  @Test
  void goldenObjectOfEachTypeHasThePinnedSha256() {
    for (JournalType type : JournalType.values()) {
      assertThat(Sha256.of(JournalFixtures.object(type).toBytes()).hex())
          .as(type.wireName())
          .isEqualTo(GOLDEN.get(type));
    }
  }

  @Test
  void everyFixtureIsValid() {
    for (JournalType type : JournalType.values()) {
      assertThatCode(() -> validate(type, JournalFixtures.payload(type, classOf(type))))
          .as(type.wireName())
          .doesNotThrowAnyException();
    }
    assertThat(JournalSchemas.V1).isEqualTo(1);
  }

  @Test
  void aMissingUnknownOrMistypedPayloadKeyIsRefusedForEveryType() {
    for (JournalType type : JournalType.values()) {
      Map<String, Object> good = JournalFixtures.payload(type, classOf(type));
      for (Map.Entry<String, Object> entry : good.entrySet()) {
        Map<String, Object> missing = new LinkedHashMap<>(good);
        missing.remove(entry.getKey());
        assertThatThrownBy(() -> validate(type, missing))
            .as(type.wireName() + " without " + entry.getKey())
            .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> mistyped = new LinkedHashMap<>(good);
        mistyped.put(entry.getKey(), entry.getValue() instanceof String ? 5L : "x");
        assertThatThrownBy(() -> validate(type, mistyped))
            .as(type.wireName() + " with a mistyped " + entry.getKey())
            .isInstanceOf(IllegalArgumentException.class);
      }
      Map<String, Object> unknown = new LinkedHashMap<>(good);
      unknown.put("extra", "x");
      assertThatThrownBy(() -> validate(type, unknown))
          .as(type.wireName() + " with an unknown key")
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> validate(type, Map.of()))
          .as(type.wireName() + " empty")
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void valuesAreHeldToTheirFieldTypes() {
    Map<String, Object> intent =
        JournalFixtures.payload(JournalType.DELIVERY_INTENT, JournalClass.A);
    List<Map.Entry<String, Object>> bad =
        List.of(
            Map.entry("attempt_id", -1L),
            Map.entry("attempt_id", 5), // an Integer is not a Long
            Map.entry("version_seq", -2L),
            Map.entry("logical_hash", hash("x").toUpperCase(java.util.Locale.ROOT)),
            Map.entry("logical_hash", hash("x").substring(1)),
            Map.entry("artifact_sha256", ""),
            Map.entry("context_ids", List.of("not a hash")),
            Map.entry("context_ids", List.of(hash("a"), 5L)),
            Map.entry("context_ids", "x"),
            Map.entry("file_name", List.of("x")),
            Map.entry("business_key", true));
    for (Map.Entry<String, Object> change : bad) {
      Map<String, Object> payload = new LinkedHashMap<>(intent);
      payload.put(change.getKey(), change.getValue());
      assertThatThrownBy(() -> validate(JournalType.DELIVERY_INTENT, payload))
          .as(change.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }
    // an empty list of hashes is a valid list
    Map<String, Object> noContext = new LinkedHashMap<>(intent);
    noContext.put("context_ids", List.of());
    assertThatCode(() -> validate(JournalType.DELIVERY_INTENT, noContext))
        .doesNotThrowAnyException();
  }

  @Test
  void instantFieldsAreInstantsWithMicrosecondPrecisionOnly() {
    for (Object bad :
        List.of(
            "2026-10-19T12:00:00.000000Z", // text, not an Instant
            Instant.parse("2026-10-19T12:00:00.123456789Z"),
            Instant.parse("+10000-01-01T00:00:00Z"),
            Instant.MIN,
            Instant.MAX,
            5L)) {
      Map<String, Object> result =
          new LinkedHashMap<>(
              JournalFixtures.payload(JournalType.TRANSPORT_RESULT, JournalClass.B));
      result.put("observed_at", bad);
      assertThatThrownBy(() -> validate(JournalType.TRANSPORT_RESULT, result))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalArgumentException.class);
      Map<String, Object> feedback =
          new LinkedHashMap<>(
              JournalFixtures.payload(JournalType.FEEDBACK_RECEIVED, JournalClass.B));
      feedback.put("received_at", bad);
      assertThatThrownBy(() -> validate(JournalType.FEEDBACK_RECEIVED, feedback))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void versionAssignedAssignmentsAreStrictObjects() {
    List<Object> bad =
        List.of(
            List.of(Map.of("subject", "S", "trn", "T")),
            List.of(Map.of("subject", "S", "trn", "T", "version_seq", 1L, "extra", 1L)),
            List.of(Map.of("subject", "S", "trn", "T", "version_seq", "1")),
            List.of(Map.of("subject", 1L, "trn", "T", "version_seq", 1L)),
            List.of("S"),
            "S");
    for (Object assignments : bad) {
      Map<String, Object> payload =
          new LinkedHashMap<>(
              JournalFixtures.payload(JournalType.VERSION_ASSIGNED, JournalClass.B));
      payload.put("assignments", assignments);
      assertThatThrownBy(() -> validate(JournalType.VERSION_ASSIGNED, payload))
          .as(String.valueOf(assignments))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Map<String, Object> none =
        new LinkedHashMap<>(JournalFixtures.payload(JournalType.VERSION_ASSIGNED, JournalClass.B));
    none.put("assignments", List.of());
    assertThatCode(() -> validate(JournalType.VERSION_ASSIGNED, none)).doesNotThrowAnyException();
  }

  @Test
  void onlySchemaVersionOneIsKnownAndATypeMustAllowTheClass() {
    Map<String, Object> payload =
        JournalFixtures.payload(JournalType.DELIVERY_INTENT, JournalClass.A);
    for (int version : new int[] {-1, 0, 2, 3}) {
      assertThatThrownBy(
              () ->
                  JournalSchemas.validate(
                      JournalType.DELIVERY_INTENT, version, JournalClass.A, SCOPE, payload))
          .as("version " + version)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                JournalSchemas.validate(
                    JournalType.DELIVERY_INTENT, 1, JournalClass.B, SCOPE, payload))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                JournalSchemas.validate(
                    JournalType.DELIVERY_INTENT, 1, JournalClass.A, SCOPE, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void messagesNameTheFieldAndNeverEchoTheValue() {
    for (JournalType type : JournalType.values()) {
      for (Map.Entry<String, Object> entry :
          JournalFixtures.payload(type, classOf(type)).entrySet()) {
        Map<String, Object> payload =
            new LinkedHashMap<>(JournalFixtures.payload(type, classOf(type)));
        payload.put(entry.getKey(), entry.getValue() instanceof String ? 5L : MARKER);
        payload.put(MARKER, MARKER);
        assertThatThrownBy(() -> validate(type, payload))
            .isInstanceOf(IllegalArgumentException.class)
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(MARKER));
      }
    }
    Map<String, Object> text =
        new LinkedHashMap<>(JournalFixtures.payload(JournalType.BATCH_ADMITTED, JournalClass.A));
    text.put("pack_digest", MARKER);
    assertThatThrownBy(() -> validate(JournalType.BATCH_ADMITTED, text))
        .hasMessageContaining("pack_digest")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(MARKER));
  }

  /** Every text field of the schemas and its limit in characters (UTF-16 code units). */
  private static final Object[][] TEXT_LIMITS = {
    {JournalType.DELIVERY_INTENT, "business_key", 1_024},
    {JournalType.DELIVERY_INTENT, "trn", 1_024},
    {JournalType.DELIVERY_INTENT, "action", 1_024},
    {JournalType.DELIVERY_INTENT, "file_name", 1_024},
    {JournalType.BATCH_ADMITTED, "batch_id", 1_024},
    {JournalType.TRANSPORT_RESULT, "status", 1_024},
    {JournalType.TRANSPORT_RESULT, "detail", 4_096},
    {JournalType.RECOVERY_DECISION, "decision", 1_024},
    {JournalType.RECOVERY_DECISION, "reason", 4_096},
    {JournalType.OPERATOR_ACTION, "operator", 1_024},
    {JournalType.OPERATOR_ACTION, "subject", 1_024},
    {JournalType.OPERATOR_ACTION, "detail", 4_096},
    {JournalType.VERSION_ASSIGNED, "batch_id", 1_024},
  };

  @Test
  void everyTextFieldIsBoundedAndTheLimitItselfIsAccepted() {
    for (Object[] row : TEXT_LIMITS) {
      JournalType type = (JournalType) row[0];
      String field = (String) row[1];
      int limit = (Integer) row[2];
      Map<String, Object> exactly =
          new LinkedHashMap<>(JournalFixtures.payload(type, classOf(type)));
      exactly.put(field, "a".repeat(limit));
      assertThatCode(() -> validate(type, exactly))
          .as(type + "." + field)
          .doesNotThrowAnyException();
      assertThat(
              new JournalObject(
                      JournalFixtures.object(type).key(), type, 1, JournalFixtures.AT, exactly)
                  .payload())
          .containsEntry(field, "a".repeat(limit));
      Map<String, Object> over = new LinkedHashMap<>(exactly);
      over.put(field, "a".repeat(limit + 1));
      assertThatThrownBy(() -> validate(type, over))
          .as(type + "." + field)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(field)
          .hasMessageContaining(Integer.toString(limit))
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("aaaa"));
    }
    // the operator action kind is a name from a closed set: a long one is refused as text too
    Map<String, Object> kind =
        new LinkedHashMap<>(JournalFixtures.payload(JournalType.OPERATOR_ACTION, JournalClass.A));
    kind.put("kind", "A".repeat(1_025));
    assertThatThrownBy(() -> validate(JournalType.OPERATOR_ACTION, kind))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind")
        .hasMessageContaining("1024");
  }

  @Test
  void theTextsOfAListAreBoundedToo() {
    Map<String, Object> ids =
        new LinkedHashMap<>(JournalFixtures.payload(JournalType.FEEDBACK_RECEIVED, JournalClass.B));
    ids.put("file_ids", List.of("a", "b".repeat(1_024)));
    assertThatCode(() -> validate(JournalType.FEEDBACK_RECEIVED, ids)).doesNotThrowAnyException();
    ids.put("file_ids", List.of("a", "b".repeat(1_025)));
    assertThatThrownBy(() -> validate(JournalType.FEEDBACK_RECEIVED, ids))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file_ids")
        .hasMessageContaining("1024")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("bbbb"));
    for (String field : List.of("subject", "trn")) {
      Map<String, Object> assigned =
          new LinkedHashMap<>(
              JournalFixtures.payload(JournalType.VERSION_ASSIGNED, JournalClass.B));
      assigned.put(
          "assignments",
          List.of(
              Map.of("subject", "s", "trn", "t", "version_seq", 1L),
              withText(field, "c".repeat(1_024))));
      assertThatCode(() -> validate(JournalType.VERSION_ASSIGNED, assigned))
          .as(field)
          .doesNotThrowAnyException();
      assigned.put(
          "assignments",
          List.of(
              Map.of("subject", "s", "trn", "t", "version_seq", 1L),
              withText(field, "c".repeat(1_025))));
      assertThatThrownBy(() -> validate(JournalType.VERSION_ASSIGNED, assigned))
          .as(field)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("assignments")
          .hasMessageContaining("1024")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("cccc"));
    }
  }

  private static Map<String, Object> withText(String field, String value) {
    Map<String, Object> assignment = new LinkedHashMap<>();
    assignment.put("subject", "s");
    assignment.put("trn", "t");
    assignment.put("version_seq", 1L);
    assignment.put(field, value);
    return assignment;
  }

  @Test
  void aRecoveryDecisionFlagMustMatchItsClass() {
    Map<String, Object> yes =
        new LinkedHashMap<>(JournalFixtures.payload(JournalType.RECOVERY_DECISION, JournalClass.A));
    Map<String, Object> no = new LinkedHashMap<>(yes);
    no.put("authorises_effect", false);
    yes.put("authorises_effect", true);
    JournalScope scope = SCOPE;
    assertThatCode(
            () ->
                JournalSchemas.validate(
                    JournalType.RECOVERY_DECISION, 1, JournalClass.A, scope, yes))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                JournalSchemas.validate(
                    JournalType.RECOVERY_DECISION, 1, JournalClass.B, scope, no))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                JournalSchemas.validate(
                    JournalType.RECOVERY_DECISION, 1, JournalClass.A, scope, no))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authorises_effect");
    assertThatThrownBy(
            () ->
                JournalSchemas.validate(
                    JournalType.RECOVERY_DECISION, 1, JournalClass.B, scope, yes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authorises_effect");
  }

  @Test
  void operatorActionKindsFixTheClassAndScope() {
    JournalScope endpoint = SCOPE;
    JournalScope control = JournalScope.control();
    // kind, allowed class, allowed scopes
    record Row(String kind, JournalClass allowed, List<JournalScope> scopes) {}
    List<Row> table =
        List.of(
            new Row("APPROVAL", JournalClass.B, List.of(endpoint, control)),
            new Row("WRITE_OFF", JournalClass.A, List.of(endpoint)),
            new Row("FORCED_RESEND", JournalClass.A, List.of(endpoint)),
            new Row("ROUTE_CHANGE", JournalClass.A, List.of(endpoint)),
            new Row("PACK_ACTIVATION", JournalClass.A, List.of(control)),
            new Row("PACK_ROLLBACK", JournalClass.A, List.of(control)),
            new Row("PACK_EXPIRY", JournalClass.A, List.of(control)));
    assertThat(table).hasSize(7);
    for (Row row : table) {
      Map<String, Object> payload =
          new LinkedHashMap<>(JournalFixtures.payload(JournalType.OPERATOR_ACTION, JournalClass.A));
      payload.put("kind", row.kind());
      for (JournalClass cls : JournalClass.values()) {
        for (JournalScope scope : List.of(endpoint, control)) {
          boolean ok = cls == row.allowed() && row.scopes().contains(scope);
          var call =
              (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
                  () ->
                      JournalSchemas.validate(JournalType.OPERATOR_ACTION, 1, cls, scope, payload);
          String label = row.kind() + " " + cls + " " + scope.value();
          if (ok) {
            assertThatCode(call).as(label).doesNotThrowAnyException();
          } else {
            assertThatThrownBy(call).as(label).isInstanceOf(IllegalArgumentException.class);
          }
        }
      }
    }
    for (String kind : new String[] {"", "approval", "DELETE_ALL", "FORCED RESEND"}) {
      Map<String, Object> payload =
          new LinkedHashMap<>(JournalFixtures.payload(JournalType.OPERATOR_ACTION, JournalClass.A));
      payload.put("kind", kind);
      assertThatThrownBy(
              () ->
                  JournalSchemas.validate(
                      JournalType.OPERATOR_ACTION, 1, JournalClass.A, endpoint, payload))
          .as(kind)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theObjectConstructorValidatesThroughTheSchemas() {
    List<JournalObject> good = new ArrayList<>();
    for (JournalType type : JournalType.values()) {
      good.add(JournalFixtures.object(type));
    }
    assertThat(good).hasSize(7);
    JournalKey key = JournalFixtures.object(JournalType.DELIVERY_INTENT).key();
    assertThatThrownBy(
            () ->
                new JournalObject(
                    key, JournalType.DELIVERY_INTENT, 1, JournalFixtures.AT, Map.of("note", "x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    key,
                    JournalType.DELIVERY_INTENT,
                    2,
                    JournalFixtures.AT,
                    JournalFixtures.payload(JournalType.DELIVERY_INTENT, JournalClass.A)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
