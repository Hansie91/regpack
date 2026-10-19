package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.attemptIntent;
import static dev.regpack.testkit.contract.LedgerFixtures.endpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * What the lease and delivery side of the adapter decides without a server (T17): the arguments
 * that are refused before a connection is opened, the unit that is rolled back and closed when the
 * delivery is unknown, and static checks of the statement texts against the migration, because the
 * statements themselves first run on a real server at CP-2.
 */
class PgFencingTest {
  private static final EndpointKey EP = endpoint("T");
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final LeaseToken TOKEN = new LeaseToken(0, 1);

  private final SqlSpy spy = new SqlSpy();
  private final ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
  private final Config config = Config.forSchema("t42_7");
  private final PgLeases leases = new PgLeases(spy.over(null), clock, config);
  private final PgDeliveries deliveries = new PgDeliveries(spy.over(null), clock, config);

  private static String migration() throws IOException {
    Path path = Path.of(System.getProperty("regpack.classes.dir"), "db/migration/V001__core.sql");
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  @Test
  void acquireRefusesABlankHolderAndABadTtlBeforeAnyConnection() {
    for (String blank : new String[] {"", " ", "\t"}) {
      assertThatThrownBy(() -> leases.acquire(EP, blank, TTL))
          .as("holder '" + blank + "'")
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Duration bad :
        new Duration[] {
          Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1_500), Duration.ofNanos(1)
        }) {
      assertThatThrownBy(() -> leases.acquire(EP, "A", bad))
          .as("ttl " + bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> leases.renew(EP, TOKEN, bad))
          .as("renew ttl " + bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aHostileHolderAndATtlBeyondTheMaximumAreRefusedBeforeAnyConnection() {
    String nul = String.valueOf((char) 0);
    String lone = String.valueOf((char) 0xD800);
    for (String hostile : new String[] {"a" + nul + "b", "a" + lone, "a	b", "h".repeat(257)}) {
      assertThatThrownBy(() -> leases.acquire(EP, hostile, TTL))
          .as("holder of %d chars", hostile.length())
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(hostile);
    }
    Duration beyond = LeaseLedger.MAX_TTL.plus(Duration.ofNanos(1_000));
    for (Duration bad :
        new Duration[] {
          beyond, Duration.ofDays(200_000_000L), Duration.ofSeconds(Long.MAX_VALUE)
        }) {
      assertThatThrownBy(() -> leases.acquire(EP, "A", bad))
          .as("ttl " + bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> leases.renew(EP, TOKEN, bad))
          .as("renew ttl " + bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aTransitionIntoSendingAndABlankBusinessKeyAreRefusedBeforeAnyConnection() {
    DeliveryId id = new DeliveryId(1);
    assertThatThrownBy(() -> deliveries.transition(id, DeliveryState.QUEUED, DeliveryState.SENDING))
        .isInstanceOf(IllegalArgumentException.class);
    for (String blank : new String[] {"", " ", "\t"}) {
      assertThatThrownBy(() -> deliveries.createDelivery(new NewDelivery(EP, 1, blank, 1)))
          .as("business key '" + blank + "'")
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aRestoreEpochThatDoesNotExceedTheCurrentOneIsDecidedByTheStatementNotByJava() {
    // the fake server answers "no row" to the update that holds the condition epoch < ?, which is
    // the answer of a real server for an epoch that does not exceed the current one
    assertThatThrownBy(() -> leases.declareRestoreEpoch(1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).contains("rollback").doesNotContain("commit");
    assertThat(spy.events())
        .anyMatch(
            e -> e.startsWith("execute: ") && e.contains("epoch < ?") && e.contains("UPDATE"));
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aBeginSendingOfAnUnknownDeliveryIsRefusedRolledBackAndNothingStays() {
    // the fake server answers every read with no row: the delivery is unknown
    assertThatThrownBy(
            () ->
                deliveries.beginSending(
                    new DeliveryId(987_654), new AttemptId(1), TOKEN, attemptIntent()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).contains("rollback").doesNotContain("commit");
    assertThat(spy.events()).noneMatch(e -> e.contains("FOR SHARE") || e.contains("INSERT"));
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aMarkerOfAnUnknownAttemptIsFalseAndTakesNoLeaseLock() {
    assertThat(deliveries.markCommitIssued(new AttemptId(987_654), TOKEN)).isFalse();
    assertThat(spy.events()).noneMatch(e -> e.contains("FOR SHARE") || e.contains("UPDATE"));
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void theSweepRepeatsTheSendingCodeOfTheSendingIndexPredicate() throws IOException {
    // a bind parameter would not let the planner prove the predicate of delivery_sending_idx, so
    // the sweep carries the code as a literal built from Codes; V001 holds the same number
    String predicate = "state = " + Codes.of(DeliveryState.SENDING);
    // (the SET clause binds the new state; only the WHERE predicate must be a literal)
    assertThat(PgLeases.SWEEP_SQL).endsWith(" WHERE endpoint_id = ? AND " + predicate);
    assertThat(migration())
        .contains("CREATE INDEX delivery_sending_idx ON delivery (endpoint_id) WHERE " + predicate);
  }

  @Test
  void theInFlightStatesOfTheIndexAreTheOnesTheAdapterGuards() throws IOException {
    String states =
        "state IN ("
            + Codes.of(DeliveryState.SENDING)
            + ", "
            + Codes.of(DeliveryState.AWAITING_ACK)
            + ", "
            + Codes.of(DeliveryState.IN_DOUBT)
            + ")";
    assertThat(migration()).contains("WHERE " + states + " AND NOT gate_released");
    assertThat(PgDeliveries.IN_FLIGHT)
        .containsExactlyInAnyOrder(
            DeliveryState.SENDING, DeliveryState.AWAITING_ACK, DeliveryState.IN_DOUBT);
  }

  @Test
  void theFenceIsASharedLockOnTheLeaseRowThatComparesTheWholePairAndTheSingleton() {
    // FOR SHARE, not FOR KEY SHARE: the takeover UPDATE of a non-key column must wait for it
    assertThat(PgLeases.FENCE_SQL)
        .endsWith(" FOR SHARE")
        .doesNotContain("KEY SHARE")
        .contains("restore_epoch = ?")
        .contains("counter = ?")
        .contains("NOT released")
        .contains("restore_epoch = (SELECT epoch FROM restore_epoch)");
  }

  @Test
  void theTakeoverModifiesTheLeaseRowFirstAndSweepsInASeparateStatement() {
    // never a join to lease inside one UPDATE (LF22): the sweep names only the delivery table
    assertThat(PgLeases.SWEEP_SQL)
        .startsWith("UPDATE delivery SET state = ?")
        .doesNotContain("lease");
    assertThat(PgLeases.ACQUIRE_SQL)
        .startsWith("INSERT INTO lease")
        .contains("ON CONFLICT (entity_id, endpoint_id) DO UPDATE")
        .doesNotContain("delivery");
  }
}
