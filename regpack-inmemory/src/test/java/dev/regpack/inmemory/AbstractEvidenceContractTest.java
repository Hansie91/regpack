package dev.regpack.inmemory;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.EvidenceStoreContract;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/** Binds {@link EvidenceStoreContract} to an adapter created over a fresh {@link ManualClock}. */
public abstract class AbstractEvidenceContractTest implements EvidenceStoreContract {
  private ManualClock clock;
  private EvidenceStore store;

  /** Creates a fresh, empty adapter that reads time from {@code clock}. */
  public abstract EvidenceStore create(ManualClock clock);

  @BeforeEach
  void setUpStore() {
    clock = new ManualClock(Instant.parse("2026-03-01T00:00:00Z"));
    store = create(clock);
  }

  @Override
  public EvidenceStore store() {
    return store;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }
}
