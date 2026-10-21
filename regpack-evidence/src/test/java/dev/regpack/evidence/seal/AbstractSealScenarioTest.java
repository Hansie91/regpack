package dev.regpack.evidence.seal;

import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.Sealing;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/**
 * Binds the shared seal-and-send scenarios ({@link SealScenarioContract}, T14a) to a {@link
 * DurableSeal} over an in-memory ledger and the evidence store that a subclass creates. The ledger
 * is the same for every binding; what the scenarios prove about the evidence tier is proved for the
 * store of the subclass: the in-memory store and P-SEALED on two real directories.
 */
abstract class AbstractSealScenarioTest implements SealScenarioContract {
  /** The retention policy the scenarios run with (OI-6: tests and tools only). */
  static final RetentionPolicy RETENTION = RetentionPolicy.fixedDays(1826);

  private ManualClock clock;
  private LedgerStore ledger;
  private EvidenceStore evidence;

  /** A fresh, empty evidence store that reads time from {@code clock}. */
  abstract EvidenceStore createEvidence(ManualClock clock) throws IOException;

  @BeforeEach
  void setUpStores() throws IOException {
    clock = new ManualClock(Instant.parse("2026-10-20T08:00:00Z"));
    ledger = new InMemoryLedgerStore(clock);
    evidence = createEvidence(clock);
  }

  @Override
  public LedgerStore ledger() {
    return ledger;
  }

  @Override
  public EvidenceStore evidence() {
    return evidence;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  /** The send gate of a binding: {@code production()} or {@code none()}. */
  static SendGate gate(boolean productionGate) {
    return new SendGate(
        productionGate ? SendGate.Requirement.production() : SendGate.Requirement.none());
  }

  /**
   * The sealer under test. A binding of a deliberately wrong sealer ({@code SealingTeethTest})
   * overrides this one method; every scenario, also those of a binding itself, builds its sealer
   * through it.
   */
  DurableSeal create(LedgerStore ledger, EvidenceStore evidence, SendGate gate) {
    return new DurableSeal(ledger, evidence, clock, RETENTION, gate);
  }

  @Override
  public Sealing sealing(LedgerStore ledger, EvidenceStore evidence, boolean productionGate) {
    return create(ledger, evidence, gate(productionGate));
  }
}
