package dev.regpack.inmemory;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.EnvelopeBodyLedgerContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/** Binds the envelope-body contract to the in-memory ledger in both configurations. */
class InMemoryEnvelopeBodiesTest implements EnvelopeBodyLedgerContract {
  private ManualClock clock;

  @BeforeEach
  void setUpClock() {
    clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
  }

  @Override
  public LedgerStore ledger() {
    return new InMemoryLedgerStore(clock);
  }

  @Override
  public LedgerStore ledgerRequiringEnvelopes() {
    return new InMemoryLedgerStore(clock).requireEnvelopeBodies(true);
  }
}
