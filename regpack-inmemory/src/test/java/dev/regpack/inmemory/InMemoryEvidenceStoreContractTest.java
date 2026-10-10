package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.testkit.clock.ManualClock;
import org.junit.jupiter.api.Test;

class InMemoryEvidenceStoreContractTest extends AbstractEvidenceContractTest {
  @Override
  public EvidenceStore create(ManualClock clock) {
    return new InMemoryEvidenceStore(clock);
  }

  @Override
  public void simulateOutage(boolean down) {
    ((InMemoryEvidenceStore) store()).simulateOutage(down);
  }

  @Override
  public EvidenceStore reopen() {
    return store();
  }

  @Test
  void declaresTestOnlyGradeAndNoDurabilityClaim() {
    assertThat(store().grade().profile()).isEqualTo("P-MEMORY");
    assertThat(store().durability()).isEqualTo(new Durability(false, 0));
  }
}
