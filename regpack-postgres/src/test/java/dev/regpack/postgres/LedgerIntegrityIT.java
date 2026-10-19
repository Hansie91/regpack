package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.bodyAt;
import static dev.regpack.testkit.contract.LedgerFixtures.context;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.withContext;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.postgres.LedgerIntegrity.Kind;
import dev.regpack.postgres.LedgerIntegrity.Violation;
import dev.regpack.testkit.contract.DecisionLedgerContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The integrity queries of {@link LedgerIntegrity} on a real PostgreSQL (T19): each violation is
 * seeded by raw SQL that the constraints of {@code V001} allow (the cross-partition twin of a
 * sequence number, a head that was moved, a stub context, a gap in the versions of a subject), on
 * top of state that the adapter itself wrote, and each query must find exactly that. Skipped
 * without {@code REGPACK_PG_URL}; first run at CP-3.
 *
 * <p>The decisions that the adapter writes here carry their context, so that the only stub contexts
 * and the only gaps of a test are the ones that it seeds.
 */
class LedgerIntegrityIT extends AbstractPgStoreTest {
  private static final String MIFIR = DecisionLedgerContract.MIFIR;
  private static final Instant EVAL = DecisionLedgerContract.EVAL;

  /** 2026-10-09T09:30:00Z. */
  private static final Instant DAY1 = DecisionLedgerContract.TRADE_DATE_1;

  /** 2026-10-10T09:30:00Z. */
  private static final Instant DAY2 = DecisionLedgerContract.TRADE_DATE_2;

  private static final LocalDate DATE1 = LocalDate.of(2026, 10, 9);
  private static final LocalDate DATE2 = LocalDate.of(2026, 10, 10);

  private static final DecisionContext CONTEXT = context("integrity-1");

  private static String event(String name) {
    return "LEI1|XPAR|" + name;
  }

  // ------------------------------------------------------------------------------------------
  // writing through the adapter

  private DecisionBody body(String name, String digest, Instant asAt) {
    return withContext(bodyAt(name, MIFIR, "ctx", digest, Outcome.REPORT, EVAL, asAt), CONTEXT);
  }

  /** A decision that carries its context, on top of the expected head. */
  private Committed commit(String name, long expectedHeadSeq, String digest, Instant asAt) {
    return store()
        .commitDecisions(
            new BatchCommit(
                List.of(decision(body(name, digest, asAt), expectedHeadSeq, CONTEXT)),
                Optional.empty()))
        .get(0);
  }

  /** A REPORT decision with one market-leg version for {@code name}, on top of the head. */
  private void commitVersion(
      String name, long expectedHeadSeq, String digest, Instant asAt, String hashTag) {
    ReportableDecision unit =
        new ReportableDecision(
            decision(body(name, digest, asAt), expectedHeadSeq, CONTEXT),
            List.of(newVersion(name, Leg.MARKET, "EXEC1", "TRN" + name, "NEWT", hashTag)));
    store()
        .commitReportable(new ReportableCommit(List.of(unit), Optional.empty(), Optional.empty()));
  }

  /** A REPORT decision without any version, on top of the head. */
  private void commitWithoutVersion(
      String name, long expectedHeadSeq, String digest, Instant asAt) {
    ReportableDecision unit =
        new ReportableDecision(
            decision(body(name, digest, asAt), expectedHeadSeq, CONTEXT), List.of());
    store()
        .commitReportable(new ReportableCommit(List.of(unit), Optional.empty(), Optional.empty()));
  }

  // ------------------------------------------------------------------------------------------
  // raw SQL

  private void exec(String... statements) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private List<Violation> check(LocalDate from, LocalDate toInclusive) {
    return LedgerIntegrity.check(dataSource(), schema(), from, toInclusive);
  }

  private static List<Kind> kinds(List<Violation> found) {
    return found.stream().map(Violation::kind).distinct().toList();
  }

  /**
   * A second row with the sequence number of {@code name}'s second decision, in the piece of day 1.
   */
  private static String twinOfSecondDecisionOnDay1(String name) {
    return "INSERT INTO decision (as_at, regime_id, event_identity, decision_seq, context_id,"
        + " input_digest, outcome, circuit_open, body_encoding, body)"
        + " SELECT DATE '2026-10-09', regime_id, event_identity, decision_seq, context_id,"
        + " decode(repeat('ee', 32), 'hex'), outcome, circuit_open, body_encoding, body"
        + " FROM decision WHERE event_identity = '"
        + event(name)
        + "' AND decision_seq = 2";
  }

  // ------------------------------------------------------------------------------------------
  // the four violations

