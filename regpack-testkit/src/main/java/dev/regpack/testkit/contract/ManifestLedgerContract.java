package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.objectEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestConflictException;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link LedgerStore} adapter must satisfy for the object manifest (spec 5.4, 11.1;
 * P3b1 D-E6, decisions D1 to D5 of the V002 document): one write-time hash per object key for ever,
 * the first row wins, a conflict stores nothing of its call, and keys are listed in byte order. The
 * rows are written by {@code recordObjects} here and by {@code beginSending} in {@link
 * LeaseDeliveryLedgerContract}.
 */
public interface ManifestLedgerContract {
  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  /** The clock the ledger reads. */
  ManualClock clock();

  private List<ManifestRow> everyRow() {
    return ledger().manifestRows("", Optional.empty(), 1_000);
  }

  @Test
  default void aRecordedEntryIsReadBackWithItsWriteTimeHashAndTheLedgerTime() {
    clock().advance(Duration.ofSeconds(90));
    Instant writtenAt = clock().now();
    ObjectEntry entry =
        objectEntry("sealed/ab/" + "c".repeat(64), ObjectClass.SEALED_FILE, "bytes-1", 1_234);
    ledger().recordObjects(List.of(entry), Provenance.WRITTEN, Optional.of(new AttemptId(7)));
    clock().advance(Duration.ofHours(1));

    assertThat(ledger().manifestRow(entry.key()))
        .contains(
            new ManifestRow(entry, Provenance.WRITTEN, Optional.of(new AttemptId(7)), writtenAt));
    assertThat(ledger().manifestRow(new EvidenceKey("sealed/ab/other"))).isEmpty();

    // every class of object is stored and read back as it was given
    List<ObjectEntry> all = new ArrayList<>();
    for (ObjectClass objectClass : ObjectClass.values()) {
      all.add(objectEntry("classes/" + objectClass.name(), objectClass, "h-" + objectClass, 0));
    }
    ledger().recordObjects(all, Provenance.ADOPTED_AT_RESTORE, Optional.empty());
    for (ObjectEntry each : all) {
      ManifestRow row = ledger().manifestRow(each.key()).orElseThrow();
      assertThat(row.entry()).isEqualTo(each);
      assertThat(row.provenance()).isEqualTo(Provenance.ADOPTED_AT_RESTORE);
      assertThat(row.attempt()).isEmpty();
      assertThat(row.writtenAt()).isEqualTo(clock().now());
    }
  }

  @Test
  default void recordingTheSameKeyAndHashAgainChangesNothing() {
    Instant first = clock().now();
    ObjectEntry entry = objectEntry("sealed/aa/same", ObjectClass.SEALED_FILE, "bytes", 10);
    ledger().recordObjects(List.of(entry), Provenance.WRITTEN, Optional.of(new AttemptId(1)));
    ManifestRow stored = ledger().manifestRow(entry.key()).orElseThrow();

    clock().advance(Duration.ofHours(1));
    // the same key and hash with another provenance, attempt, class and size is still "the same"
    ObjectEntry again = objectEntry("sealed/aa/same", ObjectClass.OTHER, "bytes", 99);
    ledger().recordObjects(List.of(again), Provenance.ADOPTED_AT_RESTORE, Optional.empty());

    assertThat(stored.writtenAt()).isEqualTo(first);
    assertThat(ledger().manifestRow(entry.key())).contains(stored);
    assertThat(everyRow()).containsExactly(stored);
  }

