package dev.regpack.postgres;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.KeyText;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.ports.ledger.ReportLedger.Subject;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.ports.ledger.ReportLedger.SubjectKey;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.ports.ledger.ReportLedger.VersionResult;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import dev.regpack.ports.ledger.TrnConflictException;
import dev.regpack.postgres.PgDecisions.Prepared;
import dev.regpack.postgres.PgDecisions.Written;
import dev.regpack.postgres.PgErrors.Violation;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The report side of the ledger on PostgreSQL (spec 10 and 11.1, D-S8, D-L2, D-L3, D-L4; T18):
 * subjects, the TRN assigned once, versions with their delivery in {@code PREPARED}, and the unit
 * of work that commits them together with their decisions. The port documents the nine rules; the
 * contract suite is the oracle.
 *
 * <p>{@link #commitReportable} is one unit (Convention 11). The decision steps are those of {@link
 * PgDecisions#commitIn}; after each wave of decisions the versions requested for the decisions of
 * that wave are written in the same transaction, so a later decision of the same chain finds them
 * (the withdrawal guard of rule 8 needs that). Per version, with the rows locked in the canonical
 * order (chains by regime and event identity, legs by code):
 *
 * <ol>
 *   <li>a decision that is {@code NOOP_REDELIVERY} first looks for its version by a read: found is
 *       {@code EXISTING} and nothing else is executed for that leg (a unique violation would abort
 *       the PostgreSQL transaction, 25P02, and so cannot be used to find out). A version that does
 *       not exist yet is created only while the decision is still the head: the head row is read
 *       {@code FOR SHARE} (rule 3), so that a concurrent decision that supersedes it waits at its
 *       compare-and-set until this unit has committed, and then its withdrawal guard sees the
 *       version. Share locks do not exclude each other, so two redeliveries of the same decision
 *       can both miss the version here; the read is repeated under the subject lock (next step);
 *   <li>the subject row is inserted {@code ON CONFLICT ON CONSTRAINT report_subject_key DO NOTHING}
 *       and locked {@code FOR UPDATE}: the version number is assigned under that lock (rule 6). For
 *       a redelivery the existing-version read is repeated now, under the lock, and a version that
 *       the other unit has committed meanwhile is {@code EXISTING}, before the TRN and suppression
 *       steps;
 *   <li>the stored TRN is read first. A subject with a TRN takes it (the cases of {@link
 *       IllegalArgumentException} are decided by this read, never by catching {@code
 *       trn_assignment_pk}) and is checked for suppression (rule 7: the latest version whose latest
 *       delivery is not dead); a subject without one needs a proposal, and the insert into {@code
 *       trn_assignment} is judged by {@code trn_assignment_lei_trn_key}: a violation is a {@link
 *       TrnConflictException} and the unit is rolled back (rule 5);
 *   <li>the version row (with the day of its decision, the key of the foreign key to the
 *       partitioned table), the increment of {@code last_version_seq}, the delivery with its
 *       subject, in this order.
 * </ol>
 *
 * <p>A unique violation of {@code report_version_decision_subject_key} on the insert would mean
 * that two units wrote the same version of one decision. It is not expected: a decision that this
 * unit inserted is not visible to any other unit, and for a redelivered decision the existing
 * version is read under the subject lock, which every writer of a version of that subject holds
 * (two redeliveries are serialised there, and the second answers {@code EXISTING}). Should it
 * happen it is surfaced as the {@link LedgerException} of {@link PgErrors#wrap}. Nothing continues
 * after an expected violation, so no savepoint is needed. The {@code SEQUENCE} outbox item is
 * inserted last when at least one version was created (rule 9).
 *
 * <p>The dictionary rows of the unit, including the endpoints of the deliveries, are resolved
 * before the unit starts ({@link PgDictionaries}); the unit reads their ids.
 *
 * <p>Nothing is checked in Java that the schema can decide (D-L3); the Java checks are the argument
 * rules of rule 2, which need no row, and the stored-TRN cases, which the port defines as {@link
 * IllegalArgumentException}.
 */
final class PgReports {
  /** {@code report_version.body_encoding}: 0 is the plain body until the key hierarchy of P3c. */
  static final short BODY_ENCODING = 0;

  /** Rule 7: a version whose latest delivery is in one of these states is not compared against. */
  static final Set<DeliveryState> DEAD =
      Collections.unmodifiableSet(
          EnumSet.of(
              DeliveryState.REJECTED,
              DeliveryState.IN_REPAIR,
              DeliveryState.WITHDRAWN,
              DeliveryState.WRITTEN_OFF));

  static final String TRN_CONFLICT_CONSTRAINT = "trn_assignment_lei_trn_key";
  static final String VERSION_KEY_CONSTRAINT = "report_version_decision_subject_key";

  /** The stored codes of {@link #DEAD}, ascending, as the text of an {@code IN} list. */
  private static final String DEAD_CODES = deadCodes();

  private static final String LATEST_DELIVERY_ID = latestDelivery("delivery_id");
  private static final String LATEST_DELIVERY_STATE = latestDelivery("state");

  /** Parameters: regime id, event identity, leg code. Step (1) of the version path. */
  static final String SUBJECT_INSERT_SQL =
      "INSERT INTO report_subject (regime_id, event_identity, leg, last_version_seq)"
          + " VALUES (?, ?, ?, 0) ON CONFLICT ON CONSTRAINT report_subject_key DO NOTHING";

  /** Step (2): the subject row, locked for the version number. */
  static final String SUBJECT_LOCK_SQL =
      "SELECT subject_id, last_version_seq FROM report_subject"
          + " WHERE regime_id = ? AND event_identity = ? AND leg = ? FOR UPDATE";

  /**
   * The version that a decision already has for a subject, with the TRN and the delivery of the
   * highest endpoint epoch. Parameters: decision id, regime id, event identity, leg code. No row:
   * the subject is unknown or the decision has no version for it.
   */
  static final String EXISTING_SQL =
      "SELECT s.subject_id, v.version_seq, t.trn, ("
          + LATEST_DELIVERY_ID
          + ") FROM report_subject s"
          + " JOIN report_version v ON v.subject_id = s.subject_id AND v.decision_id = ?"
          + " JOIN trn_assignment t ON t.subject_id = s.subject_id"
          + " WHERE s.regime_id = ? AND s.event_identity = ? AND s.leg = ?";

  /** Rule 3: the head row of the chain, shared. Parameters: regime id, event identity. */
  static final String HEAD_SHARE_SQL =
      "SELECT decision_seq FROM decision_head WHERE regime_id = ? AND event_identity = ?"
          + " FOR SHARE";

  static final String TRN_READ_SQL =
      "SELECT executing_lei, trn FROM trn_assignment" + " WHERE subject_id = ?";

  static final String TRN_INSERT_SQL =
      "INSERT INTO trn_assignment (subject_id, executing_lei, trn, key_id, assigned_at)"
          + " VALUES (?, ?, ?, ?, ?)";

  /**
   * Rule 7: the latest version of the subject whose latest delivery is not dead. The dead codes are
   * literals built from {@link Codes}. Parameter: subject id.
   */
  static final String LATEST_LIVE_SQL =
      "SELECT v.version_seq, v.action, encode(v.logical_hash, 'hex') FROM report_version v"
          + " WHERE v.subject_id = ? AND COALESCE(("
          + LATEST_DELIVERY_STATE
          + "), 0) NOT IN ("
          + DEAD_CODES
          + ") ORDER BY v.version_seq DESC LIMIT 1";

  static final String VERSION_INSERT_SQL =
      "INSERT INTO report_version (subject_id, version_seq, decision_id, decision_as_at, action,"
          + " logical_hash, body, body_encoding, origin, created_at)"
          + " VALUES (?, ?, ?, ?, ?, decode(?, 'hex'), ?, ?, ?, ?)";

  static final String SUBJECT_BUMP_SQL =
      "UPDATE report_subject SET last_version_seq = last_version_seq + 1 WHERE subject_id = ?";

  /** Rule 9. Parameters: kind code, batch id, state code. */
  static final String SEQUENCE_INSERT_SQL =
      "INSERT INTO outbox (kind, subject_id, state) VALUES (?, ?, ?)"
          + " ON CONFLICT ON CONSTRAINT outbox_kind_subject_key DO NOTHING";

  static final String SUBJECT_BY_KEY_SQL =
      "SELECT s.subject_id, s.last_version_seq, t.executing_lei, t.trn, t.key_id"
          + " FROM report_subject s JOIN regime r ON r.regime_id = s.regime_id"
          + " LEFT JOIN trn_assignment t ON t.subject_id = s.subject_id"
          + " WHERE r.regime = ? AND s.event_identity = ? AND s.leg = ?";

  static final String SUBJECT_BY_TRN_SQL =
      "SELECT s.subject_id, s.last_version_seq, r.regime, s.event_identity, s.leg, t.key_id"
          + " FROM trn_assignment t JOIN report_subject s ON s.subject_id = t.subject_id"
          + " JOIN regime r ON r.regime_id = s.regime_id"
          + " WHERE t.executing_lei = ? AND t.trn = ?";

  static final String VERSIONS_SQL =
      "SELECT v.version_seq, v.decision_id, v.action, encode(v.logical_hash, 'hex'), v.body,"
          + " v.body_encoding, v.origin, ("
          + LATEST_DELIVERY_ID
          + ") FROM report_version v WHERE v.subject_id = ? ORDER BY v.version_seq";

  private final DataSource dataSource;
  private final Clock clock;
  private final Config config;
  private final PgDecisions decisions;

  PgReports(DataSource dataSource, Clock clock, Config config, PgDecisions decisions) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.config = Objects.requireNonNull(config, "config");
    this.decisions = Objects.requireNonNull(decisions, "decisions");
  }

  private static String deadCodes() {
    StringBuilder codes = new StringBuilder();
    for (DeliveryState state : DEAD) {
      if (codes.length() > 0) {
        codes.append(", ");
      }
      codes.append(Codes.of(state));
    }
    return codes.toString();
  }

  /**
   * A subselect, for a query over {@code report_version v}, of one column of the delivery of the
   * highest endpoint epoch for the version (ties: the later id). The column is a constant of this
   * class, never input.
   */
  private static String latestDelivery(String column) {
    return "SELECT d."
        + column
        + " FROM delivery d WHERE d.subject_id = v.subject_id AND d.version_seq = v.version_seq"
        + " ORDER BY d.endpoint_epoch DESC, d.delivery_id DESC LIMIT 1";
  }

  // ---------------------------------------------------------------------------------------------
  // the unit

  List<ReportableCommitted> commitReportable(ReportableCommit commit) {
    Plan plan = plan(commit);
    Instant now = ledgerTime();
    // partitions and dictionary rows (regime, context, endpoint) first, each in a short
    // transaction: the unit below only reads their ids
    decisions.beforeUnit(plan.prepared());
    Map<EndpointKey, Integer> endpointIds = decisions.dictionaries().endpoints(plan.endpoints());
    return decisions.runUnit(
        plan.prepared().days(), connection -> run(connection, plan, now, endpointIds));
  }

  /**
   * {@link #commitReportable} in a transaction that the caller owns and started with {@link
   * Unit#begin}: nothing is committed, the partitions of the decisions must exist, and a lost race
   * for a head is signalled by an internal exception instead of the typed conflict, which only
   * {@link #commitReportable} produces after its rollback. The dictionary rows are resolved first,
   * on connections of their own, as {@link #commitReportable} does.
   */
  List<ReportableCommitted> commitReportableIn(Connection connection, ReportableCommit commit)
      throws SQLException {
    Plan plan = plan(commit);
    decisions.resolveDictionaries(plan.prepared());
    Map<EndpointKey, Integer> endpointIds = decisions.dictionaries().endpoints(plan.endpoints());
    return run(connection, plan, ledgerTime(), endpointIds);
  }

  /** The request, its decisions prepared for the unit, and the endpoints of its versions. */
  private record Plan(ReportableCommit commit, Prepared prepared, Set<EndpointKey> endpoints) {}

  /**
   * Validates the whole request before any connection is opened (rule 2 and the decision rules).
   */
  private static Plan plan(ReportableCommit commit) {
    Objects.requireNonNull(commit, "commit");
    List<NewDecision> newDecisions = new ArrayList<>(commit.decisions().size());
    for (ReportableDecision reportable : commit.decisions()) {
      newDecisions.add(reportable.decision());
    }
    Prepared prepared =
        PgDecisions.prepare(new BatchCommit(newDecisions, commit.completesOutbox()));
    Set<EndpointKey> endpoints = new LinkedHashSet<>();
    for (ReportableDecision reportable : commit.decisions()) {
      validateVersions(reportable);
      for (NewVersion version : reportable.versions()) {
        endpoints.add(version.endpoint());
      }
    }
    return new Plan(commit, prepared, Collections.unmodifiableSet(endpoints));
  }

  /** Rule 2: a version needs a REPORT decision, a leg of it, once, and its own chain. */
  private static void validateVersions(ReportableDecision reportable) {
    DecisionBody body = reportable.decision().body();
    Set<Leg> seen = EnumSet.noneOf(Leg.class);
    for (NewVersion version : reportable.versions()) {
      SubjectKey subject = version.subject();
      if (body.outcome() != Outcome.REPORT) {
        throw new IllegalArgumentException("a version needs a REPORT decision: " + body.outcome());
      }
      if (!body.legs().contains(subject.leg())) {
        throw new IllegalArgumentException(
            "the leg " + subject.leg() + " is not a leg of the decision");
      }
      if (!seen.add(subject.leg())) {
        throw new IllegalArgumentException("two versions for the leg " + subject.leg());
      }
      if (!subject.regime().equals(body.regime())
          || !subject.eventIdentity().equals(body.eventIdentity())) {
        throw new IllegalArgumentException("the subject names another regime or event");
      }
    }
  }

  /** The instant of the unit, from the injected clock; finer than a microsecond is refused. */
  private Instant ledgerTime() {
    Instant now = clock.now();
    PgTime.timestamp(now);
    return now;
  }

  private List<ReportableCommitted> run(
      Connection connection, Plan plan, Instant now, Map<EndpointKey, Integer> endpointIds)
      throws SQLException {
    Run run = new Run(plan, now, endpointIds);
    List<Committed> committed = decisions.commitIn(connection, plan.prepared(), run::afterWave);
    List<ReportableCommitted> results = new ArrayList<>(committed.size());
    for (int i = 0; i < committed.size(); i++) {
      results.add(new ReportableCommitted(committed.get(i), run.versionsOf(i)));
    }
    if (run.anyCreated && plan.commit().sequenceBatch().isPresent()) {
      insertSequenceItem(connection, plan.commit().sequenceBatch().get().value());
    }
    if (!run.anyCreated && PgDecisions.wroteNothing(plan.prepared(), committed)) {
      // a redelivery that only found its decisions and versions: see PgDecisions#wroteNothing.
      // Last statement of the unit; in a unit that the caller owns (commitReportableIn) the
      // statement is still the last of this call, and the lock is held to the caller's commit
      Unit.durabilityBarrier(connection);
    }
    return List.copyOf(results);
  }

  /** The state of one running unit: the versions written so far, by input position. */
  private final class Run {
    private final Plan plan;
    private final Instant now;
    private final Map<EndpointKey, Integer> endpointIds;
    private final Map<Integer, List<VersionResult>> byDecision = new HashMap<>();
    boolean anyCreated;

    Run(Plan plan, Instant now, Map<EndpointKey, Integer> endpointIds) {
      this.plan = plan;
      this.now = now;
      this.endpointIds = endpointIds;
    }

    List<VersionResult> versionsOf(int index) {
      return byDecision.getOrDefault(index, List.of());
    }

    /** The versions of the decisions of one wave, wave order, legs by code inside a decision. */
    void afterWave(Connection connection, List<Written> wave) throws SQLException {
      for (Written written : wave) {
        ReportableDecision requested = plan.commit().decisions().get(written.index());
        List<NewVersion> versions = requested.versions();
        VersionResult[] results = new VersionResult[versions.size()];
        Integer[] order = new Integer[versions.size()];
        for (int i = 0; i < order.length; i++) {
          order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingInt(i -> Codes.of(versions.get(i).subject().leg())));
        for (int position : order) {
          results[position] =
              applyVersion(connection, now, written, versions.get(position), endpointIds);
          anyCreated |= results[position].status() == VersionStatus.CREATED;
        }
        byDecision.put(written.index(), List.of(results));
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // one version

  private static VersionResult applyVersion(
      Connection connection,
      Instant now,
      Written written,
      NewVersion version,
      Map<EndpointKey, Integer> endpointIds)
      throws SQLException {
    DecisionRecord decision = written.committed().record();
    DecisionBody body = decision.body();
    EventIdentity event = body.eventIdentity();
    short leg = Codes.of(version.subject().leg());
    boolean redelivery = written.committed().status() == CommitStatus.NOOP_REDELIVERY;
    if (redelivery) {
      // rule 3: found by a read, never by a unique violation. This read is the cheap path; it is
      // not stable, because a concurrent unit may be creating the version right now (see below).
      Optional<VersionResult> existing =
          existingVersion(connection, decision.decisionId(), written.regimeId(), event, leg);
      if (existing.isPresent()) {
        return existing.get();
      }
      requireStillHead(connection, written, decision);
    }
    LockedSubject subject = lockSubject(connection, written.regimeId(), event, leg);
    if (redelivery) {
      // Two units that redeliver the same decision both miss the version above (neither has
      // committed) and both pass the shared lock on the head; the subject lock serialises them
      // here. The one that waited reads again under the lock, a new statement at READ COMMITTED
      // that sees what the other unit committed: that version exists, so the answer is EXISTING
      // (as in the in-memory adapter), whatever content the redelivery carries, and not a
      // suppression or a second version of the same decision.
      Optional<VersionResult> existing =
          existingVersion(connection, decision.decisionId(), written.regimeId(), event, leg);
      if (existing.isPresent()) {
        return existing.get();
      }
    }
    Optional<StoredTrn> stored = readTrn(connection, subject.id());
    String trn;
    if (stored.isPresent()) {
      if (!stored.get().executingLei().equals(version.executingLei())) {
        throw new IllegalArgumentException("the executing LEI of a subject cannot change");
      }
      if (version.trn().isPresent() && !version.trn().get().trn().equals(stored.get().trn())) {
        throw new IllegalArgumentException("a stored TRN cannot be replaced");
      }
      trn = stored.get().trn();
      Optional<LiveVersion> latest = latestLive(connection, subject.id());
      if (latest.isPresent()
          && latest.get().action().equals(version.action())
          && latest.get().logicalHash().equals(version.logicalHash().hex())) {
        return new VersionResult(
            new SubjectId(subject.id()),
            trn,
            latest.get().versionSeq(),
            VersionStatus.SUPPRESSED_UNCHANGED,
            Optional.empty());
      }
    } else {
      TrnProposal proposal =
          version
              .trn()
              .orElseThrow(() -> new IllegalArgumentException("a new subject needs a TRN"));
      insertTrn(connection, now, subject.id(), version.executingLei(), proposal);
      trn = proposal.trn();
    }
    long versionSeq = subject.lastVersionSeq() + 1;
    insertVersion(
        connection, now, subject.id(), versionSeq, written, decision.decisionId(), version);
    bumpSubject(connection, subject.id());
    Integer endpointId = endpointIds.get(version.endpoint());
    if (endpointId == null) {
      throw new LedgerException("the endpoint of a version was not resolved before the unit");
    }
    DeliveryId delivery =
        PgDeliveries.createDelivery(
            connection,
            now,
            new NewDelivery(
                version.endpoint(),
                version.endpointEpoch(),
                version.executingLei() + "|" + trn,
                versionSeq),
            OptionalLong.of(subject.id()),
            endpointId);
    return new VersionResult(
        new SubjectId(subject.id()), trn, versionSeq, VersionStatus.CREATED, Optional.of(delivery));
  }

  private static Optional<VersionResult> existingVersion(
      Connection connection, DecisionId decision, short regimeId, EventIdentity event, short leg)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(EXISTING_SQL)) {
      statement.setLong(1, decision.value());
      statement.setShort(2, regimeId);
      statement.setString(3, event.value());
      statement.setShort(4, leg);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        long subject = rows.getLong(1);
        long versionSeq = rows.getLong(2);
        String trn = rows.getString(3);
        long delivery = rows.getLong(4);
        Optional<DeliveryId> deliveryId =
            rows.wasNull() ? Optional.empty() : Optional.of(new DeliveryId(delivery));
        return Optional.of(
            new VersionResult(
                new SubjectId(subject), trn, versionSeq, VersionStatus.EXISTING, deliveryId));
      }
    }
  }

  /**
   * Rule 3: the redelivered decision must still be the head of its chain. The head row is read
   * shared, so that a decision that supersedes it cannot advance the head until this unit has
   * committed (and its withdrawal guard then sees the version).
   */
  private static void requireStillHead(
      Connection connection, Written written, DecisionRecord decision) throws SQLException {
    DecisionBody body = decision.body();
    long head = 0;
    try (PreparedStatement statement = connection.prepareStatement(HEAD_SHARE_SQL)) {
      statement.setShort(1, written.regimeId());
      statement.setString(2, body.eventIdentity().value());
      try (ResultSet rows = statement.executeQuery()) {
        if (rows.next()) {
          head = rows.getLong(1);
        }
      }
    } catch (SQLException e) {
      throw PgDecisions.lostRaceOnHead(e, body.regime().id(), body.eventIdentity());
    }
    if (head != decision.decisionSeq()) {
      throw new PgDecisions.HeadMismatch(body.regime().id(), body.eventIdentity(), null);
    }
  }

  private record LockedSubject(long id, long lastVersionSeq) {}

  /** Steps (1) and (2): get-or-create the subject row and lock it. */
  private static LockedSubject lockSubject(
      Connection connection, short regimeId, EventIdentity event, short leg) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(SUBJECT_INSERT_SQL)) {
      insert.setShort(1, regimeId);
      insert.setString(2, event.value());
      insert.setShort(3, leg);
      insert.executeUpdate();
    }
    try (PreparedStatement lock = connection.prepareStatement(SUBJECT_LOCK_SQL)) {
      lock.setShort(1, regimeId);
      lock.setString(2, event.value());
      lock.setShort(3, leg);
      try (ResultSet rows = lock.executeQuery()) {
        if (!rows.next()) {
          throw new LedgerException("the report subject row is missing after its insert");
        }
        return new LockedSubject(rows.getLong(1), rows.getLong(2));
      }
    }
  }

  private record StoredTrn(String executingLei, String trn) {}

  private static Optional<StoredTrn> readTrn(Connection connection, long subjectId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRN_READ_SQL)) {
      statement.setLong(1, subjectId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? Optional.of(new StoredTrn(rows.getString(1), rows.getString(2)))
            : Optional.empty();
      }
    }
  }

  private record LiveVersion(long versionSeq, String action, String logicalHash) {}

  private static Optional<LiveVersion> latestLive(Connection connection, long subjectId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(LATEST_LIVE_SQL)) {
      statement.setLong(1, subjectId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? Optional.of(new LiveVersion(rows.getLong(1), rows.getString(2), rows.getString(3)))
            : Optional.empty();
      }
    }
  }

  /** Assigns the TRN once; the pair (executing LEI, TRN) is judged by its unique constraint. */
  private static void insertTrn(
      Connection connection, Instant now, long subjectId, String executingLei, TrnProposal proposal)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRN_INSERT_SQL)) {
      statement.setLong(1, subjectId);
      statement.setString(2, executingLei);
      statement.setString(3, proposal.trn());
      statement.setString(4, proposal.keyId());
      statement.setObject(5, PgTime.timestamp(now));
      statement.executeUpdate();
    } catch (SQLException e) {
      if (PgErrors.classify(e) == Violation.UNIQUE
          && PgErrors.constraint(e).filter(TRN_CONFLICT_CONSTRAINT::equals).isPresent()) {
        // the unit is rolled back by the caller of the unit; the exception carries no row data
        throw new TrnConflictException(executingLei, proposal.trn());
      }
      throw e;
    }
  }

  private static void insertVersion(
      Connection connection,
      Instant now,
      long subjectId,
      long versionSeq,
      Written written,
      DecisionId decision,
      NewVersion version)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(VERSION_INSERT_SQL)) {
      statement.setLong(1, subjectId);
      statement.setLong(2, versionSeq);
      statement.setLong(3, decision.value());
      statement.setObject(4, written.day());
      statement.setString(5, version.action());
      statement.setString(6, version.logicalHash().hex());
      statement.setBytes(7, version.body());
      statement.setShort(8, BODY_ENCODING);
      statement.setShort(9, Codes.of(version.origin()));
      statement.setObject(10, PgTime.timestamp(now));
      statement.executeUpdate();
    }
  }

  private static void bumpSubject(Connection connection, long subjectId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SUBJECT_BUMP_SQL)) {
      statement.setLong(1, subjectId);
      if (statement.executeUpdate() != 1) {
        throw new LedgerException("the report subject row to advance is missing");
      }
    }
  }

  private static void insertSequenceItem(Connection connection, String batch) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SEQUENCE_INSERT_SQL)) {
      statement.setShort(1, Codes.of(OutboxKind.SEQUENCE));
      statement.setString(2, batch);
      statement.setShort(3, Codes.of(OutboxState.PENDING));
      statement.executeUpdate();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // reads

  Optional<Subject> subject(SubjectKey key) {
    Objects.requireNonNull(key, "key");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(SUBJECT_BY_KEY_SQL)) {
            statement.setString(1, key.regime().id());
            statement.setString(2, key.eventIdentity().value());
            statement.setShort(3, Codes.of(key.leg()));
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.<Subject>empty();
              }
              return Optional.of(
                  new Subject(
                      new SubjectId(rows.getLong(1)),
                      key,
                      Optional.ofNullable(rows.getString(3)),
                      Optional.ofNullable(rows.getString(4)),
                      Optional.ofNullable(rows.getString(5)),
                      rows.getLong(2)));
            }
          }
        });
  }

  Optional<Subject> subjectByTrn(String executingLei, String trn) {
    KeyText.require("executingLei", executingLei);
    KeyText.require("trn", trn);
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(SUBJECT_BY_TRN_SQL)) {
            statement.setString(1, executingLei);
            statement.setString(2, trn);
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.<Subject>empty();
              }
              SubjectKey key;
              try {
                key =
                    new SubjectKey(
                        new Regime(rows.getString(3)),
                        new EventIdentity(rows.getString(4)),
                        Codes.leg(rows.getShort(5)));
              } catch (IllegalArgumentException e) {
                throw new LedgerException("a stored report subject cannot be read", e);
              }
              return Optional.of(
                  new Subject(
                      new SubjectId(rows.getLong(1)),
                      key,
                      Optional.of(executingLei),
                      Optional.of(trn),
                      Optional.of(rows.getString(6)),
                      rows.getLong(2)));
            }
          }
        });
  }

  List<StoredVersion> versions(SubjectId subject) {
    Objects.requireNonNull(subject, "subject");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(VERSIONS_SQL)) {
            statement.setLong(1, subject.value());
            try (ResultSet rows = statement.executeQuery()) {
              List<StoredVersion> stored = new ArrayList<>();
              while (rows.next()) {
                if (rows.getShort(6) != BODY_ENCODING) {
                  throw new LedgerException("a stored report version has an unknown body encoding");
                }
                long delivery = rows.getLong(8);
                Optional<DeliveryId> deliveryId =
                    rows.wasNull() ? Optional.empty() : Optional.of(new DeliveryId(delivery));
                try {
                  stored.add(
                      new StoredVersion(
                          subject,
                          rows.getLong(1),
                          new DecisionId(rows.getLong(2)),
                          rows.getString(3),
                          new Sha256(rows.getString(4)),
                          rows.getBytes(5),
                          Codes.versionOrigin(rows.getShort(7)),
                          deliveryId));
                } catch (IllegalArgumentException e) {
                  throw new LedgerException("a stored report version cannot be read", e);
                }
              }
              return List.copyOf(stored);
            }
          }
        });
  }
}
