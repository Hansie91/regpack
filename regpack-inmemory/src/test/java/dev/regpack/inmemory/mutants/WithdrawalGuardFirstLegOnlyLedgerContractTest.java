package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code WITHDRAWAL_GUARD_FIRST_LEG_ONLY}): the withdrawal guard looks
 * at the first leg only; ContractSuiteTeethTest expects named tests to fail.
 */
@Tag("mutant")
public class WithdrawalGuardFirstLegOnlyLedgerContractTest extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(clock, InMemoryLedgerStore.Flaw.WITHDRAWAL_GUARD_FIRST_LEG_ONLY);
  }
}
