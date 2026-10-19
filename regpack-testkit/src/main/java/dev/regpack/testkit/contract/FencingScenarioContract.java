package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.endpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.queuedDelivery;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.Transport;
import dev.regpack.ports.connector.Transport.OutboundFile;
import dev.regpack.ports.connector.Transport.SendStatus;
import dev.regpack.ports.ledger.DeliveryLedger.Attempt;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.FencedSender.SendOutcome;
import dev.regpack.testkit.sim.DestinationSimulator;
import dev.regpack.testkit.sim.Scenarios;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The I9 scenarios of spec 14.4 and 11.2 on the ledger and transport ports: two concurrent senders,
 * a takeover during an upload, a stall after the token check, and a restore followed by a zombie.
 * The destination is the P0 simulator; every sender is a {@link FencedSender}. Run against the
 * in-memory adapter these are building blocks of I9, not evidence for it (spec 14.1): the evidence
 * is the run of the same suite on real PostgreSQL.
 */
public interface FencingScenarioContract {
  Duration FENCING_TTL = Duration.ofSeconds(60);
  long WAIT_SECONDS = 20;

  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  /** The clock the ledger reads; its start instant is the same for every test. */
  ManualClock clock();

  private DestinationSimulator destination() {
    return DestinationSimulator.withScenario(clock(), Scenarios.acpt(Duration.ofSeconds(1)));
  }

  private static OutboundFile file(String id) {
    byte[] bytes = ("file " + id).getBytes(StandardCharsets.UTF_8);
    return new OutboundFile(id, id + ".xml", bytes, Sha256.of(bytes), "application/xml");
  }

  private DeliveryState stateOf(DeliveryId id) {
    return ledger().delivery(id).orElseThrow().state();
  }

  private Attempt attemptOf(FencedSender sender) {
    return ledger().attempt(sender.lastAttempt().orElseThrow()).orElseThrow();
  }

