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
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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

  /** The outcome check on its own: the decision names the leg, so only the outcome refuses it. */
  @Test
  void aVersionOnANonReportDecisionThatNamesTheLegIsRefusedBeforeAnyConnection() {
    for (Outcome outcome :
        List.of(Outcome.DO_NOT_REPORT, Outcome.UNDETERMINED, Outcome.REPORT_BY_OTHER)) {
      NewDecision names =
          LedgerFixtures.decision(
              LedgerFixtures.bodyWithLegs(MIFIR_REGIME, "E1", "ctx", "d1", outcome, MARKET), 0);
      assertRefusedBeforeAnyConnection(
          unit(
              new ReportableDecision(
                  names, List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1")))));
    }
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
  // the envelope rule (P3c1 T12, note T12-1)

  /**
   * A connection that fails the test on any call: the entry point that takes one must not use it.
   */
  private static Connection untouchableConnection() {
    return (Connection)
        Proxy.newProxyInstance(
            PgReportsTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              throw new AssertionError("the connection was used by " + method.getName());
            });
  }

  /**
   * The refusal sits in the validation that both writers of {@code report_version.body} share
   * ({@code commitReportable} and the package-private {@code commitReportableIn}), runs for every
   * version of every decision, and runs before any connection is opened: the spy sees nothing, the
   * connection handed to the second entry point is never touched. The plain body is the second
   * decision's, and the empty body is a plain body too.
   */
  @Test
  void aPlainBodyIsRefusedByBothEntryPointsBeforeAnyStatement() {
    Config production = Config.production("t42_7");
    SqlSpy local = new SqlSpy();
    PgReports strict =
        new PgReports(
            local.over(null),
            new ManualClock(Instant.parse("2026-10-09T08:00:00Z")),
            production,
            new PgDecisions(local.over(null), production));
    Connection untouchable = untouchableConnection();

    for (byte[] plainBody : List.of(EnvelopeBodyFixtures.plain(), new byte[0])) {
      ReportableCommit refused =
          unit(
              new ReportableDecision(
                  reportDecision(0, "E1", "ctx", "d1", MARKET),
                  List.of(
                      EnvelopeBodyFixtures.version(
                          "E1", MARKET, "TRNE1M", EnvelopeBodyFixtures.envelope()))),
              new ReportableDecision(
                  reportDecision(0, "E2", "ctx", "d2", MARKET),
                  List.of(EnvelopeBodyFixtures.version("E2", MARKET, "TRNE2M", plainBody))));

      assertThatThrownBy(() -> strict.commitReportable(refused))
          .as("commitReportable, %d bytes", plainBody.length)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("a report body must be an envelope");
      assertThatThrownBy(() -> strict.commitReportableIn(untouchable, refused))
          .as("commitReportableIn, %d bytes", plainBody.length)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("a report body must be an envelope");
    }

    assertThat(local.events()).as("no connection, no statement, no bind").isEmpty();
    assertThat(local.statements()).isZero();
  }

  /** The same rule, for the second leg of one decision and in either order of the legs. */
  @Test
  void aPlainBodyInTheSecondLegOfOneDecisionIsRefusedToo() {
    Config production = Config.production("t42_7");
    SqlSpy local = new SqlSpy();
    PgReports strict =
        new PgReports(
            local.over(null),
            new ManualClock(Instant.parse("2026-10-09T08:00:00Z")),
            production,
            new PgDecisions(local.over(null), production));
    NewDecision both = reportDecision(0, "E1", "ctx", "d1", MARKET, CLIENT);
    NewVersion envelopeLeg =
        EnvelopeBodyFixtures.version("E1", MARKET, "TRNE1M", EnvelopeBodyFixtures.envelope());
    NewVersion plainLeg =
        EnvelopeBodyFixtures.version("E1", CLIENT, "TRNE1C", EnvelopeBodyFixtures.plain());

    for (List<NewVersion> legs :
        List.of(List.of(envelopeLeg, plainLeg), List.of(plainLeg, envelopeLeg))) {
      assertThatThrownBy(() -> strict.commitReportable(unit(new ReportableDecision(both, legs))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("a report body must be an envelope");
    }
    assertThat(local.events()).isEmpty();
  }

  @Test
  void theEnvelopeEncodingIsOneAndThePlainEncodingStaysZero() {
    assertThat(PgReports.ENVELOPE_ENCODING).isEqualTo((short) 1);
    assertThat(PgReports.BODY_ENCODING).isEqualTo((short) 0);
  }

  /** A stored version row as the fake server answers {@code VERSIONS_SQL}. */
  private static Object[] storedVersion(long seq, byte[] body, int encoding) {
    return new Object[] {
      seq,
      5L,
      "NEWT",
      "ab".repeat(32),
      body,
      (short) encoding,
      (short) 1, // SOURCE
      77L
    };
  }

  /** Both encodings that the adapter writes are read; any other code is a refused row. */
  @Test
  void theVersionsAreReadWithEncodingsZeroAndOneAndNoOther() {
    byte[] envelope = EnvelopeBodyFixtures.envelope();
    byte[] plain = EnvelopeBodyFixtures.plain();
    SqlSpy answering =
        new SqlSpy()
            .answering(
                (sql, events) ->
                    sql.equals(PgReports.VERSIONS_SQL)
                        ? List.of(storedVersion(1, plain, 0), storedVersion(2, envelope, 1))
                        : List.of());
    PgReports reading =
        new PgReports(
            answering.over(null),
            new ManualClock(Instant.parse("2026-10-09T08:00:00Z")),
            config,
            decisions);

    List<StoredVersion> versions = reading.versions(new SubjectId(9));

    assertThat(versions).extracting(StoredVersion::versionSeq).containsExactly(1L, 2L);
    assertThat(versions.get(0).body()).isEqualTo(plain);
    assertThat(versions.get(1).body()).isEqualTo(envelope);

    for (int unknown : new int[] {2, 3, -1, Short.MAX_VALUE}) {
      SqlSpy refusing =
          new SqlSpy()
              .answering(
                  (sql, events) ->
                      sql.equals(PgReports.VERSIONS_SQL)
                          ? List.<Object[]>of(storedVersion(1, plain, unknown))
                          : List.of());
      PgReports other =
          new PgReports(
              refusing.over(null),
              new ManualClock(Instant.parse("2026-10-09T08:00:00Z")),
              config,
              decisions);
      assertThatThrownBy(() -> other.versions(new SubjectId(9)))
          .as("encoding %d", unknown)
          .isInstanceOf(LedgerException.class)
          .hasMessage("a stored report version has an unknown body encoding");
    }
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
