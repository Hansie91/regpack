package dev.regpack.ports.evidence;

import dev.regpack.model.Sha256;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Write-once evidence storage. {@code put} returns only after the bytes are durable at the grade's
 * {@link Durability}; a put of an existing key with identical bytes returns {@link
 * PutOutcome#ALREADY_PRESENT_IDENTICAL} and keeps the first put's media type, write time and
 * retention; with different bytes it throws {@link EvidenceIntegrityException} and the stored
 * object is unchanged. An outage makes every method throw {@link EvidenceUnavailableException} and
 * stores nothing. Retention is fixed and extend-only; legal hold is a separate flag. There is
 * deliberately no delete. Implementations are thread-safe.
 */
public interface EvidenceStore {
  record RetentionClass(String name) {
    public RetentionClass {
      Objects.requireNonNull(name, "name");
    }
  }

  record Retention(RetentionClass retentionClass, Instant retainUntil) {
    public Retention {
      Objects.requireNonNull(retentionClass, "retentionClass");
      Objects.requireNonNull(retainUntil, "retainUntil");
    }
  }

  /** Storage grade, for example ("P-WORM", "compliance") or ("P-SEALED", ""). */
  record Grade(String profile, String mode) {
    public Grade {
      Objects.requireNonNull(profile, "profile");
      Objects.requireNonNull(mode, "mode");
    }
  }

  /**
   * A claim compared with the required durability at the send gate; (false, 0) for test doubles.
   */
  record Durability(boolean synchronousAcknowledgement, int independentFailureDomains) {
    public Durability {
      if (independentFailureDomains < 0) {
        throw new IllegalArgumentException("independentFailureDomains must not be negative");
      }
    }
  }

  /**
   * What to store. The retain-until of the retention is an {@link Instant} with at most microsecond
   * precision and a year from 0000 to 9999 (the precision of the {@code Clock} port, and what every
   * adapter can hold); {@code put} throws {@link IllegalArgumentException} for any other instant,
   * for example {@code Instant.MAX} or an instant with nanoseconds, and stores nothing. This record
   * does not check it, so a caller that computes a retain-until finds out at {@code put}.
   */
  record PutRequest(EvidenceKey key, byte[] bytes, String mediaType, Retention retention) {
    public PutRequest {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(retention, "retention");
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof PutRequest p
          && key.equals(p.key)
          && Arrays.equals(bytes, p.bytes)
          && mediaType.equals(p.mediaType)
          && retention.equals(p.retention);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, Arrays.hashCode(bytes), mediaType, retention);
    }

    @Override
    public String toString() {
      return "PutRequest[key="
          + key
          + ", length="
          + bytes.length
          + ", mediaType="
          + mediaType
          + "]";
    }
  }

  enum PutOutcome {
    CREATED,
    ALREADY_PRESENT_IDENTICAL
  }

  record StoredObject(
      EvidenceKey key,
      byte[] bytes,
      String mediaType,
      Sha256 sha256,
      Instant writtenAt,
      Retention retention,
      boolean legalHold) {
    public StoredObject {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(sha256, "sha256");
      Objects.requireNonNull(writtenAt, "writtenAt");
      Objects.requireNonNull(retention, "retention");
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StoredObject s
          && key.equals(s.key)
          && Arrays.equals(bytes, s.bytes)
          && mediaType.equals(s.mediaType)
          && sha256.equals(s.sha256)
          && writtenAt.equals(s.writtenAt)
          && retention.equals(s.retention)
          && legalHold == s.legalHold;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          key, Arrays.hashCode(bytes), mediaType, sha256, writtenAt, retention, legalHold);
    }

    @Override
    public String toString() {
      return "StoredObject[key=" + key + ", length=" + bytes.length + ", sha256=" + sha256 + "]";
    }
  }

  Grade grade();

  Durability durability();

  /**
   * Stores the object. A store that keeps several copies returns only after every copy exists, an
   * existing key included (a retry completes a missing copy).
   *
   * @throws EvidenceIntegrityException the key holds other bytes
   * @throws EvidenceCorruptionException (an {@link EvidenceIntegrityException}) a stored copy is
   *     damaged, or the copies of a store that keeps several differ from each other; nothing is
   *     written
   * @throws EvidenceUnavailableException the store cannot reach what it needs, or cannot complete
   *     every copy
   */
  PutOutcome put(PutRequest request);

  /**
   * The stored object, if any.
   *
   * @throws EvidenceCorruptionException no copy of the key passes its own check, or the retention
   *     record cannot be trusted
   * @throws EvidenceUnavailableException the store cannot be reached
   */
  Optional<StoredObject> get(EvidenceKey key);

  /**
   * Keys that start with {@code prefix} and sort after {@code after}, in UTF-16 code-unit order, at
   * most {@code limit}.
   */
  List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit);

  /**
   * Moves retain-until later; an earlier instant throws {@link EvidenceRetentionException}. The new
   * instant has at most microsecond precision and a year from 0000 to 9999, as on {@link
   * PutRequest}; any other instant throws {@link IllegalArgumentException} before it is compared
   * with the stored one (so {@code Instant.MIN} is not "earlier"), and the object is unchanged.
   *
   * <p>{@code "no such evidence key: <path>"} is the message of the {@link
   * IllegalArgumentException} that a key which was never put throws (M28); during an outage {@link
   * EvidenceUnavailableException} comes first. There is no delete, so a listed key cannot vanish; a
   * lost object is found by {@code verify}, not by this call. A store that keeps several copies of
   * the retention record returns only after every copy holds the requested state, also when an
   * earlier call that failed had written some of them; a damaged retention record or object is an
   * {@link EvidenceCorruptionException} and nothing is changed.
   */
  void extendRetention(EvidenceKey key, Instant newRetainUntil);

  /**
   * Sets or clears the legal hold, which is separate from retention. A key that was never put
   * throws {@link IllegalArgumentException} with the message {@code "no such evidence key: <path>"}
   * (M33); during an outage {@link EvidenceUnavailableException} comes first. As for {@link
   * #extendRetention}: every copy of the record holds the requested state before the call returns,
   * and a damaged record is an {@link EvidenceCorruptionException}.
   */
  void setLegalHold(EvidenceKey key, boolean hold);
}