  private static <T> T get(Future<T> future) throws Exception {
    return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("a latch was not released within " + WAIT_SECONDS + " seconds");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for a latch", e);
    }
  }

  /** Moves the clock past the ttl of a lease that was just granted. */
  private void expire() {
    clock().advance(FENCING_TTL);
  }

  @Test
  default void twoConcurrentSendersOnlyOneReachesTheDestination() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 20; round++) {
        EndpointKey ep = endpoint("RACE" + round);
        DestinationSimulator destination = destination();
        DeliveryId delivery = queuedDelivery(ledger(), ep, "EXEC1|RACE" + round, 1);
        FencedSender a = new FencedSender(ledger(), destination, ep, "A", FENCING_TTL);
        FencedSender b = new FencedSender(ledger(), destination, ep, "B", FENCING_TTL);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger sent = new AtomicInteger();
        List<Future<Boolean>> senders = new ArrayList<>();
        for (FencedSender sender : List.of(a, b)) {
          String fileId = "race-" + round + (sender == a ? "-a" : "-b");
          senders.add(
              pool.submit(
                  () -> {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    if (sender.acquire().isEmpty()) {
                      return false;
                    }
                    SendOutcome outcome = sender.send(delivery, file(fileId), () -> {});
                    assertThat(outcome.begin()).isEqualTo(SendingStatus.APPLIED);
                    assertThat(outcome.result().orElseThrow().status())
                        .isEqualTo(SendStatus.DELIVERED);
                    sent.incrementAndGet();
                    return true;
                  }));
        }
        int winners = 0;
        for (Future<Boolean> sender : senders) {
          if (get(sender)) {
            winners++;
          }
        }
        assertThat(winners).as("round " + round + ": senders that got the lease").isEqualTo(1);
        assertThat(sent.get()).isEqualTo(1);
        assertThat(destination.receivedFiles()).hasSize(1);
        assertThat(stateOf(delivery)).isEqualTo(DeliveryState.SENDING);
        FencedSender winner = a.token().isPresent() ? a : b;
        assertThat(attemptOf(winner).commitIssued()).isTrue();
      }

      // a lapsed holder races the sender that took the lease over: only the new holder gets through
      EndpointKey ep = endpoint("ZOMBIE");
      DestinationSimulator destination = destination();
      DeliveryId delivery = queuedDelivery(ledger(), ep, "EXEC1|ZOMBIE", 1);
      FencedSender lapsed = new FencedSender(ledger(), destination, ep, "A", FENCING_TTL);
      FencedSender current = new FencedSender(ledger(), destination, ep, "B", FENCING_TTL);
      assertThat(lapsed.acquire()).contains(new LeaseToken(0, 1));
      expire();
      assertThat(current.acquire()).contains(new LeaseToken(0, 2));
      CyclicBarrier start = new CyclicBarrier(2);
      Future<SendOutcome> lapsedSend =
          pool.submit(
              () -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return lapsed.send(delivery, file("lapsed"), () -> {});
              });
      Future<SendOutcome> currentSend =
          pool.submit(
              () -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return current.send(delivery, file("current"), () -> {});
              });
      assertThat(get(lapsedSend).begin()).isEqualTo(SendingStatus.STALE_LEASE);
      assertThat(get(lapsedSend).result()).isEmpty();
      assertThat(get(currentSend).begin()).isEqualTo(SendingStatus.APPLIED);
      assertThat(destination.receivedFiles())
          .extracting(f -> f.fileId())
          .containsExactly("current");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  default void takeoverDuringAnInProgressUploadAbortsTheOldSenderAtTheGate() {
    EndpointKey ep = endpoint("TAKEOVER");
    DestinationSimulator destination = destination();
    DeliveryId delivery = queuedDelivery(ledger(), ep, "EXEC1|TAKEOVER", 1);
    FencedSender a = new FencedSender(ledger(), destination, ep, "A", FENCING_TTL);
    FencedSender b = new FencedSender(ledger(), destination, ep, "B", FENCING_TTL);
    assertThat(a.acquire()).contains(new LeaseToken(0, 1));

    // the upload is in progress (begin applied, the gate is about to be asked) when B takes over
    SendOutcome outcome =
        a.send(
            delivery,
            file("upload"),
            () -> {
              expire();
              assertThat(b.acquire()).contains(new LeaseToken(0, 2));
            });

    assertThat(outcome.begin()).isEqualTo(SendingStatus.APPLIED);
    assertThat(outcome.result().orElseThrow().status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(destination.receivedFiles()).isEmpty();
    assertThat(stateOf(delivery)).isEqualTo(DeliveryState.IN_DOUBT);
    assertThat(attemptOf(a).commitIssued()).isFalse();
    assertThat(ledger().currentLease(ep).orElseThrow().token()).isEqualTo(new LeaseToken(0, 2));
  }

  @Test
  default void stallAfterTokenCheckResumesAfterTakeoverAndTheMarkerCasFails() throws Exception {
    EndpointKey ep = endpoint("STALL");
    DestinationSimulator destination = destination();
    DeliveryId delivery = queuedDelivery(ledger(), ep, "EXEC1|STALL", 1);
    FencedSender a = new FencedSender(ledger(), destination, ep, "A", FENCING_TTL);
    FencedSender b = new FencedSender(ledger(), destination, ep, "B", FENCING_TTL);
    LeaseToken tokenA = a.acquire().orElseThrow();
    CountDownLatch stalled = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    List<LeaseToken> seenByA = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<SendOutcome> sending =
          pool.submit(
              () ->
                  a.send(
                      delivery,
                      file("stalled"),
                      () -> {
                        // A's token check passes: its lease is still the current one ...
                        seenByA.add(ledger().currentLease(ep).orElseThrow().token());
                        // ... and A stalls before the marker (a pause, a swap, a slow disk)
                        stalled.countDown();
                        await(resume);
                      }));
      await(stalled);
      expire();
      // B takes over from another thread while A is stalled
      Future<LeaseToken> takeover = pool.submit(() -> b.acquire().orElseThrow());
      assertThat(get(takeover)).isEqualTo(new LeaseToken(0, 2));
      resume.countDown();

      SendOutcome outcome = get(sending);
      assertThat(seenByA).containsExactly(tokenA);
      assertThat(outcome.begin()).isEqualTo(SendingStatus.APPLIED);
      assertThat(outcome.result().orElseThrow().status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
      assertThat(destination.receivedFiles()).isEmpty();
      assertThat(stateOf(delivery)).isEqualTo(DeliveryState.IN_DOUBT);
      assertThat(attemptOf(a).commitIssued()).isFalse();
      assertThat(ledger().markCommitIssued(a.lastAttempt().orElseThrow(), tokenA)).isFalse();
    } finally {
      resume.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  default void restoreThenZombieSenderIsFencedAtTheGate() {
    EndpointKey ep = endpoint("RESTORE");
    DestinationSimulator destination = destination();
    DeliveryId inProgress = queuedDelivery(ledger(), ep, "EXEC1|RESTORE1", 1);
    DeliveryId next = queuedDelivery(ledger(), ep, "EXEC1|RESTORE2", 1);
    FencedSender zombie = new FencedSender(ledger(), destination, ep, "ZOMBIE", FENCING_TTL);
    FencedSender fresh = new FencedSender(ledger(), destination, ep, "FRESH", FENCING_TTL);
    LeaseToken stale = zombie.acquire().orElseThrow();
    assertThat(stale).isEqualTo(new LeaseToken(0, 1));

    // the database is restored while the zombie's upload is in progress: a new restore epoch
    SendOutcome gated =
        zombie.send(inProgress, file("restore-1"), () -> ledger().declareRestoreEpoch(1));
    assertThat(gated.begin()).isEqualTo(SendingStatus.APPLIED);
    assertThat(gated.result().orElseThrow().status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(destination.receivedFiles()).isEmpty();
    assertThat(attemptOf(zombie).commitIssued()).isFalse();
    assertThat(ledger().currentLease(ep)).isEmpty();

    // the zombie's next attempt is refused at the begin, with the token it still holds
    SendOutcome refused = zombie.send(next, file("restore-2"), () -> {});
    assertThat(refused.begin()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(refused.result()).isEmpty();
    assertThat(destination.receivedFiles()).isEmpty();
    assertThat(stateOf(next)).isEqualTo(DeliveryState.QUEUED);

    // a new holder gets the first token of the new epoch (at once: the restore voided the lease)
    // and its send reaches the destination; the delivery the zombie left in SENDING is in doubt
    assertThat(fresh.acquire()).contains(new LeaseToken(1, 1));
    assertThat(stateOf(inProgress)).isEqualTo(DeliveryState.IN_DOUBT);
    SendOutcome sent = fresh.send(next, file("restore-2"), () -> {});
    assertThat(sent.begin()).isEqualTo(SendingStatus.APPLIED);
    assertThat(sent.result().orElseThrow().status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(destination.receivedFiles())
        .extracting(f -> f.fileId())
        .containsExactly("restore-2");
  }

  @Test
  default void aSenderThatLostItsLeaseBeforeBeginSendingSendsNothing() {
    EndpointKey ep = endpoint("LOST");
    CountingTransport transport = new CountingTransport(destination());
    DeliveryId delivery = queuedDelivery(ledger(), ep, "EXEC1|LOST", 1);
    FencedSender a = new FencedSender(ledger(), transport, ep, "A", FENCING_TTL);
    FencedSender b = new FencedSender(ledger(), transport, ep, "B", FENCING_TTL);
    assertThat(a.acquire()).contains(new LeaseToken(0, 1));
    expire();
    assertThat(b.acquire()).contains(new LeaseToken(0, 2));

    SendOutcome outcome = a.send(delivery, file("lost"), () -> {});

    assertThat(outcome.begin()).isEqualTo(SendingStatus.STALE_LEASE);
    assertThat(outcome.result()).isEmpty();
    assertThat(transport.sends()).isZero();
    assertThat(a.lastAttempt()).isEmpty();
    assertThat(stateOf(delivery)).isEqualTo(DeliveryState.QUEUED);
  }

  /** A transport that counts the sends that reach it and delegates everything else. */
  final class CountingTransport implements Transport {
    private final Transport delegate;
    private final AtomicInteger sends = new AtomicInteger();

    CountingTransport(Transport delegate) {
      this.delegate = delegate;
    }

    int sends() {
      return sends.get();
    }

    @Override
    public AckModel ackModel() {
      return delegate.ackModel();
    }

    @Override
    public SendResult send(OutboundFile file, CommitGate gate) {
      sends.incrementAndGet();
      return delegate.send(file, gate);
    }

    @Override
    public ProbeResult probe(String fileId) {
      return delegate.probe(fileId);
    }

    @Override
    public AckPage pollAcks(AckCursor cursor, int max) {
      return delegate.pollAcks(cursor, max);
    }

    @Override
    public dev.regpack.ports.connector.ConnectorSpec spec() {
      return delegate.spec();
    }

    @Override
    public dev.regpack.ports.connector.CheckResult check() {
      return delegate.check();
    }
  }
}
