package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.attemptIntent;
import static dev.regpack.testkit.contract.LedgerFixtures.begin;
import static dev.regpack.testkit.contract.LedgerFixtures.endpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.queuedDelivery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.DeliveryLedger.TransitionStatus;
import dev.regpack.ports.ledger.DuplicateKeyException;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseGrant;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult.Granted;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult.Held;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link LedgerStore} adapter must satisfy for leases, deliveries, the in-flight
 * guard and fencing (spec 10, 11.2, 11.5 A.2). Run against the in-memory adapter these are
 * ledger-level building blocks of I1, I2 and I9, not evidence for them (spec 14.1).
 */
public interface LeaseDeliveryLedgerContract {
  EndpointKey E1 = endpoint("1");
  EndpointKey E2 = endpoint("2");
  Duration TTL = Duration.ofSeconds(60);

  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  /** The clock the ledger reads; its start instant is the same for every test. */
  ManualClock clock();

  private static LeaseGrant granted(LeaseResult result) {
    assertThat(result).isInstanceOf(Granted.class);
    return ((Granted) result).grant();
  }

  private LeaseToken acquire(EndpointKey endpoint, String holder) {
    return granted(ledger().acquire(endpoint, holder, TTL)).token();
  }

  private DeliveryState stateOf(DeliveryId id) {
    return ledger().delivery(id).orElseThrow().state();
  }

  private static AttemptId applied(SendingResult result) {
    assertThat(result.status()).isEqualTo(SendingStatus.APPLIED);
    return result.attempt().orElseThrow();
  }

  private void toState(DeliveryId id, DeliveryState from, DeliveryState to) {
    assertThat(ledger().transition(id, from, to)).isEqualTo(TransitionStatus.APPLIED);
  }

  @Test
  default void firstAcquireGrantsTokenOneInEpochZero() {
    Instant start = clock().now();
    LeaseGrant grant = granted(ledger().acquire(E1, "A", TTL));
    assertThat(grant.token()).isEqualTo(new LeaseToken(0, 1));
    assertThat(grant.holder()).isEqualTo("A");
    assertThat(grant.expiresAt()).isEqualTo(start.plus(TTL));
    assertThat(ledger().currentLease(E1)).contains(grant);
  }

