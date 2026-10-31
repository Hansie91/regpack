package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.EnvelopeBodyLedgerContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code ENVELOPE_REQUIREMENT_IGNORED}); ContractSuiteTeethTest
 * expects a named test to fail.
 */
@Tag("mutant")
public class EnvelopeRequirementIgnoredEnvelopeBodiesTest implements EnvelopeBodyLedgerContract {
  private ManualClock clock;

  @BeforeEach
  void setUpClock() {
    clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
  }

  @Override
  public LedgerStore ledger() {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.ENVELOPE_REQUIREMENT_IGNORED);
  }

  @Override
  public LedgerStore ledgerRequiringEnvelopes() {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.ENVELOPE_REQUIREMENT_IGNORED)
        .requireEnvelopeBodies(true);
  }
}
