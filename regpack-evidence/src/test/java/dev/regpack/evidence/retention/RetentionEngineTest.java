package dev.regpack.evidence.retention;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.testkit.clock.ManualClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T9, KF11 and KF12: the engine records a claim and then extends the stored object to the
 * latest date of all rows of the artifact. It never shortens, never leaves the port (extend and
 * hold only), refuses epoch markers, keeps a row when the store fails, and lets an alarm of the
 * store through.
 */
class RetentionEngineTest {
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
  private static final Regime MIFIR = new Regime("mifir.eu.rts22");
  private static final Regime EMIR = new Regime("emir.eu.refit");
  private static final EvidenceKey SEALED = EvidenceKey.of("sealed", "ab", "abcdef");
  private static final Instant Y2031 = Instant.parse("2031-10-20T08:00:00Z");
  private static final Instant Y2032 = Instant.parse("2032-10-20T08:00:00Z");
  private static final Instant Y2035 = Instant.parse("2035-10-20T08:00:00Z");

  private ManualClock clock;
  private InMemoryLedgerStore rows;
  private InMemoryEvidenceStore store;
  private final List<String> calls = new ArrayList<>();
  private EvidenceStore spy;
  private RetentionEngine engine;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(START);
    rows = new InMemoryLedgerStore(clock);
    store = new InMemoryEvidenceStore(clock);
    spy = spying(store, calls);
    engine = new RetentionEngine(rows, spy, MIFIR);
  }

  private void put(EvidenceKey key, Instant retainUntil) {
    store.put(
        new EvidenceStore.PutRequest(
            key,
            ("bytes of " + key.path()).getBytes(UTF_8),
            "application/octet-stream",
            new Retention(new RetentionClass("sealed"), retainUntil)));
  }

  private static RetentionClaim claim(
      EvidenceKey key, Regime regime, AnchorEvent anchor, Optional<Instant> until) {
    return new RetentionClaim(key, regime, anchor, until, "sealed");
  }

  private static RetentionClaim written(EvidenceKey key, Regime regime, Instant until) {
    return claim(key, regime, AnchorEvent.OBJECT_WRITTEN, Optional.of(until));
  }

  private Instant retainedUntil(EvidenceKey key) {
    return store.get(key).orElseThrow().retention().retainUntil();
  }

  private List<RetentionRow> rowsOf(EvidenceKey key) {
    return rows.retentionRows(key.path(), Optional.empty(), 100).stream()
        .filter(row -> row.claim().artifact().equals(key))
        .toList();
  }

  /** An evidence store that delegates and writes the names of the methods called down. */
  private static EvidenceStore spying(EvidenceStore delegate, List<String> calls) {
    return (EvidenceStore)
        Proxy.newProxyInstance(
            EvidenceStore.class.getClassLoader(),
            new Class<?>[] {EvidenceStore.class},
            (proxy, method, args) -> {
              calls.add(method.getName());
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @Test
  void aClaimIsRecordedAndTheStoreExtendedToTheLatestRow() {
    put(SEALED, Y2031);

    engine.register(written(SEALED, MIFIR, Y2035));

    List<RetentionRow> found = rowsOf(SEALED);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).claim().regime()).isEqualTo(MIFIR);
    assertThat(found.get(0).claim().anchor()).isEqualTo(AnchorEvent.OBJECT_WRITTEN);
    assertThat(found.get(0).claim().retainUntil()).contains(Y2035);
    assertThat(found.get(0).legalHold()).isFalse();
    assertThat(retainedUntil(SEALED)).isEqualTo(Y2035);
  }

  @Test
  void aSealedObjectIsRegisteredWithTheRetentionOfItsPutForTheRegimeOfTheEngine() {
    put(SEALED, Y2031);

    engine.sealedObject(SEALED, new Retention(new RetentionClass("sealed"), Y2032));

    List<RetentionRow> found = rowsOf(SEALED);
    assertThat(found).hasSize(1);
    RetentionClaim claim = found.get(0).claim();
    assertThat(claim.regime()).isEqualTo(MIFIR);
    assertThat(claim.anchor()).isEqualTo(AnchorEvent.OBJECT_WRITTEN);
    assertThat(claim.retentionClass()).isEqualTo("sealed");
    assertThat(claim.retainUntil()).contains(Y2032);
    assertThat(retainedUntil(SEALED)).isEqualTo(Y2032);
  }

  @Test
  void anEarlierClaimNeverShortensTheStore() {
    put(SEALED, Y2031);
    engine.register(written(SEALED, MIFIR, Y2035));

    // the same row again with an earlier date: the row keeps the later one, the store is not
    // shortened and the refusal of the store is taken as satisfied
    engine.register(written(SEALED, MIFIR, Y2032));

    assertThat(rowsOf(SEALED)).hasSize(1);
    assertThat(rowsOf(SEALED).get(0).claim().retainUntil()).contains(Y2035);
    assertThat(retainedUntil(SEALED)).isEqualTo(Y2035);
  }

  @Test
  void aStoreThatAlreadyRetainsLongerThanEveryRowIsLeftAloneWithoutAnError() {
    // the object was put with a longer retention than any claim says: the store refuses the
    // "extension" to the earlier date, and that refusal is taken as satisfied
    put(SEALED, Y2035);

    engine.register(written(SEALED, MIFIR, Y2031));

    assertThat(rowsOf(SEALED).get(0).claim().retainUntil()).contains(Y2031);
    assertThat(retainedUntil(SEALED)).isEqualTo(Y2035);
    assertThat(calls).as("the extension was tried").containsExactly("extendRetention");
  }

  @Test
  void twoRegimesGiveTwoRowsAndTheStoreHoldsTheMaximum() {
    put(SEALED, Y2031);
    RetentionEngine emir = new RetentionEngine(rows, spy, EMIR);

    engine.sealedObject(SEALED, new Retention(new RetentionClass("sealed"), Y2035));
    emir.sealedObject(SEALED, new Retention(new RetentionClass("sealed"), Y2032));

    List<RetentionRow> found = rowsOf(SEALED);
    assertThat(found).extracting(row -> row.claim().regime()).containsExactly(EMIR, MIFIR);
    assertThat(found)
        .extracting(row -> row.claim().retainUntil().orElseThrow())
        .containsExactly(Y2032, Y2035);
    assertThat(retainedUntil(SEALED)).as("the later regime came first").isEqualTo(Y2035);

    // and the other order: the later regime second extends to the maximum
    EvidenceKey other = EvidenceKey.of("sealed", "cd", "cdef01");
    put(other, Y2031);
    emir.sealedObject(other, new Retention(new RetentionClass("sealed"), Y2032));
    engine.sealedObject(other, new Retention(new RetentionClass("sealed"), Y2035));
    assertThat(retainedUntil(other)).isEqualTo(Y2035);
  }

  @Test
  void aControlKeyIsRefused() {
    EvidenceKey marker = EvidenceKey.of("control", "epoch", "e000000000001");
    put(marker, Y2031);

    assertThatThrownBy(() -> engine.register(written(marker, MIFIR, Y2035)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("control");

    assertThat(rows.retentionRows("", Optional.empty(), 100)).isEmpty();
    assertThat(calls).as("nothing reached the store").isEmpty();
    assertThat(retainedUntil(marker)).isEqualTo(Y2031);
    assertThatThrownBy(
            () -> engine.sealedObject(marker, new Retention(new RetentionClass("epoch"), Y2035)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rows.retentionRows("", Optional.empty(), 100)).isEmpty();
  }

  @Test
  void holdSetsRowsAndStore() {
    put(SEALED, Y2031);
    engine.register(written(SEALED, MIFIR, Y2032));
    engine.register(written(SEALED, EMIR, Y2035));

    engine.hold(SEALED, true);

    assertThat(rowsOf(SEALED)).hasSize(2).allMatch(RetentionRow::legalHold);
    assertThat(store.get(SEALED).orElseThrow().legalHold()).isTrue();

    // a claim recorded after the hold is held too, and the hold of the store is not lost (KF11)
    engine.register(
        claim(SEALED, new Regime("sftr.eu.refit"), AnchorEvent.TERMINATION, Optional.empty()));
    assertThat(rowsOf(SEALED)).hasSize(3).allMatch(RetentionRow::legalHold);
    assertThat(store.get(SEALED).orElseThrow().legalHold()).isTrue();

    engine.hold(SEALED, false);

    assertThat(rowsOf(SEALED)).hasSize(3).noneMatch(RetentionRow::legalHold);
    assertThat(store.get(SEALED).orElseThrow().legalHold()).isFalse();
  }

  @Test
  void aHoldOnAnArtifactWithoutARowIsAnArgumentErrorAndTouchesNoStore() {
    put(SEALED, Y2031);

    assertThatThrownBy(() -> engine.hold(SEALED, true))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(calls).as("the rows are set first, and there are none").isEmpty();
    assertThat(store.get(SEALED).orElseThrow().legalHold()).isFalse();
  }

  @Test
  void aMissingObjectIsAnArgumentError() {
    assertThatThrownBy(() -> engine.register(written(SEALED, MIFIR, Y2035)))
        .isInstanceOf(IllegalArgumentException.class);

    // the order is "record, then extend" whatever the store says, so the row stays
    assertThat(rowsOf(SEALED)).hasSize(1);
    assertThat(calls).containsExactly("extendRetention");
  }

  @Test
  void anOpenAnchorExtendsNothing() {
    put(SEALED, Y2031);

    engine.register(claim(SEALED, EMIR, AnchorEvent.TERMINATION, Optional.empty()));

    List<RetentionRow> found = rowsOf(SEALED);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).claim().retainUntil()).isEmpty();
    assertThat(retainedUntil(SEALED)).isEqualTo(Y2031);
    assertThat(calls).as("no date, no call").isEmpty();
  }

  @Test
  void anOpenAnchorNextToADatedRowKeepsTheStoreAtTheDate() {
    put(SEALED, Y2031);
    engine.register(written(SEALED, EMIR, Y2032));

    engine.register(claim(SEALED, EMIR, AnchorEvent.TERMINATION, Optional.empty()));

    assertThat(rowsOf(SEALED)).hasSize(2);
    assertThat(retainedUntil(SEALED)).isEqualTo(Y2032);
  }

  @Test
  void aStoreOutageLeavesTheRowAndThrows() {
    put(SEALED, Y2031);
    store.simulateOutage(true);

    assertThatThrownBy(() -> engine.register(written(SEALED, MIFIR, Y2035)))
        .isInstanceOf(EvidenceUnavailableException.class);

    store.simulateOutage(false);
    assertThat(rowsOf(SEALED)).as("the row was written first").hasSize(1);
    assertThat(retainedUntil(SEALED)).as("the store was not extended").isEqualTo(Y2031);

    // the retry of the same claim completes the extension
    engine.register(written(SEALED, MIFIR, Y2035));
    assertThat(rowsOf(SEALED)).hasSize(1);
    assertThat(retainedUntil(SEALED)).isEqualTo(Y2035);
  }

  @Test
  void aRetryWithAnEarlierClaimRepairsAStoreThatLagsBehindTheRows() {
    put(SEALED, Y2031);
    // a row that a crash left ahead of the store: recorded, never extended
    rows.recordRetention(List.of(written(SEALED, EMIR, Y2035)));

    engine.register(written(SEALED, MIFIR, Y2032));

    assertThat(retainedUntil(SEALED))
        .as("the maximum over all rows of the artifact, not the date of the new claim")
        .isEqualTo(Y2035);
  }

  @Test
  void aKeyThatOnlySharesAPrefixIsNotMixedUp() {
    EvidenceKey shorter = EvidenceKey.of("sealed", "ab", "abc");
    EvidenceKey longer = EvidenceKey.of("sealed", "ab", "abcdef");
    put(shorter, Y2031);
    put(longer, Y2031);
    engine.register(written(longer, EMIR, Y2035));

    engine.register(written(shorter, MIFIR, Y2032));

    assertThat(retainedUntil(shorter)).as("its own rows only").isEqualTo(Y2032);
    assertThat(retainedUntil(longer)).isEqualTo(Y2035);
  }

  @Test
  void aLedgerThatAnswersWithRowsOfAnotherArtifactDoesNotMoveTheStore() {
    put(SEALED, Y2031);
    EvidenceKey other = EvidenceKey.of("sealed", "ab", "abcdef01");
    RetentionRow foreign =
        new RetentionRow(
            written(other, EMIR, Y2035), false, START); // a row that is not the artifact's
    RetentionLedger lying =
        (RetentionLedger)
            Proxy.newProxyInstance(
                RetentionLedger.class.getClassLoader(),
                new Class<?>[] {RetentionLedger.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("retentionRows")) {
                    return List.of(foreign);
                  }
                  try {
                    return method.invoke(rows, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });

    new RetentionEngine(lying, spy, MIFIR).register(written(SEALED, MIFIR, Y2032));

    assertThat(retainedUntil(SEALED)).as("the date of its own claim").isEqualTo(Y2032);
  }

  @Test
  void theEngineCallsOnlyExtendRetentionAndSetLegalHold() {
    put(SEALED, Y2031);

    engine.sealedObject(SEALED, new Retention(new RetentionClass("sealed"), Y2032));
    engine.register(written(SEALED, EMIR, Y2035));
    engine.register(claim(SEALED, EMIR, AnchorEvent.TERMINATION, Optional.empty()));
    engine.hold(SEALED, true);
    engine.hold(SEALED, false);

    Set<String> names = new TreeSet<>(calls);
    assertThat(names)
        .as("no put, get, list: the engine neither reads nor writes an object")
        .containsExactlyInAnyOrder("extendRetention", "setLegalHold");
    assertThat(calls).isNotEmpty();
  }

  @Test
  void anAlarmOfTheStoreIsNotSwallowed() {
    put(SEALED, Y2031);
    EvidenceStore damaged =
        (EvidenceStore)
            Proxy.newProxyInstance(
                EvidenceStore.class.getClassLoader(),
                new Class<?>[] {EvidenceStore.class},
                (proxy, method, args) -> {
                  throw new EvidenceCorruptionException("the retention record is damaged");
                });
    RetentionEngine broken = new RetentionEngine(rows, damaged, MIFIR);

    assertThatThrownBy(() -> broken.register(written(SEALED, MIFIR, Y2035)))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> broken.hold(SEALED, true))
        .isInstanceOf(EvidenceCorruptionException.class);

    assertThat(rowsOf(SEALED)).as("the row of the claim stays").hasSize(1);
  }

  @Test
  void nullArgumentsAreRefused() {
    assertThatThrownBy(() -> new RetentionEngine(null, spy, MIFIR))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetentionEngine(rows, null, MIFIR))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetentionEngine(rows, spy, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.register(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> engine.sealedObject(null, new Retention(new RetentionClass("sealed"), Y2032)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.sealedObject(SEALED, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.hold(null, true)).isInstanceOf(NullPointerException.class);
  }
}
