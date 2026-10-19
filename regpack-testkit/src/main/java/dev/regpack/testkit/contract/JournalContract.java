package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.evidence.journal.JournalWriter;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.testkit.clock.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The journal-format contract test (spec 11.2, P3): the journal objects of both classes are written
 * under their keys through a {@link JournalWriter} over an {@link EvidenceStore}, read back, and a
 * re-write is idempotent for the same payload and an integrity alarm for another. Every adapter of
 * the evidence store binds this suite. It includes a {@code RecoveryDecision} for an attempt that
 * already has an intent.
 */
public interface JournalContract {
  /** A fresh, empty store for each test. */
  EvidenceStore store();

  ManualClock clock();

  /** Adapter-specific way to make every call fail. */
  void simulateOutage(boolean down);

  private JournalWriter writer() {
    return new JournalWriter(store(), RetentionPolicy.fixedDays(1826));
  }

  private static JournalScope scope() {
    return JournalScope.of(new EndpointKey("PRODUCTION", "ARM-ONE", "5493001KJTIIGC8Y1R12", "FCA"));
  }

  private static String hash(String seed) {
    return Sha256.ofUtf8(seed).hex();
  }

  private static JournalObject intent(
      long attempt, long epoch, long ordinal, String artifact, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("attempt_id", attempt);
    payload.put("delivery_id", 3L);
    payload.put("business_key", "TRADE-1");
    payload.put("trn", "TRN0000000000000000001");
    payload.put("version_seq", 1L);
    payload.put("action", "NEWT");
    payload.put("logical_hash", hash("logical"));
    payload.put("artifact_sha256", hash(artifact));
    payload.put("file_name", "report-1.xml");
    payload.put("context_ids", List.of(hash("context")));
    return new JournalObject(
        new JournalKey.ClassA(
            scope(), epoch, JournalType.DELIVERY_INTENT, Long.toString(attempt), ordinal),
        JournalType.DELIVERY_INTENT,
        1,
        at,
        payload);
  }

