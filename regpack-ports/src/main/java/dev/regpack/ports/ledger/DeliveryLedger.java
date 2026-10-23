package dev.regpack.ports.ledger;

import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Delivery states, the in-flight guard and the fenced send intent (spec 9.3, 11.2). */
public interface DeliveryLedger {
  /** The 16 states of spec 9.3. */
  enum DeliveryState {
    PREPARED,
    BLOCKED,
    QUEUED,
    SENDING,
    AWAITING_ACK,
    ACCEPTED_PROVISIONAL,
    ACCEPTED_CONFIRMED,
    PENDING_AT_ENDPOINT,
    REJECTED,
    FILE_REJECTED,
    IN_DOUBT,
    RESEND_PENDING,
    IN_REPAIR,
    SUPERSEDED,
    WITHDRAWN,
    WRITTEN_OFF
  }

  record DeliveryId(long value) {}

  record AttemptId(long value) {}

  /**
   * A delivery to create. A {@code businessKey} that is not null must be a key text ({@link
   * KeyText}: no control character, no unpaired surrogate, at most {@link
   * KeyText#MAX_BUSINESS_KEY_CHARS} chars), else {@link IllegalArgumentException}; a blank one is
   * refused by {@code createDelivery} (M49).
   */
  record NewDelivery(
      LeaseLedger.EndpointKey endpoint, long endpointEpoch, String businessKey, long versionSeq) {
    public NewDelivery {
      if (businessKey != null) {
        KeyText.require("businessKey", businessKey, KeyText.MAX_BUSINESS_KEY_CHARS);
      }
    }
  }

  record Delivery(DeliveryId id, NewDelivery key, DeliveryState state) {}

  /**
   * The file name is a key text ({@link KeyText}) when it is not null. {@code objects} are the
   * evidence objects that {@link #beginSending} records in the manifest in its own unit (spec
   * 11.2): copied, at most {@link #MAX_INTENT_OBJECTS}, no two with one key, else {@link
   * IllegalArgumentException}.
   */
  record AttemptIntent(
      Sha256 artifactSha256, String fileName, List<ManifestLedger.ObjectEntry> objects) {
    public AttemptIntent {
      if (fileName != null) {
        KeyText.require("fileName", fileName);
      }
      objects = List.copyOf(objects);
      if (objects.size() > MAX_INTENT_OBJECTS) {
        throw new IllegalArgumentException(
            "an attempt intent names at most " + MAX_INTENT_OBJECTS + " objects");
      }
      Set<EvidenceKey> keys = new HashSet<>();
      for (ManifestLedger.ObjectEntry object : objects) {
        if (!keys.add(object.key())) {
          throw new IllegalArgumentException("two objects of an attempt intent have one key");
        }
      }
    }

    /** An intent without objects, as before the manifest existed. */
    public AttemptIntent(Sha256 artifactSha256, String fileName) {
      this(artifactSha256, fileName, List.of());
    }
  }

  /** The most objects that one {@link AttemptIntent} names. */
  int MAX_INTENT_OBJECTS = 8;

  record Attempt(
      AttemptId id,
      DeliveryId delivery,
      LeaseLedger.LeaseToken token,
      Sha256 artifactSha256,
      String fileName,
      boolean commitIssued) {}

  enum TransitionStatus {
    APPLIED,
    STATE_MISMATCH,
    IN_FLIGHT_CONFLICT
  }

  enum SendingStatus {
    APPLIED,
    STALE_LEASE,
    STATE_MISMATCH,
    IN_FLIGHT_CONFLICT
  }

  /** The attempt is present if and only if the status is {@code APPLIED}. */
  record SendingResult(SendingStatus status, Optional<AttemptId> attempt) {
    public SendingResult {
      if (attempt.isPresent() != (status == SendingStatus.APPLIED)) {
        throw new IllegalArgumentException(
            "attempt must be present if and only if status is APPLIED: " + status);
      }
    }
  }

  /**
   * Creates a delivery in {@code PREPARED}. A blank business key throws {@link
   * IllegalArgumentException} (M49) and creates nothing.
   *
   * @throws DuplicateKeyException if the (endpoint, epoch, business key, version) exists
   */
  DeliveryId createDelivery(NewDelivery delivery);

  Optional<Delivery> delivery(DeliveryId id);

  /**
   * Compare-and-set on the state. Rejects {@code SENDING} as a target ({@link
   * IllegalArgumentException}; use {@link #beginSending}). An unknown delivery is an {@link
   * IllegalArgumentException}, not a status (M52). Does not check edge legality: that is the state
   * machine of a later plan. A change into {@code SENDING}, {@code AWAITING_ACK} or {@code
   * IN_DOUBT} from a state outside that set returns {@code IN_FLIGHT_CONFLICT} when another
   * delivery of the same (endpoint, business key) is in flight.
   */
  TransitionStatus transition(DeliveryId id, DeliveryState expectedFrom, DeliveryState to);

  /**
   * Spec 11.2 step 2: a ledger sequence, strictly increasing and never reused; an id is consumed
   * only by a {@link #beginSending} that returns {@code APPLIED}.
   */
  AttemptId allocateAttemptId();

  /**
   * One unit: requires the current lease token (else {@code STALE_LEASE}) and a {@code QUEUED}
   * delivery (else {@code STATE_MISMATCH}), applies the in-flight guard, moves the delivery to
   * {@code SENDING} and stores the attempt intent under {@code attemptId}. The id is checked first:
   * an id that was not allocated throws {@link IllegalArgumentException}, an id already stored on
   * an attempt throws {@link DuplicateKeyException}. An id passed to a call that does not return
   * {@code APPLIED} stays unused and creates no attempt row. An unknown delivery throws {@link
   * IllegalArgumentException} (M52).
   *
   * <p>The objects of the intent are recorded in the manifest with provenance {@code WRITTEN} and
   * this attempt id in the same unit, after the state change and the attempt row; a call that
   * returns a status other than {@code APPLIED} records nothing.
   *
   * @throws ManifestConflictException when an object key is stored with another hash; the delivery
   *     stays {@code QUEUED}, no attempt row exists, no object is recorded and the attempt id stays
   *     unused
   */
  SendingResult beginSending(
      DeliveryId id, AttemptId attemptId, LeaseLedger.LeaseToken token, AttemptIntent intent);

  /**
   * Fenced compare-and-set of spec 11.2 step 4: true only if the attempt's delivery is {@code
   * SENDING} and {@code token} is the current lease token; idempotent. An unknown attempt is {@code
   * false} (M52).
   */
  boolean markCommitIssued(AttemptId attempt, LeaseLedger.LeaseToken token);

  Optional<Attempt> attempt(AttemptId id);
}
