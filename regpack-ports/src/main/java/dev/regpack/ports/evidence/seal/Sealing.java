package dev.regpack.ports.evidence.seal;

import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LeaseLedger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Steps 2 to 4 of the seal-and-send procedure of spec 11.2 as a library: put the sealed bytes and a
 * {@code DeliveryIntent} journal object to the evidence tier, move the delivery to {@code SENDING}
 * in one ledger unit that also records the manifest rows, and, in the commit gate of the transport,
 * re-check the lease, read the restore-epoch marker and set the fenced {@code commit_issued}
 * marker. Durable before effect (I1): nothing here lets a first byte leave before both objects are
 * acknowledged by the evidence store and the {@code SENDING} commit has returned.
 *
 * <p>An implementation never calls a {@code Transport}: the sender passes {@link #beforeFirstByte}
 * to the transport as its commit gate ({@code == PreSendStatus.CLEAR}). Messages and details carry
 * fixed texts, never the text of an exception and never a business key.
 */
public interface Sealing {
  /**
   * The identity of a delivery as the {@code DeliveryIntent} journal object states it (schema
   * version 1): what {@code verify} reconstructs from the evidence tier alone (I10). The delivery
   * must be the one of the request and {@code artifactSha256} the hash of the bytes; the schema
   * checks the rest (bounded text, non-negative counts).
   */
  record DeliveryIntentPayload(
      DeliveryLedger.DeliveryId delivery,
      String businessKey,
      String trn,
      long versionSeq,
      String action,
      Sha256 logicalHash,
      Sha256 artifactSha256,
      String fileName,
      List<Sha256> contextIds) {
    public DeliveryIntentPayload {
      Objects.requireNonNull(delivery, "delivery");
      Objects.requireNonNull(businessKey, "businessKey");
      Objects.requireNonNull(trn, "trn");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(logicalHash, "logicalHash");
      Objects.requireNonNull(artifactSha256, "artifactSha256");
      Objects.requireNonNull(fileName, "fileName");
      contextIds = List.copyOf(contextIds);
    }
  }

  /**
   * A sealed file to send. The bytes are copied. {@code fileName} is the name of the attempt row
   * and of the intent object ({@code intent.fileName()} must be the same text). The request is
   * validated before any effect: the hash of the bytes must be {@code intent.artifactSha256()}, the
   * intent must name {@code delivery}, the endpoint must have a journal scope (each part 1 to 24
   * characters of {@code [A-Za-z0-9._-]}) and be the endpoint of the delivery, else {@link
   * IllegalArgumentException}; a delivery that is not {@code QUEUED} is {@link
   * SealStatus#STATE_MISMATCH}.
   */
  record SealRequest(
      LeaseLedger.EndpointKey endpoint,
      LeaseLedger.LeaseToken token,
      DeliveryLedger.DeliveryId delivery,
      String fileName,
      byte[] bytes,
      String mediaType,
      DeliveryIntentPayload intent) {
    public SealRequest {
      Objects.requireNonNull(endpoint, "endpoint");
      Objects.requireNonNull(token, "token");
      Objects.requireNonNull(delivery, "delivery");
      Objects.requireNonNull(fileName, "fileName");
      bytes = bytes.clone();
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(intent, "intent");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof SealRequest r
          && endpoint.equals(r.endpoint)
          && token.equals(r.token)
          && delivery.equals(r.delivery)
          && fileName.equals(r.fileName)
          && Arrays.equals(bytes, r.bytes)
          && mediaType.equals(r.mediaType)
          && intent.equals(r.intent);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          endpoint, token, delivery, fileName, Arrays.hashCode(bytes), mediaType, intent);
    }

    /** The delivery and the length only: no file name, no business key and no content. */
    @Override
    public String toString() {
      return "SealRequest[delivery=" + delivery.value() + ", length=" + bytes.length + "]";
    }
  }

  /**
   * What {@link #seal} did. {@code SEALED}: both objects are acknowledged and the delivery is
   * {@code SENDING}. {@code GATE_CLOSED}: the send gate is closed; nothing was written. {@code
   * EVIDENCE_UNAVAILABLE}: the evidence store could not acknowledge a write; the ledger is
   * untouched (an object that was already acknowledged stays as an orphan). {@code STALE_LEASE},
   * {@code STATE_MISMATCH} and {@code IN_FLIGHT_CONFLICT}: the ledger refused the {@code SENDING}
   * unit; the objects that were written stay as orphans (spec 7.2). A {@code STATE_MISMATCH} that
   * the check of the state before the gate already finds writes nothing.
   */
  enum SealStatus {
    SEALED,
    GATE_CLOSED,
    EVIDENCE_UNAVAILABLE,
    STALE_LEASE,
    STATE_MISMATCH,
    IN_FLIGHT_CONFLICT
  }

  /** The attempt is present if and only if the status is {@code SEALED}. */
  record SealOutcome(SealStatus status, Optional<DeliveryLedger.AttemptId> attempt, String detail) {
    public SealOutcome {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(attempt, "attempt");
      Objects.requireNonNull(detail, "detail");
      if (attempt.isPresent() != (status == SealStatus.SEALED)) {
        throw new IllegalArgumentException(
            "attempt must be present if and only if status is SEALED: " + status);
      }
    }
  }

  /** The answer of the gate in front of the first byte; only {@code CLEAR} lets it leave. */
  enum PreSendStatus {
    CLEAR,
    STALE_LEASE,
    EPOCH_MARKER_DIFFERS,
    EPOCH_MARKER_UNREADABLE,
    GATE_CLOSED,
    MARKER_REFUSED
  }

  /**
   * Seals one queued delivery: validation of the request, the send gate, an attempt id, the put of
   * the sealed bytes and of the intent object (each acknowledged by the evidence store), then the
   * one ledger unit of {@link DeliveryLedger#beginSending}. An evidence integrity alarm and a
   * {@link dev.regpack.ports.ledger.ManifestConflictException} are not caught: the seal fails
   * closed.
   *
   * @throws IllegalArgumentException the request breaks a rule of {@link SealRequest}; nothing was
   *     written and no attempt id was taken
   */
  SealOutcome seal(SealRequest request);

  /**
   * The commit gate for {@code Transport.send}, asked immediately before the first
   * destination-visible byte: the send gate, the lease (the current lease of the endpoint holds
   * {@code token}), the restore-epoch marker of the evidence tier (it must equal the restore epoch
   * of the token), and the fenced {@code commit_issued} marker of the attempt. The result is {@code
   * CLEAR} only after all of them; the marker is set last and only once per attempt.
   */
  PreSendStatus beforeFirstByte(
      LeaseLedger.EndpointKey endpoint,
      DeliveryLedger.AttemptId attempt,
      LeaseLedger.LeaseToken token);
}
