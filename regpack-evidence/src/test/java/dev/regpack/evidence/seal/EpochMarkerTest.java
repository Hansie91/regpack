package dev.regpack.evidence.seal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.testkit.clock.ManualClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The restore-epoch marker of spec 11.2 and 11.5 B.1: one write-once object per declared epoch, the
 * highest one is current, and nothing but a clean marker is read as an epoch.
 */
class EpochMarkerTest {
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
  private static final RetentionPolicy FIVE_YEARS = RetentionPolicy.fixedDays(1826);

  private ManualClock clock;
  private InMemoryEvidenceStore store;
  private EpochMarker marker;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(START);
    store = new InMemoryEvidenceStore(clock);
    marker = new EpochMarker(store, FIVE_YEARS, clock);
  }

  private void putRaw(long epoch, String text) {
    store.put(
        new EvidenceStore.PutRequest(
            EpochMarker.key(epoch),
            text.getBytes(UTF_8),
            "text/plain",
            FIVE_YEARS.retentionFor("epoch", START)));
  }

  private List<String> keys() {
    List<String> paths = new ArrayList<>();
    store.list("", Optional.empty(), 1_000).forEach(k -> paths.add(k.path()));
    return paths;
  }

  @Test
  void theMarkerKeyIsPinned() {
    assertThat(EpochMarker.key(3).path()).isEqualTo("control/epoch/e000000000003");
    assertThat(EpochMarker.key(0).path()).isEqualTo("control/epoch/e000000000000");
    assertThat(EpochMarker.key(999_999_999_999L).path()).isEqualTo("control/epoch/e999999999999");
    assertThatThrownBy(() -> EpochMarker.key(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EpochMarker.key(1_000_000_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noMarkerMeansEpochZero() {
    assertThat(marker.current()).isZero();
    assertThat(keys()).isEmpty();
  }

  @Test
  void aDeclaredEpochIsCurrentAndItsObjectIsTheJsonOfTheEpochOnly() {
    marker.declare(3);
    assertThat(marker.current()).isEqualTo(3);
    assertThat(keys()).containsExactly("control/epoch/e000000000003");
    EvidenceStore.StoredObject stored = store.get(EpochMarker.key(3)).orElseThrow();
    assertThat(new String(stored.bytes(), UTF_8)).isEqualTo("{\"epoch\":3}");
  }

  @Test
  void theMarkerIsPutWithTheEpochRetentionClassAtTheClockTime() {
    clock.advance(Duration.ofHours(5));
    List<String> classes = new ArrayList<>();
    List<Instant> writtenAt = new ArrayList<>();
    EpochMarker recording =
        new EpochMarker(
            store,
            (retentionClass, at) -> {
              classes.add(retentionClass);
              writtenAt.add(at);
              return FIVE_YEARS.retentionFor(retentionClass, at);
            },
            clock);
    recording.declare(4);
    assertThat(classes).containsExactly("epoch");
    assertThat(writtenAt).containsExactly(START.plus(Duration.ofHours(5)));
    EvidenceStore.StoredObject stored = store.get(EpochMarker.key(4)).orElseThrow();
    assertThat(stored.retention().retentionClass().name()).isEqualTo("epoch");
    assertThat(stored.retention().retainUntil())
        .isEqualTo(START.plus(Duration.ofHours(5)).plus(Duration.ofDays(1826)));
    assertThat(stored.mediaType()).isEqualTo(EpochMarker.MEDIA_TYPE);
  }

  @Test
  void theHighestMarkerWinsAcrossNineAndTen() {
    marker.declare(9);
    assertThat(marker.current()).isEqualTo(9);
    marker.declare(10);
    assertThat(marker.current()).isEqualTo(10);
    // declared out of order and padded: 2 and 100 neither beat 10 nor hide 100
    marker.declare(2);
    assertThat(marker.current()).isEqualTo(10);
    marker.declare(100);
    assertThat(marker.current()).isEqualTo(100);
    assertThat(keys())
        .containsExactly(
            "control/epoch/e000000000002",
            "control/epoch/e000000000009",
            "control/epoch/e000000000010",
            "control/epoch/e000000000100");
  }

  @Test
  void theHighestMarkerIsFoundAcrossManyPages() {
    // more markers than one page of the listing
    for (long epoch = 1; epoch <= 1_203; epoch++) {
      marker.declare(epoch);
    }
    assertThat(marker.current()).isEqualTo(1_203);
  }

  @Test
  void declaringTheSameEpochTwiceIsASuccess() {
    marker.declare(3);
    clock.advance(Duration.ofDays(2));
    marker.declare(3);
    assertThat(marker.current()).isEqualTo(3);
    assertThat(keys()).containsExactly("control/epoch/e000000000003");
    // the first write stands: same bytes, same write time
    assertThat(store.get(EpochMarker.key(3)).orElseThrow().writtenAt()).isEqualTo(START);
  }

  @Test
  void anObjectUnderTheKeyWithOtherBytesIsAnAlarmWhenDeclaring() {
    putRaw(5, "{\"epoch\":6}");
    assertThatThrownBy(() -> marker.declare(5)).isInstanceOf(EvidenceIntegrityException.class);
  }

  @Test
  void aMarkerThatDoesNotParseOrNamesAnotherEpochIsUnreadable() {
    List<String> bad =
        List.of(
            "not json",
            "",
            "{\"epoch\":6}", // names another epoch than its key
            "{\"epoch\":5,\"x\":1}", // another field
            "{\"epoch\":\"5\"}", // text, not a number
            "{\"epoch\":5.0}", // not an integer
            "{\"epoch\":-5}",
            " {\"epoch\":5}", // not the canonical text
            "{\"epoch\":5}\n",
            "{\"epoch\": 5}",
            "[5]",
            "{}");
    for (String text : bad) {
      InMemoryEvidenceStore fresh = new InMemoryEvidenceStore(clock);
      EpochMarker reader = new EpochMarker(fresh, FIVE_YEARS, clock);
      fresh.put(
          new EvidenceStore.PutRequest(
              EpochMarker.key(5),
              text.getBytes(UTF_8),
              "text/plain",
              FIVE_YEARS.retentionFor("epoch", START)));
      Throwable thrown = catchThrowable(reader::current);
      assertThat(thrown).as("marker text <" + text + ">").isInstanceOf(IllegalStateException.class);
      if (!text.isEmpty()) {
        // the message never repeats what was stored
        assertThat(thrown.getMessage()).doesNotContain(text);
      }
    }
  }

  @Test
  void aKeyUnderTheMarkerPrefixThatIsNoMarkerKeyIsUnreadable() {
    for (String path :
        List.of(
            "control/epoch/other",
            "control/epoch/e2",
            "control/epoch/e00000000000a",
            "control/epoch/e000000000002.tmp",
            "control/epoch/e000000000002/x")) {
      InMemoryEvidenceStore fresh = new InMemoryEvidenceStore(clock);
      EpochMarker reader = new EpochMarker(fresh, FIVE_YEARS, clock);
      reader.declare(2);
      fresh.put(
          new EvidenceStore.PutRequest(
              new EvidenceKey(path),
              "{\"epoch\":2}".getBytes(UTF_8),
              "text/plain",
              FIVE_YEARS.retentionFor("epoch", START)));
      assertThatThrownBy(reader::current).as(path).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void aKeyOutsideThePrefixIsNotAMarker() {
    store.put(
        new EvidenceStore.PutRequest(
            new EvidenceKey("control/epochs/e000000000009"),
            "x".getBytes(UTF_8),
            "text/plain",
            FIVE_YEARS.retentionFor("epoch", START)));
    store.put(
        new EvidenceStore.PutRequest(
            new EvidenceKey("control/zzz"),
            "x".getBytes(UTF_8),
            "text/plain",
            FIVE_YEARS.retentionFor("epoch", START)));
    marker.declare(1);
    assertThat(marker.current()).isEqualTo(1);
  }

  @Test
  void anOutageIsNotMistakenForNoMarker() {
    marker.declare(4);
    store.simulateOutage(true);
    assertThatThrownBy(marker::current).isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> marker.declare(5)).isInstanceOf(EvidenceUnavailableException.class);
    store.simulateOutage(false);
    assertThat(marker.current()).isEqualTo(4);
  }

  /**
   * A store that lists what the delegate lists and answers every read of a marker with "no such
   * object": the marker is listed on one volume and gone when it is read.
   */
  static EvidenceStore storeThatLosesTheMarkersWhenTheyAreRead(EvidenceStore delegate) {
    return (EvidenceStore)
        Proxy.newProxyInstance(
            EvidenceStore.class.getClassLoader(),
            new Class<?>[] {EvidenceStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("get")
                  && ((EvidenceKey) args[0]).path().startsWith("control/epoch/")) {
                return Optional.empty();
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @Test
  void aMarkerThatIsListedButGoneWhenItIsReadIsUnreadable() {
    marker.declare(5);
    EpochMarker reader =
        new EpochMarker(storeThatLosesTheMarkersWhenTheyAreRead(store), FIVE_YEARS, clock);

    // not "no marker, epoch 0": a sender at epoch 0 would be let through by that mistake
    assertThat(marker.current()).isEqualTo(5);
    Throwable thrown = catchThrowable(reader::current);

    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("a listed marker is missing")
        .hasMessageContaining(EpochMarker.key(5).path());
  }

  @Test
  void nullsAreRefused() {
    assertThatThrownBy(() -> new EpochMarker(null, FIVE_YEARS, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EpochMarker(store, null, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EpochMarker(store, FIVE_YEARS, null))
        .isInstanceOf(NullPointerException.class);
  }
}
