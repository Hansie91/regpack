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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The constraints of {@code V001}, (P3b1 T12) of {@code V002} and (P3c1 T11) of {@code V003}
 * against seeded duplicates and orphans (spec 14.1, review focus LF23; Convention 6): raw SQL on a
 * migrated schema, each statement expected to be rejected with a SQLState and, where the server
 * names one, the constraint. They are the seeded-duplicate evidence of the constraint clause of the
 * P3 exit criterion, together with {@code
 * PgDecisionsIT#crossPartitionDuplicateDecisionSeqIsRejectedByTheHead}. The adapter is not used:
 * what the schema itself refuses is the point. Skipped without {@code REGPACK_PG_URL}. The tests of
 * P3a first ran at P3a CP-3; the tests of {@code V002} (T12) first ran at P3b1 CP-1; those of
 * {@code V003} (P3c1 T11) first run at P3c1 CP-1. The tests of the dictionary, batch, checkpoint,
 * lease and restore-epoch keys and of the foreign keys of the decision, ingest, TRN, attempt and
 * lease tables were added in FP3 and first run at the checkpoint after it; docs/ledger/p3a-notes.md
 * (section 4) lists which constraints have a seeded test.
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
  // the keys and foreign keys of the dictionaries, the batch, the lease and the restore epoch
  // (FP3, SPEC-2): one seeded duplicate or orphan each. A key that the adapter also uses as an ON
  // CONFLICT arbiter has behavioural cover as well; these name the constraint itself.

  /**
   * The name of a constraint of the partitioned table {@code parent}, or of a copy on a partition.
   */
  private List<String> familyOf(String constraint) throws SQLException {
    String namespace = "(SELECT n.oid FROM pg_namespace n WHERE n.nspname = current_schema())";
    return names(
        "SELECT c.conname FROM pg_constraint c WHERE c.connamespace = "
            + namespace
            + " AND (c.conname = '"
            + constraint
            + "' OR c.conparentid IN (SELECT p.oid FROM pg_constraint p WHERE p.connamespace = "
            + namespace
            + " AND p.conname = '"
            + constraint
            + "'))"
            + " UNION SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
            + " WHERE i.inhparent = to_regclass('"
            + constraint
            + "')");
  }

  /**
   * A rejection by a constraint of the partitioned {@code decision} table: the server names the
   * constraint of the partition that holds the row, which is a copy of the one of the parent.
   */
  private void assertRejectedOnPartitioned(String sql, String sqlState, String constraint)
      throws SQLException {
    SQLException failure = failureOf(sql);
    assertThat(failure.getSQLState()).as(sql).isEqualTo(sqlState);
    assertThat(PgErrors.constraint(failure)).as(sql).isPresent();
    assertThat(familyOf(constraint)).as(constraint).isNotEmpty();
    assertThat(familyOf(constraint)).as(sql).contains(PgErrors.constraint(failure).orElseThrow());
  }

  @Test
  void duplicateRegimeNameIsRejected() throws SQLException {
    exec("INSERT INTO regime (regime) VALUES ('r1')");
    assertRejected("INSERT INTO regime (regime) VALUES ('r1')", "23505", "regime_name_key");
    assertThat(count("SELECT count(*) FROM regime")).isEqualTo(1);
  }

  @Test
  void duplicateSourceNameIsRejected() throws SQLException {
    exec("INSERT INTO source (source) VALUES ('srcA')");
    assertRejected("INSERT INTO source (source) VALUES ('srcA')", "23505", "source_name_key");
    assertThat(count("SELECT count(*) FROM source")).isEqualTo(1);
  }

  private static final String ENDPOINT_ROW =
      "INSERT INTO endpoint (environment, submission_route, submitting_entity_lei,"
          + " destination_authority) VALUES ('SIMULATOR', 'ARM', 'LEI-EP1', 'NCA-EP1')";

  @Test
  void duplicateEndpointIsRejected() throws SQLException {
    exec(ENDPOINT_ROW);
    assertRejected(ENDPOINT_ROW, "23505", "endpoint_key");
    assertThat(count("SELECT count(*) FROM endpoint")).isEqualTo(1);
  }

  @Test
  void duplicateContextShaIsRejected() throws SQLException {
    String context =
        "INSERT INTO decision_context (context_sha) VALUES (decode(repeat('0a', 32), 'hex'))";
    exec(context);
    assertRejected(context, "23505", "decision_context_sha_key");
    assertThat(count("SELECT count(*) FROM decision_context")).isEqualTo(1);
  }

  @Test
  void duplicateBatchIdIsRejected() throws SQLException {
    insertBatch();
    assertRejected(
        "INSERT INTO ingest_batch (batch_id, source_id, result, created_at)"
            + " VALUES ('b1', 2, decode('01', 'hex'), now())",
        "23505",
        "ingest_batch_id_key");
    assertThat(count("SELECT count(*) FROM ingest_batch")).isEqualTo(1);
  }

  @Test
  void duplicateCheckpointForASourceAndStreamIsRejected() throws SQLException {
    String checkpoint =
        "INSERT INTO ingest_checkpoint (source_id, stream, state, batch_no)"
            + " VALUES (1, 'trades', decode('00', 'hex'), 1)";
    exec(checkpoint);
    assertRejected(checkpoint, "23505", "ingest_checkpoint_pk");
    // another stream of the source, and the stream of another source, are accepted
    exec(
        checkpoint.replace("'trades'", "'quotes'"), checkpoint.replace("VALUES (1,", "VALUES (2,"));
    assertThat(count("SELECT count(*) FROM ingest_checkpoint")).isEqualTo(3);
  }

  @Test
  void aSecondLeaseRowForOneEndpointIsRejected() throws SQLException {
    long endpoint = returning(ENDPOINT_ROW + " RETURNING endpoint_id");
    String lease =
        "INSERT INTO lease (endpoint_id, restore_epoch, counter, holder, expires_at, released)"
            + " VALUES ("
            + endpoint
            + ", 0, 1, 'holder-1', now(), false)";
    exec(lease);
    // the same endpoint with other values: only the key (entity, endpoint) can clash
    assertRejected(
        lease.replace(", 0, 1, 'holder-1',", ", 3, 9, 'holder-2',"), "23505", "lease_pk");
    assertThat(count("SELECT count(*) FROM lease")).isEqualTo(1);
    assertThat(count("SELECT counter FROM lease")).isEqualTo(1);
  }

  @Test
  void aSecondRestoreEpochRowIsRejected() throws SQLException {
    assertThat(count("SELECT count(*) FROM restore_epoch")).isEqualTo(1);
    assertRejected(
        "INSERT INTO restore_epoch (singleton, epoch) VALUES (true, 5)",
        "23505",
        "restore_epoch_pk");
    // the other value of the flag is refused by the check, not by the key
    assertRejected(
        "INSERT INTO restore_epoch (singleton, epoch) VALUES (false, 5)",
        "23514",
        "restore_epoch_singleton_check");
    assertThat(count("SELECT count(*) FROM restore_epoch")).isEqualTo(1);
    assertThat(count("SELECT epoch FROM restore_epoch")).isZero();
  }

  @Test
  void duplicateDecisionIdAndDayIsRejected() throws SQLException {
    seedDictionaries();
    long decision = insertDecision("r1", EVENT, 1, "01");
    // the same (decision_id, as_at) with another sequence and digest: only decision_pk can clash
    String again =
        decisionRow("r1", EVENT, 2, "02")
            .replace("INSERT INTO decision (", "INSERT INTO decision (decision_id, ")
            .replace(" SELECT DATE", " SELECT " + decision + ", DATE");
    assertRejectedOnPartitioned(again, "23505", "decision_pk");
    assertThat(count("SELECT count(*) FROM decision")).isEqualTo(1);
  }

  @Test
  void duplicateSubjectIdIsRejected() throws SQLException {
    String subject =
        "INSERT INTO report_subject (subject_id, regime_id, event_identity, leg, last_version_seq)"
            + " OVERRIDING SYSTEM VALUE VALUES (7, 1, '"
            + EVENT
            + "', 1, 0)";
    exec(subject);
    // the same id for another leg: only the primary key can clash
    assertRejected(subject.replace(", 1, 0)", ", 2, 0)"), "23505", "report_subject_pk");
    assertThat(count("SELECT count(*) FROM report_subject")).isEqualTo(1);
  }

  @Test
  void duplicateVersionNumberForOneSubjectIsRejected() throws SQLException {
    seedDictionaries();
    long first = insertDecision("r1", EVENT, 1, "01");
    long second = insertDecision("r1", EVENT, 2, "02");
    long subject = insertSubject("r1", EVENT, 1);
    exec(versionRow(subject, 1, first));
    // version 1 again, for another decision: only the key (subject, version) can clash
    assertRejected(versionRow(subject, 1, second), "23505", "report_version_pk");
    assertThat(count("SELECT count(*) FROM report_version")).isEqualTo(1);
  }

  @Test
  void duplicateDeliveryIdIsRejected() throws SQLException {
    String delivery =
        "INSERT INTO delivery (delivery_id, endpoint_id, endpoint_epoch, business_key, version_seq,"
            + " state, gate_released, subject_id, created_at)"
            + " VALUES (77, 1, 1, 'bk-a', 1, 3, false, NULL, now())";
    exec(delivery);
    // another business key under the same id: only the primary key can clash
    assertRejected(delivery.replace("'bk-a'", "'bk-b'"), "23505", "delivery_pk");
    assertThat(count("SELECT count(*) FROM delivery")).isEqualTo(1);
  }

  @Test
  void decisionWithAnUnknownContextIsRejected() throws SQLException {
    seedDictionaries();
    String orphan =
        "INSERT INTO decision (as_at, regime_id, event_identity, decision_seq, context_id,"
            + " input_digest, outcome, circuit_open, body_encoding, body)"
            + " SELECT DATE '"
            + DAY
            + "', r.regime_id, '"
            + EVENT
            + "', 1, 999999, decode(repeat('01', 32), 'hex'), 1, false, 1, decode('01', 'hex')"
            + " FROM regime r WHERE r.regime = 'r1'";
    assertRejectedOnPartitioned(orphan, "23503", "decision_context_fk");
    assertThat(count("SELECT count(*) FROM decision")).isZero();
  }

  @Test
  void ingestWithoutABatchIsRejected() throws SQLException {
    assertRejected(ingestRow(999_999, "rec", 1, EVENT, "05"), "23503", "ingest_batch_fk");
    assertThat(count("SELECT count(*) FROM ingest")).isZero();
  }

  @Test
  void trnAssignmentWithoutASubjectIsRejected() throws SQLException {
    assertRejected(trnRow(999_999, "LEI-A", "TRN1"), "23503", "trn_assignment_subject_fk");
    assertThat(count("SELECT count(*) FROM trn_assignment")).isZero();
  }

  @Test
  void attemptWithoutADeliveryIsRejected() throws SQLException {
    assertRejected(attemptRow(1, 999_999), "23503", "attempt_delivery_fk");
    assertThat(count("SELECT count(*) FROM attempt")).isZero();
  }

  @Test
  void leaseWithoutAnEndpointIsRejected() throws SQLException {
    assertRejected(
        "INSERT INTO lease (endpoint_id, restore_epoch, counter, holder, expires_at, released)"
            + " VALUES (999999, 0, 1, 'holder-1', now(), false)",
        "23503",
        "lease_endpoint_fk");
    assertThat(count("SELECT count(*) FROM lease")).isZero();
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
  // the object manifest (V002, T12)

  /**
   * A manifest row: class SEALED_FILE 1, provenance WRITTEN 1, no attempt; the hash is {@code
   * hashBytes} bytes of {@code hashByte} ({@code decode(repeat(..), 'hex')}), the size {@code
   * size}.
   */
  private static String manifestRow(String key, String hashByte, int hashBytes, long size) {
    return "INSERT INTO object_manifest (object_key, object_class, sha256, size_bytes,"
        + " provenance, written_at) VALUES ('"
        + key
        + "', 1, decode(repeat('"
        + hashByte
        + "', "
        + hashBytes
        + "), 'hex'), "
        + size
        + ", 1, now())";
  }

  @Test
  void duplicateObjectKeyIsRejected() throws SQLException {
    exec(manifestRow("sealed/ab/key-one", "ab", 32, 10));
    // the same key with another hash, size and class: only the primary key can clash
    assertRejected(manifestRow("sealed/ab/key-one", "cd", 32, 99), "23505", "object_manifest_pk");
    // a key that differs in case is another key (byte order), and so is a longer one
    exec(
        manifestRow("sealed/ab/Key-one", "cd", 32, 99),
        manifestRow("sealed/ab/key-one2", "cd", 32, 99));
    assertThat(count("SELECT count(*) FROM object_manifest")).isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM object_manifest WHERE object_key = 'sealed/ab/key-one'"
                    + " AND sha256 = decode(repeat('ab', 32), 'hex') AND size_bytes = 10"))
        .isEqualTo(1);
  }

  @Test
  void updateAndDeleteOfObjectManifestRowsAreRejected() throws SQLException {
    // a row-level trigger fires only for a row, so the table is not empty
    exec(manifestRow("sealed/ab/key-one", "ab", 32, 10));
    List<String> statements =
        List.of(
            "UPDATE object_manifest SET size_bytes = 11",
            "UPDATE object_manifest SET sha256 = decode(repeat('ff', 32), 'hex')",
            "UPDATE object_manifest SET object_key = 'sealed/ab/key-two'",
            "DELETE FROM object_manifest");
    for (String sql : statements) {
      // the trigger raises 55000 without a constraint name
      assertRejected(sql, "55000", null);
    }
    assertThat(count("SELECT count(*) FROM object_manifest")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM object_manifest WHERE object_key = 'sealed/ab/key-one'"
                    + " AND sha256 = decode(repeat('ab', 32), 'hex') AND size_bytes = 10"))
        .isEqualTo(1);
  }

  @Test
  void aShortHashAndANegativeSizeAreRejected() throws SQLException {
    assertRejected(
        manifestRow("sealed/ab/short", "ab", 31, 10), "23514", "object_manifest_hash_check");
    assertRejected(
        manifestRow("sealed/ab/long", "ab", 33, 10), "23514", "object_manifest_hash_check");
    assertRejected(
        manifestRow("sealed/ab/negative", "ab", 32, -1), "23514", "object_manifest_size_check");
    assertThat(count("SELECT count(*) FROM object_manifest")).isZero();
    // the limits themselves are accepted: 32 bytes and a size of zero
    exec(manifestRow("sealed/ab/empty", "ab", 32, 0));
    assertThat(count("SELECT count(*) FROM object_manifest")).isEqualTo(1);
  }

  /**
   * Decision D4: the key is compared byte by byte. The ordering contract method ({@code
   * ManifestLedgerContract#rowsAreListedInKeyOrderWithPrefixAfterAndLimit}) would fail without the
   * clause, but only on a cluster whose default collation is not byte order (the pinned image is
   * en_US.utf8; a cluster started with {@code --locale=C} or {@code C.UTF-8} sorts the contract
   * keys in byte order by itself), and the range predicate of {@code PgManifest} is exact only in
   * byte order. So the column is asked in the catalogue: the collation of {@code object_key} is
   * {@code C}, and it is the only column of the table that has a collation at all.
   */
  @Test
  void theObjectKeyColumnHasTheCCollation() throws SQLException {
    String table = schema() + ".object_manifest";
    assertThat(
            strings(
                "SELECT c.collname::text FROM pg_attribute a"
                    + " JOIN pg_collation c ON c.oid = a.attcollation"
                    + " WHERE a.attrelid = to_regclass(?) AND a.attname = 'object_key'",
                table))
        .containsExactly("C");
    // a text column added later has the default collation and is a decision of its own: the
    // table has no collatable column but the key today
    assertThat(
            strings(
                "SELECT a.attname::text FROM pg_attribute a WHERE a.attrelid = to_regclass(?)"
                    + " AND a.attnum > 0 AND NOT a.attisdropped AND a.attcollation <> 0",
                table))
        .containsExactly("object_key");
    // the partial index of the attempt column holds only the rows that name an attempt
    assertThat(
            strings(
                "SELECT pg_get_indexdef(to_regclass(?))",
                schema() + ".object_manifest_attempt_idx"))
        .singleElement()
        .asString()
        .contains("WHERE")
        .contains("attempt_id IS NOT NULL");
  }

  /**
   * The text of the plan that the server makes for {@code sql} with the arguments of {@code binds}.
   */
  private String planOf(Connection connection, String sql, Binds binds) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("EXPLAIN (COSTS OFF) " + sql)) {
      binds.bind(statement);
      try (ResultSet rows = statement.executeQuery()) {
        StringBuilder plan = new StringBuilder();
        while (rows.next()) {
          plan.append(rows.getString(1)).append('\n');
        }
        return plan.toString();
      }
    }
  }

  @FunctionalInterface
  private interface Binds {
    void bind(PreparedStatement statement) throws SQLException;
  }

  /**
   * The three reads of {@code PgManifest} use the primary-key index, on a table big enough to have
   * statistics. The statements are the ones the adapter prepares (the package-visible constants)
   * and are run under {@code EXPLAIN} through the driver with the same kinds of arguments (text for
   * the keys, a {@code text[]} for the list, a bigint for the limit), so the types are the
   * driver's. {@code enable_seqscan} is off for the transaction: a statement that cannot use the
   * index (a cast or a function on the key, an argument of another type, a collation that is not
   * the index's) then shows as a sequential scan, whatever the size of the table, and the check
   * does not depend on the cost estimates of a small table.
   */
  @Test
  void theThreeManifestReadsUseThePrimaryKeyIndex() throws SQLException {
    exec(
        "INSERT INTO object_manifest (object_key, object_class, sha256, size_bytes, provenance,"
            + " written_at) SELECT 'bulk/' || lpad(g::text, 6, '0'), 1,"
            + " decode(repeat('ab', 32), 'hex'), 1, 1, now() FROM generate_series(1, 3000) g",
        "ANALYZE object_manifest");
    assertThat(count("SELECT count(*) FROM object_manifest")).isEqualTo(3000);

    String prefix = "bulk/0012";
    String index = "object_manifest_pk";
    try (Connection connection = raw()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL enable_seqscan = off");
      }
      Map<String, String> plans = new LinkedHashMap<>();
      plans.put(
          "ROWS_SQL",
          planOf(
              connection,
              PgManifest.ROWS_SQL,
              s -> {
                s.setString(1, prefix);
                s.setString(2, PgManifest.upperBound(prefix));
                s.setString(3, "bulk/001205");
                s.setLong(4, 5);
              }));
      plans.put(
          "ROW_SQL", planOf(connection, PgManifest.ROW_SQL, s -> s.setString(1, "bulk/001234")));
      plans.put(
          "STORED_SQL",
          planOf(
              connection,
              PgManifest.STORED_SQL,
              s ->
                  s.setArray(
                      1,
                      connection.createArrayOf(
                          "text", new String[] {"bulk/000001", "bulk/002999", "bulk/none"}))));
      connection.rollback();
      plans.forEach(
          (name, plan) ->
              assertThat(plan)
                  .as("plan of %s:%n%s", name, plan)
                  .contains(index)
                  .doesNotContain("Seq Scan"));
    }
  }

  // ------------------------------------------------------------------------------------------
  // the retention rows and the data-subject index (V003, P3c1 T11)

  private static final String Y2030 = "timestamptz '2030-01-01 00:00:00+00'";
  private static final String Y2031 = "timestamptz '2031-01-01 00:00:00+00'";
  private static final String Y2032 = "timestamptz '2032-01-01 00:00:00+00'";

  /**
   * A retention row: class {@code sealed}, not held; {@code anchor} is the stored code
   * (OBJECT_WRITTEN 1, TERMINATION 2) and {@code until} an SQL expression for the date (a
   * timestamptz literal, or NULL while the anchor is unknown).
   */
  private static String retentionRow(String key, String regime, int anchor, String until) {
    return "INSERT INTO retention (object_key, regime, anchor_event, retention_class,"
        + " retain_until, legal_hold, recorded_at) VALUES ('"
        + key
        + "', '"
        + regime
        + "', "
        + anchor
        + ", 'sealed', "
        + until
        + ", false, now())";
  }

  /**
   * A data-subject index row: the hash is {@code hashBytes} bytes of {@code hashByte}, the kind the
   * stored code (REPORT_VERSION 1, EVIDENCE_OBJECT 2).
   */
  private static String subjectRow(String hashByte, int hashBytes, int kind, String ref) {
    return "INSERT INTO subject_index (subject_hash, ref_kind, ref, recorded_at) VALUES"
        + " (decode(repeat('"
        + hashByte
        + "', "
        + hashBytes
        + "), 'hex'), "
        + kind
        + ", '"
        + ref
        + "', now())";
  }

  @Test
  void aSecondRetentionRowForOneArtifactRegimeAnchorIsRejected() throws SQLException {
    exec(retentionRow("sealed/ab/key-one", "mifir.eu.rts22", 1, Y2030));
    // the same triple with another date: only the primary key can clash
    assertRejected(
        retentionRow("sealed/ab/key-one", "mifir.eu.rts22", 1, Y2031), "23505", "retention_pk");
    assertRejected(
        retentionRow("sealed/ab/key-one", "mifir.eu.rts22", 1, "NULL"), "23505", "retention_pk");
    // another regime, another anchor, a regime that differs in a dot, a key that differs in case
    // and a longer key are other rows
    exec(
        retentionRow("sealed/ab/key-one", "emir.eu", 1, Y2030),
        retentionRow("sealed/ab/key-one", "mifir.eu.rts22", 2, "NULL"),
        retentionRow("sealed/ab/key-one", "mifir.eurts22", 1, Y2030),
        retentionRow("sealed/ab/Key-one", "mifir.eu.rts22", 1, Y2030),
        retentionRow("sealed/ab/key-one2", "mifir.eu.rts22", 1, Y2030));
    assertThat(count("SELECT count(*) FROM retention")).isEqualTo(6);
    assertThat(
            count(
                "SELECT count(*) FROM retention WHERE object_key = 'sealed/ab/key-one'"
                    + " AND regime = 'mifir.eu.rts22' AND anchor_event = 1 AND retain_until = "
                    + Y2030))
        .isEqualTo(1);
  }

  @Test
  void aSubjectIndexRowOfTheWrongHashLengthIsRejected() throws SQLException {
    for (int length : new int[] {0, 31, 33}) {
      assertRejected(
          subjectRow("ab", length, 1, "report/one"), "23514", "subject_index_hash_check");
    }
    assertThat(count("SELECT count(*) FROM subject_index")).isZero();
    // the length itself is accepted
    exec(subjectRow("ab", 32, 1, "report/one"));
    assertThat(count("SELECT count(*) FROM subject_index")).isEqualTo(1);
  }

  @Test
  void aDuplicateSubjectIndexRowIsRejected() throws SQLException {
    exec(subjectRow("ab", 32, 1, "report/one"));
    assertRejected(subjectRow("ab", 32, 1, "report/one"), "23505", "subject_index_pk");
    // the same hash and text under the other kind, the same reference under another hash and
    // another reference under the same hash are other rows
    exec(
        subjectRow("ab", 32, 2, "report/one"),
        subjectRow("cd", 32, 1, "report/one"),
        subjectRow("ab", 32, 1, "report/two"),
        subjectRow("ab", 32, 1, "Report/one"));
    assertThat(count("SELECT count(*) FROM subject_index")).isEqualTo(5);
  }

  /**
   * D2: the trigger {@code retention_extend_only} refuses an update that moves a date back. The
   * adapter never issues one (its upsert writes {@code GREATEST}), so this is the second defence,
   * shown with raw SQL; the trigger raises 55000 and names no constraint.
   */
  @Test
  void aRetainUntilCannotBeShortenedBySql() throws SQLException {
    exec(retentionRow("sealed/s1", "mifir.eu.rts22", 1, Y2031));
    String update = "UPDATE retention SET retain_until = ";
    String where = " WHERE object_key = 'sealed/s1'";
    assertRejected(update + Y2030 + where, "55000", null);
    // one microsecond earlier is earlier
    assertRejected(update + Y2031 + " - interval '1 microsecond'" + where, "55000", null);
    assertThat(count("SELECT count(*) FROM retention" + where + " AND retain_until = " + Y2031))
        .as("the refused updates changed nothing")
        .isEqualTo(1);
    // the same date and a later one are accepted, and the date is extend-only from there
    exec(update + Y2031 + where, update + Y2032 + where);
    assertThat(count("SELECT count(*) FROM retention" + where + " AND retain_until = " + Y2032))
        .isEqualTo(1);
    assertRejected(update + Y2031 + where, "55000", null);
  }

  @Test
  void aNonNullRetainUntilCannotBecomeNull() throws SQLException {
    exec(
        retentionRow("sealed/n1", "emir.eu", 1, Y2031),
        retentionRow("sealed/n2", "emir.eu", 2, "NULL"));
    assertRejected(
        "UPDATE retention SET retain_until = NULL WHERE object_key = 'sealed/n1'", "55000", null);
    assertThat(
            count(
                "SELECT count(*) FROM retention WHERE object_key = 'sealed/n1'"
                    + " AND retain_until = "
                    + Y2031))
        .isEqualTo(1);
    // an open anchor may stay open, and takes a value once
    exec(
        "UPDATE retention SET legal_hold = true WHERE object_key = 'sealed/n2'",
        "UPDATE retention SET retain_until = NULL WHERE object_key = 'sealed/n2'",
        "UPDATE retention SET retain_until = " + Y2030 + " WHERE object_key = 'sealed/n2'");
    assertThat(
            count(
                "SELECT count(*) FROM retention WHERE object_key = 'sealed/n2'"
                    + " AND retain_until = "
                    + Y2030))
        .isEqualTo(1);
    assertRejected(
        "UPDATE retention SET retain_until = NULL WHERE object_key = 'sealed/n2'", "55000", null);
  }

  /**
   * D2, open question 2 of the approved document: the key, the regime, the anchor and the class of
   * a row are fixed, alone or together with a column that may change; {@code legal_hold} and {@code
   * recorded_at} may change.
   */
  @Test
  void theKeyRegimeAnchorAndClassOfARowCannotBeChangedBySql() throws SQLException {
    exec(retentionRow("sealed/k1", "mifir.eu.rts22", 1, Y2030));
    String where = " WHERE object_key = 'sealed/k1'";
    List<String> forbidden =
        List.of(
            "UPDATE retention SET object_key = 'sealed/k2'",
            "UPDATE retention SET regime = 'emir.eu'",
            "UPDATE retention SET anchor_event = 2",
            "UPDATE retention SET retention_class = 'other'",
            "UPDATE retention SET object_key = 'sealed/k2', legal_hold = true",
            "UPDATE retention SET retention_class = 'other', retain_until = " + Y2032);
    for (String sql : forbidden) {
      assertRejected(sql + where, "55000", null);
    }
    assertThat(
            count(
                "SELECT count(*) FROM retention WHERE object_key = 'sealed/k1'"
                    + " AND regime = 'mifir.eu.rts22' AND anchor_event = 1"
                    + " AND retention_class = 'sealed' AND NOT legal_hold AND retain_until = "
                    + Y2030))
        .as("the refused updates changed nothing")
        .isEqualTo(1);
    // the hold and the time of the row are not guarded
    exec("UPDATE retention SET legal_hold = true, recorded_at = now()" + where);
    assertThat(count("SELECT count(*) FROM retention" + where + " AND legal_hold")).isEqualTo(1);
  }

  /**
   * Section 4 of the approved document: the tables have no foreign key, because the retention row
   * of a sealed object is written before the SENDING unit that writes its manifest row, and after a
   * restore the evidence tier is ahead of the database.
   */
  @Test
  void aRetentionRowMayNameAnArtifactThatHasNoManifestRow() throws SQLException {
    assertThat(count("SELECT count(*) FROM object_manifest")).isZero();
    exec(
        retentionRow("sealed/ab/no-manifest-row", "mifir.eu.rts22", 1, Y2030),
        subjectRow("ab", 32, 2, "sealed/ab/no-manifest-row"),
        subjectRow("ab", 32, 1, "report/with-no-version"));
    assertThat(count("SELECT count(*) FROM retention")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM subject_index")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM object_manifest")).isZero();
  }

  private List<String> collationOf(String table, String column) throws SQLException {
    try (Connection connection = raw();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT c.collname::text FROM pg_attribute a"
                    + " JOIN pg_collation c ON c.oid = a.attcollation"
                    + " WHERE a.attrelid = to_regclass(?) AND a.attname = ?")) {
      statement.setString(1, schema() + "." + table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        List<String> found = new ArrayList<>();
        while (rows.next()) {
          found.add(rows.getString(1));
        }
        return found;
      }
    }
  }

  /**
   * D6, task note T11-1 (D18, D21): the key columns are compared byte by byte. The ordering tests
   * of both contracts would fail without the clause, but only on a cluster whose default collation
   * is not byte order (the pinned image is en_US.utf8 [R]; a cluster started with {@code
   * --locale=C} or {@code C.UTF-8} sorts the contract keys in byte order by itself), and the range
   * predicate of {@code PgRetention} is exact only in byte order. So the columns are asked in the
   * catalogue: the collation of {@code object_key}, {@code regime} and {@code ref} is {@code C},
   * and the collatable columns are exactly the ones named here, so a text column added later is a
   * decision of its own. The order of the references by code point (D21) is the byte order of UTF-8
   * only in a UTF8 database, so the encoding of the database is asked too.
   */
  @Test
  void theKeyColumnsOfRetentionAndTheSubjectIndexHaveTheCCollation() throws SQLException {
    assertThat(collationOf("retention", "object_key")).containsExactly("C");
    assertThat(collationOf("retention", "regime")).containsExactly("C");
    assertThat(collationOf("subject_index", "ref")).containsExactly("C");
    String collatable =
        "SELECT a.attname::text FROM pg_attribute a WHERE a.attrelid = to_regclass(?)"
            + " AND a.attnum > 0 AND NOT a.attisdropped AND a.attcollation <> 0"
            + " ORDER BY a.attname::text";
    assertThat(strings(collatable, schema() + ".retention"))
        .as("the class is compared for equality only and has no clause")
        .containsExactly("object_key", "regime", "retention_class");
    assertThat(strings(collatable, schema() + ".subject_index")).containsExactly("ref");
    assertThat(collationOf("retention", "retention_class")).doesNotContain("C");
    assertThat(
            names(
                "SELECT pg_encoding_to_char(encoding)::text FROM pg_database"
                    + " WHERE datname = current_database()"))
        .as("the order of the references by code point is the byte order of UTF-8")
        .containsExactly("UTF8");
    // the expiry index holds the dated rows that are not held, and the expiry list asks the same
    assertThat(
            strings("SELECT pg_get_indexdef(to_regclass(?))", schema() + ".retention_expiry_idx"))
        .singleElement()
        .asString()
        .contains("WHERE")
        .contains("retain_until IS NOT NULL")
        .contains("NOT legal_hold");
  }

  /**
   * Task note T14-3 (D18, CP1-R): the byte-order tests of the manifest, retention and subject-index
   * contracts tell the clause {@code COLLATE "C"} from the default collation only on a cluster
   * whose default collation is NOT byte order. This test says which cluster the run is on: it is
   * skipped, with the values it read, when the default collation of the database orders bytes, so
   * that a green tally with no skipped test means the order tests ran where they can fail. Two
   * observations must both say "not byte order": the catalogue (a libc database whose {@code
   * datcollate} is {@code C}, {@code POSIX} or {@code C.UTF-8}, or a database of the builtin
   * provider, sorts by code point) and the behaviour (the default collation puts {@code a} before
   * {@code B}; byte order puts {@code B}, 0x42, before {@code a}, 0x61). The values are in the
   * message of the skip; a green run prints nothing, and a run with {@code skipped=0} is the proof.
   */
  @Test
  void theDefaultCollationOfTheDatabaseIsNotByteOrderSoTheOrderTestsCanFail() throws SQLException {
    String collate;
    String ctype;
    String provider;
    String locale;
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT datcollate::text, datctype::text, datlocprovider::text, datlocale::text"
                    + " FROM pg_database WHERE datname = current_database()")) {
      assertThat(rows.next()).as("the row of the current database").isTrue();
      collate = rows.getString(1);
      ctype = rows.getString(2);
      provider = rows.getString(3);
      locale = rows.getString(4);
    }
    boolean aBeforeUpperB;
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT 'a'::text COLLATE \"default\" < 'B'::text COLLATE \"default\"")) {
      assertThat(rows.next()).isTrue();
      aBeforeUpperB = rows.getBoolean(1);
    }
    boolean catalogueSaysByteOrder =
        "b".equals(provider)
            || ("c".equals(provider)
                && collate != null
                && List.of("C", "POSIX", "C.UTF-8", "C.utf8").contains(collate));
    String found =
        "datcollate="
            + collate
            + " datctype="
            + ctype
            + " datlocprovider="
            + provider
            + " datlocale="
            + locale
            + " 'a' < 'B' is "
            + aBeforeUpperB;
    Assumptions.assumeTrue(
        !catalogueSaysByteOrder && aBeforeUpperB,
        () ->
            "the default collation of this database orders bytes ("
                + found
                + "), so the byte-order tests of the contracts cannot tell the clause COLLATE \"C\""
                + " from the default here");
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
