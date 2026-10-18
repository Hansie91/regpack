package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The constraints of {@code V001} against seeded duplicates and orphans (spec 14.1, review focus
 * LF23; Convention 6): raw SQL on a migrated schema, each statement expected to be rejected with a
 * SQLState and, where the server names one, the constraint. They are the seeded-duplicate evidence
 * of the constraint clause of the P3 exit criterion, together with {@code
 * PgDecisionsIT#crossPartitionDuplicateDecisionSeqIsRejectedByTheHead}. The adapter is not used:
 * what the schema itself refuses is the point. Skipped without {@code REGPACK_PG_URL}; first run at
 * CP-3.
 *
 * <p>State and kind codes in the statements are the stored ones of {@link Codes}: delivery states
 * QUEUED 3, SENDING 4, AWAITING_ACK 5; outcome REPORT 1; leg MARKET 1, CLIENT 2; version origin
 * SOURCE 1; outbox kinds DECIDE_BATCH 1, SEQUENCE 2; outbox state PENDING 1.
 */
class PgConstraintsIT extends AbstractPgStoreTest {
  private static final LocalDate DAY = LocalDate.of(2026, 10, 9);
  private static final String EVENT = "LEI1|XPAR|E1";
  private static final long WAIT_SECONDS = 30;

  // ------------------------------------------------------------------------------------------
  // helpers

  private void exec(String... statements) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  /** Runs an {@code INSERT ... RETURNING} and returns the first column of its row. */
  private long returning(String sql) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).as(sql).isTrue();
      return rows.getLong(1);
    }
  }

  /** The failure of a statement that the schema must refuse; fails if it is accepted. */
  private SQLException failureOf(String sql) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      return e;
    }
    return fail("the statement was accepted: " + sql);
  }

  private void assertRejected(String sql, String sqlState, String constraint) throws SQLException {
    SQLException failure = failureOf(sql);
    assertThat(failure.getSQLState()).as(sql).isEqualTo(sqlState);
    if (constraint != null) {
      assertThat(PgErrors.constraint(failure)).as(sql).contains(constraint);
    }
  }

  private List<String> names(String sql) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      List<String> found = new ArrayList<>();
      while (rows.next()) {
        found.add(rows.getString(1));
      }
      return found;
    }
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private boolean someBackendWaitsFor(int pid) throws SQLException {
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND ? = ANY (pg_blocking_pids(pid))")) {
      statement.setInt(1, pid);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1) > 0;
      }
    }
  }

  private void awaitWaitingFor(int pid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (someBackendWaitsFor(pid)) {
        return;
      }
      Thread.sleep(25);
    }
    fail("no backend waited for a lock of backend " + pid + " within " + WAIT_SECONDS + " s");
  }

  // ------------------------------------------------------------------------------------------
  // seeds (raw SQL; the regimes get the identities 1 and 2 on a fresh schema)

  /** Two regimes, one context row and the partition of {@link #DAY}. */
  private void seedDictionaries() throws SQLException {
    exec(
        "INSERT INTO regime (regime) VALUES ('r1'), ('r2')",
        "INSERT INTO decision_context (context_sha) VALUES (decode(repeat('00', 32), 'hex'))");
    exec(Partitions.createStatements(schema(), DAY).toArray(new String[0]));
  }

  /** The text of one decision row of regime {@code regime} on {@link #DAY}. */
  private static String decisionRow(String regime, String event, long seq, String digestByte) {
    return "INSERT INTO decision (as_at, regime_id, event_identity, decision_seq, context_id,"
        + " input_digest, outcome, circuit_open, body_encoding, body)"
        + " SELECT DATE '"
        + DAY
        + "', r.regime_id, '"
        + event
        + "', "
        + seq
        + ", x.context_id, decode(repeat('"
        + digestByte
        + "', 32), 'hex'), 1, false, 1, decode('01', 'hex')"
        + " FROM regime r, decision_context x WHERE r.regime = '"
        + regime
        + "'";
  }

  private long insertDecision(String regime, String event, long seq, String digestByte)
      throws SQLException {
    return returning(decisionRow(regime, event, seq, digestByte) + " RETURNING decision_id");
  }

  private long insertSubject(String regime, String event, int leg) throws SQLException {
    return returning(
        "INSERT INTO report_subject (regime_id, event_identity, leg, last_version_seq)"
            + " SELECT regime_id, '"
            + event
            + "', "
            + leg
            + ", 0 FROM regime WHERE regime = '"
            + regime
            + "' RETURNING subject_id");
  }

  private static String subjectRow(String regime, String event, int leg) {
    return "INSERT INTO report_subject (regime_id, event_identity, leg, last_version_seq)"
        + " SELECT regime_id, '"
        + event
        + "', "
        + leg
        + ", 0 FROM regime WHERE regime = '"
        + regime
        + "'";
  }

  private static String trnRow(long subject, String lei, String trn) {
    return "INSERT INTO trn_assignment (subject_id, executing_lei, trn, key_id, assigned_at)"
        + " VALUES ("
        + subject
        + ", '"
        + lei
        + "', '"
        + trn
        + "', 'KEY-1', now())";
  }

  private static String versionRow(long subject, long seq, long decision) {
    return "INSERT INTO report_version (subject_id, version_seq, decision_id, decision_as_at,"
        + " action, logical_hash, body, body_encoding, origin, created_at) VALUES ("
        + subject
        + ", "
        + seq
        + ", "
        + decision
        + ", DATE '"
        + DAY
        + "', 'NEWT', decode(repeat('02', 32), 'hex'), decode('03', 'hex'), 0, 1, now())";
  }

  private static String deliveryRow(
      String businessKey, long version, int state, boolean released, String subject) {
    return "INSERT INTO delivery (endpoint_id, endpoint_epoch, business_key, version_seq, state,"
        + " gate_released, subject_id, created_at) VALUES (1, 1, '"
        + businessKey
        + "', "
        + version
        + ", "
        + state
        + ", "
        + released
        + ", "
        + subject
        + ", now())";
  }

  private long insertDelivery(String businessKey, long version, int state) throws SQLException {
    return returning(
        deliveryRow(businessKey, version, state, false, "NULL") + " RETURNING delivery_id");
  }

  private static String attemptRow(long attemptId, long delivery) {
    return "INSERT INTO attempt (attempt_id, delivery_id, lease_restore_epoch, lease_counter,"
        + " artifact_sha256, file_name, created_at) VALUES ("
        + attemptId
        + ", "
        + delivery
        + ", 0, 1, decode(repeat('04', 32), 'hex'), 'file.xml', now())";
  }

  private static String outboxRow(int kind, String subject) {
    return "INSERT INTO outbox (kind, subject_id, state) VALUES ("
        + kind
        + ", '"
        + subject
        + "', 1)";
  }

  private long insertBatch() throws SQLException {
    return returning(
        "INSERT INTO ingest_batch (batch_id, source_id, result, created_at)"
            + " VALUES ('b1', 1, decode('00', 'hex'), now()) RETURNING batch_no");
  }

  private static String ingestRow(
      long batch, String recordId, long version, String event, String hashByte) {
    return "INSERT INTO ingest (source_id, source_record_id, source_version, event_identity,"
        + " content_hash, batch_no, landed_on) VALUES (1, '"
        + recordId
        + "', "
        + version
        + ", '"
        + event
        + "', decode(repeat('"
        + hashByte
        + "', 32), 'hex'), "
        + batch
        + ", now())";
  }

  // ------------------------------------------------------------------------------------------
  // unique keys

  @Test
  void duplicateIngestKeyIsRejected() throws SQLException {
    long batch = insertBatch();
    exec(ingestRow(batch, "rec", 1, "LEI1|XPAR|E1", "05"));
    // the same (source, record, version) under another event identity: only the primary key clashes
    assertRejected(ingestRow(batch, "rec", 1, "LEI1|XPAR|E2", "06"), "23505", "ingest_pk");
    assertThat(count("SELECT count(*) FROM ingest")).isEqualTo(1);
  }

  @Test
  void duplicateEventSourceVersionUnderAnotherRecordIdIsRejected() throws SQLException {
    long batch = insertBatch();
    exec(ingestRow(batch, "rec-a", 1, "LEI1|XPAR|E1", "05"));
    assertRejected(
        ingestRow(batch, "rec-b", 1, "LEI1|XPAR|E1", "06"), "23505", "ingest_event_version_key");
    // another version of the same event, and the same version of another event, are accepted
    exec(
        ingestRow(batch, "rec-b", 2, "LEI1|XPAR|E1", "06"),
        ingestRow(batch, "rec-c", 1, "LEI1|XPAR|E2", "07"));
    assertThat(count("SELECT count(*) FROM ingest")).isEqualTo(3);
  }

  @Test
  void duplicateDecisionIdempotencyKeyInOnePartitionIsRejected() throws SQLException {
    seedDictionaries();
    exec(decisionRow("r1", EVENT, 1, "01"));
    // same (regime, event, context, input digest, day), another sequence: the partition's index
    // refuses it; that index is the copy of decision_idem_key that the attach adopted
    SQLException failure = failureOf(decisionRow("r1", EVENT, 2, "01"));
    assertThat(failure.getSQLState()).isEqualTo("23505");
    List<String> partitionIndexes =
        names(
            "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " WHERE i.inhparent = 'decision_idem_key'::regclass");
    assertThat(partitionIndexes).isNotEmpty();
    assertThat(PgErrors.constraint(failure)).isPresent();
    assertThat(partitionIndexes).contains(PgErrors.constraint(failure).orElseThrow());
    // another digest, and another regime with the same digest, are accepted
    exec(decisionRow("r1", EVENT, 2, "02"), decisionRow("r2", EVENT, 1, "01"));
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(3);
  }

  @Test
  void duplicateHeadIsRejected() throws SQLException {
    exec("INSERT INTO regime (regime) VALUES ('r1')");
    String head =
        "INSERT INTO decision_head (regime_id, event_identity, decision_seq, decision_id, as_at,"
            + " outcome) SELECT regime_id, '"
            + EVENT
            + "', 1, 1, DATE '"
            + DAY
            + "', 1 FROM regime WHERE regime = 'r1'";
    exec(head);
    assertRejected(head, "23505", "decision_head_pk");
  }

  @Test
  void duplicateSubjectKeyInOneRegimeIsRejectedButAnotherRegimeIsNot() throws SQLException {
    exec("INSERT INTO regime (regime) VALUES ('r1'), ('r2')");
    exec(subjectRow("r1", EVENT, 1));
    assertRejected(subjectRow("r1", EVENT, 1), "23505", "report_subject_key");
    // the same event and leg under another regime, and another leg under the same regime
    exec(subjectRow("r2", EVENT, 1), subjectRow("r1", EVENT, 2));
    assertThat(count("SELECT count(*) FROM report_subject")).isEqualTo(3);
  }

  @Test
  void duplicateTrnForOneLeiIsRejectedButAnotherLeiIsNot() throws SQLException {
    exec("INSERT INTO regime (regime) VALUES ('r1')");
    long first = insertSubject("r1", EVENT, 1);
    long second = insertSubject("r1", EVENT, 2);
    exec(trnRow(first, "LEI-A", "TRN1"));
    assertRejected(trnRow(second, "LEI-A", "TRN1"), "23505", "trn_assignment_lei_trn_key");
    exec(trnRow(second, "LEI-B", "TRN1"));
    assertThat(count("SELECT count(*) FROM trn_assignment")).isEqualTo(2);
  }

  @Test
  void secondTrnForOneSubjectIsRejected() throws SQLException {
    exec("INSERT INTO regime (regime) VALUES ('r1')");
    long subject = insertSubject("r1", EVENT, 1);
    exec(trnRow(subject, "LEI-A", "TRN1"));
    assertRejected(trnRow(subject, "LEI-A", "TRN2"), "23505", "trn_assignment_pk");
    assertThat(count("SELECT count(*) FROM trn_assignment")).isEqualTo(1);
  }

  @Test
  void duplicateVersionForADecisionAndSubjectIsRejected() throws SQLException {
    seedDictionaries();
    long decision = insertDecision("r1", EVENT, 1, "01");
    long subject = insertSubject("r1", EVENT, 1);
    exec(versionRow(subject, 1, decision));
    // another version number for the same decision and subject: only the decision key clashes
    assertRejected(
        versionRow(subject, 2, decision), "23505", "report_version_decision_subject_key");
    assertThat(count("SELECT count(*) FROM report_version")).isEqualTo(1);
  }

  @Test
  void duplicateDeliveryKeyIsRejected() throws SQLException {
    exec(deliveryRow("bk", 1, 3, false, "NULL"));
    assertRejected(deliveryRow("bk", 1, 3, false, "NULL"), "23505", "delivery_key");
    exec(deliveryRow("bk", 2, 3, false, "NULL"), deliveryRow("bk-other", 1, 3, false, "NULL"));
    assertThat(count("SELECT count(*) FROM delivery")).isEqualTo(3);
  }

  @Test
  void secondInFlightDeliveryForOneBusinessKeyIsRejected() throws SQLException {
    exec(deliveryRow("bk", 1, 4, false, "NULL"));
    // a second delivery of the business key in an in-flight state (AWAITING_ACK)
    assertRejected(deliveryRow("bk", 2, 5, false, "NULL"), "23505", "delivery_in_flight");
    // a released one is not in the index; nor is one that is not in flight; nor another key
    exec(
        deliveryRow("bk", 3, 5, true, "NULL"),
        deliveryRow("bk", 4, 3, false, "NULL"),
        deliveryRow("bk-other", 1, 4, false, "NULL"));
    assertThat(count("SELECT count(*) FROM delivery")).isEqualTo(4);
  }

  @Test
  void duplicateAttemptIdIsRejected() throws SQLException {
    long delivery = insertDelivery("bk", 1, 3);
    exec(attemptRow(1, delivery));
    assertRejected(attemptRow(1, delivery), "23505", "attempt_pk");
    assertThat(count("SELECT count(*) FROM attempt")).isEqualTo(1);
  }

  @Test
  void duplicateOutboxItemForOneKindAndSubjectIsRejected() throws SQLException {
    exec(outboxRow(2, "batch-1"));
    assertRejected(outboxRow(2, "batch-1"), "23505", "outbox_kind_subject_key");
    // the same subject under the other kind is another item
    exec(outboxRow(1, "batch-1"));
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // foreign keys

  @Test
  void versionWithoutSubjectIsRejected() throws SQLException {
    seedDictionaries();
    long decision = insertDecision("r1", EVENT, 1, "01");
    assertRejected(versionRow(999_999, 1, decision), "23503", "report_version_subject_fk");
    assertThat(count("SELECT count(*) FROM report_version")).isZero();
  }

  @Test
  void versionWithAnUnknownDecisionIsRejected() throws SQLException {
    seedDictionaries();
    long subject = insertSubject("r1", EVENT, 1);
    assertRejected(versionRow(subject, 1, 999_999), "23503", "report_version_decision_fk");
    // the decision exists but on another day than the version names: the pair is the key
    long decision = insertDecision("r1", EVENT, 1, "01");
    assertRejected(
        versionRow(subject, 1, decision).replace("DATE '" + DAY + "'", "DATE '2026-10-10'"),
        "23503",
        "report_version_decision_fk");
    assertThat(count("SELECT count(*) FROM report_version")).isZero();
  }

  @Test
  void deliveryWithoutVersionIsRejected() throws SQLException {
    exec("INSERT INTO regime (regime) VALUES ('r1')");
    long subject = insertSubject("r1", EVENT, 1);
    // a delivery that names a subject and a version that do not exist
    assertRejected(
        deliveryRow("bk", 7, 1, false, Long.toString(subject)), "23503", "delivery_version_fk");
    assertThat(count("SELECT count(*) FROM delivery")).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // insert-only tables (D-S16)

  @Test
  void updateAndDeleteOfInsertOnlyTablesAreRejected() throws SQLException {
    seedDictionaries();
    long decision = insertDecision("r1", EVENT, 1, "01");
    long subject = insertSubject("r1", EVENT, 1);
    exec(trnRow(subject, "LEI-A", "TRN1"), versionRow(subject, 1, decision));
    String partition = Partitions.partitionName(DAY);

    List<String> statements =
        List.of(
            "UPDATE decision SET outcome = 2",
            "DELETE FROM decision",
            // a partition addressed directly carries the cloned trigger too
            "UPDATE " + partition + " SET outcome = 2",
            "DELETE FROM " + partition,
            "UPDATE report_version SET action = 'CANC'",
            "DELETE FROM report_version",
            "UPDATE trn_assignment SET trn = 'TRN2'",
            "DELETE FROM trn_assignment");
    for (String sql : statements) {
      // the trigger raises 55000 without a constraint name
      assertRejected(sql, "55000", null);
    }
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM decision WHERE outcome = 1")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM report_version WHERE action = 'NEWT'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trn_assignment WHERE trn = 'TRN1'")).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // the version number under a race

  /**
   * Two connections run steps (5) and (6) of the version path for one (decision, subject): the
   * second waits for the first, then fails on the decision key, and after its rollback {@code
   * last_version_seq} equals the number of versions, with no gap.
   */
  @Test
  void aVersionRaceOnTheSameDecisionAndSubjectIsRejectedByTheKeyAndLeavesNoGap() throws Exception {
    seedDictionaries();
    long decision = insertDecision("r1", EVENT, 1, "01");
    long subject = insertSubject("r1", EVENT, 1);
    String bump =
        "UPDATE report_subject SET last_version_seq = last_version_seq + 1 WHERE subject_id = "
            + subject;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection first = raw();
        Connection second = raw()) {
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      int firstPid = backendPid(first);
      try (Statement statement = first.createStatement()) {
        statement.execute(versionRow(subject, 1, decision));
        statement.execute(bump);
      }
      // the second writer holds a version number the first one has not given out, so only the
      // decision key can refuse it, and only once the first writer has decided
      Future<SQLException> race =
          pool.submit(
              () -> {
                try (Statement statement = second.createStatement()) {
                  statement.execute(versionRow(subject, 2, decision));
                  statement.execute(bump);
                  second.commit();
                  return null;
                } catch (SQLException e) {
                  second.rollback();
                  return e;
                }
              });
      awaitWaitingFor(firstPid);
      assertThat(race.isDone()).as("the second writer waits for the first").isFalse();
      first.commit();
      SQLException failure = race.get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertThat(failure != null).as("the second writer must be refused").isTrue();
      assertThat(failure.getSQLState()).isEqualTo("23505");
      assertThat(PgErrors.constraint(failure)).contains("report_version_decision_subject_key");
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("SELECT count(*) FROM report_version")).isEqualTo(1);
    assertThat(count("SELECT last_version_seq FROM report_subject WHERE subject_id = " + subject))
        .isEqualTo(1);
  }
}
