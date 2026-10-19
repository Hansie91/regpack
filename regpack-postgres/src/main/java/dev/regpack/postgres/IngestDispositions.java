package dev.regpack.postgres;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The disposition of every entry of an ingest commit (spec 10 rule 6, T4), decided from what the
 * ledger already holds. Pure: the adapter reads the facts with a few set-based queries before it
 * inserts anything and hands them in, so the order of the rules and the way the entries of one
 * commit are decided against each other can be tested without a database.
 *
 * <p>The rules, in this order, are those of the in-memory adapter:
 *
 * <ol>
 *   <li>the exact key {@code (source, record id, version)} is stored: the same content hash is
 *       {@code NOOP_SAME_VERSION_SAME_HASH}, another is {@code
 *       CONFLICT_SAME_VERSION_DIFFERENT_HASH};
 *   <li>the key {@code (event identity, source, version)} is stored under another record id:
 *       decided by the hash in the same way ({@code ingest_event_version_key});
 *   <li>the event identity is held by another source: {@code SOURCE_CONFLICT};
 *   <li>the version is lower than the highest stored version of the event for this source: {@code
 *       STALE};
 *   <li>otherwise {@code NEW_VERSION}.
 * </ol>
 *
 * Only a new entry counts for the entries after it in the same commit, so a second entry with the
 * same event, source and version is decided against the first, and a lower version after a higher
 * one is stale. Nothing else is stored, as in the in-memory adapter.
 */
final class IngestDispositions {
  /** The pair of the spec 10 key {@code (event identity, source, source version)}, per source. */
  record EventVersion(EventIdentity eventIdentity, long sourceVersion) {}

  /**
   * What the ledger holds for the entries of one commit of one source: the content hash stored
   * under an exact key, the hash stored under an event and version, the events that another source
   * holds, and the highest stored version per event for this source.
   */
  record Facts(
      Map<IngestKey, Sha256> byKey,
      Map<EventVersion, Sha256> byEventVersion,
      Set<EventIdentity> ownedByAnotherSource,
      Map<EventIdentity, Long> highestVersion) {
    Facts {
      byKey = Map.copyOf(byKey);
      byEventVersion = Map.copyOf(byEventVersion);
      ownedByAnotherSource = Set.copyOf(ownedByAnotherSource);
      highestVersion = Map.copyOf(highestVersion);
    }
  }

  private IngestDispositions() {}

  /** The dispositions in the order of the entries. */
  static List<Disposition> decide(List<Entry> entries, Facts facts) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(facts, "facts");
    Map<EventVersion, Sha256> newInThisCommit = new HashMap<>();
    Map<EventIdentity, Long> highestInThisCommit = new HashMap<>();
    List<Disposition> decided = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      EventIdentity event = entry.eventIdentity();
      long version = entry.key().sourceVersion();
      EventVersion eventVersion = new EventVersion(event, version);

      Sha256 sameKey = facts.byKey().get(entry.key());
      if (sameKey != null) {
        decided.add(byHash(sameKey, entry));
        continue;
      }
      Sha256 sameEventVersion = newInThisCommit.get(eventVersion);
      if (sameEventVersion == null) {
        sameEventVersion = facts.byEventVersion().get(eventVersion);
      }
      if (sameEventVersion != null) {
        decided.add(byHash(sameEventVersion, entry));
        continue;
      }
      if (facts.ownedByAnotherSource().contains(event)) {
        decided.add(Disposition.SOURCE_CONFLICT);
        continue;
      }
      Long highest = highestInThisCommit.get(event);
      if (highest == null) {
        highest = facts.highestVersion().get(event);
      }
      if (highest != null && version < highest) {
        decided.add(Disposition.STALE);
        continue;
      }
      newInThisCommit.put(eventVersion, entry.contentHash());
      highestInThisCommit.put(event, version);
      decided.add(Disposition.NEW_VERSION);
    }
    return List.copyOf(decided);
  }

  private static Disposition byHash(Sha256 stored, Entry entry) {
    return stored.equals(entry.contentHash())
        ? Disposition.NOOP_SAME_VERSION_SAME_HASH
        : Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH;
  }
}
