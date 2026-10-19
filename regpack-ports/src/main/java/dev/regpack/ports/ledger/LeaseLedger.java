package dev.regpack.ports.ledger;

import dev.regpack.model.KeyText;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/**
 * Per-endpoint send lease with a fencing token (spec 10, 11.2, 11.5 A.2). Time is the ledger's own
 * clock, never the caller's.
 */
public interface LeaseLedger {
  /**
   * The longest lease ttl, 100 years of 365 days. A longer ttl is an {@link
   * IllegalArgumentException} in every adapter, so that {@code now + ttl} stays far inside the
   * range of the instants of the real store (a {@code timestamptz} ends in the year 294276).
   */
  Duration MAX_TTL = Duration.ofDays(36_500);

  /**
   * The four texts of an endpoint. Each is a key text (no control character, no unpaired surrogate,
   * at most {@link KeyText#MAX_ENDPOINT_PART_CHARS} chars: four of them make one index row), else
   * {@link IllegalArgumentException}; none may be null.
   */
  record EndpointKey(
      String environment,
      String submissionRoute,
      String submittingEntityLei,
      String destinationAuthority) {
    public EndpointKey {
      KeyText.require("environment", environment, KeyText.MAX_ENDPOINT_PART_CHARS);
      KeyText.require("submissionRoute", submissionRoute, KeyText.MAX_ENDPOINT_PART_CHARS);
      KeyText.require("submittingEntityLei", submittingEntityLei, KeyText.MAX_ENDPOINT_PART_CHARS);
      KeyText.require(
          "destinationAuthority", destinationAuthority, KeyText.MAX_ENDPOINT_PART_CHARS);
    }
  }

  /** Fencing token: ordered by restore epoch first, then by the per-endpoint counter. */
  record LeaseToken(long restoreEpoch, long counter) implements Comparable<LeaseToken> {
    private static final Comparator<LeaseToken> ORDER =
        Comparator.comparingLong(LeaseToken::restoreEpoch).thenComparingLong(LeaseToken::counter);

    @Override
    public int compareTo(LeaseToken other) {
      return ORDER.compare(this, other);
    }
  }

  record LeaseGrant(LeaseToken token, String holder, Instant expiresAt) {}

  sealed interface LeaseResult {
    record Granted(LeaseGrant grant) implements LeaseResult {}

    record Held(String holder, Instant until) implements LeaseResult {}
  }

  /**
   * Grants the lease when there is none, it was released, or {@code now >= expiresAt}; the counter
   * of the endpoint is incremented per acquisition, and every {@code SENDING} delivery of the
   * endpoint moves to {@code IN_DOUBT} in the same unit (the takeover rule). A blank {@code
   * holder}, a {@code holder} that is not a key text ({@link KeyText}: no control character, no
   * unpaired surrogate, at most {@link KeyText#MAX_CHARS} chars), and a {@code ttl} that is not
   * positive whole microseconds or is longer than {@link #MAX_TTL}, throw {@link
   * IllegalArgumentException} (M48) and grant nothing.
   */
  LeaseResult acquire(EndpointKey endpoint, String holder, Duration ttl);

  /**
   * True only for the current token while {@code now < expiresAt}; then {@code now + ttl}. A {@code
   * ttl} that is not positive whole microseconds or is longer than {@link #MAX_TTL} is an {@link
   * IllegalArgumentException}.
   */
  boolean renew(EndpointKey endpoint, LeaseToken token, Duration ttl);

  /** Releases the lease if {@code token} is current; any other token is ignored. */
  void release(EndpointKey endpoint, LeaseToken token);

  /**
   * The current lease: the latest grant of the endpoint unless it was released or voided by a
   * restore epoch. It stays current after its expiry until a takeover or release, so the caller
   * compares {@code expiresAt} with its clock; a token is fenced only by a newer grant, a release
   * or a restore epoch. The fence has no expiry predicate (M50, M51): that is safe because every
   * acquisition moves the {@code SENDING} deliveries of the endpoint to {@code IN_DOUBT} in the
   * same unit, so an expired holder that has not been taken over can only continue what it owns,
   * and {@link #renew} is the only call that refuses on expiry.
   */
  Optional<LeaseGrant> currentLease(EndpointKey endpoint);

  long restoreEpoch();

  /**
   * Declares a new restore epoch: it must exceed the current epoch (else {@link
   * IllegalArgumentException}), voids every lease and restarts every endpoint counter, so the next
   * acquisition is allowed at once and gets the token ({@code epoch}, 1).
   */
  void declareRestoreEpoch(long epoch);
}
