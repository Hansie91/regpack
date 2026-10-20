package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.ports.ledger.ReportLedger.VersionOrigin;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * D-S9: states and kinds are stored as smallint codes that never change. The codes are pinned here
 * in full; the numbers inside the partial-index predicates of V001 (SENDING 4, AWAITING_ACK 5,
 * IN_DOUBT 11, outbox PENDING 1) are pinned by {@code
 * SchemaDocMatchesMigrationsTest#partialIndexPredicatesCarryThePinnedStateCodes} and checked
 * against {@link Codes} below.
 */
class CodesTest {
  private record Family(Class<?> type, String inverse, Map<String, Integer> pinned) {}

  private static Map<String, Integer> codes(String... namesInCodeOrder) {
    Map<String, Integer> map = new LinkedHashMap<>();
    for (int i = 0; i < namesInCodeOrder.length; i++) {
      map.put(namesInCodeOrder[i], i + 1);
    }
    return map;
  }

  private static final Family[] FAMILIES = {
    new Family(
        Outcome.class,
        "outcome",
        codes("REPORT", "DO_NOT_REPORT", "REPORT_BY_OTHER", "UNDETERMINED")),
    new Family(Leg.class, "leg", codes("MARKET", "CLIENT", "ALLOCATION")),
    // stored inside ingest_batch.result (IngestResultCodec), not in a smallint column
    new Family(
        Disposition.class,
        "disposition",
        codes(
            "NEW_VERSION",
            "NOOP_SAME_VERSION_SAME_HASH",
            "STALE",
            "CONFLICT_SAME_VERSION_DIFFERENT_HASH",
            "SOURCE_CONFLICT")),
    new Family(
        DeliveryState.class,
        "deliveryState",
        codes(
            "PREPARED",
            "BLOCKED",
            "QUEUED",
            "SENDING",
            "AWAITING_ACK",
            "ACCEPTED_PROVISIONAL",
            "ACCEPTED_CONFIRMED",
            "PENDING_AT_ENDPOINT",
            "REJECTED",
            "FILE_REJECTED",
            "IN_DOUBT",
            "RESEND_PENDING",
            "IN_REPAIR",
            "SUPERSEDED",
            "WITHDRAWN",
            "WRITTEN_OFF")),
    new Family(OutboxKind.class, "outboxKind", codes("DECIDE_BATCH", "SEQUENCE")),
    new Family(OutboxState.class, "outboxState", codes("PENDING", "DONE")),
    new Family(VersionOrigin.class, "versionOrigin", codes("SOURCE", "ARM", "IMPORTED")),
    // object_manifest.object_class and .provenance (V002), pinned by number in the test below
    new Family(
        ObjectClass.class,
        "objectClass",
        codes(
            "SEALED_FILE",
            "JOURNAL_A",
            "JOURNAL_B",
            "LANDING_BATCH",
            "ENRICHMENT_RESPONSE",
            "SIGNED_MANIFEST",
            "OTHER")),
    new Family(Provenance.class, "provenance", codes("WRITTEN", "ADOPTED_AT_RESTORE")),
  };

  @Test
  void everyEnumConstantHasAStableCode() throws ReflectiveOperationException {
    for (Family family : FAMILIES) {
      Method of = Codes.class.getMethod("of", family.type());
      Method inverse = Codes.class.getMethod(family.inverse(), short.class);
      Object[] constants = family.type().getEnumConstants();
      Map<String, Integer> actual = new LinkedHashMap<>();
      TreeSet<Short> distinct = new TreeSet<>();
      for (Object constant : constants) {
        short code = (short) of.invoke(null, constant);
        actual.put(((Enum<?>) constant).name(), (int) code);
        distinct.add(code);
        assertThat(inverse.invoke(null, code)).as("%s code %d", constant, code).isSameAs(constant);
        assertThat(code).as("%s is a positive code", constant).isPositive();
      }
      assertThat(actual).as(family.type().getSimpleName()).isEqualTo(family.pinned());
      assertThat(distinct)
          .as("codes of %s are unique", family.type().getSimpleName())
          .hasSize(constants.length);
    }
  }

  @Test
  void theCodesInsideTheIndexPredicatesOfV001AreTheOnesPinned() {
    // docs/ledger/decisions.md, "state codes inside V001"
    assertThat(Codes.of(DeliveryState.SENDING)).isEqualTo((short) 4);
    assertThat(Codes.of(DeliveryState.AWAITING_ACK)).isEqualTo((short) 5);
    assertThat(Codes.of(DeliveryState.IN_DOUBT)).isEqualTo((short) 11);
    assertThat(Codes.of(OutboxState.PENDING)).isEqualTo((short) 1);
    assertThat(Codes.of(DeliveryState.PREPARED)).isEqualTo((short) 1);
    assertThat(Codes.of(DeliveryState.WRITTEN_OFF)).isEqualTo((short) 16);
    assertThat(Codes.of(OutboxState.DONE)).isEqualTo((short) 2);
  }

  @Test
  void objectClassAndProvenanceCodesArePinned() {
    // V002 stores both as smallint, without a CHECK on the set (schema-design-p3b.md, note a): the
    // numbers are written here as literals, not derived from the order of the constants
    assertThat(Codes.of(ObjectClass.SEALED_FILE)).isEqualTo((short) 1);
    assertThat(Codes.of(ObjectClass.JOURNAL_A)).isEqualTo((short) 2);
    assertThat(Codes.of(ObjectClass.JOURNAL_B)).isEqualTo((short) 3);
    assertThat(Codes.of(ObjectClass.LANDING_BATCH)).isEqualTo((short) 4);
    assertThat(Codes.of(ObjectClass.ENRICHMENT_RESPONSE)).isEqualTo((short) 5);
    assertThat(Codes.of(ObjectClass.SIGNED_MANIFEST)).isEqualTo((short) 6);
    assertThat(Codes.of(ObjectClass.OTHER)).isEqualTo((short) 7);
    assertThat(Codes.of(Provenance.WRITTEN)).isEqualTo((short) 1);
    assertThat(Codes.of(Provenance.ADOPTED_AT_RESTORE)).isEqualTo((short) 2);
    for (ObjectClass objectClass : ObjectClass.values()) {
      assertThat(Codes.objectClass(Codes.of(objectClass))).isSameAs(objectClass);
    }
    for (Provenance provenance : Provenance.values()) {
      assertThat(Codes.provenance(Codes.of(provenance))).isSameAs(provenance);
    }
    assertThat(ObjectClass.values()).as("a new object class needs a code and a pin").hasSize(7);
    assertThat(Provenance.values()).as("a new provenance needs a code and a pin").hasSize(2);
    assertThatThrownBy(() -> Codes.of((ObjectClass) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Codes.of((Provenance) null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void unknownCodeIsRejected() throws ReflectiveOperationException {
    for (Family family : FAMILIES) {
      Method inverse = Codes.class.getMethod(family.inverse(), short.class);
      int highest = family.pinned().size();
      for (int bad : new int[] {0, -1, highest + 1, 1000, Short.MAX_VALUE, Short.MIN_VALUE}) {
        assertThatThrownBy(() -> invoke(inverse, (short) bad))
            .as("%s code %d", family.type().getSimpleName(), bad)
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  private static Object invoke(Method method, Object argument) throws Throwable {
    try {
      return method.invoke(null, argument);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  @Test
  void aNullConstantIsRejected() {
    assertThatThrownBy(() -> Codes.of((Outcome) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Codes.of((DeliveryState) null))
        .isInstanceOf(NullPointerException.class);
  }
}
