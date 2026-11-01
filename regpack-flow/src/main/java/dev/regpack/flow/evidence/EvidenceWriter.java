package dev.regpack.flow.evidence;

import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.journal.JournalWriter;
import dev.regpack.ports.ledger.ManifestLedger;
import dev.regpack.ports.ledger.RetentionLedger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one door through which the flow writes to the evidence tier (plan D-W11): a put of the
 * object, a manifest row (the hash of the plaintext, as the store returns it) and a retention claim
 * for the object.
 *
 * <p>A replay writes nothing new and never fails because a key is already there (SF1). When the
 * store already holds the key, the stored object stays (its bytes, media type and retention), the
 * stored object is put again so that a store of several copies completes a missing one (also in
 * {@link #recordStored}, which the callers of the journal writer use: a half-written put of such a
 * store leaves the object served from one copy), and the manifest row and the retention claim are
 * completed from what is stored: from the stored hash and the stored retention, never from the
 * bytes of this call. So an attempt that stopped between the put and the row, or between the first
 * copy and the second, is finished by the next attempt, at any later instant, with no object, row
 * or date more than the first attempt made. A caller that must know that the bytes are the same
 * compares them with {@link #get} first.
 *
 * <p>The order is the object, then the manifest row, then the claim: a failure in between leaves an
 * object that the next attempt completes, and never a row that names a missing object.
 *
 * <p>The store is the decrypting view of the tier when the tier is encrypted (the composition wires
 * it), so {@code get} returns plaintext and the manifest holds the hash of the plaintext. Messages
 * carry fixed texts only. Thread-safe when the store, the ledger and the sink are.
 */
public final class EvidenceWriter {
  private static final String MISSING = "the evidence object to record is not in the store";

  private final EvidenceStore store;
  private final ManifestLedger manifest;
  private final ClaimSink claims;
  private final RetentionPolicy policy;
  private final Clock clock;
  private final Regime regime;
  private final JournalWriter journal;

  public EvidenceWriter(
      EvidenceStore store,
      ManifestLedger manifest,
      ClaimSink claims,
      RetentionPolicy policy,
      Clock clock,
      Regime regime) {
    this.store = Objects.requireNonNull(store, "store");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.claims = Objects.requireNonNull(claims, "claims");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.regime = Objects.requireNonNull(regime, "regime");
    this.journal = new JournalWriter(store, policy);
  }

  /**
   * Stores the object (unless the key holds one), records it in the manifest and registers its
   * retention.
   *
   * @param retentionClass the retention class name, 1 to 100 characters of {@code [A-Za-z0-9._-]}
   * @return {@code CREATED} when this call stored the object, else {@code
   *     ALREADY_PRESENT_IDENTICAL} (also when the stored bytes are other than {@code bytes}: they
   *     stay)
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the store cannot be reached
   * @throws dev.regpack.ports.ledger.ManifestConflictException the manifest names another hash for
   *     the key than the store holds (an alarm, not a replay)
   */
  public EvidenceStore.PutOutcome put(
      EvidenceKey key,
      byte[] bytes,
      String mediaType,
      String retentionClass,
      ManifestLedger.ObjectClass objectClass) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(retentionClass, "retentionClass");
    Objects.requireNonNull(objectClass, "objectClass");
    Optional<EvidenceStore.StoredObject> present = store.get(key);
    if (present.isPresent()) {
      EvidenceStore.StoredObject stored = present.get();
      byte[] storedBytes = stored.bytes();
      // acknowledged only after the stored object has been put again (a missing copy is completed)
      store.put(
          new EvidenceStore.PutRequest(key, storedBytes, stored.mediaType(), stored.retention()));
      complete(key, objectClass, Sha256.of(storedBytes), storedBytes.length, stored.retention());
      return EvidenceStore.PutOutcome.ALREADY_PRESENT_IDENTICAL;
    }
    EvidenceStore.Retention retention = policy.retentionFor(retentionClass, clock.now());
    EvidenceStore.PutOutcome outcome =
        store.put(new EvidenceStore.PutRequest(key, bytes, mediaType, retention));
    complete(key, objectClass, Sha256.of(bytes), bytes.length, retention);
    return outcome;
  }

  /**
   * Records an object that another writer put (a journal object is put by {@link #journal()}): the
   * stored object is put again with its own bytes, media type and retention, which completes a
   * missing copy of a store that keeps several (and fails, with nothing recorded, when the store
   * cannot complete it); then the manifest row from the stored hash and size, and the retention
   * claim from the stored retention. Idempotent.
   *
   * @throws IllegalStateException the store does not hold the key
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the store cannot complete every
   *     copy
   */
  public void recordStored(EvidenceKey key, ManifestLedger.ObjectClass objectClass) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(objectClass, "objectClass");
    EvidenceStore.StoredObject stored =
        store.get(key).orElseThrow(() -> new IllegalStateException(MISSING));
    byte[] storedBytes = stored.bytes();
    // as put does on its present branch: the row and the claim follow the completed copies
    store.put(
        new EvidenceStore.PutRequest(key, storedBytes, stored.mediaType(), stored.retention()));
    complete(key, objectClass, Sha256.of(storedBytes), storedBytes.length, stored.retention());
  }

  /**
   * Records an object that the journal writer has just written in this call: the manifest row and
   * the retention claim from the stored object, as {@link #recordStored} does, but without the
   * second put, because {@link JournalWriter#write} returns only after every copy exists (so a
   * first delivery puts the object once). An object that was found in the store, and not written by
   * the caller, is recorded with {@link #recordStored}, which completes a missing copy.
   *
   * @throws IllegalStateException the store does not hold the key
   */
  public void recordWritten(EvidenceKey key, ManifestLedger.ObjectClass objectClass) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(objectClass, "objectClass");
    EvidenceStore.StoredObject stored =
        store.get(key).orElseThrow(() -> new IllegalStateException(MISSING));
    complete(
        key, objectClass, Sha256.of(stored.bytes()), stored.bytes().length, stored.retention());
  }

  /** The stored object, if any (plaintext when the tier is encrypted). */
  public Optional<EvidenceStore.StoredObject> get(EvidenceKey key) {
    return store.get(Objects.requireNonNull(key, "key"));
  }

  /** The journal writer over the same store and retention policy. */
  public JournalWriter journal() {
    return journal;
  }

  private void complete(
      EvidenceKey key,
      ManifestLedger.ObjectClass objectClass,
      Sha256 sha256,
      long size,
      EvidenceStore.Retention retention) {
    manifest.recordObjects(
        List.of(new ManifestLedger.ObjectEntry(key, objectClass, sha256, size)),
        ManifestLedger.Provenance.WRITTEN,
        Optional.empty());
    claims.register(
        new RetentionLedger.RetentionClaim(
            key,
            regime,
            RetentionLedger.AnchorEvent.OBJECT_WRITTEN,
            Optional.of(retention.retainUntil()),
            retention.retentionClass().name()));
  }
}
