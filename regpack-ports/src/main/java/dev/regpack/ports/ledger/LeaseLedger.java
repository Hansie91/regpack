package dev.regpack.ports.ledger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/**
 * Per-endpoint send lease with a fencing token (spec 10, 11.2, 11.5 A.2). Time is the ledger's own
 * clock, never the caller's.
 */
public interface LeaseLedger {
  record EndpointKey(
      String environment,
      String submissionRoute,
      String submittingEntityLei,
      String destinationAuthority) {}

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
   * endpoint moves to {@code IN_DOUBT} in the same unit (the takeover rule).
   */
  LeaseResult acquire(EndpointKey endpoint, String holder, Duration ttl);

  /** True only for the current token while {@code now < expiresAt}; then {@code now + ttl}. */
  boolean renew(EndpointKey endpoint, LeaseToken token, Duration ttl);

  /** Releases the lease if {@code token} is current; any other token is ignored. */
  void release(EndpointKey endpoint, LeaseToken token);

  /**
   * The current lease: the latest grant of the endpoint unless it was released or voided by a
   * restore epoch. It stays current after its expiry until a takeover or release, so the caller
   * compares {@code expiresAt} with its clock; a token is fenced only by a newer grant, a release
   * or a restore epoch.
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
