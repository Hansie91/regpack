package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code RETENTION_HOLD_IGNORED_IN_EXPIRED}); ContractSuiteTeethTest
 * expects named tests to fail.
 */
@Tag("mutant")
public class RetentionHoldIgnoredInExpiredLedgerContractTest extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(
        clock, InMemoryLedgerStore.Flaw.RETENTION_HOLD_IGNORED_IN_EXPIRED);
  }
}
