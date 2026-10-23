package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.LedgerDurability.Status;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerDurabilityContract;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The in-memory ledger is not durable unless a test says so (the plan, rule 3 of the manifest). */
class InMemoryLedgerDurabilityTest {
  private static InMemoryLedgerStore ledger() {
    return new InMemoryLedgerStore(new ManualClock(Instant.parse("2026-10-20T08:00:00Z")));
  }

  @Test
  void aNewLedgerReportsItselfAsNotSynchronous() {
    assertThat(ledger().durability()).isEqualTo(new Status(false, 0, "in-memory ledger"));
  }

  @Test
  void aTestMayChangeTheStatusAndNeverToNull() {
    InMemoryLedgerStore ledger = ledger();
    Status synchronous = new Status(true, 2, "two test standbys");
    ledger.setDurability(synchronous);
    assertThat(ledger.durability()).isEqualTo(synchronous);
    assertThatThrownBy(() -> ledger.setDurability(null)).isInstanceOf(NullPointerException.class);
    assertThat(ledger.durability()).isEqualTo(synchronous);
  }

  /**
   * The contract method that the ledger can only pass vacuously (it is never synchronous in the
   * contract run) bites here: the rule it applies accepts a synchronous status with standbys and
   * refuses one without.
   */
  @Test
  void theRuleOfTheContractAcceptsASynchronousStatusWithStandbysAndRefusesOneWithout() {
    InMemoryLedgerStore ledger = ledger();
    ledger.setDurability(new Status(true, 2, "x"));
    LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(ledger.durability());
    ledger.setDurability(new Status(true, 1, "x"));
    LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(ledger.durability());
    // not synchronous: the number of standbys is not asked
    ledger.setDurability(new Status(false, 0, "x"));
    LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(ledger.durability());

    // a status that claims to be synchronous and names no standby
    ledger.setDurability(new Status(true, 0, "claims it"));
    assertThatThrownBy(
            () ->
                LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(
                    ledger.durability()))
        .isInstanceOf(AssertionError.class);
  }
}
