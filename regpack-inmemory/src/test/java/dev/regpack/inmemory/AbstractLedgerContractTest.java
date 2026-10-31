package dev.regpack.inmemory;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.DecisionLedgerContract;
import dev.regpack.testkit.contract.ExitFixturesContract;
import dev.regpack.testkit.contract.FencingScenarioContract;
import dev.regpack.testkit.contract.IngestLedgerContract;
import dev.regpack.testkit.contract.LeaseDeliveryLedgerContract;
import dev.regpack.testkit.contract.LedgerDurabilityContract;
import dev.regpack.testkit.contract.ManifestLedgerContract;
import dev.regpack.testkit.contract.ReportLedgerContract;
import dev.regpack.testkit.contract.RetentionLedgerContract;
import dev.regpack.testkit.contract.StorableValuesLedgerContract;
import dev.regpack.testkit.contract.SubjectIndexLedgerContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/** Binds the ledger contract suites to an adapter created over a fresh {@link ManualClock}. */
public abstract class AbstractLedgerContractTest
    implements IngestLedgerContract,
        DecisionLedgerContract,
        LeaseDeliveryLedgerContract,
        ReportLedgerContract,
        ExitFixturesContract,
        FencingScenarioContract,
        StorableValuesLedgerContract,
        ManifestLedgerContract,
        RetentionLedgerContract,
        SubjectIndexLedgerContract,
        LedgerDurabilityContract {
  private ManualClock clock;
  private LedgerStore ledger;

  /** Creates a fresh, empty adapter that reads time from {@code clock}. */
  public abstract LedgerStore create(ManualClock clock);

  @BeforeEach
  void setUpLedger() {
    clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
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
