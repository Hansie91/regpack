package dev.regpack.evidence.retention;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceRetentionException;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.seal.RetentionRegistrar;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The retention engine (spec 5.4 and 11.6, P3c1 D-K4, SEAL-1): it records a retention claim in the
 * {@code retention} rows and then extends the stored object to the latest {@code retain_until} of
 * all rows of the artifact. Retention is extend-only everywhere: the ledger merges a claim into its
 * row by the later date, and the store refuses an earlier one, which the engine takes as satisfied
 * (the object is already retained at least that long). So a claim never shortens a retention, and
 * neither does a second regime, a retry or a claim for an open anchor.
 *
 * <p>It is the implementation of {@link RetentionRegistrar} for {@code DurableSeal}: a sealed key
 * is content-addressed, so every delivery with the same bytes shares one object, a put of an
 * existing key keeps the retention of the first put, and the registration of each later seal is
 * what extends the shared object (SEAL-1). The regime of that claim is the engine's: there is one
 * engine per regime, and how a sender supplies the regime is for P8 to design. {@link
 * #register(RetentionClaim)} is general: the claim names its own regime and anchor, and a
 * TERMINATION claim whose date is not yet known is allowed (it is recorded and extends nothing).
 *
 * <p><b>Order and failures.</b> The row is written first and the store is extended second, so that
 * a failure of the store, an outage, a missing object or an alarm, leaves the row behind and
 * throws: the retry of the same claim completes the extension, and until then {@link
 * RetentionVerifier} reports a store that lags behind its rows ({@code RETAIN_UNTIL_SHORTER}). A
 * row without the extension only over-retains in the ledger's records; the reverse order would
 * leave a store that holds longer than any record says, which nothing would find. An {@link
 * IllegalArgumentException} of the store (the key was never put, so the engine records a row for an
 * object that is not there; that is {@code verify}'s MISSING, not a retention fault), an outage and
 * an alarm of the store ({@link dev.regpack.ports.evidence.EvidenceCorruptionException} among them)
 * are not caught.
 *
 * <p><b>What the engine touches.</b> Nothing but the rows and the two retention calls of the store
 * port, {@link EvidenceStore#extendRetention} and {@link EvidenceStore#setLegalHold}: it never
 * reads, writes or lists an object, never asks for a bypass and never removes anything (the module
 * has no delete request and no bypass header, and {@code NoBypassInEvidenceTest} scans for both).
 * Epoch markers ({@link RetentionLedger#EXEMPT_PREFIX}) get no row and are refused. Over an
 * encrypted tier the engine works on the decorated store, which passes both calls to the inner
 * store, so retention logic is not in the decorator.
 *
 * <p>Thread-safe when the ledger and the store are. Two claims for one artifact at once both end
 * with the store at the latest date because the store only extends: the claim with the lower date
 * that extends last is refused and counts as satisfied. Reading the rows after the record is what
 * lets a retry repair a store that lags behind its rows.
 */
public final class RetentionEngine implements RetentionRegistrar {
  private static final String EXEMPT_KEY = "an epoch marker has no retention row";

  private final RetentionLedger rows;
  private final EvidenceStore evidence;
  private final Regime regime;

  /**
   * @param rows the {@code retention} rows of the ledger
   * @param evidence the evidence store whose objects the rows describe (the decorated one over an
   *     encrypted tier)
   * @param regime the regime of the claims that {@link #sealedObject} records
   */
  public RetentionEngine(RetentionLedger rows, EvidenceStore evidence, Regime regime) {
    this.rows = Objects.requireNonNull(rows, "rows");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.regime = Objects.requireNonNull(regime, "regime");
  }

  /**
   * Registers the retention that a seal used for a sealed object: an {@code OBJECT_WRITTEN} claim
   * for the regime of the engine, with the class and the retain-until of {@code putWith}.
   *
   * @throws IllegalArgumentException the key is under {@code control/}, or the class name of {@code
   *     putWith} is no retention class name of the ledger ({@code [A-Za-z0-9._-]}, 1 to 100), or
   *     the store has no such object
   */
  @Override
  public void sealedObject(EvidenceKey sealedKey, EvidenceStore.Retention putWith) {
    Objects.requireNonNull(sealedKey, "sealedKey");
    Objects.requireNonNull(putWith, "putWith");
    register(
        new RetentionClaim(
            sealedKey,
            regime,
            AnchorEvent.OBJECT_WRITTEN,
            Optional.of(putWith.retainUntil()),
            putWith.retentionClass().name()));
  }

  /**
   * Records the claim and extends the stored object to the latest date of all rows of the artifact.
   * A claim without a date extends nothing by itself (if the artifact has dated rows the extension
   * to their latest date is repeated, which changes nothing when it already holds).
   *
   * @throws IllegalArgumentException the artifact is under {@code control/} (nothing is recorded),
   *     or the store has no such object (the row stays)
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the store cannot be reached
   *     (the row stays)
   */
  public void register(RetentionClaim claim) {
    Objects.requireNonNull(claim, "claim");
    EvidenceKey artifact = claim.artifact();
    if (artifact.path().startsWith(RetentionLedger.EXEMPT_PREFIX)) {
      throw new IllegalArgumentException(EXEMPT_KEY);
    }
    rows.recordRetention(List.of(claim));
    Optional<Instant> latest = latestOf(artifact, claim);
    if (latest.isEmpty()) {
      return;
    }
    try {
      evidence.extendRetention(artifact, latest.get());
    } catch (EvidenceRetentionException e) {
      // the object is retained for longer than every row says: nothing to extend
    }
  }

  /**
   * Sets the legal hold of every row of the artifact and then of the stored object. The order
   * leaves a crash between the two as a hold that the rows have and the store lost, which {@link
   * RetentionVerifier} reports as {@code HOLD_MISSING}.
   *
   * @throws IllegalArgumentException the artifact has no row (the store is not touched), or the
   *     store has no such object
   */
  public void hold(EvidenceKey artifact, boolean hold) {
    Objects.requireNonNull(artifact, "artifact");
    rows.setRetentionHold(artifact, hold);
    evidence.setLegalHold(artifact, hold);
  }

  /**
   * The latest date of the claim and of all rows of the artifact. The rows of one artifact come
   * first among the rows whose key starts with its path, and a call for one artifact returns all of
   * its rows, so the first page of one artifact is enough; rows of other artifacts that merely
   * share the prefix are ignored.
   */
  private Optional<Instant> latestOf(EvidenceKey artifact, RetentionClaim claim) {
    Optional<Instant> latest = claim.retainUntil();
    for (RetentionRow row : rows.retentionRows(artifact.path(), Optional.empty(), 1)) {
      if (!row.claim().artifact().equals(artifact)) {
        continue;
      }
      Optional<Instant> date = row.claim().retainUntil();
      if (date.isPresent() && (latest.isEmpty() || date.get().isAfter(latest.get()))) {
        latest = date;
      }
    }
    return latest;
  }
}
