package dev.regpack.spikes.l.schema;

import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Criterion L-12 (spec 6.3, 10, 14.1; review focus RF15): can PostgreSQL 18 express the constraints
 * that the ledger contract assumes? Ten checks, each named after the P0 contract test it mirrors,
 * each in its own schema ({@code sp_chk_c01} to {@code sp_chk_c10}) that is dropped afterwards. A
 * check that fails is a finding, not a bug to hide: the report states which constraint cannot be
 * expressed and how it can be re-shaped.
 *
 * <p>The SQL of the fenced compare-and-sets is the SQL that the P3 adapter would send; the checks
 * run it with the current token and with a stale one and count the affected rows.
 */
public final class ConstraintChecks {
  /** The outcome of one check: its name, whether it passed and what was observed. */
  public record CheckResult(String name, boolean passed, String detail) {}

  /** The ten check names, in the order of {@link #runAll(PgFixture)}. */
  public static final List<String> NAMES =
      List.of(
          "inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey",
          "markCommitIssuedIsFencedCompareAndSet",
          "takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder",
          "staleExpectedHeadSeqIsRejectedByHeadCompareAndSet",
          "crossPartitionDuplicateDecisionSeqIsRejectedByHead",
          "redeliveryOfADecisionIsNoopThroughOnConflict",
          "restoreEpochTokenPairIsComparedAsAWhole",
          "crossPartitionSameVersionDifferentHashNeedsAnUnpartitionedRegistry",
          "reportSubjectIsRegimeScoped",
          "trnUniquenessIsPerExecutingLei");

  private static final String UNIQUE_VIOLATION = "23505";
  private static final LocalDate DAY1 = LocalDate.of(2026, 1, 1);
  private static final LocalDate DAY2 = DAY1.plusDays(1);
  private static final LocalDate DAY3 = DAY1.plusDays(2);
  private static final int PARTITIONS = 3;

  /**
   * Fenced compare-and-set of spec 11.2 step 4: the attempt's own token must equal the given one.
   */
  private static final String MARK_COMMIT_ISSUED =
      "UPDATE attempt a SET commit_issued = true FROM delivery d"
          + " WHERE a.attempt_id = ? AND (a.lease_epoch, a.lease_counter) = (?, ?)"
          + " AND d.delivery_id = a.delivery_id AND d.state = 'SENDING'";

  /** Starting to send needs the current lease token of the endpoint (the row of {@code lease}). */
  private static final String BEGIN_SENDING =
      "UPDATE delivery d SET state = 'SENDING' FROM lease l"
          + " WHERE d.delivery_id = ? AND d.state = 'QUEUED' AND l.endpoint_id = d.endpoint_id"
          + " AND (l.restore_epoch, l.counter) = (?, ?)";

  private static final String INSERT_DELIVERY =
      "INSERT INTO delivery (delivery_id, endpoint_id, business_key, state) VALUES (?, ?, ?, ?)";
  private static final String INSERT_ATTEMPT =
      "INSERT INTO attempt (attempt_id, delivery_id, lease_epoch, lease_counter) VALUES (?, ?, ?, ?)";
  private static final String INSERT_LEASE =
      "INSERT INTO lease (endpoint_id, restore_epoch, counter) VALUES (?, ?, ?)";
  private static final String SET_STATE = "UPDATE delivery SET state = ? WHERE delivery_id = ?";
  private static final String INSERT_DECISION =
      "INSERT INTO decision (decision_id, regime, event_identity, decision_seq,"
          + " decision_context_id, input_digest, as_at, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, 1)";
  private static final String INSERT_SUBJECT =
      "INSERT INTO report_subject (subject_id, regime, event_identity, leg) VALUES (?, ?, ?, ?)";
  private static final String INSERT_TRN =
      "INSERT INTO trn_assignment (subject_id, executing_lei, trn) VALUES (?, ?, ?)";
  private static final String INSERT_INGEST =
      "INSERT INTO ingest (source_id, record_id, version, trade_date, content_hash, batch_id)"
          + " VALUES (?, ?, ?, ?, ?, ?)";

