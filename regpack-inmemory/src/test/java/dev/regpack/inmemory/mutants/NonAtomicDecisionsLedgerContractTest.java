package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code NON_ATOMIC_DECISIONS}); ContractSuiteTeethTest expects named
 * tests to fail.
 */
@Tag("mutant")
public class NonAtomicDecisionsLedgerContractTest extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.NON_ATOMIC_DECISIONS);
  }
}