  /**
   * The second call is two days after the first: a table cut by day cannot see the key of the first
   * call, a table with the key as its primary key can.
   */
  @Test
  default void recordingTheSameKeyWithAnotherHashIsAConflictAndStoresNothingOfTheCall() {
    ObjectEntry stored = objectEntry("sealed/bb/key", ObjectClass.SEALED_FILE, "bytes-A", 10);
    ledger().recordObjects(List.of(stored), Provenance.WRITTEN, Optional.of(new AttemptId(3)));
    ManifestRow before = ledger().manifestRow(stored.key()).orElseThrow();
    clock().advance(Duration.ofDays(2));

    ObjectEntry other = objectEntry("sealed/bb/key", ObjectClass.SEALED_FILE, "bytes-B", 10);
    ObjectEntry fresh = objectEntry("sealed/bb/fresh", ObjectClass.SEALED_FILE, "bytes-C", 10);
    ObjectEntry later = objectEntry("sealed/bb/later", ObjectClass.SEALED_FILE, "bytes-D", 10);
    for (List<ObjectEntry> call : List.of(List.of(fresh, other), List.of(other, later))) {
      assertThatThrownBy(
              () -> ledger().recordObjects(call, Provenance.WRITTEN, Optional.of(new AttemptId(4))))
          .isInstanceOf(ManifestConflictException.class)
          .message()
          .contains(stored.key().path())
          .doesNotContain(stored.sha256().hex())
          .doesNotContain(other.sha256().hex());
      assertThat(ledger().manifestRow(fresh.key())).isEmpty();
      assertThat(ledger().manifestRow(later.key())).isEmpty();
      assertThat(everyRow()).containsExactly(before);
    }
  }

  @Test
  default void anEmptyListIsANoop() {
    ledger().recordObjects(List.of(), Provenance.WRITTEN, Optional.empty());
    assertThat(everyRow()).isEmpty();
  }

