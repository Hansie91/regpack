package dev.regpack.flow.evidence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.ManifestConflictException;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one door to the evidence tier of the flow (D-W11): the object, its manifest row and its
 * retention claim, and a replay that completes what an earlier attempt left and writes nothing new.
 */
class EvidenceWriterTest {
  private static final Regime REGIME = new Regime("demo.tr.s1");
  private static final EvidenceKey KEY = EvidenceKey.of("landing", "abc", "0");
  private static final byte[] BYTES = "synthetic bytes".getBytes(UTF_8);

  private ManualClock clock;
  private InMemoryEvidenceStore store;
  private InMemoryLedgerStore ledger;
  private List<RetentionClaim> claims;
  private EvidenceWriter writer;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(Instant.parse("2026-10-31T12:00:00Z"));
    store = new InMemoryEvidenceStore(clock);
    ledger = new InMemoryLedgerStore(clock);
    claims = new ArrayList<>();
    writer =
        new EvidenceWriter(
            store,
            ledger,
            claim -> {
              claims.add(claim);
              ledger.recordRetention(List.of(claim));
            },
            RetentionPolicy.fixedDays(1826),
            clock,
            REGIME);
  }

  @Test
  void aPutStoresTheObjectItsManifestRowAndItsRetentionClaim() {
    PutOutcome outcome = writer.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH);

    assertThat(outcome).isEqualTo(PutOutcome.CREATED);
    EvidenceStore.StoredObject stored = store.get(KEY).orElseThrow();
    assertThat(stored.bytes()).isEqualTo(BYTES);
    assertThat(stored.mediaType()).isEqualTo("text/csv");
    var row = ledger.manifestRow(KEY).orElseThrow();
    assertThat(row.entry().objectClass()).isEqualTo(ObjectClass.LANDING_BATCH);
    assertThat(row.entry().sha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(row.entry().sizeBytes()).isEqualTo(BYTES.length);
    assertThat(row.provenance()).isEqualTo(Provenance.WRITTEN);
    assertThat(claims).hasSize(1);
    RetentionClaim claim = claims.getFirst();
    assertThat(claim.artifact()).isEqualTo(KEY);
    assertThat(claim.regime()).isEqualTo(REGIME);
    assertThat(claim.anchor()).isEqualTo(AnchorEvent.OBJECT_WRITTEN);
    assertThat(claim.retentionClass()).isEqualTo("landing");
    assertThat(claim.retainUntil())
        .contains(Instant.parse("2026-10-31T12:00:00Z").plus(Duration.ofDays(1826)));
    assertThat(ledger.retentionRows(KEY.path(), Optional.empty(), 10)).hasSize(1);
  }

  @Test
  void aReplayAtALaterInstantWritesNothingNewAndKeepsTheFirstRetention() {
    writer.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH);
    Instant firstRetention = store.get(KEY).orElseThrow().retention().retainUntil();

    clock.advance(Duration.ofHours(1));
    PutOutcome outcome = writer.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH);

    assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(store.list("", Optional.empty(), 10)).hasSize(1);
    assertThat(ledger.manifestRows("", Optional.empty(), 10)).hasSize(1);
    var rows = ledger.retentionRows("", Optional.empty(), 10);
    assertThat(rows).hasSize(1);
    // the claim of the replay is the retention the object has, not a new date
    assertThat(rows.getFirst().claim().retainUntil()).contains(firstRetention);
    assertThat(claims)
        .extracting(RetentionClaim::retainUntil)
        .containsOnly(Optional.of(firstRetention));
  }

  @Test
  void anObjectWithoutItsRowsIsCompletedFromWhatIsStored() {
    // an earlier attempt stored the object and stopped before the manifest row and the claim
    store.put(
        new PutRequest(
            KEY,
            BYTES,
            "text/csv",
            new Retention(new RetentionClass("landing"), Instant.parse("2031-01-01T00:00:00Z"))));
    assertThat(ledger.manifestRow(KEY)).isEmpty();

    PutOutcome outcome = writer.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH);

    assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(ledger.manifestRow(KEY).orElseThrow().entry().sha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(claims.getFirst().retainUntil()).contains(Instant.parse("2031-01-01T00:00:00Z"));
    assertThat(ledger.retentionRows(KEY.path(), Optional.empty(), 10)).hasSize(1);
  }

  @Test
  void otherBytesUnderAStoredKeyNeverFailAndTheRowsFollowTheStoredBytes() {
    writer.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH);

    // D-W11: the stored bytes stay, and nothing throws EvidenceIntegrityException or
    // ManifestConflictException; the caller that needs equality compares first
    PutOutcome outcome =
        writer.put(
            KEY, "other bytes".getBytes(UTF_8), "text/csv", "landing", ObjectClass.LANDING_BATCH);

    assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(BYTES);
    assertThat(ledger.manifestRow(KEY).orElseThrow().entry().sha256()).isEqualTo(Sha256.of(BYTES));
  }

  @Test
  void recordStoredAdoptsAnObjectAnotherWriterPut() {
    // a journal object is put by the journal writer; the manifest row and the claim follow
    store.put(
        new PutRequest(
            KEY,
            BYTES,
            "application/json",
            new Retention(new RetentionClass("journal"), Instant.parse("2031-01-01T00:00:00Z"))));

    writer.recordStored(KEY, ObjectClass.JOURNAL_A);
    writer.recordStored(KEY, ObjectClass.JOURNAL_A);

    assertThat(ledger.manifestRow(KEY).orElseThrow().entry().objectClass())
        .isEqualTo(ObjectClass.JOURNAL_A);
    assertThat(ledger.manifestRows("", Optional.empty(), 10)).hasSize(1);
    assertThat(ledger.retentionRows("", Optional.empty(), 10)).hasSize(1);
    assertThat(claims.getFirst().retentionClass()).isEqualTo("journal");
  }

  private EvidenceWriter writerOver(EvidenceStore tier) {
    return new EvidenceWriter(
        tier,
        ledger,
        claim -> {
          claims.add(claim);
          ledger.recordRetention(List.of(claim));
        },
        RetentionPolicy.fixedDays(1826),
        clock,
        REGIME);
  }

  @Test
  void aReplayPutsTheStoredObjectAgainSoThatAMissingSecondCopyIsCompleted() {
    TwoCopyEvidenceStore tier = new TwoCopyEvidenceStore(clock);
    EvidenceWriter twoCopyWriter = writerOver(tier);
    // the first copy is stored, then the tier cannot write the second: the put fails, no row
    tier.halfWriteNextPut(KEY.path());
    assertThatThrownBy(
            () -> twoCopyWriter.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThat(tier.copies(KEY)).isEqualTo(1);
    assertThat(ledger.manifestRow(KEY)).isEmpty();
    assertThat(claims).isEmpty();
    EvidenceStore.StoredObject first = tier.firstVolume().get(KEY).orElseThrow();

    clock.advance(Duration.ofHours(1));
    PutOutcome outcome =
        twoCopyWriter.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH);

    // the replay acknowledges only after the second copy exists, and it is the first copy's object
    assertThat(outcome).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(tier.copies(KEY)).isEqualTo(2);
    EvidenceStore.StoredObject second = tier.secondVolume().get(KEY).orElseThrow();
    assertThat(second.bytes()).isEqualTo(BYTES);
    assertThat(second.mediaType()).isEqualTo(first.mediaType());
    assertThat(second.retention()).isEqualTo(first.retention());
    assertThat(ledger.manifestRow(KEY).orElseThrow().entry().sha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().retainUntil()).contains(first.retention().retainUntil());
  }

  @Test
  void recordStoredPutsTheStoredObjectAgainBeforeItWritesTheRowAndTheClaim() {
    TwoCopyEvidenceStore tier = new TwoCopyEvidenceStore(clock);
    EvidenceWriter twoCopyWriter = writerOver(tier);
    Retention retention =
        new Retention(new RetentionClass("journal"), Instant.parse("2031-01-01T00:00:00Z"));
    // another writer (the journal writer) put the object; its second copy is missing
    tier.halfWriteNextPut(KEY.path());
    assertThatThrownBy(() -> tier.put(new PutRequest(KEY, BYTES, "application/json", retention)))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThat(tier.copies(KEY)).isEqualTo(1);

    // the tier still cannot complete the copy: nothing is recorded, the call fails
    tier.halfWriteNextPut(KEY.path());
    assertThatThrownBy(() -> twoCopyWriter.recordStored(KEY, ObjectClass.JOURNAL_A))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThat(ledger.manifestRow(KEY)).isEmpty();
    assertThat(claims).isEmpty();

    // the tier is back: the record completes the copy first, then writes the row and the claim
    twoCopyWriter.recordStored(KEY, ObjectClass.JOURNAL_A);

    assertThat(tier.copies(KEY)).isEqualTo(2);
    EvidenceStore.StoredObject second = tier.secondVolume().get(KEY).orElseThrow();
    assertThat(second.bytes()).isEqualTo(BYTES);
    assertThat(second.mediaType()).isEqualTo("application/json");
    assertThat(second.retention()).isEqualTo(retention);
    assertThat(ledger.manifestRow(KEY).orElseThrow().entry().objectClass())
        .isEqualTo(ObjectClass.JOURNAL_A);
    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().retainUntil()).contains(retention.retainUntil());
  }

  @Test
  void recordWrittenRecordsTheRowAndTheClaimAndPutsNothingAgain() {
    TwoCopyEvidenceStore tier = new TwoCopyEvidenceStore(clock);
    EvidenceWriter twoCopyWriter = writerOver(tier);
    Retention retention =
        new Retention(new RetentionClass("journal"), Instant.parse("2031-01-01T00:00:00Z"));
    tier.put(new PutRequest(KEY, BYTES, "application/json", retention));
    // a second put would fail now; recordWritten makes none, recordStored makes one
    tier.halfWriteNextPut(KEY.path());

    twoCopyWriter.recordWritten(KEY, ObjectClass.JOURNAL_A);

    assertThat(ledger.manifestRow(KEY).orElseThrow().entry().sha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().retainUntil()).contains(retention.retainUntil());
    assertThatThrownBy(() -> twoCopyWriter.recordStored(KEY, ObjectClass.JOURNAL_A))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(
            () -> writer.recordWritten(EvidenceKey.of("landing", "none", "0"), ObjectClass.OTHER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("none");
  }

  @Test
  void recordStoredOfAnAbsentObjectFailsWithAFixedText() {
    assertThatThrownBy(() -> writer.recordStored(KEY, ObjectClass.OTHER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("landing");
  }

  @Test
  void aManifestRowOfAnotherHashIsAnAlarmThatPropagates() {
    ledger.recordObjects(
        List.of(new ObjectEntry(KEY, ObjectClass.LANDING_BATCH, Sha256.ofUtf8("another"), 3)),
        Provenance.WRITTEN,
        Optional.empty());

    assertThatThrownBy(
            () -> writer.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH))
        .isInstanceOf(ManifestConflictException.class);
  }

  @Test
  void anOutageOfTheStorePropagatesAndWritesNoRow() {
    store.simulateOutage(true);

    assertThatThrownBy(
            () -> writer.put(KEY, BYTES, "text/csv", "landing", ObjectClass.LANDING_BATCH))
        .isInstanceOf(dev.regpack.ports.evidence.EvidenceUnavailableException.class);
    assertThat(ledger.manifestRows("", Optional.empty(), 10)).isEmpty();
    assertThat(claims).isEmpty();
  }

  @Test
  void theConstructorAndPutRefuseNulls() {
    assertThatThrownBy(
            () ->
                new EvidenceWriter(
                    null, ledger, claims::add, RetentionPolicy.fixedDays(1), clock, REGIME))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> writer.put(null, BYTES, "text/csv", "landing", ObjectClass.OTHER))
        .isInstanceOf(NullPointerException.class);
  }
}
