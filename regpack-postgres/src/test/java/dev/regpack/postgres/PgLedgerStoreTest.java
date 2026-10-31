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
import dev.regpack.model.Regime;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.SubjectIndexLedger.RefKind;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectEntry;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectRef;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    // the fake server has no regime row, so the unit fails later, for another reason: a ledger
    // exception of the server side, not the refusal of the configuration
    assertThatThrownBy(() -> standard.commitReportable(plainUnit))
        .isInstanceOf(LedgerException.class)
        .hasMessageNotContaining(REFUSAL);

    assertThat(spy.events()).as("the unit reached the server").contains("open");
  }

  private static List<String> statementsOf(SqlSpy recorded) {
    return recorded.events().stream()
        .filter(
            e ->
                e.startsWith("execute: ")
                    && !e.contains("set_config")
                    && !e.startsWith("execute: SET TRANSACTION"))
        .map(e -> e.substring("execute: ".length()))
        .toList();
  }

  /**
   * The seven methods that the store hands to the retention and subject index adapters, each with
   * its arguments and under the configuration of the store: the statement of each, the hold as it
   * was given (a release here), the limit and the hash and the kind in the positions of their
   * statements, the count that {@code removeSubjectRefs} returns, and a lock timeout of the
   * configuration in the prologue of every one of the seven units. The adapters are tested alone;
   * this ties the store to them.
   */
  @Test
  void theSevenNewMethodsOfTheStoreReachTheirAdapterWithTheirArguments() {
    SqlSpy recorded = new SqlSpy().recordingTypedBinds().strictBinds().updatingRows(2);
    PgLedgerStore store =
        new PgLedgerStore(
            recorded.over(null),
            clock,
            Config.forSchema("t42_7").withLockTimeout(Duration.ofMillis(1234)));
    SubjectHash hash = new SubjectHash("ab".repeat(32));
    SubjectRef ref = new SubjectRef(RefKind.EVIDENCE_OBJECT, "sealed/x");

    store.recordRetention(
        List.of(
            new RetentionClaim(
                new EvidenceKey("sealed/a"),
                new Regime("mifir.eu.rts22"),
                AnchorEvent.OBJECT_WRITTEN,
                Optional.of(Instant.parse("2031-06-01T12:30:45Z")),
                "sealed")));
    store.setRetentionHold(new EvidenceKey("sealed/a"), false);
    store.retentionRows("sealed/", Optional.empty(), 3);
    store.expiredRetention(NOW, Optional.empty(), 4);
    store.indexSubjects(List.of(new SubjectEntry(hash, ref)));
    store.subjectRefs(hash, Optional.empty(), 5);
    int removed = store.removeSubjectRefs(ref);

    assertThat(removed).isEqualTo(2);
    assertThat(statementsOf(recorded))
        .containsExactly(
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.RECORD_SQL,
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.HOLD_SQL,
            PgRetention.ROWS_SQL,
            PgRetention.EXPIRED_SQL,
            PgSubjectIndex.INSERT_SQL,
            PgSubjectIndex.REFS_SQL,
            PgSubjectIndex.DELETE_SQL);
    List<String> events = recorded.events();
    assertThat(events)
        .contains(
            "bind boolean 1=false", // the release, as it was given
            "bind long 4=3", // ROWS_SQL: the limit of artifacts
            "bind long 3=4", // EXPIRED_SQL: the limit of rows
            "bind long 2=5", // REFS_SQL: the limit of references
            "bind short 2=2", // INSERT_SQL: the kind code of an evidence object
            "bind short 1=2"); // DELETE_SQL: the same
    assertThat(events.stream().filter(e -> e.equals("bind bytes 1=" + hash.hex())).count())
        .as("the hash, as bytes, in the insert and in the lookup")
        .isEqualTo(2);
    assertThat(events.stream().filter(e -> e.equals("bind 2=1234ms")).count())
        .as("the lock timeout of the configuration, once in the prologue of each unit")
        .isEqualTo(7);
  }
}
