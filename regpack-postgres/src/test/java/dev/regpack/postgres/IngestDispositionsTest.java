package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.postgres.IngestDispositions.EventVersion;
import dev.regpack.postgres.IngestDispositions.Facts;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The decision of an ingest commit (T4, spec 10 rule 6), pure: what the ledger holds goes in as
 * facts, the dispositions come out in input order. The rules are the ones of the in-memory adapter,
 * in the same order: the exact key, then (event, source, version) by hash, then a source conflict,
 * then stale; and the entries of one commit are decided against each other in order.
 */
class IngestDispositionsTest {
  private static final String SRC = "srcA";
  private static final Sha256 A = Sha256.ofUtf8("A");
  private static final Sha256 B = Sha256.ofUtf8("B");

  /** A mutable picture of what is stored, turned into {@link Facts} at the end. */
  private static final class Stored {
    private final Map<IngestKey, Sha256> byKey = new HashMap<>();
    private final Map<EventVersion, Sha256> byEventVersion = new HashMap<>();
    private final Set<EventIdentity> otherSource = new HashSet<>();
    private final Map<EventIdentity, Long> highest = new HashMap<>();

    Stored row(String record, long version, String event, Sha256 hash) {
      EventIdentity identity = EventIdentity.of("LEI1", "XPAR", event);
      byKey.put(new IngestKey(SRC, record, version), hash);
      byEventVersion.put(new EventVersion(identity, version), hash);
      highest.merge(identity, version, Math::max);
      return this;
    }

    Stored ownedByAnotherSource(String event) {
      otherSource.add(EventIdentity.of("LEI1", "XPAR", event));
      return this;
    }

    Facts facts() {
      return new Facts(byKey, byEventVersion, otherSource, highest);
    }
  }

  private static Entry entry(String record, long version, String event, Sha256 hash) {
    return new Entry(
        new IngestKey(SRC, record, version), EventIdentity.of("LEI1", "XPAR", event), hash);
  }

  private static Entry entry(String record, long version, Sha256 hash) {
    return entry(record, version, record, hash);
  }

  private static List<Disposition> decide(Stored stored, Entry... entries) {
    return IngestDispositions.decide(List.of(entries), stored.facts());
  }

  @Test
  void anEntryWithNothingStoredIsNewAndAnEmptyCommitDecidesNothing() {
    assertThat(decide(new Stored(), entry("r1", 1, A))).containsExactly(Disposition.NEW_VERSION);
    assertThat(decide(new Stored())).isEmpty();
  }

  @Test
  void theExactKeyIsDecidedByTheHash() {
    Stored stored = new Stored().row("r1", 1, "r1", A);
    assertThat(decide(stored, entry("r1", 1, A)))
        .containsExactly(Disposition.NOOP_SAME_VERSION_SAME_HASH);
    assertThat(decide(stored, entry("r1", 1, B)))
        .containsExactly(Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);
  }

  @Test
  void theSameEventSourceAndVersionUnderAnotherRecordIdIsDecidedByTheHash() {
    Stored stored = new Stored().row("rec-1", 1, "e1", A);
    assertThat(decide(stored, entry("rec-2", 1, "e1", A)))
        .containsExactly(Disposition.NOOP_SAME_VERSION_SAME_HASH);
    assertThat(decide(stored, entry("rec-2", 1, "e1", B)))
        .containsExactly(Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);
    // another version of the event under the other record id is a different key
    assertThat(decide(stored, entry("rec-2", 2, "e1", B))).containsExactly(Disposition.NEW_VERSION);
  }

  @Test
  void anEventOfAnotherSourceIsASourceConflict() {
    Stored stored = new Stored().ownedByAnotherSource("r1");
    assertThat(decide(stored, entry("r1", 1, A))).containsExactly(Disposition.SOURCE_CONFLICT);
    assertThat(decide(stored, entry("r2", 1, A))).containsExactly(Disposition.NEW_VERSION);
  }

  @Test
  void aLowerVersionThanTheHighestStoredIsStaleAndAHigherOneIsNew() {
    Stored stored = new Stored().row("r9", 2, "r9", A);
    assertThat(decide(stored, entry("r9", 1, A))).containsExactly(Disposition.STALE);
    assertThat(decide(stored, entry("r9", 3, A))).containsExactly(Disposition.NEW_VERSION);
    // the same event under another record id: the highest version is per event and source
    assertThat(decide(stored, entry("alt", 1, "r9", B))).containsExactly(Disposition.STALE);
  }

