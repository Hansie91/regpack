package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.bodyAt;
import static dev.regpack.testkit.contract.LedgerFixtures.context;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static dev.regpack.testkit.contract.LedgerFixtures.withContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What the decision side of the adapter decides without a server (T16): the arguments that are
 * refused before a connection is opened, and static checks of the statement texts against the
 * migration, because the statements themselves first run on a real server at CP-2.
 */
class PgDecisionsTest {
  private static final String MIFIR = "mifir.eu.rts22";
  private static final Instant EVAL = Instant.parse("2026-10-09T10:00:00Z");
  private static final Instant AS_AT = Instant.parse("2026-10-09T09:30:00Z");

  private final SqlSpy spy = new SqlSpy();
  private final PgDecisions decisions = new PgDecisions(spy.over(null), Config.forSchema("t42_7"));

  private static DecisionBody body(String digest, Instant asAt) {
    return bodyAt("E1", MIFIR, "ctx", digest, Outcome.REPORT, EVAL, asAt);
  }

  private static BatchCommit batch(NewDecision... list) {
    return new BatchCommit(List.of(list), Optional.empty());
  }

  @Test
  void aNegativeExpectedHeadSeqIsRefusedBeforeAnyConnection() {
    BatchCommit bad = batch(decision(body("d1", AS_AT), -1));
    assertThatThrownBy(() -> decisions.commitDecisions(bad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aContextWhoseIdDiffersFromTheBodyIsRefusedBeforeAnyConnection() {
    DecisionContext good = context("b1");
    DecisionContext other = context("b2");
    BatchCommit bad = batch(decision(withContext(body("d1", AS_AT), good), 0, other));
    assertThatThrownBy(() -> decisions.commitDecisions(bad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void anAsAtOutsideTheSupportedRangeIsRefusedBeforeAnyConnection() {
    for (Instant asAt :
        List.of(
            Instant.parse("1999-12-31T23:59:59.999999Z"), Instant.parse("2200-01-01T00:00:00Z"))) {
      BatchCommit bad = batch(decision(body("d1", asAt), 0));
      assertThatThrownBy(() -> decisions.commitDecisions(bad))
          .as("asAt %s", asAt)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aBodyThatHasNoCanonicalFormIsRefusedBeforeAnyConnection() {
    // nanosecond precision has no canonical JSON, so the body cannot be stored
    BatchCommit bad =
        batch(decision(body("d1", Instant.parse("2026-10-09T09:30:00.000000001Z")), 0));
    assertThatThrownBy(() -> decisions.commitDecisions(bad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aBadDecisionAfterAGoodOneStillWritesNothing() {
    BatchCommit bad = batch(decision(body("d1", AS_AT), 0), decision(body("d2", AS_AT), -3));
    assertThatThrownBy(() -> decisions.commitDecisions(bad))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void theBodyEncodingColumnHoldsTheVersionOfTheCodec() {
    // decision.body_encoding is the version byte of DecisionBodyCodec; 1 is the deflated canonical
    // JSON of A13. A different codec writes a different number and never rewrites old rows.
    assertThat(PgDecisions.BODY_ENCODING).isEqualTo((short) 1);
    assertThat(PgDecisions.BODY_ENCODING).isEqualTo((short) DecisionBodyCodec.VERSION);
  }

  @Test
  void aDecisionIsCircuitOpenExactlyWhenItsFlagsSayCircuitOpen() {
    DecisionBody plain = body("d1", AS_AT);
    assertThat(PgDecisions.circuitOpen(plain)).isFalse();
    DecisionBody open =
        new DecisionBody(
            plain.eventIdentity(),
            new Regime(MIFIR),
            plain.decisionContextId(),
            AS_AT,
            EVAL,
            Outcome.UNDETERMINED,
            List.of("rule_id=R1", "circuit=open"),
            new ReasonCode("EXEC_CIRCUIT_OPEN"),
            "N08",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Sha256.ofUtf8("d1"));
    assertThat(PgDecisions.circuitOpen(open)).isTrue();
    DecisionBody errorOnly =
        new DecisionBody(
            plain.eventIdentity(),
            new Regime(MIFIR),
            plain.decisionContextId(),
            AS_AT,
            EVAL,
            Outcome.UNDETERMINED,
            List.of("rule_id=R1"),
            new ReasonCode("EXEC_ERROR"),
            "N08",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Sha256.ofUtf8("d1"));
    assertThat(PgDecisions.circuitOpen(errorOnly)).isFalse();
  }

  /**
   * A mistake in the number of arrays of an {@code unnest} statement cannot be found without a
   * server, so the text is checked against itself: one bind per array, one column name per array
   * (and one more for the ordinality), and no other bind.
   */
  @Test
  void everyUnnestStatementBindsAsManyArraysAsItNamesColumns() {
    for (String sql :
        List.of(PgDecisions.HEADS_SQL, PgDecisions.INSERT_SQL, PgDecisions.HEAD_INSERT_SQL)) {
      int open = sql.indexOf("unnest(");
      int close = sql.indexOf(')', open);
      long binds = sql.substring(open, close).chars().filter(c -> c == '?').count();
      int aliasOpen = sql.indexOf(" t(", close) + 3;
      int aliasClose = sql.indexOf(')', aliasOpen);
      int columns = sql.substring(aliasOpen, aliasClose).split(",").length;
      int ordinality = sql.contains("WITH ORDINALITY") ? 1 : 0;
      assertThat(columns).as(sql).isEqualTo(binds + ordinality);
      assertThat(sql.chars().filter(c -> c == '?').count()).as(sql).isEqualTo(binds);
    }
  }

  @Test
  void theSetBasedInsertsFollowTheArrayOrderAndNameTheirArbiter() {
    assertThat(PgDecisions.INSERT_SQL)
        .contains("WITH ORDINALITY")
        .contains(
            "WHERE true ORDER BY t.ord ON CONFLICT ON CONSTRAINT decision_idem_key DO NOTHING")
        .endsWith("RETURNING regime_id, event_identity, decision_id");
    assertThat(PgDecisions.HEAD_INSERT_SQL)
        .contains("WITH ORDINALITY")
        .contains("WHERE true ORDER BY t.ord ON CONFLICT ON CONSTRAINT decision_head_pk DO NOTHING")
        .endsWith("RETURNING regime_id, event_identity");
    // the compare-and-set of an existing head names the sequence it expects
    assertThat(PgDecisions.HEAD_UPDATE_SQL).endsWith("AND decision_seq = ?");
  }

  @Test
  void theStatementsOnlyNameColumnsAndConstraintsThatV001Defines() throws IOException {
    String v001 = migration();
    String decision = tableBlock(v001, "decision");
    String head = tableBlock(v001, "decision_head");
    String context = tableBlock(v001, "decision_context");
    String regime = tableBlock(v001, "regime");
    String outbox = tableBlock(v001, "outbox");

    assertColumns(decision, columnList(PgDecisions.INSERT_SQL, "INSERT INTO decision ("));
    assertColumns(head, columnList(PgDecisions.HEAD_INSERT_SQL, "INSERT INTO decision_head ("));
    assertColumns(head, setColumns(PgDecisions.HEAD_UPDATE_SQL));
    assertColumns(
        head, List.of("regime_id", "event_identity", "decision_seq", "decision_id", "as_at"));
    assertColumns(
        decision,
        List.of(
            "decision_id",
            "decision_seq",
            "prev_decision_id",
            "prev_as_at",
            "body_encoding",
            "body",
            "regime_id",
            "event_identity",
            "context_id",
            "input_digest",
            "as_at"));
    assertColumns(
        context,
        List.of(
            "context_id", "context_sha", "pack_digest", "circuit_epoch", "engine_build", "body"));
    assertColumns(regime, List.of("regime_id", "regime"));
    assertColumns(outbox, List.of("outbox_id", "state"));

    assertThat(v001)
        .contains("CONSTRAINT decision_idem_key UNIQUE (regime_id, event_identity, context_id,")
        .contains("CONSTRAINT decision_head_pk PRIMARY KEY (regime_id, event_identity)")
        .contains("CONSTRAINT regime_name_key UNIQUE (regime)")
        .contains("CONSTRAINT decision_context_sha_key UNIQUE (context_sha)");
    assertThat(PgDecisions.CONTEXT_INSERT_SQL).contains("ON CONFLICT (context_sha) DO NOTHING");
    assertThat(PgDecisions.REGIME_INSERT_SQL)
        .contains("ON CONFLICT ON CONSTRAINT regime_name_key DO NOTHING");
    assertThat(PgDecisions.INSERT_SQL).contains("decision_idem_key");
  }

  @Test
  void theDecisionTableIsPartitionedByDayOnAsAtSoEveryProbeCarriesIt() throws IOException {
    String v001 = migration();
    assertThat(v001).contains(") PARTITION BY RANGE (as_at);");
    // the two point reads that must touch one partition both bind as_at
    assertThat(PgDecisions.PROBE_SQL).contains("decision_id = ? AND as_at = ?");
    assertThat(PgDecisions.STORED_SQL).contains("AND as_at = ?");
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
      assertThat(m.find()).as("column %s of %s", column, firstLine(tableBlock)).isTrue();
    }
  }

  private static String firstLine(String text) {
    return text.substring(0, text.indexOf('\n'));
  }

  private static List<String> columnList(String sql, String prefix) {
    int open = sql.indexOf(prefix) + prefix.length();
    int close = sql.indexOf(')', open);
    return List.of(sql.substring(open, close).split(",\\s*"));
  }

  private static List<String> setColumns(String updateSql) {
    int set = updateSql.indexOf(" SET ") + 5;
    int where = updateSql.indexOf(" WHERE ");
    return Arrays.stream(updateSql.substring(set, where).split(","))
        .map(assignment -> assignment.trim().split(" ")[0])
        .toList();
  }
}
