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

  /**
   * The rule about the shape of a status that the contract checks: a status that is synchronous
   * names at least one standby. It is a method of its own so that a test can show that it bites
   * (the in-memory ledger is never synchronous in the contract run; its own test sets a synchronous
   * status and applies this check to it).
   *
   * @throws AssertionError when the status breaks the rule
   */
  static void requireASynchronousStatusToNameAStandby(Status status) {
    assertThat(status).isNotNull();
    if (status.synchronous()) {
      assertThat(status.synchronousStandbys())
          .as("a synchronous status names at least one standby")
          .isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  default void durabilityReturnsAStatusWithANonBlankDetail() {
    Status status = ledger().durability();
    assertThat(status).isNotNull();
    assertThat(status.detail()).isNotBlank();
    assertThat(status.synchronousStandbys()).isGreaterThanOrEqualTo(0);
  }

  @Test
  default void aSynchronousStatusNamesAtLeastOneStandby() {
    requireASynchronousStatusToNameAStandby(ledger().durability());
  }
}