  /**
   * The cross-partition rows of T16: a correction that changed the trade date puts sequence 2 into
   * the piece of day 2, and a second row with sequence 2 is written into the piece of day 1, where
   * no unique index can see the first.
   */
  @Test
  void duplicateDecisionSeqIsFound() throws SQLException {
    commit("D1", 0, "d1", DAY1);
    commit("D1", 1, "d2", DAY2);
    exec(twinOfSecondDecisionOnDay1("D1"));
    assertThat(count("SELECT count(*) FROM decision WHERE decision_seq = 2")).isEqualTo(2);

    List<Violation> found = check(DATE1, DATE2);

    assertThat(found).hasSize(1);
    Violation violation = found.get(0);
    assertThat(violation.kind()).isEqualTo(Kind.DUPLICATE_DECISION_SEQ);
    assertThat(violation.detail())
        .contains("regime=" + MIFIR, "event=" + event("D1"), "decision_seq=2", "copies=2");
  }

  /**
   * Four chains, each with a head that is wrong in another way, and a fifth that is right: a head
   * behind the chain, a head that names a decision that does not exist, a chain without a head and
   * a head whose outcome is not that of the decision it names. Exactly the four are found.
   */
  @Test
  void headMismatchIsFound() throws SQLException {
    commit("H1", 0, "d1", DAY1);
    commit("H1", 1, "d2", DAY1);
    commit("H2", 0, "d1", DAY1);
    commit("H3", 0, "d1", DAY1);
    commit("H4", 0, "d1", DAY1);
    commit("H5", 0, "d1", DAY1);
    exec(
        "UPDATE decision_head SET decision_seq = 1, decision_id = (SELECT decision_id FROM decision"
            + " WHERE event_identity = '"
            + event("H1")
            + "' AND decision_seq = 1) WHERE event_identity = '"
            + event("H1")
            + "'",
        "UPDATE decision_head SET decision_id = 987654321 WHERE event_identity = '"
            + event("H2")
            + "'",
        "DELETE FROM decision_head WHERE event_identity = '" + event("H3") + "'",
        "UPDATE decision_head SET outcome = 2 WHERE event_identity = '" + event("H4") + "'");

    List<Violation> found = check(DATE1, DATE2);

    assertThat(found).hasSize(4);
    assertThat(kinds(found)).containsExactly(Kind.HEAD_MISMATCH);
    assertThat(found.get(0).detail())
        .contains(
            "event=" + event("H1"),
            "reason=head behind the chain",
            "highest_seq_in_range=2",
            "head_seq=1");
    assertThat(found.get(1).detail())
        .contains(
            "event=" + event("H2"), "reason=head names no decision", "head_decision_id=987654321");
    assertThat(found.get(2).detail())
        .contains(
            "event=" + event("H3"), "reason=no head row", "head_seq=none", "head_decision_id=none");
    assertThat(found.get(3).detail())
        .contains("event=" + event("H4"), "reason=head differs from the decision it names");
    assertThat(found).noneMatch(v -> v.detail().contains(event("H5")));
  }

