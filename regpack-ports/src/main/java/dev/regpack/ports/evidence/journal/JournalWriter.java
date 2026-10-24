package dev.regpack.ports.evidence.journal;

import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes and reads journal objects through an {@link EvidenceStore} (spec 11.2). A put of an
 * existing key with the same {@code payload_sha256} is success, and one with another hash is an
 * integrity alarm, whatever the envelope's instant says. A damaged stored copy ({@link
 * EvidenceCorruptionException}) is never taken for "already present". "Already present" is
 * acknowledged only after the stored object has been put again through the store and that put has
 * returned: a store that keeps two copies completes a missing one, or throws. The writer reads no
 * clock: the retention is asked from the {@link RetentionPolicy} for the class {@code journal} and
 * the ledger event time of the object. Messages carry key paths only.
 */
public final class JournalWriter {
  /** The media type of every journal object. */
  public static final String MEDIA_TYPE = "application/vnd.regpack.journal+json";

  /** The retention class name of journal objects (OI-6). */
  public static final String RETENTION_CLASS = "journal";

  private static final int PAGE = 1_000;
  private static final int ORDINAL_DIGITS = 12;

  public enum Outcome {
    CREATED,
    ALREADY_PRESENT_SAME_PAYLOAD
  }

  private final EvidenceStore store;
  private final RetentionPolicy retention;

  public JournalWriter(EvidenceStore store, RetentionPolicy retention) {
    this.store = Objects.requireNonNull(store, "store");
    this.retention = Objects.requireNonNull(retention, "retention");
  }

  /**
   * Puts the object under its key.
   *
   * @throws EvidenceCorruptionException a stored copy is damaged, or the copies of a store differ
   * @throws EvidenceIntegrityException another payload hash is stored under the key
   * @throws EvidenceUnavailableException the store cannot be written or read, also when it cannot
   *     complete the stored object of an earlier write that had the same payload
   */
  public Outcome write(JournalObject object) {
    Objects.requireNonNull(object, "object");
    EvidenceKey key = object.key().evidenceKey();
    EvidenceStore.PutRequest request =
        new EvidenceStore.PutRequest(
            key,
            object.toBytes(),
            MEDIA_TYPE,
            retention.retentionFor(RETENTION_CLASS, object.clockInstant()));
    try {
      return switch (store.put(request)) {
        case CREATED -> Outcome.CREATED;
        case ALREADY_PRESENT_IDENTICAL -> Outcome.ALREADY_PRESENT_SAME_PAYLOAD;
      };
    } catch (EvidenceCorruptionException e) {
      throw e;
    } catch (EvidenceIntegrityException e) {
      Optional<EvidenceStore.StoredObject> stored = storedWithPayloadOf(key, object);
      if (stored.isEmpty()) {
        throw e;
      }
      // The store refused these bytes before it looked at the rest of its state (a store of two
      // volumes may hold the stored object on one of them only). The write is acknowledged only
      // when the stored object itself has been put again and that has succeeded: for such a store
      // that publishes the missing copy, for any other it is an idempotent success. What the put
      // throws (a damaged or divergent copy, an outage) is not absorbed.
      store.put(
          new EvidenceStore.PutRequest(
              key, stored.get().bytes(), stored.get().mediaType(), stored.get().retention()));
      return Outcome.ALREADY_PRESENT_SAME_PAYLOAD;
    }
  }

  /**
   * The object stored under the key, if any.
   *
   * @throws EvidenceIntegrityException the stored bytes are not a journal object, or the object
   *     names another key than the one it is stored under
   */
  public Optional<JournalObject> read(JournalKey key) {
    Objects.requireNonNull(key, "key");
    EvidenceKey evidenceKey = key.evidenceKey();
    Optional<EvidenceStore.StoredObject> stored = store.get(evidenceKey);
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    JournalObject object;
    try {
      object = JournalObject.fromBytes(key, stored.get().bytes());
    } catch (IllegalArgumentException e) {
      // the cause is dropped on purpose: it describes stored content
      throw new EvidenceIntegrityException(
          "stored journal object cannot be read: " + evidenceKey.path());
    }
    if (!object.key().equals(key)) {
      throw new EvidenceIntegrityException(
          "stored journal object names another key: " + evidenceKey.path());
    }
    return Optional.of(object);
  }

  /**
   * The next free ordinal of a class (a) subject: the highest stored ordinal plus one, or 1 when
   * there is none. The ordinal is the caller's to supply (D-E7); this reads the tier and does not
   * reserve anything.
   */
  public long nextOrdinal(
      JournalScope scope, long restoreEpoch, JournalType type, String subjectId) {
    String path =
        new JournalKey.ClassA(scope, restoreEpoch, type, subjectId, 1).evidenceKey().path();
    // everything but the last segment (the ordinal) ends with a slash: the subject's own prefix
    String prefix = path.substring(0, path.length() - ORDINAL_DIGITS);
    Optional<EvidenceKey> after = Optional.empty();
    long highest = 0;
    while (true) {
      List<EvidenceKey> page = store.list(prefix, after, PAGE);
      // read to an empty page: a page shorter than the limit is not taken for the end (D-W10)
      if (page.isEmpty()) {
        return highest + 1;
      }
      // a non-empty page must start strictly after the previous page's last key, else the loop
      // would never end
      if (after.isPresent() && page.get(0).path().compareTo(after.get().path()) <= 0) {
        throw new EvidenceIntegrityException("evidence listing does not advance: " + prefix);
      }
      for (EvidenceKey listed : page) {
        highest = Math.max(highest, ordinalOf(listed));
      }
      after = Optional.of(page.get(page.size() - 1));
    }
  }

  private static long ordinalOf(EvidenceKey key) {
    try {
      if (JournalKey.parse(key) instanceof JournalKey.ClassA a) {
        return a.ordinal();
      }
    } catch (IllegalArgumentException e) {
      // falls through to the alarm below
    }
    throw new EvidenceIntegrityException(
        "not a class a journal key under a subject: " + key.path());
  }

  /**
   * The stored object under the key if it is a journal object with the payload of {@code object}.
   */
  private Optional<EvidenceStore.StoredObject> storedWithPayloadOf(
      EvidenceKey key, JournalObject object) {
    Optional<EvidenceStore.StoredObject> stored = store.get(key);
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    try {
      boolean same =
          JournalObject.fromBytes(object.key(), stored.get().bytes())
              .payloadSha256()
              .equals(object.payloadSha256());
      return same ? stored : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
