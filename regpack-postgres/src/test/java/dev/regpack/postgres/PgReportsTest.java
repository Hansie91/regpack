package dev.regpack.postgres;

import static dev.regpack.model.Leg.ALLOCATION;
import static dev.regpack.model.Leg.CLIENT;
import static dev.regpack.model.Leg.MARKET;
import static dev.regpack.testkit.contract.LedgerFixtures.EMIR_REGIME;
import static dev.regpack.testkit.contract.LedgerFixtures.MIFIR_REGIME;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static dev.regpack.testkit.contract.LedgerFixtures.subjectKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What the report side of the adapter decides without a server (T18): the requests that are refused
 * before a connection is opened (rule 2 of {@code commitReportable}), and static checks of the
 * statement texts against the migration, because the statements themselves first run on a real
 * server at CP-3.
 */
class PgReportsTest {
  private final SqlSpy spy = new SqlSpy();
  private final Config config = Config.forSchema("t42_7");
  private final PgDecisions decisions = new PgDecisions(spy.over(null), config);
  private final PgReports reports =
      new PgReports(
          spy.over(null),
          new ManualClock(Instant.parse("2026-10-09T08:00:00Z")),
          config,
          decisions);

  private static ReportableCommit unit(ReportableDecision... decisions) {
    return new ReportableCommit(List.of(decisions), Optional.empty(), Optional.empty());
  }

