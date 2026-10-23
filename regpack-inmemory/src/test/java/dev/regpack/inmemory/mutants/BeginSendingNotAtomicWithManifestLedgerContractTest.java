package dev.regpack.inmemory.mutants;

import dev.regpack.inmemory.AbstractLedgerContractTest;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately flawed adapter ({@code BEGIN_SENDING_NOT_ATOMIC_WITH_MANIFEST});
 * ContractSuiteTeethTest expects named tests to fail.
 */
@Tag("mutant")
public class BeginSendingNotAtomicWithManifestLedgerContractTest
    extends AbstractLedgerContractTest {
  @Override
  public LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(
        clock, InMemoryLedgerStore.Flaw.BEGIN_SENDING_NOT_ATOMIC_WITH_MANIFEST);
  }
}
