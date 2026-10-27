package dev.regpack.ports.evidence.seal;

import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;

/**
 * The seam through which a sealer tells the retention side what it has just put (SEAL-1, spec 5.4
 * and 11.6, P3c1 D-K4). The sealed key is content-addressed, so every delivery with the same bytes
 * shares one object, and a put of an existing key keeps the retention of the first put ({@link
 * EvidenceStore#put}); a registrar is how the retention of a LATER seal reaches the shared object
 * and the {@code retention} rows.
 *
 * <p>{@link #sealedObject} is called once per seal, after the sealed put was acknowledged and
 * before anything else is written for the seal (the intent object, the ledger unit), with the
 * {@link EvidenceStore.Retention} that the seal used for that put (also when the put found the
 * object and kept the retention of the first one). It throws what the retention side throws: an
 * {@link dev.regpack.ports.evidence.EvidenceUnavailableException} makes the seal answer {@code
 * EVIDENCE_UNAVAILABLE} with the ledger unchanged, any other exception (a ledger failure included)
 * ends the seal as it is. Idempotent: the call can be repeated for the same arguments.
 */
@FunctionalInterface
public interface RetentionRegistrar {
  /** A registrar that registers nothing: the shared object keeps the retention of its first put. */
  RetentionRegistrar NONE = (sealedKey, putWith) -> {};

  /**
   * Registers the retention that a seal used for the sealed object under {@code sealedKey}.
   *
   * @param sealedKey the content-addressed key of the sealed file
   * @param putWith the retention that the seal gave to its put of that key
   */
  void sealedObject(EvidenceKey sealedKey, EvidenceStore.Retention putWith);
}