  /** One check: runs against an installed schema, returns what it observed, throws on a failure. */
  @FunctionalInterface
  private interface Check {
    String run(Connection c) throws SQLException;
  }

  private static final class CheckFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CheckFailed(String message) {
      super(message);
    }
  }

  private static final List<Check> CHECKS =
      List.of(
          ConstraintChecks::inFlightGuard,
          ConstraintChecks::markCommitIssued,
          ConstraintChecks::takeover,
          ConstraintChecks::staleExpectedHead,
          ConstraintChecks::crossPartitionHead,
          ConstraintChecks::redeliveryNoop,
          ConstraintChecks::restoreEpochPair,
          ConstraintChecks::unpartitionedRegistry,
          ConstraintChecks::regimeScopedSubject,
          ConstraintChecks::trnPerLei);

  private ConstraintChecks() {}

  /** The schema of check {@code index} (0-based): {@code sp_chk_c01} for the first. */
  static String schemaName(int index) {
    return String.format(Locale.ROOT, "sp_chk_c%02d", index + 1);
  }

  /** Runs the ten checks in the order of {@link #NAMES}, each in its own schema. */
  public static List<CheckResult> runAll(PgFixture pg) throws SQLException {
    List<CheckResult> results = new ArrayList<>();
    for (int i = 0; i < NAMES.size(); i++) {
      results.add(runOne(pg, i));
    }
    return List.copyOf(results);
  }

  private static CheckResult runOne(PgFixture pg, int index) throws SQLException {
    String name = NAMES.get(index);
    String schema = schemaName(index);
    CheckResult result;
    try (Connection c = pg.primary()) {
      // a schema left by an aborted earlier run must not make this check fail for the wrong reason
      SchemaInstaller.drop(c, schema);
      SchemaInstaller.install(c, schema, Variant.E1_COMPACT, DAY1, PARTITIONS);
      result = new CheckResult(name, true, CHECKS.get(index).run(c));
    } catch (CheckFailed e) {
      result = new CheckResult(name, false, e.getMessage());
    } catch (SQLException e) {
      result = new CheckResult(name, false, describe(e));
    }
    // cleanup on a fresh connection: the first one may sit in an aborted transaction
    try (Connection c = pg.primary()) {
      SchemaInstaller.drop(c, schema);
    } catch (SQLException e) {
      result = new CheckResult(name, false, result.detail() + "; cleanup failed: " + describe(e));
    }
    return result;
  }

  private static String describe(SQLException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
    return "SQLException " + e.getSQLState() + ": " + message;
  }

  // ---- the ten checks -------------------------------------------------------------------------

  private static String inFlightGuard(Connection c) throws SQLException {
    update(c, INSERT_DELIVERY, 1L, 1, "bk", "QUEUED");
    update(c, INSERT_DELIVERY, 2L, 1, "bk", "QUEUED");
    expect(
        update(c, SET_STATE, "SENDING", 1L) == 1, "the first delivery of the key may go SENDING");
    expectViolation(c, UNIQUE_VIOLATION, SET_STATE, "SENDING", 2L);
    // the guard covers the three in-flight states, not only SENDING
    expect(update(c, SET_STATE, "AWAITING_ACK", 1L) == 1, "SENDING to AWAITING_ACK");
    expectViolation(c, UNIQUE_VIOLATION, SET_STATE, "SENDING", 2L);
    expectViolation(c, UNIQUE_VIOLATION, SET_STATE, "IN_DOUBT", 2L);
    // a duplicate that is created directly in an in-flight state is refused as well
    expectViolation(c, UNIQUE_VIOLATION, INSERT_DELIVERY, 3L, 1, "bk", "SENDING");
    // another endpoint and another key are independent
    update(c, INSERT_DELIVERY, 4L, 2, "bk", "SENDING");
    update(c, INSERT_DELIVERY, 5L, 1, "other", "SENDING");
    // leaving the in-flight set releases the key
    expect(update(c, SET_STATE, "ACCEPTED_CONFIRMED", 1L) == 1, "AWAITING_ACK to confirmed");
    expect(update(c, SET_STATE, "SENDING", 2L) == 1, "the key is free once the first is confirmed");
    // so does the gate release flag
    expectViolation(c, UNIQUE_VIOLATION, SET_STATE, "IN_DOUBT", 1L);
    expect(
        update(c, "UPDATE delivery SET gate_released = true WHERE delivery_id = ?", 2L) == 1,
        "gate_released set");
    expect(update(c, SET_STATE, "SENDING", 1L) == 1, "a released delivery no longer holds the key");
    return "partial unique index: SENDING, AWAITING_ACK and IN_DOUBT hold the key; others do not";
  }

  private static String markCommitIssued(Connection c) throws SQLException {
    update(c, INSERT_DELIVERY, 1L, 1, "bk1", "SENDING");
    update(c, INSERT_DELIVERY, 2L, 1, "bk2", "AWAITING_ACK");
    update(c, INSERT_ATTEMPT, 10L, 1L, 0, 2L);
    update(c, INSERT_ATTEMPT, 11L, 2L, 0, 2L);
    int stale = update(c, MARK_COMMIT_ISSUED, 10L, 0, 1L);
    expect(stale == 0, "a stale token affects 0 rows, affected " + stale);
    expect(!flag(c, 10L), "the marker is still false after the stale token");
    int current = update(c, MARK_COMMIT_ISSUED, 10L, 0, 2L);
    expect(current == 1, "the current token affects 1 row, affected " + current);
    expect(flag(c, 10L), "the marker is true after the current token");
    int again = update(c, MARK_COMMIT_ISSUED, 10L, 0, 2L);
    expect(again == 1, "the current token again is idempotent, affected " + again);
    int notSending = update(c, MARK_COMMIT_ISSUED, 11L, 0, 2L);
    expect(notSending == 0, "a delivery that is no longer SENDING affects 0 rows");
    expect(!flag(c, 11L), "its marker stays false");
    return "stale token 0 rows, current token 1 row, repeat 1 row, not SENDING 0 rows";
  }

  private static String takeover(Connection c) throws SQLException {
    update(c, INSERT_LEASE, 1, 0, 1L);
    update(c, INSERT_LEASE, 2, 0, 1L);
    update(c, INSERT_DELIVERY, 1L, 1, "bk1", "SENDING");
    update(c, INSERT_DELIVERY, 2L, 1, "bk2", "QUEUED");
    update(c, INSERT_DELIVERY, 3L, 1, "bk3", "AWAITING_ACK");
    update(c, INSERT_DELIVERY, 4L, 2, "bk4", "SENDING");
    update(c, INSERT_ATTEMPT, 10L, 1L, 0, 1L);
    // holder A has checked its token and stalls; holder B takes over: the new counter and the
    // SENDING deliveries of this endpoint move to IN_DOUBT in one transaction
    c.setAutoCommit(false);
    try {
      long counter =
          scalarLong(
              c,
              "UPDATE lease SET counter = counter + 1"
                  + " WHERE endpoint_id = ? AND restore_epoch = ? AND counter = ? RETURNING counter",
              1,
              0,
              1L);
      expect(counter == 2L, "the takeover raises the counter to 2, got " + counter);
      int moved =
          update(
              c,
              "UPDATE delivery SET state = 'IN_DOUBT' WHERE endpoint_id = ? AND state = 'SENDING'",
              1);
      expect(moved == 1, "exactly the SENDING delivery of the endpoint moves, moved " + moved);
      c.commit();
    } catch (SQLException | RuntimeException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
    expect(state(c, 1L).equals("IN_DOUBT"), "SENDING became IN_DOUBT, is " + state(c, 1L));
    expect(state(c, 3L).equals("AWAITING_ACK"), "AWAITING_ACK stays, is " + state(c, 3L));
    expect(state(c, 4L).equals("SENDING"), "another endpoint stays SENDING, is " + state(c, 4L));
    // a second contender that read counter 1 loses the compare-and-set
    int second =
        update(
            c,
            "UPDATE lease SET counter = counter + 1"
                + " WHERE endpoint_id = ? AND restore_epoch = ? AND counter = ?",
            1,
            0,
            1L);
    expect(second == 0, "a contender with the stale counter affects 0 rows, affected " + second);
    // the old holder is fenced: its marker and its next send are refused. The marker is refused by
    // the state guard here (the delivery is IN_DOUBT now); the token compare of MARK_COMMIT_ISSUED
    // is covered by check 2 (a stale token on a SENDING delivery). The send is refused by the
    // token compare of BEGIN_SENDING.
    int marker = update(c, MARK_COMMIT_ISSUED, 10L, 0, 1L);
    expect(marker == 0, "the old holder's marker affects 0 rows, affected " + marker);
    expect(!flag(c, 10L), "the old holder's marker stays false");
    int oldSend = update(c, BEGIN_SENDING, 2L, 0, 1L);
    expect(oldSend == 0, "the old token cannot begin sending, affected " + oldSend);
    expect(state(c, 2L).equals("QUEUED"), "the delivery stays QUEUED for the old holder");
    int newSend = update(c, BEGIN_SENDING, 2L, 0, 2L);
    expect(newSend == 1, "the new token begins sending, affected " + newSend);
    return "takeover in one transaction; old token 0 rows for marker and send, new token 1 row";
  }

  private static String staleExpectedHead(Connection c) throws SQLException {
    byte[] digest = {1};
    expect(
        commitDecision(c, "MIFIR", "E1", 0, 1L, 1L, digest, DAY1), "the first decision is applied");
    // a batch of two decisions whose second carries a stale expected head: all or nothing
    c.setAutoCommit(false);
    boolean batchApplied;
    try {
      boolean first = commitDecision(c, "MIFIR", "E2", 0, 2L, 1L, digest, DAY1);
      boolean second = commitDecision(c, "MIFIR", "E1", 0, 3L, 2L, digest, DAY1);
      expect(first, "the first decision of the batch is fine");
      batchApplied = second;
      if (batchApplied) {
        c.commit();
      } else {
        c.rollback();
      }
    } catch (SQLException | RuntimeException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
    expect(!batchApplied, "the stale expected head (0 on an existing chain) is rejected");
    expect(
        scalarLong(c, "SELECT count(*) FROM decision_head WHERE event_identity = ?", "E2") == 0,
        "the rollback removed the head of E2");
    expect(
        scalarLong(c, "SELECT count(*) FROM decision WHERE event_identity = ?", "E2") == 0,
        "the rollback removed the decision of E2");
    expect(
        scalarLong(c, "SELECT count(*) FROM decision WHERE event_identity = ?", "E1") == 1,
        "the chain of E1 still has one decision");
    // the update form: a stale expected seq on an existing head affects 0 rows
    expect(commitDecision(c, "MIFIR", "E1", 1, 4L, 3L, digest, DAY1), "expected 1 on head 1");
    expect(
        !commitDecision(c, "MIFIR", "E1", 1, 5L, 4L, digest, DAY1),
        "expected 1 on head 2 is stale");
    expect(
        scalarLong(c, "SELECT decision_seq FROM decision_head WHERE event_identity = ?", "E1")
            == 2L,
        "the head of E1 is at 2");
    return "head insert-if-absent and update-where-seq: stale expected seq 0 rows, batch rolled back";
  }

  private static String crossPartitionHead(Connection c) throws SQLException {
    byte[] a = {1};
    byte[] b = {2};
    // without the head: seq 2 twice in two partitions is accepted (no key can span the partitions)
    update(c, INSERT_DECISION, 20L, "MIFIR", "E1", 2L, 1L, a, DAY1);
    update(c, INSERT_DECISION, 21L, "MIFIR", "E1", 2L, 2L, b, DAY2);
    long twice =
        scalarLong(
            c,
            "SELECT count(*) FROM decision WHERE regime = ? AND event_identity = ? AND decision_seq = ?",
            "MIFIR",
            "E1",
            2L);
    expect(twice == 2L, "without the head two rows with seq 2 are accepted, found " + twice);
    // with the head: the second writer of seq 2 on another partition loses the compare-and-set
    expect(commitDecision(c, "MIFIR", "E2", 0, 30L, 1L, a, DAY1), "seq 1 of E2");
    expect(commitDecision(c, "MIFIR", "E2", 1, 31L, 2L, a, DAY2), "the first writer of seq 2");
    expect(
        !commitDecision(c, "MIFIR", "E2", 1, 32L, 3L, b, DAY3),
        "the second writer of seq 2, in another partition, is rejected by the head");
    long rows =
        scalarLong(
            c,
            "SELECT count(*) FROM decision WHERE regime = ? AND event_identity = ? AND decision_seq = ?",
            "MIFIR",
            "E2",
            2L);
    expect(rows == 1L, "with the head exactly one row has seq 2, found " + rows);
    return "no partitioned key can hold (regime, event, seq): 2 rows without the head, 1 with it";
  }

  private static String redeliveryNoop(Connection c) throws SQLException {
    String idempotent =
        INSERT_DECISION
            + " ON CONFLICT (event_identity, decision_context_id, input_digest, as_at) DO NOTHING";
    byte[] digest = {7};
    int first = update(c, idempotent, 1L, "MIFIR", "E1", 1L, 5L, digest, DAY1);
    expect(first == 1, "the first delivery inserts, affected " + first);
    expect(
        update(
                c,
                "INSERT INTO decision_head (regime, event_identity, decision_seq, decision_id)"
                    + " VALUES (?, ?, 1, ?) ON CONFLICT (regime, event_identity) DO NOTHING",
                "MIFIR",
                "E1",
                1L)
            == 1,
        "the head of the first delivery");
    // the redelivery has a new decision id and the same idempotency key
    int again = update(c, idempotent, 2L, "MIFIR", "E1", 1L, 5L, digest, DAY1);
    expect(again == 0, "the redelivery is a no-op, affected " + again);
    expect(
        scalarLong(c, "SELECT count(*) FROM decision WHERE event_identity = ?", "E1") == 1L,
        "still one decision");
    expect(
        scalarLong(c, "SELECT decision_seq FROM decision_head WHERE event_identity = ?", "E1")
            == 1L,
        "the head did not advance");
    // a different input digest on the same event and context is a new decision, not a no-op
    byte[] other = {8};
    expect(
        update(c, idempotent, 3L, "MIFIR", "E1", 2L, 5L, other, DAY1) == 1,
        "a new input digest inserts");
    // finding: as_at is part of the key, so the same key under another trade date is NOT caught
    // here
    int moved = update(c, idempotent, 4L, "MIFIR", "E1", 1L, 5L, digest, DAY2);
    return "ON CONFLICT on the partitioned unique key: redelivery 0 rows; same key under another"
        + " as_at inserted "
        + moved
        + " row (only the head catches it)";
  }

  private static String restoreEpochPair(Connection c) throws SQLException {
    update(c, INSERT_DELIVERY, 1L, 1, "bk", "SENDING");
    update(c, INSERT_ATTEMPT, 10L, 1L, 0, 5L);
    int otherEpoch = update(c, MARK_COMMIT_ISSUED, 10L, 1, 5L);
    expect(otherEpoch == 0, "(1, 5) never equals (0, 5), affected " + otherEpoch);
    int sameEpoch = update(c, MARK_COMMIT_ISSUED, 10L, 0, 5L);
    expect(sameEpoch == 1, "(0, 5) equals (0, 5), affected " + sameEpoch);
    update(c, INSERT_LEASE, 1, 1, 5L);
    long counterOnly = scalarLong(c, "SELECT count(*) FROM lease WHERE counter = ?", 5L);
    expect(counterOnly == 1L, "a counter-only compare would wrongly match, found " + counterOnly);
    long oldPair =
        scalarLong(c, "SELECT count(*) FROM lease WHERE (restore_epoch, counter) = (?, ?)", 0, 5L);
    expect(oldPair == 0L, "the whole pair (0, 5) does not match the lease (1, 5)");
    long newPair =
        scalarLong(c, "SELECT count(*) FROM lease WHERE (restore_epoch, counter) = (?, ?)", 1, 5L);
    expect(newPair == 1L, "the whole pair (1, 5) matches the lease (1, 5)");
    expect(
        scalarLong(
                c,
                "SELECT CASE WHEN ROW(?::int, ?::bigint) > ROW(?::int, ?::bigint) THEN 1 ELSE 0 END",
                1,
                1L,
                0,
                99L)
            == 1L,
        "the pair orders by epoch first: (1, 1) is greater than (0, 99)");
    return "row comparison (epoch, counter) = (?, ?): same counter in another epoch never matches";
  }

  private static String unpartitionedRegistry(Connection c) throws SQLException {
    byte[] hashA = {1, 1};
    byte[] hashB = {2, 2};
    // the gap: the key of ingest holds trade_date, so one version under two dates is accepted twice
    update(c, INSERT_INGEST, 1, "r1", 1, DAY1, hashA, 1L);
    update(c, INSERT_INGEST, 1, "r1", 1, DAY2, hashB, 2L);
    long both =
        scalarLong(
            c,
            "SELECT count(*) FROM ingest WHERE source_id = ? AND record_id = ? AND version = ?",
            1,
            "r1",
            1);
    expect(both == 2L, "without the registry both versions are accepted, found " + both);
    // the registry (not partitioned, key without trade_date) catches it
    expect(admit(c, 1, "r2", 1, hashA).equals("NEW"), "the first hash of r2 is new");
    update(c, INSERT_INGEST, 1, "r2", 1, DAY1, hashA, 3L);
    expect(admit(c, 1, "r2", 1, hashA).equals("NOOP"), "the same hash is a no-op");
    String conflict = admit(c, 1, "r2", 1, hashB);
    expect(conflict.equals("CONFLICT"), "another hash for the same version conflicts: " + conflict);
    expect(
        scalarLong(c, "SELECT count(*) FROM ingest WHERE record_id = ?", "r2") == 1L,
        "the conflicting row was not inserted");
    return "finding for P3: ingest alone accepts one version under two trade dates ("
        + both
        + " rows); the unpartitioned ingest_registry returns NEW, NOOP and CONFLICT";
  }

  private static String regimeScopedSubject(Connection c) throws SQLException {
    update(c, INSERT_SUBJECT, 1L, "MIFIR", "E1", 1);
    update(c, INSERT_SUBJECT, 2L, "EMIR", "E1", 1);
    expectViolation(c, UNIQUE_VIOLATION, INSERT_SUBJECT, 3L, "MIFIR", "E1", 1);
    update(c, INSERT_SUBJECT, 4L, "MIFIR", "E1", 2);
    int getOrCreate =
        update(
            c,
            INSERT_SUBJECT + " ON CONFLICT (regime, event_identity, leg) DO NOTHING",
            5L,
            "EMIR",
            "E1",
            1);
    expect(getOrCreate == 0, "get-or-create on an existing key inserts nothing");
    long rows = scalarLong(c, "SELECT count(*) FROM report_subject WHERE event_identity = ?", "E1");
    expect(rows == 3L, "three subjects: two regimes on leg 1 and one more leg, found " + rows);
    return "unique (regime, event_identity, leg): other regime accepted, duplicate in a regime 23505";
  }

  private static String trnPerLei(Connection c) throws SQLException {
    update(c, INSERT_SUBJECT, 1L, "EMIR", "E1", 1);
    update(c, INSERT_SUBJECT, 2L, "EMIR", "E2", 1);
    update(c, INSERT_SUBJECT, 3L, "EMIR", "E3", 1);
    update(c, INSERT_TRN, 1L, "LEI_A", "TRN1");
    update(c, INSERT_TRN, 2L, "LEI_B", "TRN1");
    // subject 3 is free, so only the (executing_lei, trn) key can refuse this row
    expectViolation(c, UNIQUE_VIOLATION, INSERT_TRN, 3L, "LEI_A", "TRN1");
    expectViolation(c, UNIQUE_VIOLATION, INSERT_TRN, 1L, "LEI_A", "TRN2");
    expectViolation(c, "23503", INSERT_TRN, 9L, "LEI_A", "TRN3");
    return "unique (executing_lei, trn): the same trn under another lei accepted, under one lei"
        + " 23505; one trn per subject; a trn needs its subject (23503)";
  }

  // ---- decision commit as the adapter would send it -------------------------------------------

  /**
   * One decision of a batch: the head compare-and-set first (insert-if-absent for expected 0,
   * update-where-seq otherwise), then the decision row. Returns false, having changed nothing, when
   * the expected head is stale; the caller then rolls the whole batch back.
   */
  private static boolean commitDecision(
      Connection c,
      String regime,
      String event,
      long expectedSeq,
      long decisionId,
      long contextId,
      byte[] digest,
      LocalDate asAt)
      throws SQLException {
    int head;
    if (expectedSeq == 0) {
      head =
          update(
              c,
              "INSERT INTO decision_head (regime, event_identity, decision_seq, decision_id)"
                  + " VALUES (?, ?, 1, ?) ON CONFLICT (regime, event_identity) DO NOTHING",
              regime,
              event,
              decisionId);
    } else {
      head =
          update(
              c,
              "UPDATE decision_head SET decision_seq = decision_seq + 1, decision_id = ?"
                  + " WHERE regime = ? AND event_identity = ? AND decision_seq = ?",
              decisionId,
              regime,
              event,
              expectedSeq);
    }
    if (head != 1) {
      return false;
    }
    update(c, INSERT_DECISION, decisionId, regime, event, expectedSeq + 1, contextId, digest, asAt);
    return true;
  }

  /** The ingest registry: NEW, NOOP (same hash) or CONFLICT (another hash for the same version). */
  private static String admit(Connection c, int source, String record, int version, byte[] hash)
      throws SQLException {
    int inserted =
        update(
            c,
            "INSERT INTO ingest_registry (source_id, record_id, version, content_hash)"
                + " VALUES (?, ?, ?, ?) ON CONFLICT (source_id, record_id, version) DO NOTHING",
            source,
            record,
            version,
            hash);
    if (inserted == 1) {
      return "NEW";
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT content_hash FROM ingest_registry"
                + " WHERE source_id = ? AND record_id = ? AND version = ?")) {
      ps.setInt(1, source);
      ps.setString(2, record);
      ps.setInt(3, version);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new CheckFailed("the registry row of " + record + " vanished");
        }
        return Arrays.equals(rs.getBytes(1), hash) ? "NOOP" : "CONFLICT";
      }
    }
  }

  // ---- small JDBC helpers ---------------------------------------------------------------------

  private static void expect(boolean condition, String message) {
    if (!condition) {
      throw new CheckFailed(message);
    }
  }

  private static void expectViolation(Connection c, String sqlState, String sql, Object... params)
      throws SQLException {
    try {
      int affected = update(c, sql, params);
      throw new CheckFailed(
          "expected SQLState " + sqlState + " but " + affected + " rows changed: " + sql);
    } catch (SQLException e) {
      if (!sqlState.equals(e.getSQLState())) {
        throw new CheckFailed(
            "expected SQLState "
                + sqlState
                + " but got "
                + e.getSQLState()
                + ": "
                + e.getMessage());
      }
    }
  }

  private static boolean flag(Connection c, long attemptId) throws SQLException {
    return scalarLong(
            c,
            "SELECT CASE WHEN commit_issued THEN 1 ELSE 0 END FROM attempt WHERE attempt_id = ?",
            attemptId)
        == 1L;
  }

  private static String state(Connection c, long deliveryId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT state FROM delivery WHERE delivery_id = ?")) {
      ps.setLong(1, deliveryId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      return ps.executeUpdate();
    }
  }

  private static long scalarLong(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new CheckFailed("the query returned no row: " + sql);
        }
        return rs.getLong(1);
      }
    }
  }

  private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      int index = i + 1;
      switch (params[i]) {
        case Integer v -> ps.setInt(index, v);
        case Long v -> ps.setLong(index, v);
        case String v -> ps.setString(index, v);
        case Boolean v -> ps.setBoolean(index, v);
        case byte[] v -> ps.setBytes(index, v);
        case LocalDate v -> ps.setObject(index, v);
        default ->
            throw new IllegalArgumentException(
                "unsupported parameter type: " + params[i].getClass());
      }
    }
  }
}
