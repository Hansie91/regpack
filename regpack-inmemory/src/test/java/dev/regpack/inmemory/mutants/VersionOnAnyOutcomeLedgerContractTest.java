package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code VERSION_ON_ANY_OUTCOME}): a version is accepted on a
 * non-REPORT decision; ContractSuiteTeethTest expects named tests to fail.
 */
@Tag("mutant")
public class VersionOnAnyOutcomeLedgerContractTest extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.VERSION_ON_ANY_OUTCOME);
  }
}
