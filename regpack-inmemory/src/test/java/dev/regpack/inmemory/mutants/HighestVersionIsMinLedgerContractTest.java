package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code HIGHEST_VERSION_IS_MIN}): the highest stored version is kept
 * as the lowest; ContractSuiteTeethTest expects named tests to fail.
 */
@Tag("mutant")
public class HighestVersionIsMinLedgerContractTest extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.HIGHEST_VERSION_IS_MIN);
  }
}
