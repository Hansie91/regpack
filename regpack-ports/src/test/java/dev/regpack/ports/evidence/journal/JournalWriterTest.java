package dev.regpack.ports.evidence.journal;

import static dev.regpack.ports.evidence.journal.JournalFixtures.AT;
import static dev.regpack.ports.evidence.journal.JournalFixtures.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class JournalWriterTest {
  private static final RetentionPolicy FIVE_YEARS = RetentionPolicy.fixedDays(1826);

  /** A minimal write-once map; {@code failWith} makes every put throw. */
  private static final class MapStore implements EvidenceStore {
    final TreeMap<String, StoredObject> objects = new TreeMap<>();
    final List<PutRequest> puts = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public Grade grade() {
      return new Grade("P-MEMORY", "");
    }

    @Override
    public Durability durability() {
      return new Durability(false, 0);
    }

    @Override
    public PutOutcome put(PutRequest request) {
      if (failWith != null) {
        throw failWith;
      }
      puts.add(request);
      StoredObject existing = objects.get(request.key().path());
      Sha256 digest = Sha256.of(request.bytes());
      if (existing == null) {
        objects.put(
            request.key().path(),
            new StoredObject(
                request.key(),
                request.bytes(),
                request.mediaType(),
                digest,
                AT,
                request.retention(),
                false));
        return PutOutcome.CREATED;
      }
      if (existing.sha256().equals(digest)) {
        return PutOutcome.ALREADY_PRESENT_IDENTICAL;
      }
      throw new EvidenceIntegrityException("different bytes");
    }

    @Override
    public Optional<StoredObject> get(EvidenceKey key) {
      return Optional.ofNullable(objects.get(key.path()));
    }

    @Override
    public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
      List<EvidenceKey> result = new ArrayList<>();
      for (var entry : objects.tailMap(prefix, true).entrySet()) {
        if (result.size() >= limit || !entry.getKey().startsWith(prefix)) {
          break;
        }
        if (after.isEmpty() || entry.getKey().compareTo(after.get().path()) > 0) {
          result.add(entry.getValue().key());
        }
      }
      return result;
    }

    @Override
    public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setLegalHold(EvidenceKey key, boolean hold) {
      throw new UnsupportedOperationException();
    }
  }

  private static JournalKey.ClassA intentKey(String subject, long ordinal) {
    return new JournalKey.ClassA(SCOPE, 3, JournalType.DELIVERY_INTENT, subject, ordinal);
  }

  private static void storeKeyOnly(MapStore store, JournalKey key) {
    store.put(
        new EvidenceStore.PutRequest(
            key.evidenceKey(),
            new byte[] {1},
            "x",
            new EvidenceStore.Retention(new EvidenceStore.RetentionClass("x"), AT)));
  }

  @Test
  void nextOrdinalIsOneWhenNoneAndMaxPlusOneOtherwise() {
    MapStore store = new MapStore();
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    assertThat(writer.nextOrdinal(SCOPE, 3, JournalType.DELIVERY_INTENT, "42")).isEqualTo(1);
    storeKeyOnly(store, intentKey("42", 1));
    assertThat(writer.nextOrdinal(SCOPE, 3, JournalType.DELIVERY_INTENT, "42")).isEqualTo(2);
    storeKeyOnly(store, intentKey("42", 2));
    storeKeyOnly(store, intentKey("42", 7)); // a gap: the highest wins
    assertThat(writer.nextOrdinal(SCOPE, 3, JournalType.DELIVERY_INTENT, "42")).isEqualTo(8);
    // another subject whose text starts like this one, another epoch, another type, another scope
    storeKeyOnly(store, intentKey("420", 50));
    storeKeyOnly(store, new JournalKey.ClassA(SCOPE, 4, JournalType.DELIVERY_INTENT, "42", 60));
    storeKeyOnly(store, new JournalKey.ClassA(SCOPE, 3, JournalType.RECOVERY_DECISION, "42", 70));
    storeKeyOnly(
        store,
        new JournalKey.ClassA(JournalScope.control(), 3, JournalType.OPERATOR_ACTION, "42", 80));
    assertThat(writer.nextOrdinal(SCOPE, 3, JournalType.DELIVERY_INTENT, "42")).isEqualTo(8);
    assertThat(writer.nextOrdinal(SCOPE, 3, JournalType.DELIVERY_INTENT, "420")).isEqualTo(51);
    assertThat(writer.nextOrdinal(SCOPE, 4, JournalType.DELIVERY_INTENT, "42")).isEqualTo(61);
    assertThat(writer.nextOrdinal(SCOPE, 5, JournalType.DELIVERY_INTENT, "42")).isEqualTo(1);
    // a non-numeric subject, whose text is also the start of another subject
    storeKeyOnly(store, new JournalKey.ClassA(SCOPE, 3, JournalType.OPERATOR_ACTION, "op", 3));
    storeKeyOnly(store, new JournalKey.ClassA(SCOPE, 3, JournalType.OPERATOR_ACTION, "op-2", 9));
    assertThat(writer.nextOrdinal(SCOPE, 3, JournalType.OPERATOR_ACTION, "op")).isEqualTo(4);
  }

  @Test
  void nextOrdinalWalksPagesOfALongSubject() {
    MapStore store = new MapStore();
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    for (long ordinal = 1; ordinal <= 2_345; ordinal++) {
      storeKeyOnly(store, intentKey("42", ordinal));
    }
    assertThat(writer.nextOrdinal(SCOPE, 3, JournalType.DELIVERY_INTENT, "42")).isEqualTo(2_346);
  }

  @Test
  void nextOrdinalRefusesWhatNoKeyCouldHold() {
    JournalWriter writer = new JournalWriter(new MapStore(), FIVE_YEARS);
    assertThatThrownBy(() -> writer.nextOrdinal(SCOPE, 3, JournalType.DELIVERY_INTENT, "042"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> writer.nextOrdinal(SCOPE, 3, JournalType.TRANSPORT_RESULT, "42"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> writer.nextOrdinal(JournalScope.control(), 3, JournalType.DELIVERY_INTENT, "42"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullRetentionPolicyIsRefused() {
    assertThatThrownBy(() -> new JournalWriter(new MapStore(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JournalWriter(null, FIVE_YEARS))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aCorruptionAlarmIsRethrown() {
    MapStore store = new MapStore();
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    JournalObject object = JournalFixtures.object(JournalType.DELIVERY_INTENT);
    // The same object is already stored and readable, so absorbing the alarm as "already present"
    // would be possible (and wrong): the stored payload hash equals the new one.
    assertThat(writer.write(object)).isEqualTo(JournalWriter.Outcome.CREATED);
    EvidenceCorruptionException alarm = new EvidenceCorruptionException("one copy is damaged");
    store.failWith = alarm;
    assertThatThrownBy(() -> writer.write(object)).isSameAs(alarm);
    assertThat(writer.read(object.key())).contains(object);
    // the counterpart: a plain integrity alarm over the same stored payload is already present
    store.failWith = new EvidenceIntegrityException("a copy differs");
    assertThat(writer.write(object)).isEqualTo(JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD);
    // other store failures pass through unchanged as well
    EvidenceUnavailableException outage = new EvidenceUnavailableException("down");
    store.failWith = outage;
    assertThatThrownBy(() -> writer.write(object)).isSameAs(outage);
  }

  @Test
  void aCorruptionAlarmIsAnIntegrityAlarmOfItsOwnKind() {
    assertThat(new EvidenceCorruptionException("x")).isInstanceOf(EvidenceIntegrityException.class);
    Throwable cause = new RuntimeException("c");
    assertThat(new EvidenceCorruptionException("x", cause).getCause()).isSameAs(cause);
  }

  @Test
  void writePutsTheCanonicalBytesWithTheJournalRetentionAndMediaType() {
    MapStore store = new MapStore();
    List<String> asked = new ArrayList<>();
    RetentionPolicy recording =
        (retentionClass, writtenAt) -> {
          asked.add(retentionClass + "@" + writtenAt);
          return new EvidenceStore.Retention(
              new EvidenceStore.RetentionClass(retentionClass), writtenAt.plus(Duration.ofDays(1)));
        };
    JournalWriter writer = new JournalWriter(store, recording);
    JournalObject object = JournalFixtures.object(JournalType.TRANSPORT_RESULT);
    assertThat(writer.write(object)).isEqualTo(JournalWriter.Outcome.CREATED);
    assertThat(asked).containsExactly("journal@" + AT);
    EvidenceStore.PutRequest put = store.puts.get(0);
    assertThat(put.key()).isEqualTo(object.key().evidenceKey());
    assertThat(put.bytes()).isEqualTo(object.toBytes());
    assertThat(put.mediaType()).isEqualTo("application/vnd.regpack.journal+json");
    assertThat(put.retention().retentionClass().name()).isEqualTo("journal");
    assertThat(put.retention().retainUntil()).isEqualTo(AT.plus(Duration.ofDays(1)));
  }

  @Test
  void rewritingIsIdempotentForTheSamePayloadAndAlarmingForAnother() {
    MapStore store = new MapStore();
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    JournalObject first = JournalFixtures.object(JournalType.DELIVERY_INTENT);
    assertThat(writer.write(first)).isEqualTo(JournalWriter.Outcome.CREATED);
    assertThat(writer.write(first)).isEqualTo(JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD);
    JournalObject later =
        new JournalObject(first.key(), first.type(), 1, AT.plusSeconds(5), first.payload());
    assertThat(writer.write(later)).isEqualTo(JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD);
    assertThat(writer.read(first.key())).contains(first);
    Map<String, Object> changed = new LinkedHashMap<>(first.payload());
    changed.put("action", "CANC");
    JournalObject other = new JournalObject(first.key(), first.type(), 1, AT, changed);
    assertThatThrownBy(() -> writer.write(other)).isInstanceOf(EvidenceIntegrityException.class);
    assertThat(writer.read(first.key())).contains(first);
  }

  @Test
  void aStoredObjectThatIsNotAJournalObjectIsNeverTakenForTheSamePayload() {
    MapStore store = new MapStore();
    JournalObject object = JournalFixtures.object(JournalType.DELIVERY_INTENT);
    storeBytes(
        store,
        object.key().evidenceKey(),
        "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    assertThatThrownBy(() -> writer.write(object)).isInstanceOf(EvidenceIntegrityException.class);
    assertThatThrownBy(() -> writer.read(object.key()))
        .isInstanceOf(EvidenceIntegrityException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("not json"));
  }

  @Test
  void anObjectStoredUnderAnotherKeyThanItsEnvelopeNamesIsAnIntegrityAlarm() {
    MapStore store = new MapStore();
    JournalObject object = JournalFixtures.object(JournalType.DELIVERY_INTENT);
    JournalKey elsewhere = intentKey("43", 1);
    storeBytes(store, elsewhere.evidenceKey(), object.toBytes());
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    assertThatThrownBy(() -> writer.read(elsewhere)).isInstanceOf(EvidenceIntegrityException.class);
    assertThat(writer.read(intentKey("44", 1))).isEmpty();
  }

  @Test
  void anObjectOverTheSizeLimitIsRefusedAtTheWriterAndNothingIsPut() {
    MapStore store = new MapStore();
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    JournalObject over = JournalFixtures.feedbackOfLength(JournalObject.MAX_BYTES + 1L);
    assertThatThrownBy(() -> writer.write(over)).isInstanceOf(IllegalArgumentException.class);
    assertThat(store.puts).isEmpty();
    JournalObject exactly = JournalFixtures.feedbackOfLength(JournalObject.MAX_BYTES);
    assertThat(writer.write(exactly)).isEqualTo(JournalWriter.Outcome.CREATED);
    assertThat(writer.read(exactly.key())).contains(exactly);
  }

  @Test
  void aStoredObjectOverTheSizeLimitIsAnIntegrityAlarmAndNotAnOutOfMemoryError() {
    MapStore store = new MapStore();
    JournalWriter writer = new JournalWriter(store, FIVE_YEARS);
    JournalKey key = JournalFixtures.object(JournalType.DELIVERY_INTENT).key();
    byte[] over = new byte[JournalObject.MAX_BYTES + 1];
    Arrays.fill(over, (byte) ' ');
    storeBytes(store, key.evidenceKey(), over);
    assertThatThrownBy(() -> writer.read(key)).isInstanceOf(EvidenceIntegrityException.class);
  }

  private static void storeBytes(MapStore store, EvidenceKey key, byte[] bytes) {
    store.put(
        new EvidenceStore.PutRequest(
            key,
            Arrays.copyOf(bytes, bytes.length),
            "x",
            new EvidenceStore.Retention(new EvidenceStore.RetentionClass("x"), AT)));
  }
}