  /**
   * A decision that cited its context before the context was given has a stub context row (D-S7)
   * and is found; a decision that carries its context is not; a later decision that gives the
   * context fills the stub, and the violation is gone.
   */
  @Test
  void decisionWithoutContextIsFound() throws SQLException {
    DecisionContext stubbed = context("integrity-stub");
    DecisionBody citing =
        withContext(bodyAt("S1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, DAY1), stubbed);
    Committed stub =
        store()
            .commitDecisions(new BatchCommit(List.of(decision(citing, 0)), Optional.empty()))
            .get(0);
    commit("S2", 0, "d1", DAY1);
    assertThat(count("SELECT count(*) FROM decision_context WHERE body IS NULL")).isEqualTo(1);

    List<Violation> found = check(DATE1, DATE2);

    assertThat(found).hasSize(1);
    Violation violation = found.get(0);
    assertThat(violation.kind()).isEqualTo(Kind.DECISION_WITHOUT_CONTEXT);
    assertThat(violation.detail())
        .contains(
            "decision_id=" + stub.record().decisionId().value(),
            "event=" + event("S1"),
            "decision_seq=1");

    store()
        .commitDecisions(
            new BatchCommit(
                List.of(
                    decision(
                        withContext(
                            bodyAt("S3", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, DAY1), stubbed),
                        0,
                        stubbed)),
                Optional.empty()));
    assertThat(count("SELECT count(*) FROM decision_context WHERE body IS NULL")).isZero();
    assertThat(check(DATE1, DATE2)).isEmpty();
  }

  /**
   * Three subjects of two versions each, two of them damaged: the first has a hole (versions 1, 2
   * and 4, and a counter that says 4), the second a counter that is ahead of its versions. The
   * third is clean. The versions are insert-only, so the hole is made by an insert that skips a
   * number, which the constraints allow.
   */
  @Test
  void versionGapIsFound() throws SQLException {
    commitVersion("G1", 0, "d1", DAY1, "h1");
    commitVersion("G1", 1, "d2", DAY1, "h2");
    commitWithoutVersion("G1", 2, "d3", DAY1);
    commitVersion("G2", 0, "d1", DAY1, "h1");
    commitVersion("G2", 1, "d2", DAY1, "h2");
    commitVersion("G3", 0, "d1", DAY1, "h1");
    commitVersion("G3", 1, "d2", DAY1, "h2");
    exec(
        "INSERT INTO report_version (subject_id, version_seq, decision_id, decision_as_at, action,"
            + " logical_hash, body, body_encoding, origin, created_at)"
            + " SELECT s.subject_id, 4, d.decision_id, d.as_at, 'NEWT', decode(repeat('aa', 32),"
            + " 'hex'), decode('01', 'hex'), 0, 1, TIMESTAMPTZ '2026-10-09 10:00:00+00'"
            + " FROM report_subject s, decision d WHERE s.event_identity = '"
            + event("G1")
            + "' AND d.event_identity = '"
            + event("G1")
            + "' AND d.decision_seq = 3",
        "UPDATE report_subject SET last_version_seq = 4 WHERE event_identity = '"
            + event("G1")
            + "'",
        "UPDATE report_subject SET last_version_seq = 3 WHERE event_identity = '"
            + event("G2")
            + "'");

    List<Violation> found = check(DATE1, DATE2);

    assertThat(found).hasSize(2);
    assertThat(kinds(found)).containsExactly(Kind.VERSION_GAP);
    assertThat(found.get(0).detail())
        .contains(
            "event=" + event("G1"), "last_version_seq=4", "versions=3", "lowest=1", "highest=4");
    assertThat(found.get(1).detail())
        .contains(
            "event=" + event("G2"), "last_version_seq=3", "versions=2", "lowest=1", "highest=2");
  }

  // ------------------------------------------------------------------------------------------
  // no false alarm, and the range

  @Test
  void aCleanLedgerHasNoViolations() {
    assertThat(check(DATE1, DATE2)).as("an empty ledger").isEmpty();

    // a chain whose trade date changes (its pieces are in two partitions), with versions
    commitVersion("C1", 0, "d1", DAY1, "h1");
    commitVersion("C1", 1, "d2", DAY2, "h2");
    commitWithoutVersion("C1", 2, "d3", DAY2);
    // a chain that is a single decision, and a second subject with one version
    commit("C2", 0, "d1", DAY1);
    commitVersion("C3", 0, "d1", DAY2, "h1");

    assertThat(check(DATE1, DATE2)).isEmpty();
    assertThat(check(DATE1, DATE1)).isEmpty();
    assertThat(check(DATE2, DATE2)).isEmpty();
    assertThat(check(LocalDate.of(2000, 1, 1), LocalDate.of(2199, 12, 31))).isEmpty();
  }

  /**
   * Both ends of the range are inclusive, and a violation outside the range is not reported (a twin
   * that lies outside needs the full scan, a batch job of P4): the twin of a sequence number is
   * found only when both rows are in the range, a stub decision of day 2 only when day 2 is, a hole
   * in the versions of subjects of day 1 only when day 1 is.
   */
  @Test
  void theRangeBoundsTheScan() throws SQLException {
    commit("R1", 0, "d1", DAY1);
    commit("R1", 1, "d2", DAY2);
    exec(twinOfSecondDecisionOnDay1("R1"));
    DecisionContext stubbed = context("integrity-range-stub");
    store()
        .commitDecisions(
            new BatchCommit(
                List.of(
                    decision(
                        withContext(
                            bodyAt("R2", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, DAY2), stubbed),
                        0)),
                Optional.empty()));
    commitVersion("R3", 0, "d1", DAY1, "h1");
    commitVersion("R3", 1, "d2", DAY1, "h2");
    exec(
        "UPDATE report_subject SET last_version_seq = 3 WHERE event_identity = '"
            + event("R3")
            + "'");

    // day 1 alone: the twin is there, the row it duplicates is not; the stub is not; the hole is
    assertThat(kinds(check(DATE1, DATE1))).containsExactly(Kind.VERSION_GAP);
    // day 2 alone: the stub; not the twin (day 1), not the hole (its decisions are of day 1)
    assertThat(kinds(check(DATE2, DATE2))).containsExactly(Kind.DECISION_WITHOUT_CONTEXT);
    // both days: all three, in the order of the kinds
    assertThat(kinds(check(DATE1, DATE2)))
        .containsExactly(
            Kind.DUPLICATE_DECISION_SEQ, Kind.DECISION_WITHOUT_CONTEXT, Kind.VERSION_GAP);
    // days that hold nothing, before, after and between the rows
    assertThat(check(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8))).isEmpty();
    assertThat(check(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 31))).isEmpty();
  }
}
