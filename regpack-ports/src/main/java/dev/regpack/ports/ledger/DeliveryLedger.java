package dev.regpack.ports.ledger;

import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import java.util.Optional;

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

  /** The file name is a key text ({@link KeyText}) when it is not null. */
  record AttemptIntent(Sha256 artifactSha256, String fileName) {
    public AttemptIntent {
      if (fileName != null) {
        KeyText.require("fileName", fileName);
      }
    }
  }

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
