package dev.regpack.ports.ledger;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ledger's own list of the evidence objects it has written, with the hash that was true at
 * write time (spec 5.4, 11.1). A key has one hash for ever: the first row wins, and another hash
 * for the same key is a {@link ManifestConflictException}. The rows are insert-only. There is no
 * update and no delete, and a row may name an attempt that has no attempt row (after a restore the
 * evidence tier is ahead of the database).
 */
public interface ManifestLedger {
  /** The most rows that one {@link #manifestRows} call returns. */
  int MAX_ROWS_PER_CALL = 1_000;

  /** What an object is. Codes 1 to 7 in this order; the PostgreSQL adapter pins them. */
  enum ObjectClass {
    SEALED_FILE,
    JOURNAL_A,
    JOURNAL_B,
    LANDING_BATCH,
    ENRICHMENT_RESPONSE,
    SIGNED_MANIFEST,
    OTHER
  }

  /** How the row came to be. Codes 1 and 2 in this order. */
  enum Provenance {
    WRITTEN,
    ADOPTED_AT_RESTORE
  }

  /**
   * An object to record. {@code sha256} is the hash of the object bytes as stored (not the payload
   * hash of a journal object).
   *
   * @throws IllegalArgumentException when {@code sizeBytes} is negative
   */
  record ObjectEntry(EvidenceKey key, ObjectClass objectClass, Sha256 sha256, long sizeBytes) {
    public ObjectEntry {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(objectClass, "objectClass");
      Objects.requireNonNull(sha256, "sha256");
      if (sizeBytes < 0) {
        throw new IllegalArgumentException("sizeBytes must not be negative");
      }
    }
  }

  /** A stored row: the entry, how it came to be, the attempt it names and the ledger time. */
  record ManifestRow(
      ObjectEntry entry,
      Provenance provenance,
      Optional<DeliveryLedger.AttemptId> attempt,
      Instant writtenAt) {
    public ManifestRow {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(provenance, "provenance");
      Objects.requireNonNull(attempt, "attempt");
      Objects.requireNonNull(writtenAt, "writtenAt");
    }
  }

  /**
   * Records the entries in one unit, all or nothing. A key that is stored with the same hash is
   * left unchanged (the first row wins, with its provenance, attempt and time); {@code writtenAt}
   * of a new row is the ledger clock. An empty list does nothing.
   *
   * @throws IllegalArgumentException when two entries of the list have one key; nothing is stored
   * @throws ManifestConflictException when a key is stored with another hash; nothing of the call
   *     is stored and the message names the key path only
   */
  void recordObjects(
      List<ObjectEntry> entries, Provenance provenance, Optional<DeliveryLedger.AttemptId> attempt);

  /** The row of {@code key}, if any. */
  Optional<ManifestRow> manifestRow(EvidenceKey key);

  /**
   * Rows whose key starts with {@code prefix} and sorts after {@code after}, in byte order of the
   * key (the UTF-16 code-unit order of {@code EvidenceStore#list}), at most {@code min(limit,
   * MAX_ROWS_PER_CALL)}. The prefix need not end at a segment boundary and may be empty.
   *
   * @throws IllegalArgumentException when {@code limit} is negative
   */
  List<ManifestRow> manifestRows(String prefix, Optional<EvidenceKey> after, int limit);
}
