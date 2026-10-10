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

  PutOutcome put(PutRequest request);

  Optional<StoredObject> get(EvidenceKey key);

  /**
   * Keys that start with {@code prefix} and sort after {@code after}, in UTF-16 code-unit order, at
   * most {@code limit}.
   */
  List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit);

  /** Moves retain-until later; an earlier instant throws {@link EvidenceRetentionException}. */
  void extendRetention(EvidenceKey key, Instant newRetainUntil);

  void setLegalHold(EvidenceKey key, boolean hold);
}
