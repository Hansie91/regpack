package dev.regpack.evidence.crypto;

import dev.regpack.model.Sha256;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.KeyUnavailableException;
import dev.regpack.ports.crypto.ObjectCipher;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * An {@link EvidenceStore} that stores the objects of the encrypted classes as envelopes (D-K3,
 * spec 11.6). It sits above the store port and below the sealing unit: the sealing unit, the
 * manifest rows and the intent hash see the plaintext it hands in, and the inner store holds the
 * envelope that {@link ObjectCipher} made of it. Keys for which the policy says "not encrypted"
 * pass through unchanged.
 *
 * <p>For an encrypted key:
 *
 * <ul>
 *   <li>{@code put} reads the key first. An absent key is sealed and put. A present key is opened:
 *       equal plaintext (compared by SHA-256 with {@link MessageDigest#isEqual}) puts the stored
 *       envelope again, so that a store that keeps two copies completes a missing one (as {@code
 *       JournalWriter} relies on), and answers {@link PutOutcome#ALREADY_PRESENT_IDENTICAL}; other
 *       plaintext is an {@link EvidenceIntegrityException}. A put that loses a race against a
 *       writer of the same key ({@link EvidenceIntegrityException} that is not a corruption alarm)
 *       reads the key again and decides in the same way. A new envelope is never written over a
 *       stored one: the retention, the media type and the write time of the first put stay.
 *   <li>{@code get} opens the stored envelope and returns the plaintext with its SHA-256 (the
 *       store's own hash is that of the envelope).
 *   <li>Bytes that are no envelope of this key (not an envelope at all, an envelope of another kind
 *       or for another key, a changed byte, a wrapped key the KMS rejects, a KEK the KMS does not
 *       know) are an {@link EvidenceCorruptionException}. There is never a fallback to the stored
 *       bytes as plaintext, so a store that was written to behind the decorator cannot downgrade.
 *   <li>A KEK that was destroyed is {@link dev.regpack.ports.crypto.DataShreddedException} on a
 *       read or a repeated put: neither an outage nor a corruption alarm, the object stays listed
 *       and its retention record stays readable. A new object cannot be sealed into a destroyed
 *       KEK: that is an outage.
 *   <li>A failure of the key service ({@link KeyUnavailableException}) is an {@link
 *       EvidenceUnavailableException} with a fixed message and no cause; nothing is stored. A fault
 *       of the host (the {@code Entropy} or the {@code Clock}) is not the key service's and passes
 *       through unchanged.
 * </ul>
 *
 * <p>Alarms of the inner store ({@link EvidenceCorruptionException}, an outage, an integrity alarm
 * for other bytes) are rethrown as they are. {@code list}, {@code extendRetention}, {@code
 * setLegalHold}, {@code grade} and {@code durability} pass through. The messages of the exceptions
 * made here are fixed texts: no key, KEK id, evidence key path or plaintext. Thread-safe when the
 * inner store and the cipher are.
 *
 * <p>Every reader and writer of the encrypted namespaces must use a decorated store, and nothing
 * outside the wiring may touch the raw store; a reader of the raw store sees envelopes.
 */
public final class EncryptingEvidenceStore implements EvidenceStore {
  private static final Set<String> ENCRYPTED_NAMESPACES = Set.of("sealed", "landing", "enrichment");

  /**
   * The namespaces of the encrypted classes (assumption A5): {@code sealed}, {@code landing},
   * {@code enrichment}. {@code journal} and {@code control} stay plain.
   */
  public static final Predicate<EvidenceKey> DEFAULT_POLICY =
      key -> ENCRYPTED_NAMESPACES.contains(key.namespace());

  /** The message of an outage caused by the key service. */
  static final String KEY_SERVICE_UNAVAILABLE = "the key service could not serve this request";

  /** The message of bytes that are no valid envelope of the key they are stored under. */
  static final String NOT_A_VALID_ENVELOPE =
      "an encrypted evidence object is not a valid envelope for its key";

  /** The message of a put of other bytes under an encrypted key that is already in use. */
  static final String OTHER_BYTES = "the encrypted key already holds other bytes";

  private final EvidenceStore inner;
  private final ObjectCipher cipher;
  private final Predicate<EvidenceKey> encrypted;

  /**
   * @param inner the store that holds the envelopes
   * @param cipher the cipher that seals and opens them
   * @param encrypted says which keys are encrypted; the other keys pass through unchanged
   */
  public EncryptingEvidenceStore(
      EvidenceStore inner, ObjectCipher cipher, Predicate<EvidenceKey> encrypted) {
    this.inner = Objects.requireNonNull(inner, "inner");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.encrypted = Objects.requireNonNull(encrypted, "encrypted");
  }

  /** The decorator with {@link #DEFAULT_POLICY}. */
  public static EncryptingEvidenceStore withDefaultPolicy(
      EvidenceStore inner, ObjectCipher cipher) {
    return new EncryptingEvidenceStore(inner, cipher, DEFAULT_POLICY);
  }

  @Override
  public Grade grade() {
    return inner.grade();
  }

  @Override
  public Durability durability() {
    return inner.durability();
  }

  @Override
  public PutOutcome put(PutRequest request) {
    Objects.requireNonNull(request, "request");
    EvidenceKey key = request.key();
    if (!encrypted.test(key)) {
      return inner.put(request);
    }
    Optional<StoredObject> present = inner.get(key);
    if (present.isPresent()) {
      return putAgain(request, present.get());
    }
    byte[] envelope = seal(key, request.bytes());
    try {
      return inner.put(new PutRequest(key, envelope, request.mediaType(), request.retention()));
    } catch (EvidenceCorruptionException e) {
      throw e;
    } catch (EvidenceIntegrityException e) {
      // the key was taken between the read and the put (or the store refused for a reason of its
      // own): decide by what is stored now, as if it had been there at the first read
      Optional<StoredObject> winner = inner.get(key);
      if (winner.isEmpty()) {
        throw e;
      }
      return putAgain(request, winner.get());
    }
  }

  @Override
  public Optional<StoredObject> get(EvidenceKey key) {
    Objects.requireNonNull(key, "key");
    if (!encrypted.test(key)) {
      return inner.get(key);
    }
    Optional<StoredObject> stored = inner.get(key);
    if (stored.isEmpty()) {
      return stored;
    }
    StoredObject s = stored.get();
    byte[] plaintext = open(key, s.bytes());
    return Optional.of(
        new StoredObject(
            key,
            plaintext,
            s.mediaType(),
            Sha256.of(plaintext),
            s.writtenAt(),
            s.retention(),
            s.legalHold()));
  }

  @Override
  public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
    return inner.list(prefix, after, limit);
  }

  @Override
  public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
    inner.extendRetention(key, newRetainUntil);
  }

  @Override
  public void setLegalHold(EvidenceKey key, boolean hold) {
    inner.setLegalHold(key, hold);
  }

  /**
   * A put of a key that holds an object: the same plaintext puts the stored envelope again with the
   * first put's media type and retention (the inner store keeps its first write either way) and is
   * {@code ALREADY_PRESENT_IDENTICAL}; other plaintext is an integrity alarm.
   */
  private PutOutcome putAgain(PutRequest request, StoredObject stored) {
    byte[] plaintext = open(request.key(), stored.bytes());
    if (!sameBytes(plaintext, request.bytes())) {
      throw new EvidenceIntegrityException(OTHER_BYTES);
    }
    inner.put(
        new PutRequest(request.key(), stored.bytes(), stored.mediaType(), stored.retention()));
    return PutOutcome.ALREADY_PRESENT_IDENTICAL;
  }

  private byte[] seal(EvidenceKey key, byte[] plaintext) {
    try {
      return cipher.seal(key, plaintext);
    } catch (KeyUnavailableException e) {
      throw new EvidenceUnavailableException(KEY_SERVICE_UNAVAILABLE);
    }
  }

  /**
   * The plaintext of stored bytes. A destroyed KEK ({@code DataShreddedException}) and a fault of
   * the host pass through; every other failure is named by a fixed text without a cause.
   */
  private byte[] open(EvidenceKey key, byte[] stored) {
    try {
      return cipher.open(key, stored);
    } catch (IllegalArgumentException | Envelope.AuthenticationException e) {
      throw new EvidenceCorruptionException(NOT_A_VALID_ENVELOPE);
    } catch (KeyUnavailableException e) {
      throw new EvidenceUnavailableException(KEY_SERVICE_UNAVAILABLE);
    }
  }

  private static boolean sameBytes(byte[] a, byte[] b) {
    return MessageDigest.isEqual(digestOf(a), digestOf(b));
  }

  private static byte[] digestOf(byte[] bytes) {
    return Sha256.of(bytes).hex().getBytes(StandardCharsets.US_ASCII);
  }
}
