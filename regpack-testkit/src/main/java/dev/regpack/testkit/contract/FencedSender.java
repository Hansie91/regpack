package dev.regpack.testkit.contract;

import dev.regpack.ports.connector.Transport;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerStore;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Test support: the sender side of spec 11.2 steps 3 to 5 written against the ledger and transport
 * ports, so that the fencing scenarios run on every adapter. It is not the sender of P8: it has no
 * state machine, no retry and no evidence store.
 *
 * <p>One instance is one sender process with at most one lease token. {@link #acquire} may be
 * called again after a takeover; {@link #send} then uses the latest token this instance obtained.
 */
public final class FencedSender {
  /** What one {@link #send} did: the fenced begin and, if it was applied, the transport result. */
  public record SendOutcome(SendingStatus begin, Optional<Transport.SendResult> result) {
    public SendOutcome {
      Objects.requireNonNull(begin, "begin");
      Objects.requireNonNull(result, "result");
      if (result.isPresent() != (begin == SendingStatus.APPLIED)) {
        throw new IllegalArgumentException(
            "the transport is invoked if and only if the begin was APPLIED: " + begin);
      }
    }
  }

  private final LedgerStore ledger;
  private final Transport transport;
  private final LeaseLedger.EndpointKey endpoint;
  private final String holder;
  private final Duration ttl;
  private volatile LeaseToken token;
  private volatile AttemptId lastAttempt;

  public FencedSender(
      LedgerStore ledger,
      Transport transport,
      LeaseLedger.EndpointKey endpoint,
      String holder,
      Duration ttl) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.holder = Objects.requireNonNull(holder, "holder");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  /** Acquires the lease of the endpoint; empty when it is held by another holder. */
  public Optional<LeaseToken> acquire() {
    LeaseResult result = ledger.acquire(endpoint, holder, ttl);
    if (result instanceof LeaseResult.Granted granted) {
      token = granted.grant().token();
      return Optional.of(token);
    }
    return Optional.empty();
  }

  /** The token of the latest successful {@link #acquire} of this sender. */
  public Optional<LeaseToken> token() {
    return Optional.ofNullable(token);
  }

  /**
   * The attempt id of the latest {@link #send} whose begin was applied. An accessor added to the
   * plan's signature so that a scenario can read the attempt back from the ledger.
   */
  public Optional<AttemptId> lastAttempt() {
    return Optional.ofNullable(lastAttempt);
  }

  /**
   * Allocates an attempt id and calls {@code beginSending} with the held token. When that is
   * applied, hands the file to the transport with a commit gate that first runs {@code
   * beforeMarker} (the scenario's hook for a stall or a takeover) and then returns the result of
   * the fenced {@code markCommitIssued}; when it is not, the transport is never called.
   *
   * @throws IllegalStateException if this sender never acquired a lease
   */
  public SendOutcome send(DeliveryId delivery, Transport.OutboundFile file, Runnable beforeMarker) {
    Objects.requireNonNull(delivery, "delivery");
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(beforeMarker, "beforeMarker");
    LeaseToken held = token;
    if (held == null) {
      throw new IllegalStateException("the sender holds no lease token: acquire() first");
    }
    AttemptId attemptId = ledger.allocateAttemptId();
    SendingResult begin =
        ledger.beginSending(
            delivery, attemptId, held, new AttemptIntent(file.sha256(), file.fileName()));
    if (begin.status() != DeliveryLedger.SendingStatus.APPLIED) {
      return new SendOutcome(begin.status(), Optional.empty());
    }
    AttemptId attempt = begin.attempt().orElseThrow();
    lastAttempt = attempt;
    Transport.SendResult result =
        transport.send(
            file,
            () -> {
              beforeMarker.run();
              return ledger.markCommitIssued(attempt, held);
            });
    return new SendOutcome(SendingStatus.APPLIED, Optional.of(result));
  }
}
