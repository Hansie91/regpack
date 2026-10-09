package dev.regpack.inmemory;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;

class InMemoryLedgerStoreContractTest extends AbstractLedgerContractTest {
  @Override
  LedgerStore create(ManualClock clock) {
    return new InMemoryLedgerStore(clock);
  }
}
