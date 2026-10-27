package dev.regpack.postgres;

import static dev.regpack.postgres.EnvelopeBodyFixtures.SECRET;
import static dev.regpack.postgres.EnvelopeBodyFixtures.envelope;
import static dev.regpack.postgres.EnvelopeBodyFixtures.plain;
import static dev.regpack.postgres.EnvelopeBodyFixtures.unit;
import static dev.regpack.postgres.EnvelopeBodyFixtures.version;
import static dev.regpack.postgres.EnvelopeBodyFixtures.with;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Leg;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the adapter as a whole decides without a server (P3c1 T12): the configuration that makes a
 * ledger require envelope bodies, and the refusal of a plain body before any statement. The
 * statements of an accepted unit are covered by {@code PgReportsTest} and {@code PgUnitFlowTest};
 * the stored encoding on a real server by {@code PgEnvelopeBodiesIT}.
 */
class PgLedgerStoreTest {
  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
  private static final String REFUSAL = "a report body must be an envelope";

  private final SqlSpy spy = new SqlSpy();
  private final ManualClock clock = new ManualClock(NOW);

  private PgLedgerStore store(Config config) {
    return new PgLedgerStore(spy.over(null), clock, config);
  }

  @Test
  void productionConfigRefusesPlainBodies() {
    Config production = Config.production("t42_7");

    assertThat(production.requireEnvelopeBodies()).isTrue();
    assertThat(Config.forSchema("t42_7").requireEnvelopeBodies()).isFalse();
    // production changes that one setting and nothing else
    assertThat(production)
        .isEqualTo(Config.forSchema("t42_7").withEnvelopeBodiesRequired(true))
        .isNotEqualTo(Config.forSchema("t42_7"));
    assertThat(production.schema()).isEqualTo("t42_7");
    assertThat(production.partitionsOnDemand()).isTrue();
    assertThat(production.forceSynchronousCommit()).isTrue();
    assertThat(production.lockTimeout()).isEqualTo(Config.DEFAULT_LOCK_TIMEOUT);
    assertThat(production.statementTimeout()).isEqualTo(Config.DEFAULT_STATEMENT_TIMEOUT);
    // the other settings keep it, and it can be switched off again
    assertThat(production.withPartitionsOnDemand(false).requireEnvelopeBodies()).isTrue();
    assertThat(production.withForceSynchronousCommit(false).requireEnvelopeBodies()).isTrue();
    assertThat(production.withLockTimeout(Duration.ofSeconds(1)).requireEnvelopeBodies()).isTrue();
    assertThat(production.withStatementTimeout(Duration.ofSeconds(1)).requireEnvelopeBodies())
        .isTrue();
    assertThat(production.withEnvelopeBodiesRequired(false)).isEqualTo(Config.forSchema("t42_7"));
    // an identifier that is not one is refused as for any configuration
    assertThatThrownBy(() -> Config.production("Public"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The unit has two decisions and the plain body is the second one: a check of the first version
   * only, or of the first decision only, would let it through to a connection. The spy records
   * every open, statement and bind, so an empty record means nothing was sent.
   */
  @Test
  void aPlainBodyIsRefusedBeforeAnyStatement() {
    PgLedgerStore production = store(Config.production("t42_7"));
    NewDecision first = reportDecision("E1", "ctx", "d1", Leg.MARKET);
    NewDecision second = reportDecision("E2", "ctx", "d2", Leg.MARKET);

    for (byte[] plainBody : List.of(plain(), new byte[0])) {
      ReportableCommit refused =
          unit(
              with(first, version("E1", Leg.MARKET, "TRNE1M", envelope())),
              with(second, version("E2", Leg.MARKET, "TRNE2M", plainBody)));

      assertThatThrownBy(() -> production.commitReportable(refused))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(REFUSAL)
          .satisfies(
              e -> {
                assertThat(e.toString()).doesNotContain(SECRET);
                assertThat(e.getCause()).isNull();
              });
    }

    assertThat(spy.events()).as("no connection, no statement, no bind").isEmpty();
    assertThat(spy.statements()).isZero();
  }

  /**
   * The same units are not refused when envelopes are not required: the default configuration still
   * stores a plain body (A6), and the refusal above is the configuration's and nothing else.
   */
  @Test
  void theDefaultConfigurationDoesNotRefuseAPlainBodyBeforeTheServer() {
    PgLedgerStore standard = store(Config.forSchema("t42_7"));
    ReportableCommit plainUnit =
        unit(
            with(
                reportDecision("E1", "ctx", "d1", Leg.MARKET),
                version("E1", Leg.MARKET, "TRNE1M", plain())));

    // the fake server has no rows, so the unit fails later and for another reason, or not at all
    try {
      standard.commitReportable(plainUnit);
    } catch (RuntimeException e) {
      assertThat(String.valueOf(e.getMessage())).isNotEqualTo(REFUSAL);
    }

    assertThat(spy.events()).as("the unit reached the server").contains("open");
  }
}