  @Test
  default void duplicateKeysInOneCallAndNegativeSizesAreRejectedBeforeAnyWrite() {
    assertThatThrownBy(() -> objectEntry("neg/key", ObjectClass.OTHER, "x", -1))
        .isInstanceOf(IllegalArgumentException.class);

    ObjectEntry one = objectEntry("dup/one", "one");
    ObjectEntry two = objectEntry("dup/two", "two");
    ObjectEntry oneAgain = objectEntry("dup/one", "one");
    ObjectEntry oneOther = objectEntry("dup/one", "another hash");
    for (List<ObjectEntry> call :
        List.of(List.of(one, two, oneAgain), List.of(two, one, oneOther))) {
      assertThatThrownBy(() -> ledger().recordObjects(call, Provenance.WRITTEN, Optional.empty()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(everyRow()).isEmpty();
    }
  }

  /**
   * The keys differ in case, in the punctuation of the alphabet and in digit against letter, so
   * that a collation of the store other than byte order puts them in another order (D4). Two keys
   * sit at the top of the alphabet inside the prefix ({@code ord/z}, {@code ord/zz}: a range that
   * ends at the largest letter instead of above it loses them), one holds the prefix twice ({@code
   * ord/ord/a}) and one holds it in the middle without starting with it ({@code x/ord/a}: a match
   * by "contains" lists it).
   *
   * <p>Note for a real server: this method tells byte order from another collation only when the
   * default collation of the cluster is not {@code C} (on a cluster whose default is {@code C} or
   * {@code C.UTF-8} a missing {@code COLLATE "C"} on the column cannot be seen here); the column
   * itself is checked in the catalogue by the tests of the adapter.
   */
  @Test
  default void rowsAreListedInKeyOrderWithPrefixAfterAndLimit() {
    List<String> inByteOrder =
        List.of(
            "ord/B",
            "ord/a+",
            "ord/a,",
            "ord/a-",
            "ord/a.",
            "ord/a/x",
            "ord/a0",
            "ord/a=",
            "ord/aA",
            "ord/a_",
            "ord/aa",
            "ord/b",
            "ord/ord/a",
            "ord/z",
            "ord/zz");
    // stored in an order that is none of the sorted ones
    List<String> shuffled = new ArrayList<>(inByteOrder);
    Collections.rotate(shuffled, 5);
    Collections.swap(shuffled, 0, 7);
    List<ObjectEntry> entries = new ArrayList<>();
    for (String path : shuffled) {
      entries.add(objectEntry(path, "h-" + path));
    }
    // keys outside the prefix, around it
    entries.add(objectEntry("ord", "h-ord"));
    entries.add(objectEntry("orc/z", "h-orc"));
    entries.add(objectEntry("ore/a", "h-ore"));
    entries.add(objectEntry("ord.", "h-ord."));
    // the prefix inside another key, and a key above every key of the prefix
    entries.add(objectEntry("x/ord/a", "h-x"));
    ledger().recordObjects(entries, Provenance.WRITTEN, Optional.empty());

    assertThat(paths(ledger().manifestRows("ord/", Optional.empty(), 100)))
        .containsExactlyElementsOf(inByteOrder);
    // a prefix need not end at a segment boundary
    assertThat(paths(ledger().manifestRows("ord/a", Optional.empty(), 100)))
        .containsExactlyElementsOf(inByteOrder.subList(1, 11));
    // the top of the alphabet: the keys of the prefix 'ord/z' are the last two of the prefix
    assertThat(paths(ledger().manifestRows("ord/z", Optional.empty(), 100)))
        .containsExactly("ord/z", "ord/zz");
    assertThat(paths(ledger().manifestRows("ord/zz", Optional.empty(), 100)))
        .containsExactly("ord/zz");
    // 'ord/ord/' starts one key; 'x/ord/a' holds 'ord/' in its middle and matches only 'x/'
    assertThat(paths(ledger().manifestRows("ord/ord/", Optional.empty(), 100)))
        .containsExactly("ord/ord/a");
    assertThat(paths(ledger().manifestRows("x/", Optional.empty(), 100)))
        .containsExactly("x/ord/a");
    // the whole ledger, with the keys outside the prefix in their places
    assertThat(paths(everyRow()))
        .containsExactly(
            "orc/z",
            "ord",
            "ord.",
            "ord/B",
            "ord/a+",
            "ord/a,",
            "ord/a-",
            "ord/a.",
            "ord/a/x",
            "ord/a0",
            "ord/a=",
            "ord/aA",
            "ord/a_",
            "ord/aa",
            "ord/b",
            "ord/ord/a",
            "ord/z",
            "ord/zz",
            "ore/a",
            "x/ord/a");

    // after: exclusive, and the key need not exist
    assertThat(paths(ledger().manifestRows("ord/", Optional.of(new EvidenceKey("ord/a.")), 100)))
        .containsExactlyElementsOf(inByteOrder.subList(5, inByteOrder.size()));
    assertThat(paths(ledger().manifestRows("ord/", Optional.of(new EvidenceKey("ord/a.5")), 100)))
        .containsExactlyElementsOf(inByteOrder.subList(5, inByteOrder.size()));
    assertThat(paths(ledger().manifestRows("ord/", Optional.of(new EvidenceKey("ord/b")), 100)))
        .containsExactly("ord/ord/a", "ord/z", "ord/zz");
    assertThat(ledger().manifestRows("ord/", Optional.of(new EvidenceKey("ord/zz")), 100))
        .isEmpty();
    assertThat(ledger().manifestRows("ord/", Optional.of(new EvidenceKey("zzz")), 100)).isEmpty();

    // limit
    assertThat(paths(ledger().manifestRows("ord/", Optional.empty(), 3)))
        .containsExactlyElementsOf(inByteOrder.subList(0, 3));
    assertThat(ledger().manifestRows("ord/", Optional.empty(), 0)).isEmpty();
    assertThatThrownBy(() -> ledger().manifestRows("ord/", Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class);

    // pages of five walk every key once
    List<String> walked = new ArrayList<>();
    Optional<EvidenceKey> after = Optional.empty();
    while (true) {
      List<ManifestRow> page = ledger().manifestRows("ord/", after, 5);
      walked.addAll(paths(page));
      if (page.size() < 5) {
        break;
      }
      after = Optional.of(page.get(page.size() - 1).entry().key());
    }
    assertThat(walked).containsExactlyElementsOf(inByteOrder);
  }

  @Test
  default void aLimitAboveTheCapReturnsAtMostOneThousandRows() {
    List<ObjectEntry> entries = new ArrayList<>();
    for (int i = 0; i < 1_100; i++) {
      entries.add(objectEntry(String.format(Locale.ROOT, "cap/%05d", i), "h" + i));
    }
    ledger().recordObjects(entries, Provenance.WRITTEN, Optional.empty());

    List<ManifestRow> page = ledger().manifestRows("cap/", Optional.empty(), 5_000);
    assertThat(page).hasSize(1_000);
    assertThat(ledger().manifestRows("cap/", Optional.empty(), Integer.MAX_VALUE)).hasSize(1_000);
    assertThat(page.get(0).entry().key().path()).isEqualTo("cap/00000");
    assertThat(page.get(999).entry().key().path()).isEqualTo("cap/00999");
    List<ManifestRow> rest =
        ledger().manifestRows("cap/", Optional.of(page.get(999).entry().key()), 5_000);
    assertThat(paths(rest)).hasSize(100).first().isEqualTo("cap/01000");
  }

  @Test
  default void adoptedAtRestoreProvenanceIsKeptAndALaterWrittenRecordDoesNotChangeIt() {
    ObjectEntry entry = objectEntry("sealed/dd/adopted", ObjectClass.JOURNAL_A, "bytes", 5);
    Instant first = clock().now();
    ledger().recordObjects(List.of(entry), Provenance.ADOPTED_AT_RESTORE, Optional.empty());
    clock().advance(Duration.ofMinutes(5));
    ledger().recordObjects(List.of(entry), Provenance.WRITTEN, Optional.of(new AttemptId(9)));

    assertThat(ledger().manifestRow(entry.key()))
        .contains(new ManifestRow(entry, Provenance.ADOPTED_AT_RESTORE, Optional.empty(), first));
  }

  /** D3: there is no foreign key from the manifest to the attempts. */
  @Test
  default void aManifestRowMayNameAnAttemptThatHasNoAttemptRow() {
    AttemptId ghost = new AttemptId(987_654);
    ObjectEntry entry =
        objectEntry("journal/ghost/DeliveryIntent/1", ObjectClass.JOURNAL_A, "i", 1);
    ledger().recordObjects(List.of(entry), Provenance.ADOPTED_AT_RESTORE, Optional.of(ghost));

    assertThat(ledger().attempt(ghost)).isEmpty();
    assertThat(ledger().manifestRow(entry.key()).orElseThrow().attempt()).contains(ghost);
  }

  /** EF22: the longest key and the largest size that the model accepts are stored. */
  @Test
  default void aKeyOfFiveHundredTwelveCharactersAndASizeOfOneTerabyteAreStored() {
    EvidenceKey longest = LedgerFixtures.maximumLengthKey();
    assertThat(longest.path()).hasSize(512);
    ObjectEntry entry =
        new ObjectEntry(
            longest,
            ObjectClass.ENRICHMENT_RESPONSE,
            objectEntry("x", "big").sha256(),
            1_000_000_000_000L);
    ledger().recordObjects(List.of(entry), Provenance.WRITTEN, Optional.empty());

    assertThat(ledger().manifestRow(longest).orElseThrow().entry()).isEqualTo(entry);
    assertThat(ledger().manifestRows(longest.path(), Optional.empty(), 10)).hasSize(1);
    // the same key with another hash is still a conflict at this length
    ObjectEntry other =
        new ObjectEntry(longest, ObjectClass.OTHER, objectEntry("x", "other").sha256(), 1);
    assertThatThrownBy(
            () -> ledger().recordObjects(List.of(other), Provenance.WRITTEN, Optional.empty()))
        .isInstanceOf(ManifestConflictException.class);
  }

  /**
   * No key can contain U+0000 or a character outside the alphabet of an {@link EvidenceKey}, so a
   * prefix that has one lists nothing, with any {@code after} and any limit (EF22, EF23: a server
   * cannot even hold a NUL in a text, and the adapter answers the empty list for it without asking;
   * the in-memory adapter must say the same). Keys that are close to such a prefix exist.
   */
  @Test
  default void aPrefixThatNoKeyCanHaveListsNothing() {
    ledger()
        .recordObjects(
            List.of(
                objectEntry("a", "h-a"),
                objectEntry("a/b", "h-ab"),
                objectEntry("ord/a", "h-ord-a"),
                objectEntry("ord/e", "h-ord-e")),
            Provenance.WRITTEN,
            Optional.empty());
    char nul = (char) 0;
    char eAcute = (char) 0x00E9;
    char euro = (char) 0x20AC;
    List<String> impossible =
        List.of(
            "a" + nul,
            String.valueOf(nul),
            "a/" + nul + "b",
            "ord/" + nul,
            "ord/" + eAcute,
            "ord/e" + eAcute,
            "ord/" + euro,
            String.valueOf(eAcute),
            "a b");
    for (String prefix : impossible) {
      assertThat(ledger().manifestRows(prefix, Optional.empty(), 100))
          .as("prefix with code points %s", prefix.chars().boxed().toList())
          .isEmpty();
      assertThat(ledger().manifestRows(prefix, Optional.of(new EvidenceKey("a")), 100)).isEmpty();
      assertThat(ledger().manifestRows(prefix, Optional.empty(), 1)).isEmpty();
    }
    // the neighbours of those prefixes are still listed
    assertThat(paths(ledger().manifestRows("a", Optional.empty(), 100)))
        .containsExactly("a", "a/b");
    assertThat(paths(ledger().manifestRows("ord/", Optional.empty(), 100)))
        .containsExactly("ord/a", "ord/e");
  }

  /**
   * Two units record the same key with different hashes at the same moment, round after round on
   * fresh keys, each with a second key of its own. Exactly one of them stores the shared key, the
   * other gets a {@link ManifestConflictException}, the second key of the loser is not stored (its
   * call stored nothing), the stored hash is the winner's, and no other row exists. On a real
   * server this is the race of two transactions on one primary key: the second insert waits for the
   * first, skips when it commits, and the read that follows (a new statement of a READ COMMITTED
   * transaction) sees the winner's row.
   */
  @Test
  default void concurrentRecordsOfOneKeyWithDifferentHashesLeaveOneRowAndOneConflict()
      throws Exception {
    int rounds = 25;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < rounds; round++) {
        String shared = "race/r" + round + "/shared";
        List<ObjectEntry> sharedEntries = new ArrayList<>();
        List<ObjectEntry> ownEntries = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
          sharedEntries.add(objectEntry(shared, "hash-" + round + "-side-" + side));
          ownEntries.add(
              objectEntry("race/r" + round + "/own-" + side, "own-" + round + "-" + side));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> sides = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
          List<ObjectEntry> call = List.of(sharedEntries.get(side), ownEntries.get(side));
          Callable<Boolean> task =
              () -> {
                if (!start.await(60, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("the start signal did not come");
                }
                try {
                  ledger().recordObjects(call, Provenance.WRITTEN, Optional.empty());
                  return Boolean.TRUE;
                } catch (ManifestConflictException e) {
                  return Boolean.FALSE;
                }
              };
          sides.add(pool.submit(task));
        }
        start.countDown();
        List<Boolean> won = new ArrayList<>();
        for (Future<Boolean> side : sides) {
          won.add(side.get(60, TimeUnit.SECONDS));
        }

        assertThat(won).as("round %d: one winner and one conflict", round).containsOnlyOnce(true);
        assertThat(won).containsOnlyOnce(false);
        int winner = won.get(0) ? 0 : 1;
        int loser = 1 - winner;
        assertThat(ledger().manifestRows("race/r" + round + "/", Optional.empty(), 100))
            .as("round %d: the shared key and the own key of the winner, nothing else", round)
            .extracting(ManifestRow::entry)
            .containsExactlyInAnyOrder(sharedEntries.get(winner), ownEntries.get(winner));
        assertThat(ledger().manifestRow(ownEntries.get(loser).key()))
            .as("round %d: the loser's other key is not stored", round)
            .isEmpty();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static List<String> paths(List<ManifestRow> rows) {
    List<String> paths = new ArrayList<>();
    for (ManifestRow row : rows) {
      paths.add(row.entry().key().path());
    }
    return paths;
  }
}
