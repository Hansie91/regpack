package dev.regpack.inmemory;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.DecisionLedgerContract;
import dev.regpack.testkit.contract.IngestLedgerContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/** Binds the ledger contract suites to an adapter created over a fresh {@link ManualClock}. */
abstract class AbstractLedgerContractTest implements IngestLedgerContract, DecisionLedgerContract {
  private ManualClock clock;
  private LedgerStore ledger;

  /** Creates a fresh, empty adapter that reads time from {@code clock}. */
  abstract LedgerStore create(ManualClock clock);

  @BeforeEach
  void setUpLedger() {
    clock = new ManualClock(Instant.parse("2026-03-01T00:00:00Z"));
    ledger = create(clock);
  }

  @Override
  public LedgerStore ledger() {
    return ledger;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }
}