  private void assertRefusedBeforeAnyConnection(ReportableCommit commit) {
    assertThatThrownBy(() -> reports.commitReportable(commit))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aVersionOnADecisionThatIsNotReportIsRefusedBeforeAnyConnection() {
    NewDecision doNotReport =
        LedgerFixtures.decision(
            LedgerFixtures.body(
                "E1",
                MIFIR_REGIME,
                "ctx",
                "d1",
                Outcome.DO_NOT_REPORT,
                Instant.parse("2026-10-09T10:00:00Z")),
            0);
    assertRefusedBeforeAnyConnection(
        unit(
            new ReportableDecision(
                doNotReport, List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1")))));
  }

  @Test
  void aLegThatTheDecisionDoesNotReportIsRefusedBeforeAnyConnection() {
    assertRefusedBeforeAnyConnection(
        unit(
            new ReportableDecision(
                reportDecision(0, "E1", "ctx", "d1", MARKET),
                List.of(newVersion("E1", CLIENT, "EXEC1", "TRN1", "NEWT", "h1")))));
  }

  @Test
  void twoVersionsForOneLegOfOneDecisionAreRefusedBeforeAnyConnection() {
    assertRefusedBeforeAnyConnection(
        unit(
            new ReportableDecision(
                reportDecision(0, "E1", "ctx", "d1", MARKET),
                List.of(
                    newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"),
                    newVersion("E1", MARKET, "EXEC1", "TRN1", "CANC", "h2")))));
  }

  @Test
  void aSubjectOfAnotherEventIsRefusedBeforeAnyConnection() {
    assertRefusedBeforeAnyConnection(
        unit(
            new ReportableDecision(
                reportDecision(0, "E1", "ctx", "d1", MARKET),
                List.of(newVersion("E2", MARKET, "EXEC1", "TRN1", "NEWT", "h1")))));
  }

  @Test
  void aSubjectOfAnotherRegimeIsRefusedBeforeAnyConnection() {
    NewVersion otherRegime =
        newVersion(
            subjectKey(EMIR_REGIME, "E1", MARKET),
            "EXEC1",
            Optional.of(new TrnProposal("TRN1", "KEY-1")),
            "NEWT",
            "h1");
    assertRefusedBeforeAnyConnection(
        unit(
            new ReportableDecision(
                reportDecision(0, "E1", "ctx", "d1", MARKET), List.of(otherRegime))));
  }

  @Test
  void aBadDecisionAfterAGoodOneStillWritesNothing() {
    // the decision rules of commitDecisions apply to the decisions of a reportable unit
    assertRefusedBeforeAnyConnection(
        unit(
            new ReportableDecision(
                reportDecision(0, "E1", "ctx", "d1", MARKET),
                List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"))),
            new ReportableDecision(reportDecision(-1, "E2", "ctx", "d1", MARKET), List.of())));
  }

  @Test
  void aBadVersionAfterAGoodOneStillWritesNothing() {
    assertRefusedBeforeAnyConnection(
        unit(
            new ReportableDecision(
                reportDecision(0, "E1", "ctx", "d1", MARKET),
                List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"))),
            new ReportableDecision(
                reportDecision(0, "E2", "ctx", "d1", MARKET),
                List.of(newVersion("E3", MARKET, "EXEC1", "TRN2", "NEWT", "h2")))));
  }

  // ------------------------------------------------------------------------------------------
  // static checks of the statements

  @Test
  void theLegMaskHasOneBitPerLegCode() {
    assertThat(PgDecisions.legMask(List.of())).isZero();
    assertThat(PgDecisions.legMask(List.of(MARKET))).isEqualTo(1L << Codes.of(MARKET));
    assertThat(PgDecisions.legMask(List.of(MARKET, CLIENT)))
        .isEqualTo((1L << Codes.of(MARKET)) | (1L << Codes.of(CLIENT)));
    assertThat(PgDecisions.legMask(List.of(ALLOCATION, ALLOCATION)))
        .isEqualTo(1L << Codes.of(ALLOCATION));
    // distinct legs never share a bit
    long all = PgDecisions.legMask(List.of(Leg.values()));
    assertThat(Long.bitCount(all)).isEqualTo(Leg.values().length);
  }

  @Test
  void theDeadStatesOfTheSuppressionRuleAreTheFourOfThePort() {
    assertThat(PgReports.DEAD)
        .containsExactlyInAnyOrderElementsOf(
            EnumSet.of(
                DeliveryState.REJECTED,
                DeliveryState.IN_REPAIR,
                DeliveryState.WITHDRAWN,
                DeliveryState.WRITTEN_OFF));
    // the statement lists exactly their stored codes
    assertThat(PgReports.LATEST_LIVE_SQL).contains("NOT IN (9, 13, 15, 16)");
  }

  @Test
  void theWithdrawalGuardBindsAsManyArraysAsItNamesColumns() {
    String sql = PgDecisions.WITHDRAWAL_SQL;
    int open = sql.indexOf("unnest(");
    int close = sql.indexOf(')', open);
    long binds = sql.substring(open, close).chars().filter(c -> c == '?').count();
    int aliasOpen = sql.indexOf(" t(", close) + 3;
    int aliasClose = sql.indexOf(')', aliasOpen);
    int columns = sql.substring(aliasOpen, aliasClose).split(",").length;
    assertThat(columns).isEqualTo(binds + 1);
    assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(binds);
    assertThat(sql).contains("WITH ORDINALITY").contains("ORDER BY t.ord, s.leg");
  }

  @Test
  void theFirstWriterOfEachKeyNamesItsArbiterAndTheLocksAreTaken() {
    assertThat(PgReports.SUBJECT_INSERT_SQL)
        .contains("ON CONFLICT ON CONSTRAINT report_subject_key DO NOTHING");
    assertThat(PgReports.SEQUENCE_INSERT_SQL)
        .contains("ON CONFLICT ON CONSTRAINT outbox_kind_subject_key DO NOTHING");
    // the subject row is locked for the version number, the head row only shared
    assertThat(PgReports.SUBJECT_LOCK_SQL).endsWith("FOR UPDATE");
    assertThat(PgReports.HEAD_SHARE_SQL).endsWith("FOR SHARE");
    // the existing version is found by a read, never by catching a unique violation
    assertThat(PgReports.EXISTING_SQL).contains("v.decision_id = ?").doesNotContain("INSERT");
  }

  @Test
  void theStatementsOnlyNameColumnsAndConstraintsThatV001Defines() throws IOException {
    String v001 = migration();
    assertColumns(
        tableBlock(v001, "report_subject"),
        columnList(PgReports.SUBJECT_INSERT_SQL, "INSERT INTO report_subject ("));
    assertColumns(
        tableBlock(v001, "trn_assignment"),
        columnList(PgReports.TRN_INSERT_SQL, "INSERT INTO trn_assignment ("));
    assertColumns(
        tableBlock(v001, "report_version"),
        columnList(PgReports.VERSION_INSERT_SQL, "INSERT INTO report_version ("));
    assertColumns(
        tableBlock(v001, "outbox"),
        columnList(PgReports.SEQUENCE_INSERT_SQL, "INSERT INTO outbox ("));
    assertColumns(
        tableBlock(v001, "report_subject"),
        List.of("subject_id", "regime_id", "event_identity", "leg", "last_version_seq"));
    assertColumns(
        tableBlock(v001, "trn_assignment"),
        List.of("subject_id", "executing_lei", "trn", "key_id"));
    assertColumns(
        tableBlock(v001, "report_version"),
        List.of(
            "subject_id",
            "version_seq",
            "decision_id",
            "action",
            "logical_hash",
            "body",
            "origin"));
    assertColumns(
        tableBlock(v001, "delivery"),
        List.of("delivery_id", "subject_id", "version_seq", "endpoint_epoch", "state"));
    assertColumns(tableBlock(v001, "decision_head"), List.of("decision_seq", "regime_id"));

    assertThat(v001)
        .contains("CONSTRAINT report_subject_key UNIQUE (regime_id, event_identity, leg)")
        .contains("CONSTRAINT trn_assignment_lei_trn_key UNIQUE (executing_lei, trn)")
        .contains("CONSTRAINT report_version_decision_subject_key UNIQUE (decision_id, subject_id)")
        .contains("CONSTRAINT outbox_kind_subject_key UNIQUE (kind, subject_id)");
    assertThat(PgReports.TRN_CONFLICT_CONSTRAINT).isEqualTo("trn_assignment_lei_trn_key");
    assertThat(PgReports.VERSION_KEY_CONSTRAINT).isEqualTo("report_version_decision_subject_key");
  }

  @Test
  void theVersionIsStoredPlainAndTheDecisionDayIsKept() throws IOException {
    // body_encoding 0 is plain until the key hierarchy of P3c; the foreign key to the partitioned
    // decision table needs the day
    assertThat(PgReports.BODY_ENCODING).isZero();
    assertThat(PgReports.VERSION_INSERT_SQL).contains("decision_as_at");
    assertThat(migration())
        .contains(
            "FOREIGN KEY (decision_id, decision_as_at) REFERENCES decision (decision_id, as_at)");
  }

  private static String migration() throws IOException {
    Path migration =
        Path.of(System.getProperty("regpack.classes.dir"), "db/migration/V001__core.sql");
    return Files.readString(migration, StandardCharsets.UTF_8);
  }

  /** The text of {@code CREATE TABLE <name> (...)} up to the first line that starts with ')'. */
  private static String tableBlock(String v001, String name) {
    int start = v001.indexOf("CREATE TABLE " + name + " (");
    assertThat(start).as("table %s", name).isGreaterThanOrEqualTo(0);
    int end = v001.indexOf("\n)", start);
    return v001.substring(start, end);
  }

  private static void assertColumns(String tableBlock, List<String> columns) {
    for (String column : columns) {
      Matcher m = Pattern.compile("\\n  " + column + " ").matcher(tableBlock);
      assertThat(m.find())
          .as("column %s of %s", column, tableBlock.substring(0, tableBlock.indexOf('\n')))
          .isTrue();
    }
  }

  private static List<String> columnList(String sql, String prefix) {
    int open = sql.indexOf(prefix) + prefix.length();
    int close = sql.indexOf(')', open);
    return List.of(sql.substring(open, close).split(",\\s*"));
  }
}