  private static Map<String, Object> decisionPayload(
      long attempt, long intentEpoch, String artifact, boolean authorises) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("delivery_id", 3L);
    payload.put("attempt_id", attempt);
    payload.put("intent_restore_epoch", intentEpoch);
    payload.put("artifact_sha256", hash(artifact));
    payload.put("decision", "RESEND");
    payload.put("authorises_effect", authorises);
    payload.put("reason", "no acknowledgement was seen");
    return payload;
  }

  private static JournalObject transportResult(long seq, long epoch, String status, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("attempt_id", 7L);
    payload.put("status", status);
    payload.put("detail", "http 202");
    payload.put("observed_at", at);
    return new JournalObject(
        new JournalKey.ClassB(scope(), epoch, seq), JournalType.TRANSPORT_RESULT, 1, at, payload);
  }

  private static Map<String, Object> actionPayload(String kind) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", kind);
    payload.put("operator", "op-1");
    payload.put("subject", "something");
    payload.put("detail", "approved");
    return payload;
  }

  private List<EvidenceKey> everyKey() {
    List<EvidenceKey> keys = new ArrayList<>();
    Optional<EvidenceKey> after = Optional.empty();
    while (true) {
      List<EvidenceKey> page = store().list("journal/", after, 100);
      keys.addAll(page);
      if (page.size() < 100) {
        return keys;
      }
      after = Optional.of(page.get(page.size() - 1));
    }
  }

  @Test
  default void aClassAObjectIsWrittenUnderItsKeyAndReadBack() {
    JournalObject object = intent(42, 3, 1, "artifact", clock().now());
    assertThat(writer().write(object)).isEqualTo(JournalWriter.Outcome.CREATED);
    StoredObject stored = store().get(object.key().evidenceKey()).orElseThrow();
    assertThat(object.key().evidenceKey().path())
        .isEqualTo(
            "journal/PRODUCTION,ARM-ONE,5493001KJTIIGC8Y1R12,FCA/e000000000003/a/DeliveryIntent/"
                + "n00000000/000000000042/000000000001");
    assertThat(stored.bytes()).isEqualTo(object.toBytes());
    assertThat(stored.mediaType()).isEqualTo("application/vnd.regpack.journal+json");
    assertThat(stored.retention().retentionClass().name()).isEqualTo("journal");
    assertThat(stored.retention().retainUntil())
        .isEqualTo(object.clockInstant().plus(Duration.ofDays(1826)));
    assertThat(writer().read(object.key())).contains(object);
    assertThat(
            writer().read(new JournalKey.ClassA(scope(), 3, JournalType.DELIVERY_INTENT, "43", 1)))
        .isEmpty();
  }

  @Test
  default void aClassBObjectIsWrittenUnderItsKeyAndReadBack() {
    JournalObject object = transportResult(10_001, 3, "ACCEPTED", clock().now());
    assertThat(writer().write(object)).isEqualTo(JournalWriter.Outcome.CREATED);
    StoredObject stored = store().get(object.key().evidenceKey()).orElseThrow();
    assertThat(object.key().evidenceKey().path())
        .isEqualTo(
            "journal/PRODUCTION,ARM-ONE,5493001KJTIIGC8Y1R12,FCA/e000000000003/b/00000001/"
                + "0000000000010001");
    assertThat(stored.bytes()).isEqualTo(object.toBytes());
    assertThat(stored.retention().retentionClass().name()).isEqualTo("journal");
    assertThat(writer().read(object.key())).contains(object);
    // the instant-typed payload field is an Instant again after the read
    assertThat(writer().read(object.key()).orElseThrow().payload().get("observed_at"))
        .isEqualTo(clock().now());
  }

  @Test
  default void rewritingTheSameObjectIsAlreadyPresent() {
    JournalObject object = intent(42, 3, 1, "artifact", clock().now());
    writer().write(object);
    clock().advance(Duration.ofMinutes(5));
    assertThat(writer().write(object))
        .isEqualTo(JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD);
    assertThat(store().get(object.key().evidenceKey()).orElseThrow().bytes())
        .isEqualTo(object.toBytes());
    assertThat(everyKey()).containsExactly(object.key().evidenceKey());
  }

  @Test
  default void rewritingAnObjectWithTheSamePayloadButAnotherInstantIsSuccess() {
    JournalObject first = intent(42, 3, 1, "artifact", clock().now());
    writer().write(first);
    JournalObject later = intent(42, 3, 1, "artifact", clock().now().plus(Duration.ofSeconds(5)));
    assertThat(later.toBytes()).isNotEqualTo(first.toBytes());
    assertThat(writer().write(later)).isEqualTo(JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD);
    // the first object is kept
    assertThat(store().get(first.key().evidenceKey()).orElseThrow().bytes())
        .isEqualTo(first.toBytes());
    assertThat(writer().read(first.key())).contains(first);
  }

  @Test
  default void rewritingWithAnotherPayloadIsAnIntegrityAlarmAndKeepsTheFirst() {
    JournalObject first = intent(42, 3, 1, "artifact", clock().now());
    writer().write(first);
    JournalObject other = intent(42, 3, 1, "another artifact", clock().now());
    assertThat(other.key()).isEqualTo(first.key());
    assertThatThrownBy(() -> writer().write(other)).isInstanceOf(EvidenceIntegrityException.class);
    assertThat(store().get(first.key().evidenceKey()).orElseThrow().bytes())
        .isEqualTo(first.toBytes());
    assertThat(writer().read(first.key())).contains(first);
  }

  @Test
  default void anOutageFailsClosedAndWritesNothing() {
    JournalObject object = intent(42, 3, 1, "artifact", clock().now());
    simulateOutage(true);
    assertThatThrownBy(() -> writer().write(object))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> writer().read(object.key()))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> writer().nextOrdinal(scope(), 3, JournalType.DELIVERY_INTENT, "42"))
        .isInstanceOf(EvidenceUnavailableException.class);
    simulateOutage(false);
    assertThat(everyKey()).isEmpty();
    assertThat(writer().read(object.key())).isEmpty();
    assertThat(writer().write(object)).isEqualTo(JournalWriter.Outcome.CREATED);
  }

  @Test
  default void aRecoveryDecisionForAnAttemptThatAlreadyHasAnIntentHasItsOwnKeyAndBothAreReadable() {
    // spec 11.2: the intent of attempt 7 at ordinal 1 in epoch 3; two decisions for delivery 3 in
    // epoch 3; a third decision after a restore, in epoch 4, about the intent of epoch 3
    JournalObject intent = intent(7, 3, 1, "artifact", clock().now());
    JournalObject first = decision(3, 3, 1, 7, 3);
    JournalObject second = decision(3, 3, 2, 7, 3);
    JournalObject afterRestore = decision(3, 4, 1, 7, 3);
    List<JournalObject> all = List.of(intent, first, second, afterRestore);
    Set<String> paths = new HashSet<>();
    for (JournalObject object : all) {
      assertThat(writer().write(object)).isEqualTo(JournalWriter.Outcome.CREATED);
      paths.add(object.key().evidenceKey().path());
    }
    assertThat(paths).hasSize(4);
    assertThat(everyKey()).hasSize(4);
    for (JournalObject object : all) {
      assertThat(writer().read(object.key())).contains(object);
    }
    // a match on (attempt_id, artifact_sha256, restore_epoch) finds the intent from each decision
    for (JournalObject decision : List.of(first, second, afterRestore)) {
      long attempt = (Long) decision.payload().get("attempt_id");
      long intentEpoch = (Long) decision.payload().get("intent_restore_epoch");
      JournalKey intentKey =
          new JournalKey.ClassA(
              scope(), intentEpoch, JournalType.DELIVERY_INTENT, Long.toString(attempt), 1);
      JournalObject found = writer().read(intentKey).orElseThrow();
      assertThat(found).isEqualTo(intent);
      assertThat(found.payload().get("attempt_id")).isEqualTo(attempt);
      assertThat(found.payload().get("artifact_sha256"))
          .isEqualTo(decision.payload().get("artifact_sha256"));
    }
    // the decisions of delivery 3 count on, and the intent is untouched by them
    assertThat(writer().nextOrdinal(scope(), 3, JournalType.RECOVERY_DECISION, "3")).isEqualTo(3);
    assertThat(writer().nextOrdinal(scope(), 4, JournalType.RECOVERY_DECISION, "3")).isEqualTo(2);
    assertThat(writer().nextOrdinal(scope(), 3, JournalType.DELIVERY_INTENT, "7")).isEqualTo(2);
    assertThat(writer().write(intent))
        .isEqualTo(JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD);
  }

  private JournalObject decision(
      long delivery, long epoch, long ordinal, long attempt, long intentEpoch) {
    return new JournalObject(
        new JournalKey.ClassA(
            scope(), epoch, JournalType.RECOVERY_DECISION, Long.toString(delivery), ordinal),
        JournalType.RECOVERY_DECISION,
        1,
        clock().now(),
        decisionPayload(attempt, intentEpoch, "artifact", true));
  }

  @Test
  default void aClassARecoveryDecisionMustSayItAuthorisesAnEffect() {
    Instant at = clock().now();
    JournalKey classA = new JournalKey.ClassA(scope(), 3, JournalType.RECOVERY_DECISION, "3", 1);
    JournalKey classB = new JournalKey.ClassB(scope(), 3, 1);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    classA,
                    JournalType.RECOVERY_DECISION,
                    1,
                    at,
                    decisionPayload(7, 3, "artifact", false)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    classB,
                    JournalType.RECOVERY_DECISION,
                    1,
                    at,
                    decisionPayload(7, 3, "artifact", true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(everyKey()).isEmpty();
    // the other pairings are written: a class b decision that authorises nothing
    JournalObject informational =
        new JournalObject(
            classB, JournalType.RECOVERY_DECISION, 1, at, decisionPayload(7, 3, "artifact", false));
    assertThat(writer().write(informational)).isEqualTo(JournalWriter.Outcome.CREATED);
    assertThat(writer().read(classB)).contains(informational);
  }

  @Test
  default void packOperatorActionsLiveUnderTheControlScope() {
    Instant at = clock().now();
    JournalScope control = JournalScope.control();
    JournalObject activation =
        new JournalObject(
            new JournalKey.ClassA(control, 0, JournalType.OPERATOR_ACTION, "pack-1", 1),
            JournalType.OPERATOR_ACTION,
            1,
            at,
            actionPayload("PACK_ACTIVATION"));
    assertThat(writer().write(activation)).isEqualTo(JournalWriter.Outcome.CREATED);
    assertThat(activation.key().evidenceKey().path())
        .startsWith("journal/control/e000000000000/a/");
    assertThat(writer().read(activation.key())).contains(activation);
    // a pack action under an endpoint scope, and an effect-gating action under the control scope
    assertThatThrownBy(
            () ->
                new JournalObject(
                    new JournalKey.ClassA(scope(), 0, JournalType.OPERATOR_ACTION, "pack-1", 1),
                    JournalType.OPERATOR_ACTION,
                    1,
                    at,
                    actionPayload("PACK_ROLLBACK")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JournalObject(
                    new JournalKey.ClassA(control, 0, JournalType.OPERATOR_ACTION, "w-1", 1),
                    JournalType.OPERATOR_ACTION,
                    1,
                    at,
                    actionPayload("WRITE_OFF")))
        .isInstanceOf(IllegalArgumentException.class);
    JournalObject writeOff =
        new JournalObject(
            new JournalKey.ClassA(scope(), 3, JournalType.OPERATOR_ACTION, "w-1", 1),
            JournalType.OPERATOR_ACTION,
            1,
            at,
            actionPayload("WRITE_OFF"));
    assertThat(writer().write(writeOff)).isEqualTo(JournalWriter.Outcome.CREATED);
    assertThat(everyKey()).hasSize(2);
  }

  @Test
  default void aScopeListsItsClassBObjectsInSeqOrderAcrossAShard() {
    Instant at = clock().now();
    for (long seq : new long[] {10_001, 9_999, 10_000}) {
      writer().write(transportResult(seq, 3, "ACCEPTED", at));
    }
    // another epoch and the intent class do not mix in
    writer().write(transportResult(5, 4, "ACCEPTED", at));
    writer().write(intent(42, 3, 1, "artifact", at));
    String prefix = "journal/" + scope().value() + "/e000000000003/b/";
    List<EvidenceKey> listed = store().list(prefix, Optional.empty(), 10);
    List<Long> seqs = new ArrayList<>();
    for (EvidenceKey key : listed) {
      seqs.add(((JournalKey.ClassB) JournalKey.parse(key)).seq());
    }
    assertThat(seqs).containsExactly(9_999L, 10_000L, 10_001L);
    assertThat(store().list(prefix, Optional.of(listed.get(0)), 10))
        .containsExactly(listed.get(1), listed.get(2));
  }

  @Test
  default void schemaViolationsAreRefusedBeforeAnyPut() {
    Instant at = clock().now();
    JournalKey key = new JournalKey.ClassA(scope(), 3, JournalType.DELIVERY_INTENT, "42", 1);
    Map<String, Object> good = intent(42, 3, 1, "artifact", at).payload();
    Map<String, Object> missing = new LinkedHashMap<>(good);
    missing.remove("trn");
    Map<String, Object> unknown = new LinkedHashMap<>(good);
    unknown.put("extra", "x");
    Map<String, Object> mistyped = new LinkedHashMap<>(good);
    mistyped.put("attempt_id", "42");
    for (Map<String, Object> payload :
        List.<Map<String, Object>>of(missing, unknown, mistyped, Map.of())) {
      assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 1, at, payload))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new JournalObject(key, JournalType.DELIVERY_INTENT, 2, at, good))
        .isInstanceOf(IllegalArgumentException.class);
    // The writer's only input type is JournalObject, whose constructor validates, so nothing that
    // breaks its schema can reach put: the store stays empty. The read side holds the same line
    // for bytes that are already stored: a schema-violating byte string is an integrity alarm.
    assertThat(everyKey()).isEmpty();
    String text = new String(intent(42, 3, 1, "artifact", at).toBytes(), StandardCharsets.UTF_8);
    assertThat(text).contains("\"version_seq\":1");
    byte[] violating =
        text.replace("\"version_seq\":1", "\"version_seq\":\"1\"").getBytes(StandardCharsets.UTF_8);
    store()
        .put(
            new EvidenceStore.PutRequest(
                key.evidenceKey(),
                violating,
                JournalWriter.MEDIA_TYPE,
                RetentionPolicy.fixedDays(1826).retentionFor(JournalWriter.RETENTION_CLASS, at)));
    assertThatThrownBy(() -> writer().read(key)).isInstanceOf(EvidenceIntegrityException.class);
  }
}
