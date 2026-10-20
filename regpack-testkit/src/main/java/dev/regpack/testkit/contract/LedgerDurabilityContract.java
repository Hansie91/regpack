package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.ledger.LedgerDurability;
import dev.regpack.ports.ledger.LedgerDurability.Status;
import dev.regpack.ports.ledger.LedgerStore;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link LedgerDurability} must satisfy about the shape of its answer. What a status
 * means for a real server (the setting, the standbys, the warning after a commit) is the subject of
 * the tests of the adapter; the send gate only reads this shape.
 */
public interface LedgerDurabilityContract {
  /** A fresh ledger for each test. */
  LedgerStore ledger();

  @Test
  default void durabilityReturnsAStatusWithANonBlankDetail() {
    Status status = ledger().durability();
    assertThat(status).isNotNull();
    assertThat(status.detail()).isNotBlank();
    assertThat(status.synchronousStandbys()).isGreaterThanOrEqualTo(0);
  }

  @Test
  default void aSynchronousStatusNamesAtLeastOneStandby() {
    Status status = ledger().durability();
    if (status.synchronous()) {
      assertThat(status.synchronousStandbys()).isGreaterThanOrEqualTo(1);
    }
  }
}
