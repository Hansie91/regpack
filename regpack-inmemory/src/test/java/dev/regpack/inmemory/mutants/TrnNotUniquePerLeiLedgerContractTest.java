package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code TRN_NOT_UNIQUE_PER_LEI}); ContractSuiteTeethTest expects
 * named tests to fail.
 */
@Tag("mutant")
public class TrnNotUniquePerLeiLedgerContractTest extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.TRN_NOT_UNIQUE_PER_LEI);
  }
}
