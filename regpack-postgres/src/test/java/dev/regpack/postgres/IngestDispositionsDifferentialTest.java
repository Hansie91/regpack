package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.postgres.IngestDispositions.EventVersion;
import dev.regpack.postgres.IngestDispositions.Facts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link IngestDispositions#decide} against a plain model that applies one entry at a time to a
 * growing store, the way the in-memory adapter does, on random commits with non-monotone versions,
 * record ids that do not follow the event identity and events that another source owns. The adapter
 * gives the decider the facts as they were before the commit; the model sees every entry of the
 * commit already stored, so the two agree only if the decider's in-commit bookkeeping is right. The
 * seeds are fixed: a failure names the seed and the commit.
 */
class IngestDispositionsDifferentialTest {
  private static final String SRC = "srcA";
  private static final List<Sha256> HASHES =
      List.of(Sha256.ofUtf8("A"), Sha256.ofUtf8("B"), Sha256.ofUtf8("C"));
  private static final int EVENTS = 4;
  private static final int RECORDS = 5;
  private static final int MAX_VERSION = 6;

  /** The store of one source, applying entries one by one. */
  private static final class Model {
    private final Map<IngestKey, Sha256> byKey = new HashMap<>();
    private final Map<EventVersion, Sha256> byEventVersion = new HashMap<>();
    private final Map<EventIdentity, Long> highest = new HashMap<>();
    private final Set<EventIdentity> ownedByAnotherSource;

    Model(Set<EventIdentity> ownedByAnotherSource) {
      this.ownedByAnotherSource = ownedByAnotherSource;
    }

    Facts facts() {
      return new Facts(byKey, byEventVersion, ownedByAnotherSource, highest);
    }

    Disposition apply(Entry entry) {
      Sha256 sameKey = byKey.get(entry.key());
      if (sameKey != null) {
        return byHash(sameKey, entry);
      }
      EventVersion eventVersion =
          new EventVersion(entry.eventIdentity(), entry.key().sourceVersion());
      Sha256 sameEventVersion = byEventVersion.get(eventVersion);
      if (sameEventVersion != null) {
        return byHash(sameEventVersion, entry);
      }
      if (ownedByAnotherSource.contains(entry.eventIdentity())) {
        return Disposition.SOURCE_CONFLICT;
      }
      Long top = highest.get(entry.eventIdentity());
      if (top != null && entry.key().sourceVersion() < top) {
        return Disposition.STALE;
      }
      byKey.put(entry.key(), entry.contentHash());
      byEventVersion.put(eventVersion, entry.contentHash());
      highest.put(entry.eventIdentity(), entry.key().sourceVersion());
      return Disposition.NEW_VERSION;
    }

    private static Disposition byHash(Sha256 stored, Entry entry) {
      return stored.equals(entry.contentHash())
          ? Disposition.NOOP_SAME_VERSION_SAME_HASH
          : Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH;
    }
  }

  private static EventIdentity event(int n) {
    return EventIdentity.of("LEI1", "XPAR", "e" + n);
  }

  private static List<Entry> randomCommit(Random random) {
    int size = random.nextInt(7);
    Map<IngestKey, Entry> entries = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      IngestKey key =
          new IngestKey(SRC, "r" + random.nextInt(RECORDS), 1 + random.nextInt(MAX_VERSION));
      entries.putIfAbsent(
          key,
          new Entry(key, event(random.nextInt(EVENTS)), HASHES.get(random.nextInt(HASHES.size()))));
    }
    return List.copyOf(entries.values());
  }

  @Test
  void theDeciderAgreesWithAnEntryByEntryModelOnRandomCommits() {
    int dispositionsSeen = 0;
    Set<Disposition> kinds = new HashSet<>();
    for (long seed = 1; seed <= 40; seed++) {
      Random random = new Random(seed);
      Set<EventIdentity> foreign = new HashSet<>();
      if (random.nextInt(3) == 0) {
        foreign.add(event(random.nextInt(EVENTS)));
      }
      Model model = new Model(foreign);
      for (int commitNo = 0; commitNo < 60; commitNo++) {
        List<Entry> entries = randomCommit(random);
        Facts before = model.facts();
        List<Disposition> decided = IngestDispositions.decide(entries, before);
        List<Disposition> expected = new ArrayList<>();
        for (Entry entry : entries) {
          expected.add(model.apply(entry));
        }
        assertThat(decided)
            .as("seed " + seed + " commit " + commitNo + " " + entries)
            .isEqualTo(expected);
        dispositionsSeen += decided.size();
        kinds.addAll(decided);
      }
    }
    // the generator reaches every disposition, or the comparison proves little
    assertThat(dispositionsSeen).isGreaterThan(3_000);
    assertThat(kinds).containsExactlyInAnyOrder(Disposition.values());
  }
}
