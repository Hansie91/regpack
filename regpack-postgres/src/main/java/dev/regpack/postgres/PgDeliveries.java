package dev.regpack.postgres;

import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.DeliveryLedger.Attempt;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent;
import dev.regpack.ports.ledger.DeliveryLedger.Delivery;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.DeliveryLedger.TransitionStatus;
import dev.regpack.ports.ledger.DuplicateKeyException;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.postgres.PgErrors.Violation;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Deliveries, the in-flight guard and the fenced send intent on PostgreSQL (spec 9.3 and 11.2, I2,
 * I9; D-S8, D-S9, T17). Nothing is checked in Java that the schema can decide (D-L3): a second
 * delivery of one business key in an in-flight state is refused by the partial unique index {@code
 * delivery_in_flight} (the unique violation is caught under a savepoint and becomes {@code
 * IN_FLIGHT_CONFLICT}), a duplicate delivery key by {@code delivery_key}, a stale token by the
 * fence of {@link PgLeases}.
 *
 * <p>{@code beginSending}, in the order of the P0 contract: the delivery must exist (else {@link
 * IllegalArgumentException}), the attempt id must have been allocated (else {@link
 * IllegalArgumentException}) and not be stored on an attempt (else {@link DuplicateKeyException}),
 * the fence {@link PgLeases#FENCE_SQL} (no match: {@code STALE_LEASE}, nothing written), then the
 * compare-and-set {@code QUEUED} to {@code SENDING} (no row: {@code STATE_MISMATCH}), then the
 * attempt row, then the manifest rows of the intent ({@link PgManifest#record}; a key stored with
 * another hash is a {@code ManifestConflictException} and the unit rolls the state change back; a
 * call that returns another status than {@code APPLIED} has recorded nothing). {@code
 * markCommitIssued}: the fence, then the compare-and-set of {@code commit_issued} that requires the
 * delivery to be {@code SENDING}. Because the fence is a share lock on the lease row, a takeover
 * that commits first makes the later unit find no match, and one that has not committed makes the
 * unit wait; either way no marker survives a takeover and no SENDING delivery survives under a dead
 * token (LF22).
 *
 * <p>The methods that take a {@link Connection} work in a transaction that the caller owns and
 * started with {@link Unit#begin}; they commit nothing. The others run one unit each.
 */
final class PgDeliveries {
  /** The states of the partial unique index {@code delivery_in_flight} (D-S9). */
  static final Set<DeliveryState> IN_FLIGHT =
      Collections.unmodifiableSet(
          EnumSet.of(DeliveryState.SENDING, DeliveryState.AWAITING_ACK, DeliveryState.IN_DOUBT));

  static final String IN_FLIGHT_INDEX = "delivery_in_flight";
  static final String DELIVERY_KEY = "delivery_key";
  static final String ATTEMPT_KEY = "attempt_pk";

  /** Parameters: endpoint, epoch, business key, version, state, subject (nullable), created. */
  static final String CREATE_SQL =
      "INSERT INTO delivery (endpoint_id, endpoint_epoch, business_key, version_seq, state,"
          + " subject_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING delivery_id";

  static final String READ_SQL =
      "SELECT d.state, d.endpoint_epoch, d.business_key, d.version_seq, e.environment,"
          + " e.submission_route, e.submitting_entity_lei, e.destination_authority"
          + " FROM delivery d JOIN endpoint e ON e.endpoint_id = d.endpoint_id"
          + " WHERE d.delivery_id = ?";

  /** Parameters: new state, delivery, expected state. The one compare-and-set on the state. */
  static final String TRANSITION_SQL =
      "UPDATE delivery SET state = ? WHERE delivery_id = ? AND state = ?";

  static final String EXISTS_SQL = "SELECT 1 FROM delivery WHERE delivery_id = ?";

  static final String ENDPOINT_OF_DELIVERY_SQL =
      "SELECT endpoint_id FROM delivery WHERE delivery_id = ?";

  static final String ENDPOINT_OF_ATTEMPT_SQL =
      "SELECT d.endpoint_id FROM attempt a JOIN delivery d ON d.delivery_id = a.delivery_id"
          + " WHERE a.attempt_id = ?";

  static final String ALLOCATE_SQL = "SELECT nextval('attempt_id_seq')";

  /** The state of the attempt sequence; allocated ids are 1 to last_value once is_called. */
  static final String ALLOCATED_SQL = "SELECT last_value, is_called FROM attempt_id_seq";

  static final String ATTEMPT_EXISTS_SQL = "SELECT 1 FROM attempt WHERE attempt_id = ?";

  static final String ATTEMPT_INSERT_SQL =
      "INSERT INTO attempt (attempt_id, delivery_id, lease_restore_epoch, lease_counter,"
          + " artifact_sha256, file_name, commit_issued, created_at)"
          + " VALUES (?, ?, ?, ?, decode(?, 'hex'), ?, false, ?)";

  /**
   * The marker compare-and-set of spec 11.2 step 4. Idempotent: setting a true marker again still
   * matches one row. The code of SENDING is a literal for the same reason as in {@link
   * PgLeases#SWEEP_SQL}. Parameter: the attempt.
   */
  static final String MARKER_SQL =
      "UPDATE attempt SET commit_issued = true FROM delivery d WHERE attempt.attempt_id = ?"
          + " AND d.delivery_id = attempt.delivery_id AND d.state = "
          + Codes.of(DeliveryState.SENDING);

  static final String ATTEMPT_READ_SQL =
      "SELECT delivery_id, lease_restore_epoch, lease_counter, encode(artifact_sha256, 'hex'),"
          + " file_name, commit_issued FROM attempt WHERE attempt_id = ?";

  private final DataSource dataSource;
  private final Clock clock;
  private final Config config;

  PgDeliveries(DataSource dataSource, Clock clock, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.config = Objects.requireNonNull(config, "config");
  }

  // ---------------------------------------------------------------------------------------------
  // the port

  DeliveryId createDelivery(NewDelivery delivery) {
    requireCreate(delivery);
    Instant now = clock.now();
    return Unit.inUnit(
        dataSource,
        config,
        connection -> createDelivery(connection, now, delivery, OptionalLong.empty()));
  }

  /**
   * Creates a delivery in {@code PREPARED} inside the caller's transaction. {@code subjectId} is
   * empty for the bare port path (the column stays NULL and the foreign key to the version is not
   * checked); the reportable unit of T18 passes the subject of the version.
   *
   * @throws DuplicateKeyException if the (endpoint, epoch, business key, version) exists
   */
  static DeliveryId createDelivery(
      Connection connection, Instant createdAt, NewDelivery delivery, OptionalLong subjectId)
      throws SQLException {
    requireCreate(delivery);
    int endpointId = PgLeases.resolveEndpoint(connection, delivery.endpoint());
    return createDelivery(connection, createdAt, delivery, subjectId, endpointId);
  }

  /**
   * {@link #createDelivery(Connection, Instant, NewDelivery, OptionalLong)} for an endpoint whose
   * dictionary id is known: the reportable unit resolves its endpoints before the unit starts
   * ({@link PgDictionaries}), so that the first delivery to a new endpoint does not hold an
   * uncommitted endpoint row for the length of a long unit.
   */
  static DeliveryId createDelivery(
      Connection connection,
      Instant createdAt,
      NewDelivery delivery,
      OptionalLong subjectId,
      int endpointId)
      throws SQLException {
    requireCreate(delivery);
    try (PreparedStatement statement = connection.prepareStatement(CREATE_SQL)) {
      statement.setInt(1, endpointId);
      statement.setLong(2, delivery.endpointEpoch());
      statement.setString(3, delivery.businessKey());
      statement.setLong(4, delivery.versionSeq());
      statement.setShort(5, Codes.of(DeliveryState.PREPARED));
      if (subjectId.isPresent()) {
        statement.setLong(6, subjectId.getAsLong());
      } else {
        statement.setNull(6, Types.BIGINT);
      }
      statement.setObject(7, PgTime.timestamp(createdAt));
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new LedgerException("the new delivery row was not returned");
        }
        return new DeliveryId(rows.getLong(1));
      }
    } catch (SQLException e) {
      if (isViolationOf(e, DELIVERY_KEY)) {
        throw new DuplicateKeyException("delivery already exists (" + DELIVERY_KEY + ")");
      }
      throw e;
    }
  }

  Optional<Delivery> delivery(DeliveryId id) {
    Objects.requireNonNull(id, "id");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(READ_SQL)) {
            statement.setLong(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.empty();
              }
              try {
                return Optional.of(
                    new Delivery(
                        id,
                        new NewDelivery(
                            new EndpointKey(
                                rows.getString(5),
                                rows.getString(6),
                                rows.getString(7),
                                rows.getString(8)),
                            rows.getLong(2),
                            rows.getString(3),
                            rows.getLong(4)),
                        Codes.deliveryState(rows.getShort(1))));
              } catch (IllegalArgumentException e) {
                throw new LedgerException("stored delivery row has an unknown state", e);
              }
            }
          }
        });
  }

  TransitionStatus transition(DeliveryId id, DeliveryState expectedFrom, DeliveryState to) {
    requireTransition(id, expectedFrom, to);
    return Unit.inUnit(
        dataSource, config, connection -> transition(connection, id, expectedFrom, to));
  }

  /** {@link #transition(DeliveryId, DeliveryState, DeliveryState)} in the caller's transaction. */
  TransitionStatus transition(
      Connection connection, DeliveryId id, DeliveryState expectedFrom, DeliveryState to)
      throws SQLException {
    requireTransition(id, expectedFrom, to);
    OptionalInt moved = moveState(connection, id.value(), expectedFrom, to);
    if (moved.isEmpty()) {
      return TransitionStatus.IN_FLIGHT_CONFLICT;
    }
    if (moved.getAsInt() == 1) {
      return TransitionStatus.APPLIED;
    }
    if (!exists(connection, id.value())) {
      throw new IllegalArgumentException("unknown delivery " + id.value());
    }
    return TransitionStatus.STATE_MISMATCH;
  }

  AttemptId allocateAttemptId() {
    return Unit.inUnit(dataSource, config, this::allocateAttemptId);
  }

  /** The next value of the attempt sequence; a sequence value is never given out twice. */
  AttemptId allocateAttemptId(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ALLOCATE_SQL);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new LedgerException("the attempt sequence returned no value");
      }
      return new AttemptId(rows.getLong(1));
    }
  }

  SendingResult beginSending(
      DeliveryId id, AttemptId attemptId, LeaseToken token, AttemptIntent intent) {
    requireBegin(id, attemptId, token, intent);
    return Unit.inUnit(
        dataSource, config, connection -> beginSending(connection, id, attemptId, token, intent));
  }

  /**
   * {@link #beginSending(DeliveryId, AttemptId, LeaseToken, AttemptIntent)} in the caller's unit.
   *
   * <p>The caller must roll its transaction back when this method throws, in particular on a {@link
   * dev.regpack.ports.ledger.ManifestConflictException}: the conflict is found in Java after the
   * state change, the attempt row and the manifest rows of the other keys were written, and no SQL
   * error has aborted the transaction, so a caller that caught the exception and committed would
   * keep the {@code SENDING} state, the attempt row and those rows. Today the only caller outside
   * the tests is the port variant above, which runs in {@link Unit#inUnit}: that rolls back on any
   * exception and passes it on (and the tests that call this method use an intent without objects).
   * A later unit that composes this method with other writes and wants to survive the conflict must
   * take a savepoint before it and roll back to it.
   */
  SendingResult beginSending(
      Connection connection,
      DeliveryId id,
      AttemptId attemptId,
      LeaseToken token,
      AttemptIntent intent)
      throws SQLException {
    requireBegin(id, attemptId, token, intent);
    OptionalInt endpointId = scalarInt(connection, ENDPOINT_OF_DELIVERY_SQL, id.value());
    if (endpointId.isEmpty()) {
      throw new IllegalArgumentException("unknown delivery " + id.value());
    }
    requireUsableAttemptId(connection, attemptId.value());
    if (!PgLeases.holds(connection, endpointId.getAsInt(), token)) {
      return new SendingResult(SendingStatus.STALE_LEASE, Optional.empty());
    }
    OptionalInt moved =
        moveState(connection, id.value(), DeliveryState.QUEUED, DeliveryState.SENDING);
    if (moved.isEmpty()) {
      return new SendingResult(SendingStatus.IN_FLIGHT_CONFLICT, Optional.empty());
    }
    if (moved.getAsInt() == 0) {
      return new SendingResult(SendingStatus.STATE_MISMATCH, Optional.empty());
    }
    // one ledger time for the attempt row and the manifest rows of the unit
    OffsetDateTime now = PgTime.timestamp(clock.now());
    insertAttempt(connection, id, attemptId, token, intent, now);
    // the manifest rows come last (EF21): a key that is already stored with another hash throws
    // ManifestConflictException, and the unit that called us rolls the state change back
    PgManifest.record(
        connection, now, intent.objects(), Provenance.WRITTEN, Optional.of(attemptId));
    return new SendingResult(SendingStatus.APPLIED, Optional.of(attemptId));
  }

  boolean markCommitIssued(AttemptId attempt, LeaseToken token) {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(token, "token");
    return Unit.inUnit(
        dataSource, config, connection -> markCommitIssued(connection, attempt, token));
  }

  /** {@link #markCommitIssued(AttemptId, LeaseToken)} in the caller's transaction. */
  boolean markCommitIssued(Connection connection, AttemptId attempt, LeaseToken token)
      throws SQLException {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(token, "token");
    OptionalInt endpointId = scalarInt(connection, ENDPOINT_OF_ATTEMPT_SQL, attempt.value());
    if (endpointId.isEmpty()) {
      return false;
    }
    if (!PgLeases.holds(connection, endpointId.getAsInt(), token)) {
      return false;
    }
    try (PreparedStatement statement = connection.prepareStatement(MARKER_SQL)) {
      statement.setLong(1, attempt.value());
      return statement.executeUpdate() > 0;
    }
  }

  Optional<Attempt> attempt(AttemptId id) {
    Objects.requireNonNull(id, "id");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(ATTEMPT_READ_SQL)) {
            statement.setLong(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.empty();
              }
              try {
                return Optional.of(
                    new Attempt(
                        id,
                        new DeliveryId(rows.getLong(1)),
                        new LeaseToken(rows.getLong(2), rows.getLong(3)),
                        new Sha256(rows.getString(4)),
                        rows.getString(5),
                        rows.getBoolean(6)));
              } catch (IllegalArgumentException e) {
                throw new LedgerException("stored attempt row is malformed", e);
              }
            }
          }
        });
  }

  // ---------------------------------------------------------------------------------------------
  // internals

  private static void requireCreate(NewDelivery delivery) {
    Objects.requireNonNull(delivery, "delivery");
    Objects.requireNonNull(delivery.endpoint(), "endpoint");
    if (delivery.businessKey().isBlank()) {
      throw new IllegalArgumentException("businessKey must not be blank");
    }
  }

  private static void requireTransition(
      DeliveryId id, DeliveryState expectedFrom, DeliveryState to) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(expectedFrom, "expectedFrom");
    Objects.requireNonNull(to, "to");
    if (to == DeliveryState.SENDING) {
      throw new IllegalArgumentException("a delivery moves into SENDING only through beginSending");
    }
  }

  private static void requireBegin(
      DeliveryId id, AttemptId attemptId, LeaseToken token, AttemptIntent intent) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(attemptId, "attemptId");
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(intent, "intent");
  }

  /**
   * The compare-and-set on the state. The number of rows changed (0 or 1); empty when the target is
   * an in-flight state and the change hit {@code delivery_in_flight}, which is rolled back to a
   * savepoint so that the caller's transaction goes on.
   */
  private static OptionalInt moveState(
      Connection connection, long id, DeliveryState from, DeliveryState to) throws SQLException {
    if (!IN_FLIGHT.contains(to)) {
      return OptionalInt.of(updateState(connection, id, from, to));
    }
    Savepoint savepoint = connection.setSavepoint();
    try {
      int rows = updateState(connection, id, from, to);
      connection.releaseSavepoint(savepoint);
      return OptionalInt.of(rows);
    } catch (SQLException e) {
      if (!isViolationOf(e, IN_FLIGHT_INDEX)) {
        throw e;
      }
      connection.rollback(savepoint);
      return OptionalInt.empty();
    }
  }

  private static int updateState(
      Connection connection, long id, DeliveryState from, DeliveryState to) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRANSITION_SQL)) {
      statement.setShort(1, Codes.of(to));
      statement.setLong(2, id);
      statement.setShort(3, Codes.of(from));
      return statement.executeUpdate();
    }
  }

  private static boolean exists(Connection connection, long id) throws SQLException {
    return scalarInt(connection, EXISTS_SQL, id).isPresent();
  }

  /** The first column of the first row of a query with one long parameter, if there is a row. */
  private static OptionalInt scalarInt(Connection connection, String sql, long parameter)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalInt.of(rows.getInt(1)) : OptionalInt.empty();
      }
    }
  }

  /**
   * An id that no {@code allocateAttemptId} gave out is an {@link IllegalArgumentException}; an id
   * already stored on an attempt is a {@link DuplicateKeyException}. The primary key stays the
   * judge of a race between two units that use the same id.
   */
  private static void requireUsableAttemptId(Connection connection, long attemptId)
      throws SQLException {
    long lastAllocated = 0;
    try (PreparedStatement statement = connection.prepareStatement(ALLOCATED_SQL);
        ResultSet rows = statement.executeQuery()) {
      if (rows.next() && rows.getBoolean(2)) {
        lastAllocated = rows.getLong(1);
      }
    }
    if (attemptId < 1 || attemptId > lastAllocated) {
      throw new IllegalArgumentException("attempt id was not allocated: " + attemptId);
    }
    if (scalarInt(connection, ATTEMPT_EXISTS_SQL, attemptId).isPresent()) {
      throw new DuplicateKeyException("attempt id already used");
    }
  }

  private void insertAttempt(
      Connection connection,
      DeliveryId delivery,
      AttemptId attemptId,
      LeaseToken token,
      AttemptIntent intent,
      OffsetDateTime createdAt)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ATTEMPT_INSERT_SQL)) {
      statement.setLong(1, attemptId.value());
      statement.setLong(2, delivery.value());
      statement.setLong(3, token.restoreEpoch());
      statement.setLong(4, token.counter());
      statement.setString(5, intent.artifactSha256().hex());
      statement.setString(6, intent.fileName());
      statement.setObject(7, createdAt);
      statement.executeUpdate();
    } catch (SQLException e) {
      if (isViolationOf(e, ATTEMPT_KEY)) {
        throw new DuplicateKeyException("attempt id already used");
      }
      throw e;
    }
  }

  /** True for a unique violation of the named constraint or index (by name, never by message). */
  private static boolean isViolationOf(SQLException e, String constraint) {
    return PgErrors.classify(e) == Violation.UNIQUE
        && PgErrors.constraint(e).filter(constraint::equals).isPresent();
  }
}
