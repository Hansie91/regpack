package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code WITHDRAWAL_GUARD_ON_REDELIVERY}): the withdrawal guard also
 * runs on a redelivery; ContractSuiteTeethTest expects named tests to fail.
 */
@Tag("mutant")
public class WithdrawalGuardOnRedeliveryLedgerContractTest extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.WITHDRAWAL_GUARD_ON_REDELIVERY);
  }
}