  /** M48: a blank holder and a ttl that is not positive whole microseconds are errors. */
  @Test
  default void acquireRejectsBlankHolderAndBadTtl() {
    for (String blank : new String[] {"", " ", "\t"}) {
      assertThatThrownBy(() -> ledger().acquire(E1, blank, TTL))
          .as("holder '" + blank + "'")
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Duration bad :
        new Duration[] {
          Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1_500), Duration.ofNanos(1)
        }) {
      assertThatThrownBy(() -> ledger().acquire(E1, "A", bad))
          .as("ttl " + bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    // nothing was granted by a rejected call: the first real acquire still gets counter 1
    assertThat(ledger().currentLease(E1)).isEmpty();
    assertThat(acquire(E1, "A")).isEqualTo(new LeaseToken(0, 1));
    // the smallest legal ttl is one microsecond
    assertThat(granted(ledger().acquire(E2, "A", Duration.of(1, ChronoUnit.MICROS))).token())
        .isEqualTo(new LeaseToken(0, 1));
  }

  @Test
  default void secondAcquireWhileHeldIsRefused() {
    Instant start = clock().now();
    acquire(E1, "A");
    assertThat(ledger().acquire(E1, "B", TTL)).isEqualTo(new Held("A", start.plus(TTL)));
  }

  @Test
  default void leaseExpiresAtTtlBoundary() {
    acquire(E1, "A");
    clock().advance(Duration.ofSeconds(59, 999_999_000));
    assertThat(ledger().acquire(E1, "B", TTL)).isInstanceOf(Held.class);
    clock().advance(Duration.of(1, ChronoUnit.MICROS));
    assertThat(granted(ledger().acquire(E1, "B", TTL)).token()).isEqualTo(new LeaseToken(0, 2));
  }

  @Test
  default void renewExtendsOnlyForTheCurrentToken() {
    Instant start = clock().now();
    LeaseToken t1 = acquire(E1, "A");
    clock().advance(Duration.ofSeconds(30));
    LeaseToken c = acquire(E2, "C");
    assertThat(ledger().renew(E1, t1, TTL)).isTrue();
    assertThat(ledger().currentLease(E1).orElseThrow().expiresAt())
        .isEqualTo(start.plusSeconds(90));
    // both leases are now at their expiry instant (E2 was acquired at +30 s, E1 renewed to +90 s)
    clock().advance(Duration.ofSeconds(60));
    // the current token of E2 after its expiry but before any takeover
    assertThat(ledger().renew(E2, c, TTL)).isFalse();
    LeaseToken t2 = acquire(E1, "B");
    assertThat(t2).isEqualTo(new LeaseToken(0, 2));
    assertThat(ledger().renew(E1, t1, TTL)).isFalse();
    assertThat(ledger().renew(E1, t2, TTL)).isTrue();
  }

  @Test
  default void cleanReleaseAllowsImmediateAcquireAndTokenStillIncrements() {
    LeaseToken t1 = acquire(E1, "A");
    ledger().release(E1, t1);
    assertThat(ledger().currentLease(E1)).isEmpty();
    LeaseGrant second = granted(ledger().acquire(E1, "B", TTL));
    assertThat(second.token()).isEqualTo(new LeaseToken(0, 2));
    ledger().release(E1, t1);
    assertThat(ledger().currentLease(E1)).contains(second);
  }

  @Test
  default void restoreEpochRaisesTokenPairAndNoOldTokenEqualsNewOne() {
    LeaseToken first = acquire(E1, "A");
    clock().advance(TTL);
    LeaseToken second = acquire(E1, "B");
    assertThat(first).isEqualTo(new LeaseToken(0, 1));
    assertThat(second).isEqualTo(new LeaseToken(0, 2));
    ledger().declareRestoreEpoch(1);
    LeaseToken third = acquire(E1, "C");
    assertThat(third).isEqualTo(new LeaseToken(1, 1));
    assertThat(third).isNotEqualTo(first).isNotEqualTo(second);
    assertThat(third.compareTo(second)).isPositive();
    assertThatThrownBy(() -> ledger().declareRestoreEpoch(1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ledger().declareRestoreEpoch(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger().restoreEpoch()).isEqualTo(1);
  }

  /** The restore-then-zombie case of spec 14.4. */
  @Test
  default void restoreThenZombieStaleTokenIsFencedEvenBeforeExpiry() {
    LeaseToken t1 = acquire(E1, "A");
    DeliveryId d1 = queuedDelivery(ledger(), E1, "bk1", 1);
    DeliveryId d2 = queuedDelivery(ledger(), E1, "bk2", 1);
    AttemptId a1 = applied(begin(ledger(), d1, t1));
    ledger().declareRestoreEpoch(1);
    assertThat(begin(ledger(), d2, t1).status()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(stateOf(d2)).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger().markCommitIssued(a1, t1)).isFalse();
    assertThat(ledger().attempt(a1).orElseThrow().commitIssued()).isFalse();
    assertThat(ledger().renew(E1, t1, TTL)).isFalse();
    assertThat(ledger().currentLease(E1)).isEmpty();
    ledger().release(E1, t1);
    LeaseToken t2 = acquire(E1, "B");
    assertThat(t2).isEqualTo(new LeaseToken(1, 1));
    assertThat(ledger().markCommitIssued(a1, t1)).isFalse();
    assertThat(begin(ledger(), d2, t1).status()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(stateOf(d1)).isEqualTo(DeliveryState.IN_DOUBT);
  }

  @Test
  default void acquireAfterRestoreIsImmediateAndStartsANewEpochPair() {
    LeaseToken old = acquire(E1, "A");
    ledger().declareRestoreEpoch(1);
    assertThat(acquire(E1, "B")).isEqualTo(new LeaseToken(1, 1));
    assertThat(ledger().renew(E1, old, TTL)).isFalse();
    clock().advance(TTL);
    assertThat(acquire(E1, "C")).isEqualTo(new LeaseToken(1, 2));
  }

  @Test
  default void leaseIsPerEndpoint() {
    acquire(E1, "A");
    assertThat(acquire(E2, "B")).isEqualTo(new LeaseToken(0, 1));
  }

  @Test
  default void createDeliveryRejectsDuplicateKey() {
    NewDelivery key = new NewDelivery(E1, 1, "bk", 1);
    DeliveryId id = ledger().createDelivery(key);
    assertThat(ledger().delivery(id).orElseThrow().state()).isEqualTo(DeliveryState.PREPARED);
    assertThat(ledger().delivery(id).orElseThrow().key()).isEqualTo(key);
    assertThatThrownBy(() -> ledger().createDelivery(key))
        .isInstanceOf(DuplicateKeyException.class);
    DeliveryId second = ledger().createDelivery(new NewDelivery(E1, 1, "bk", 2));
    assertThat(second).isNotEqualTo(id);
    assertThat(stateOf(second)).isEqualTo(DeliveryState.PREPARED);
  }

  /** M49: a blank business key is an error, and no delivery is created. */
  @Test
  default void createDeliveryRejectsBlankBusinessKey() {
    for (String blank : new String[] {"", " ", "\t"}) {
      assertThatThrownBy(() -> ledger().createDelivery(new NewDelivery(E1, 1, blank, 1)))
          .as("business key '" + blank + "'")
          .isInstanceOf(IllegalArgumentException.class);
    }
    // nothing was created by a rejected call: the first real delivery is not a duplicate
    DeliveryId id = ledger().createDelivery(new NewDelivery(E1, 1, "bk", 1));
    assertThat(stateOf(id)).isEqualTo(DeliveryState.PREPARED);
  }

  /** M52: an unknown delivery is an error for transition and beginSending, not a status. */
  @Test
  default void unknownDeliveryIsRejectedByTransitionAndBeginSending() {
    LeaseToken t1 = acquire(E1, "A");
    DeliveryId unknown = new DeliveryId(987_654);
    assertThat(ledger().delivery(unknown)).isEmpty();
    assertThatThrownBy(
            () -> ledger().transition(unknown, DeliveryState.PREPARED, DeliveryState.QUEUED))
        .isInstanceOf(IllegalArgumentException.class);
    AttemptId attemptId = ledger().allocateAttemptId();
    assertThatThrownBy(() -> ledger().beginSending(unknown, attemptId, t1, attemptIntent()))
        .isInstanceOf(IllegalArgumentException.class);
    // the rejected call consumed nothing: the allocated id is still usable
    assertThat(ledger().attempt(attemptId)).isEmpty();
    DeliveryId real = queuedDelivery(ledger(), E1, "bk", 1);
    assertThat(applied(ledger().beginSending(real, attemptId, t1, attemptIntent())))
        .isEqualTo(attemptId);
  }

  /** M52: marking an attempt that does not exist is false, not an error. */
  @Test
  default void markCommitIssuedOfAnUnknownAttemptIsFalse() {
    LeaseToken t1 = acquire(E1, "A");
    assertThat(ledger().markCommitIssued(new AttemptId(987_654), t1)).isFalse();
    AttemptId allocatedButUnused = ledger().allocateAttemptId();
    assertThat(ledger().markCommitIssued(allocatedButUnused, t1)).isFalse();
    assertThat(ledger().attempt(allocatedButUnused)).isEmpty();
  }

  /**
   * M50 and M51: the fence compares the token with the latest unreleased grant and has no expiry
   * predicate. That is safe because every acquisition moves the SENDING deliveries of the endpoint
   * to IN_DOUBT in the same unit, so an expired holder that has not been taken over can only
   * continue what it already owns.
   */
  @Test
  default void anExpiredButUntakenLeaseStillFencesItsOwnTokenAndNothingElse() {
    LeaseToken t1 = acquire(E1, "A");
    DeliveryId d1 = queuedDelivery(ledger(), E1, "bk1", 1);
    DeliveryId d2 = queuedDelivery(ledger(), E1, "bk2", 1);
    clock().advance(TTL.plus(Duration.of(1, ChronoUnit.MICROS)));
    assertThat(ledger().currentLease(E1).orElseThrow().token()).isEqualTo(t1);
    // expired but not taken over: its own token is still current for the intent ...
    AttemptId a1 = applied(begin(ledger(), d1, t1));
    // ... but it can no longer be renewed
    assertThat(ledger().renew(E1, t1, TTL)).isFalse();
    // a token that is not the latest grant is fenced even before any takeover
    assertThat(begin(ledger(), d2, new LeaseToken(0, 7)).status())
        .isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(begin(ledger(), d2, new LeaseToken(1, 1)).status())
        .isEqualTo(SendingStatus.STALE_LEASE);
    // the takeover grants counter + 1 and fences the old token
    LeaseGrant second = granted(ledger().acquire(E1, "B", TTL));
    assertThat(second.token()).isEqualTo(new LeaseToken(0, 2));
    assertThat(begin(ledger(), d2, t1).status()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(ledger().markCommitIssued(a1, t1)).isFalse();
    assertThat(stateOf(d1)).isEqualTo(DeliveryState.IN_DOUBT);
    assertThat(stateOf(d2)).isEqualTo(DeliveryState.QUEUED);
    assertThat(applied(begin(ledger(), d2, second.token()))).isNotNull();
  }

  @Test
  default void beginSendingRequiresCurrentLeaseAndQueuedState() {
    LeaseToken t1 = acquire(E1, "A");
    DeliveryId queued = queuedDelivery(ledger(), E1, "bk-queued", 1);
    SendingResult ok = begin(ledger(), queued, t1);
    AttemptId attemptId = applied(ok);
    assertThat(stateOf(queued)).isEqualTo(DeliveryState.SENDING);
    var attempt = ledger().attempt(attemptId).orElseThrow();
    assertThat(attempt.commitIssued()).isFalse();
    assertThat(attempt.delivery()).isEqualTo(queued);
    assertThat(attempt.token()).isEqualTo(t1);
    assertThat(attempt.fileName()).isEqualTo(attemptIntent().fileName());
    assertThat(attempt.artifactSha256()).isEqualTo(attemptIntent().artifactSha256());

    DeliveryId prepared = ledger().createDelivery(new NewDelivery(E1, 1, "bk-prepared", 1));
    SendingResult mismatch = begin(ledger(), prepared, t1);
    assertThat(mismatch.status()).isEqualTo(SendingStatus.STATE_MISMATCH);
    assertThat(mismatch.attempt()).isEmpty();
    assertThat(stateOf(prepared)).isEqualTo(DeliveryState.PREPARED);

    clock().advance(TTL);
    LeaseToken t2 = acquire(E1, "B");
    DeliveryId other = queuedDelivery(ledger(), E1, "bk-other", 1);
    SendingResult stale = begin(ledger(), other, t1);
    assertThat(stale.status()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(stale.attempt()).isEmpty();
    assertThat(stateOf(other)).isEqualTo(DeliveryState.QUEUED);
    // both wrong: the fence is checked first
    assertThat(begin(ledger(), prepared, t1).status()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(applied(begin(ledger(), other, t2))).isNotNull();
  }

  @Test
  default void inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey() {
    LeaseToken t1 = acquire(E1, "A");
    LeaseToken t2 = acquire(E2, "A");
    DeliveryId v1 = queuedDelivery(ledger(), E1, "bk", 1);
    DeliveryId v2 = queuedDelivery(ledger(), E1, "bk", 2);
    applied(begin(ledger(), v1, t1));
    assertThat(begin(ledger(), v2, t1).status()).isEqualTo(SendingStatus.IN_FLIGHT_CONFLICT);
    assertThat(stateOf(v2)).isEqualTo(DeliveryState.QUEUED);
    // other business keys on E1 and the same business key on E2 are never blocked
    DeliveryId otherKey = queuedDelivery(ledger(), E1, "bk-other", 1);
    applied(begin(ledger(), otherKey, t1));
    DeliveryId otherEndpoint = queuedDelivery(ledger(), E2, "bk", 1);
    applied(begin(ledger(), otherEndpoint, t2));
    toState(v1, DeliveryState.SENDING, DeliveryState.AWAITING_ACK);
    toState(v1, DeliveryState.AWAITING_ACK, DeliveryState.ACCEPTED_PROVISIONAL);
    applied(begin(ledger(), v2, t1));
    assertThat(stateOf(v2)).isEqualTo(DeliveryState.SENDING);
  }

  @Test
  default void inFlightGuardAlsoCoversAwaitingAckAndInDoubt() {
    LeaseToken t1 = acquire(E1, "A");
    DeliveryId v1 = queuedDelivery(ledger(), E1, "bk", 1);
    DeliveryId v2 = queuedDelivery(ledger(), E1, "bk", 2);
    DeliveryId v3 = queuedDelivery(ledger(), E1, "bk", 3);
    applied(begin(ledger(), v1, t1));
    // Moving within the in-flight states is never blocked by a QUEUED sibling.
    assertThat(ledger().transition(v1, DeliveryState.SENDING, DeliveryState.AWAITING_ACK))
        .isEqualTo(TransitionStatus.APPLIED);
    assertThat(begin(ledger(), v2, t1).status()).isEqualTo(SendingStatus.IN_FLIGHT_CONFLICT);
    assertThat(ledger().transition(v1, DeliveryState.AWAITING_ACK, DeliveryState.IN_DOUBT))
        .isEqualTo(TransitionStatus.APPLIED);
    assertThat(stateOf(v1)).isEqualTo(DeliveryState.IN_DOUBT);
    assertThat(stateOf(v2)).isEqualTo(DeliveryState.QUEUED);
    assertThat(begin(ledger(), v2, t1).status()).isEqualTo(SendingStatus.IN_FLIGHT_CONFLICT);
    toState(v3, DeliveryState.QUEUED, DeliveryState.REJECTED);
    // Entering the in-flight set from outside it is guarded for both targets.
    assertThat(ledger().transition(v3, DeliveryState.REJECTED, DeliveryState.IN_DOUBT))
        .isEqualTo(TransitionStatus.IN_FLIGHT_CONFLICT);
    assertThat(ledger().transition(v3, DeliveryState.REJECTED, DeliveryState.AWAITING_ACK))
        .isEqualTo(TransitionStatus.IN_FLIGHT_CONFLICT);
    assertThat(stateOf(v3)).isEqualTo(DeliveryState.REJECTED);
  }

  @Test
  default void transitionIsCompareAndSetOnExpectedState() {
    DeliveryId id = ledger().createDelivery(new NewDelivery(E1, 1, "bk", 1));
    assertThat(ledger().transition(id, DeliveryState.QUEUED, DeliveryState.PREPARED))
        .isEqualTo(TransitionStatus.STATE_MISMATCH);
    assertThat(stateOf(id)).isEqualTo(DeliveryState.PREPARED);
    assertThat(ledger().transition(id, DeliveryState.PREPARED, DeliveryState.QUEUED))
        .isEqualTo(TransitionStatus.APPLIED);
    assertThat(stateOf(id)).isEqualTo(DeliveryState.QUEUED);
  }

  @Test
  default void transitionIntoSendingMustUseBeginSending() {
    DeliveryId id = queuedDelivery(ledger(), E1, "bk", 1);
    assertThatThrownBy(() -> ledger().transition(id, DeliveryState.QUEUED, DeliveryState.SENDING))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(stateOf(id)).isEqualTo(DeliveryState.QUEUED);
  }

  @Test
  default void markCommitIssuedIsFencedCompareAndSet() {
    LeaseToken older = acquire(E1, "A");
    ledger().release(E1, older);
    LeaseToken current = acquire(E1, "B");
    DeliveryId d = queuedDelivery(ledger(), E1, "bk", 1);
    AttemptId a = applied(begin(ledger(), d, current));
    assertThat(ledger().markCommitIssued(a, older)).isFalse();
    assertThat(ledger().attempt(a).orElseThrow().commitIssued()).isFalse();
    assertThat(ledger().markCommitIssued(a, current)).isTrue();
    assertThat(ledger().attempt(a).orElseThrow().commitIssued()).isTrue();
    assertThat(ledger().markCommitIssued(a, current)).isTrue();

    DeliveryId d2 = queuedDelivery(ledger(), E1, "bk2", 1);
    AttemptId a2 = applied(begin(ledger(), d2, current));
    toState(d2, DeliveryState.SENDING, DeliveryState.AWAITING_ACK);
    assertThat(ledger().markCommitIssued(a2, current)).isFalse();
    assertThat(ledger().attempt(a2).orElseThrow().commitIssued()).isFalse();
  }

  /** The stall-after-token-check case of spec 14.4. */
  @Test
  default void takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder() {
    LeaseToken t1 = acquire(E1, "A");
    DeliveryId d1 = queuedDelivery(ledger(), E1, "bk1", 1);
    DeliveryId d2 = queuedDelivery(ledger(), E1, "bk2", 1);
    AttemptId a1 = applied(begin(ledger(), d1, t1));
    // The takeover is scoped to the endpoint and to SENDING: another endpoint's SENDING delivery
    // and an AWAITING_ACK delivery of this endpoint must stay as they are.
    DeliveryId awaiting = queuedDelivery(ledger(), E1, "bk-awaiting", 1);
    applied(begin(ledger(), awaiting, t1));
    toState(awaiting, DeliveryState.SENDING, DeliveryState.AWAITING_ACK);
    LeaseToken otherEndpointToken = acquire(E2, "A");
    DeliveryId otherEndpointSending = queuedDelivery(ledger(), E2, "bk-other", 1);
    applied(begin(ledger(), otherEndpointSending, otherEndpointToken));
    // holder A has checked its token and now stalls before the marker commit
    clock().advance(Duration.ofSeconds(61));
    LeaseToken t2 = acquire(E1, "B");
    assertThat(t2).isNotEqualTo(t1);
    assertThat(stateOf(d1)).isEqualTo(DeliveryState.IN_DOUBT);
    assertThat(stateOf(awaiting)).isEqualTo(DeliveryState.AWAITING_ACK);
    assertThat(stateOf(otherEndpointSending)).isEqualTo(DeliveryState.SENDING);
    assertThat(ledger().markCommitIssued(a1, t1)).isFalse();
    assertThat(ledger().attempt(a1).orElseThrow().commitIssued()).isFalse();
    assertThat(begin(ledger(), d2, t1).status()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(stateOf(d2)).isEqualTo(DeliveryState.QUEUED);
  }

  /**
   * Meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec
   * 14.1).
   */
  @Test
  default void concurrentAcquireGrantsExactlyOneHolder() throws Exception {
    int threads = 16;
    List<LeaseResult> results = runConcurrently(threads, i -> ledger().acquire(E1, "h" + i, TTL));
    assertThat(results.stream().filter(r -> r instanceof Granted).count()).isEqualTo(1);
    assertThat(results.stream().filter(r -> r instanceof Held).count()).isEqualTo(threads - 1);
  }

  /**
   * The takeover of an expired lease under a race (TEETH-11): after the lease has expired, many
   * contenders acquire at once and exactly one is granted, with the next counter of the endpoint;
   * the others see the new holder. Repeated, so that every round races on a lease row that already
   * exists and is expired, not on the insert of the first one. Meaningful only against a real
   * store; the single-lock in-memory adapter passes trivially (spec 14.1).
   */
  @Test
  default void concurrentTakeoverOfAnExpiredLeaseGrantsExactlyOneHolder() throws Exception {
    int threads = 16;
    LeaseToken previous = acquire(E1, "first");
    for (int round = 1; round <= 3; round++) {
      clock().advance(TTL);
      int current = round;
      List<LeaseResult> results =
          runConcurrently(threads, i -> ledger().acquire(E1, "r" + current + "h" + i, TTL));
      List<Granted> granted =
          results.stream().filter(r -> r instanceof Granted).map(r -> (Granted) r).toList();
      assertThat(granted).as("round " + round).hasSize(1);
      assertThat(results.stream().filter(r -> r instanceof Held).count())
          .as("round " + round)
          .isEqualTo(threads - 1);
      LeaseToken token = granted.get(0).grant().token();
      assertThat(token.restoreEpoch()).isEqualTo(previous.restoreEpoch());
      assertThat(token.counter()).as("round " + round).isEqualTo(previous.counter() + 1);
      String winner = granted.get(0).grant().holder();
      assertThat(
              results.stream()
                  .filter(r -> r instanceof Held)
                  .map(r -> ((Held) r).holder())
                  .distinct())
          .as("every loser sees the winner")
          .containsExactly(winner);
      previous = token;
    }
  }

  /**
   * Meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec
   * 14.1).
   */
  @Test
  default void concurrentBeginSendingForSameBusinessKeyAppliesExactlyOnce() throws Exception {
    int threads = 8;
    LeaseToken token = acquire(E1, "A");
    List<DeliveryId> deliveries = new ArrayList<>();
    for (int i = 1; i <= threads; i++) {
      deliveries.add(queuedDelivery(ledger(), E1, "bk", i));
    }
    List<SendingResult> results =
        runConcurrently(threads, i -> begin(ledger(), deliveries.get(i), token));
    assertThat(results.stream().filter(r -> r.status() == SendingStatus.APPLIED).count())
        .isEqualTo(1);
    assertThat(results.stream().filter(r -> r.status() == SendingStatus.IN_FLIGHT_CONFLICT).count())
        .isEqualTo(threads - 1);
    assertThat(deliveries.stream().filter(d -> stateOf(d) == DeliveryState.SENDING).count())
        .isEqualTo(1);
  }

  @Test
  default void attemptIdsAreStrictlyIncreasingAndStableUntilUsed() {
    LeaseToken t1 = acquire(E1, "A");
    clock().advance(TTL);
    LeaseToken t2 = acquire(E1, "B");
    DeliveryId d = queuedDelivery(ledger(), E1, "bk", 1);
    AttemptId first = ledger().allocateAttemptId();
    AttemptId second = ledger().allocateAttemptId();
    assertThat(ledger().beginSending(d, second, t1, attemptIntent()).status())
        .isEqualTo(SendingStatus.STALE_LEASE);
    AttemptId third = ledger().allocateAttemptId();
    assertThat(List.of(first.value(), second.value(), third.value())).containsExactly(1L, 2L, 3L);
    SendingResult result = ledger().beginSending(d, second, t2, attemptIntent());
    assertThat(applied(result)).isEqualTo(second);
  }

  /** The orphan-intent case of spec 11.5 B.5. */
  @Test
  default void allocatedButUnusedAttemptIdLeavesNoAttemptRow() {
    LeaseToken t1 = acquire(E1, "A");
    clock().advance(TTL);
    LeaseToken t2 = acquire(E1, "B");
    DeliveryId d = queuedDelivery(ledger(), E1, "bk", 1);
    AttemptId id = ledger().allocateAttemptId();
    assertThat(ledger().beginSending(d, id, t1, attemptIntent()).status())
        .isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(ledger().attempt(id)).isEmpty();
    assertThat(stateOf(d)).isEqualTo(DeliveryState.QUEUED);
    assertThat(applied(ledger().beginSending(d, id, t2, attemptIntent()))).isEqualTo(id);
    assertThat(ledger().attempt(id)).isPresent();
  }

  @Test
  default void beginSendingRejectsUnallocatedAndReusedAttemptIds() {
    LeaseToken t1 = acquire(E1, "A");
    DeliveryId d1 = queuedDelivery(ledger(), E1, "bk1", 1);
    DeliveryId d2 = queuedDelivery(ledger(), E1, "bk2", 1);
    AttemptId neverAllocated = new AttemptId(42);
    assertThatThrownBy(() -> ledger().beginSending(d1, neverAllocated, t1, attemptIntent()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(stateOf(d1)).isEqualTo(DeliveryState.QUEUED);
    AttemptId id = ledger().allocateAttemptId();
    assertThat(applied(ledger().beginSending(d1, id, t1, attemptIntent()))).isEqualTo(id);
    assertThatThrownBy(() -> ledger().beginSending(d2, id, t1, attemptIntent()))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(stateOf(d2)).isEqualTo(DeliveryState.QUEUED);
  }

  private <T> List<T> runConcurrently(int threads, IntFunction<T> work) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        int index = i;
        Callable<T> task =
            () -> {
              release.await();
              return work.apply(index);
            };
        futures.add(pool.submit(task));
      }
      release.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> f : futures) {
        results.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