  @Test
  void abaIsThreeNewVersions() {
    Stored stored = new Stored();
    assertThat(decide(stored, entry("r5", 1, A), entry("r5", 2, B), entry("r5", 3, A)))
        .containsExactly(Disposition.NEW_VERSION, Disposition.NEW_VERSION, Disposition.NEW_VERSION);
  }

  @Test
  void theEntriesOfOneCommitAreDecidedAgainstEachOtherInOrder() {
    Stored stored = new Stored();
    // the same event, source and version: the first is new, the second is decided against it
    assertThat(
            decide(
                stored,
                entry("rec-1", 1, "e1", A),
                entry("rec-2", 1, "e1", A),
                entry("rec-3", 1, "e1", B)))
        .containsExactly(
            Disposition.NEW_VERSION,
            Disposition.NOOP_SAME_VERSION_SAME_HASH,
            Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);
    // a lower version after a higher one inside the commit is stale; the order matters
    assertThat(decide(stored, entry("r1", 3, A), entry("r1", 1, B), entry("r1", 2, B)))
        .containsExactly(Disposition.NEW_VERSION, Disposition.STALE, Disposition.STALE);
    assertThat(decide(stored, entry("r1", 1, A), entry("r1", 2, B), entry("r1", 3, A)))
        .containsExactly(Disposition.NEW_VERSION, Disposition.NEW_VERSION, Disposition.NEW_VERSION);
  }

  @Test
  void aStaleEntryDoesNotRaiseTheHighestVersionAndASkippedEntryStoresNothing() {
    Stored stored = new Stored().row("r1", 5, "r1", A);
    // v3 is stale against the stored 5; v6 then is new; v4 after v6 is stale again
    assertThat(decide(stored, entry("r1", 3, B), entry("r1", 6, B), entry("r1", 4, A)))
        .containsExactly(Disposition.STALE, Disposition.NEW_VERSION, Disposition.STALE);
    // a conflict or a no-op does not count as a stored version either
    assertThat(decide(stored, entry("r1", 5, B), entry("r1x", 5, "r1", A), entry("r1", 6, A)))
        .containsExactly(
            Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH,
            Disposition.NOOP_SAME_VERSION_SAME_HASH,
            Disposition.NEW_VERSION);
  }

  @Test
  void theRulesApplyInTheOrderKeyThenEventVersionThenSourceThenStale() {
    // the exact key wins over a source conflict and over stale
    Stored byKey =
        new Stored().row("r1", 2, "r1", A).row("r1", 3, "r1", A).ownedByAnotherSource("r1");
    assertThat(decide(byKey, entry("r1", 2, A)))
        .containsExactly(Disposition.NOOP_SAME_VERSION_SAME_HASH);
    // the event version wins over a source conflict and over stale
    assertThat(decide(byKey, entry("alt", 2, "r1", B)))
        .containsExactly(Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);
    // a source conflict wins over stale
    assertThat(decide(byKey, entry("alt", 1, "r1", B)))
        .containsExactly(Disposition.SOURCE_CONFLICT);
    // and stale decides when the event is the source's own
    Stored own = new Stored().row("r1", 3, "r1", A);
    assertThat(decide(own, entry("alt", 1, "r1", B))).containsExactly(Disposition.STALE);
  }

  @Test
  void aMixedCommitComesBackInInputOrder() {
    Stored stored =
        new Stored().row("rA", 1, "rA", A).ownedByAnotherSource("rB").row("rS", 5, "rS", A);
    List<Disposition> decided =
        decide(
            stored,
            entry("rN2", 1, A),
            entry("rA", 1, A),
            entry("rN1", 1, A),
            entry("alt", 1, "rA", B),
            entry("rB", 1, A),
            entry("rS", 4, A),
            entry("rS", 6, B));
    assertThat(decided)
        .containsExactly(
            Disposition.NEW_VERSION,
            Disposition.NOOP_SAME_VERSION_SAME_HASH,
            Disposition.NEW_VERSION,
            Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH,
            Disposition.SOURCE_CONFLICT,
            Disposition.STALE,
            Disposition.NEW_VERSION);
  }
}
