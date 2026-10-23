package dev.regpack.postgres;

import dev.regpack.model.KeyText;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseGrant;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import javax.sql.DataSource;

/**
 * Per-endpoint send leases with a fencing token on PostgreSQL (spec 10, 11.2, 11.5 A.2; D-S10,
 * D-S12, T17). The lease row of an endpoint is {@code (restore_epoch, counter, holder, expires_at,
 * released)}; the token is the pair {@code (restore_epoch, counter)}, always compared as a pair.
 *
 * <p>The protocol that makes a takeover race-free (review focus LF22) has two halves. The fence of
 * a sender ({@link #FENCE_SQL}, used by {@code beginSending} and {@code markCommitIssued} in {@link
 * PgDeliveries}) is a {@code SELECT ... FOR SHARE} on the lease row, run in the same transaction
 * and before the write it guards. The takeover ({@code acquire}, {@code declareRestoreEpoch})
 * modifies that row first, which needs a lock that conflicts with the share lock: it waits for
 * every unit that has passed its fence check and not yet committed, and a unit that arrives after
 * the takeover has committed finds a different row and no match. Only then, as a separate
 * statement, does the takeover move the {@code SENDING} deliveries of the endpoint to {@code
 * IN_DOUBT} ({@link #SWEEP_SQL}); it never joins to {@code lease} inside one UPDATE. The fence has
 * no expiry predicate (M50, M51): an expired holder that has not been taken over can only continue
 * what it owns, because every acquisition sweeps.
 *
 * <p>Locks are taken in one order: the {@code restore_epoch} singleton, then lease rows (by
 * endpoint id), then delivery rows, then the attempt row, then the {@code object_manifest} keys in
 * byte order ({@link PgManifest#record} sorts them). A unit that takes a key before a delivery row
 * can form a cycle with {@code beginSending}. {@code acquire} reads the singleton with {@code FOR
 * SHARE} first, so that the epoch it writes cannot be overtaken by a {@code declareRestoreEpoch}
 * that commits while the statement runs (a plain read would be a stale snapshot inside a conflict
 * re-check).
 *
 * <p>Time is the injected {@link Clock}: every instant in a statement is a bind parameter.
 */
final class PgLeases {
  /** The one reporting entity of release 1 (A8, D-S12); the column default is the same number. */
  static final short ENTITY_ID = 1;

  static final String ENDPOINT_SELECT_SQL =
      "SELECT endpoint_id FROM endpoint WHERE entity_id = ? AND environment = ?"
          + " AND submission_route = ? AND submitting_entity_lei = ? AND destination_authority = ?";

  static final String ENDPOINT_INSERT_SQL =
      "INSERT INTO endpoint (entity_id, environment, submission_route, submitting_entity_lei,"
          + " destination_authority) VALUES (?, ?, ?, ?, ?)"
          + " ON CONFLICT ON CONSTRAINT endpoint_key DO NOTHING";

  /** The singleton, read with a share lock so that a restore declaration waits for the unit. */
  static final String EPOCH_SHARE_SQL = "SELECT epoch FROM restore_epoch WHERE singleton FOR SHARE";

  static final String EPOCH_SQL = "SELECT epoch FROM restore_epoch WHERE singleton";

  /**
   * The grant: one upsert that takes the row only when it is released, expired at the injected
   * time, or of an older restore epoch (then the counter restarts at 1 in the current epoch).
   * Parameters: entity, endpoint, epoch, holder, expiry, now. No row back means the lease is held;
   * the row is then locked by this statement anyway, so the read of the holder is stable.
   */
  static final String ACQUIRE_SQL =
      "INSERT INTO lease (entity_id, endpoint_id, restore_epoch, counter, holder, expires_at,"
          + " released) VALUES (?, ?, ?, 1, ?, ?, false)"
          + " ON CONFLICT (entity_id, endpoint_id) DO UPDATE SET"
          + " restore_epoch = EXCLUDED.restore_epoch,"
          + " counter = CASE WHEN lease.restore_epoch < EXCLUDED.restore_epoch THEN 1"
          + " ELSE lease.counter + 1 END,"
          + " holder = EXCLUDED.holder, expires_at = EXCLUDED.expires_at, released = false"
          + " WHERE lease.released OR lease.expires_at <= ?"
          + " OR lease.restore_epoch < EXCLUDED.restore_epoch"
          + " RETURNING restore_epoch, counter";

  static final String HELD_SQL =
      "SELECT holder, expires_at FROM lease WHERE entity_id = ? AND endpoint_id = ?";

  /** Parameters: new expiry, entity, endpoint, epoch, counter, now. Refuses on expiry (M50). */
  static final String RENEW_SQL =
      "UPDATE lease SET expires_at = ? WHERE entity_id = ? AND endpoint_id = ?"
          + " AND restore_epoch = ? AND counter = ? AND NOT released"
          + " AND restore_epoch = (SELECT epoch FROM restore_epoch) AND expires_at > ?";

  /** Parameters: entity, endpoint, epoch, counter. Any other token matches no row. */
  static final String RELEASE_SQL =
      "UPDATE lease SET released = true WHERE entity_id = ? AND endpoint_id = ?"
          + " AND restore_epoch = ? AND counter = ? AND NOT released";

  /** The latest grant unless released or voided; no expiry predicate. */
  static final String CURRENT_SQL =
      "SELECT restore_epoch, counter, holder, expires_at FROM lease WHERE entity_id = ?"
          + " AND endpoint_id = ? AND NOT released"
          + " AND restore_epoch = (SELECT epoch FROM restore_epoch)";

  /**
   * The fence (LF22). Parameters: entity, endpoint, epoch, counter. A row means the token is the
   * current one. {@code FOR SHARE}, not {@code FOR KEY SHARE}: the takeover changes no key column
   * and must still wait for it. The singleton clause covers a lease row that a restore brought back
   * from a backup next to a newer epoch.
   */
  static final String FENCE_SQL =
      "SELECT 1 FROM lease WHERE entity_id = ? AND endpoint_id = ? AND restore_epoch = ?"
          + " AND counter = ? AND NOT released"
          + " AND restore_epoch = (SELECT epoch FROM restore_epoch) FOR SHARE";

  /**
   * The takeover sweep (D-S9). Parameters: the new state (IN_DOUBT), the endpoint. The code of
   * SENDING is a literal, not a bind parameter: the planner proves the predicate of the partial
   * index {@code delivery_sending_idx} only from a constant, and {@link Codes} is where the number
   * comes from.
   */
  static final String SWEEP_SQL =
      "UPDATE delivery SET state = ? WHERE endpoint_id = ? AND state = "
          + Codes.of(DeliveryState.SENDING);

  static final String DECLARE_SQL =
      "UPDATE restore_epoch SET epoch = ? WHERE singleton AND epoch < ?";

  /** Every lease row, locked in endpoint order. */
  static final String LOCK_ALL_SQL =
      "SELECT endpoint_id FROM lease WHERE entity_id = ? ORDER BY endpoint_id FOR UPDATE";

  static final String VOID_ALL_SQL =
      "UPDATE lease SET released = true WHERE entity_id = ? AND NOT released";

  private final DataSource dataSource;
  private final Clock clock;
  private final Config config;

  PgLeases(DataSource dataSource, Clock clock, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.config = Objects.requireNonNull(config, "config");
  }

  // ---------------------------------------------------------------------------------------------
  // the port

  LeaseResult acquire(EndpointKey endpoint, String holder, Duration ttl) {
    requireAcquire(endpoint, holder, ttl);
    return Unit.inUnit(
        dataSource, config, connection -> acquireIn(connection, endpoint, holder, ttl));
  }

  /**
   * {@link #acquire(EndpointKey, String, Duration)} inside a transaction that the caller owns
   * (started with {@link Unit#begin}); nothing is committed here.
   */
  LeaseResult acquire(Connection connection, EndpointKey endpoint, String holder, Duration ttl)
      throws SQLException {
    requireAcquire(endpoint, holder, ttl);
    return acquireIn(connection, endpoint, holder, ttl);
  }

  boolean renew(EndpointKey endpoint, LeaseToken token, Duration ttl) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(token, "token");
    requirePositiveWholeMicros(ttl);
    Instant now = clock.now();
    Instant expires = expiry(now, ttl);
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          OptionalInt endpointId = findEndpoint(connection, endpoint);
          if (endpointId.isEmpty()) {
            return false;
          }
          try (PreparedStatement statement = connection.prepareStatement(RENEW_SQL)) {
            statement.setObject(1, PgTime.timestamp(expires));
            statement.setShort(2, ENTITY_ID);
            statement.setInt(3, endpointId.getAsInt());
            statement.setLong(4, token.restoreEpoch());
            statement.setLong(5, token.counter());
            statement.setObject(6, PgTime.timestamp(now));
            return statement.executeUpdate() == 1;
          }
        });
  }

  void release(EndpointKey endpoint, LeaseToken token) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(token, "token");
    Unit.inUnit(
        dataSource,
        config,
        connection -> {
          OptionalInt endpointId = findEndpoint(connection, endpoint);
          if (endpointId.isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement(RELEASE_SQL)) {
              statement.setShort(1, ENTITY_ID);
              statement.setInt(2, endpointId.getAsInt());
              statement.setLong(3, token.restoreEpoch());
              statement.setLong(4, token.counter());
              statement.executeUpdate();
            }
          }
          return Boolean.TRUE;
        });
  }

  Optional<LeaseGrant> currentLease(EndpointKey endpoint) {
    Objects.requireNonNull(endpoint, "endpoint");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          OptionalInt endpointId = findEndpoint(connection, endpoint);
          if (endpointId.isEmpty()) {
            return Optional.empty();
          }
          try (PreparedStatement statement = connection.prepareStatement(CURRENT_SQL)) {
            statement.setShort(1, ENTITY_ID);
            statement.setInt(2, endpointId.getAsInt());
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.empty();
              }
              return Optional.of(
                  new LeaseGrant(
                      new LeaseToken(rows.getLong(1), rows.getLong(2)),
                      rows.getString(3),
                      PgTime.instant(rows.getObject(4, OffsetDateTime.class))));
            }
          }
        });
  }

  long restoreEpoch() {
    return Unit.inUnit(dataSource, config, connection -> readEpoch(connection, EPOCH_SQL));
  }

  /**
   * Raises the singleton and voids every lease row in one transaction. The statement that raises
   * the singleton is the check: it matches only when the new epoch exceeds the current one.
   */
  void declareRestoreEpoch(long epoch) {
    Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(DECLARE_SQL)) {
            statement.setLong(1, epoch);
            statement.setLong(2, epoch);
            if (statement.executeUpdate() == 0) {
              throw new IllegalArgumentException(
                  "restore epoch " + epoch + " must exceed the current epoch");
            }
          }
          List<Integer> endpoints = lockAllLeases(connection);
          try (PreparedStatement statement = connection.prepareStatement(VOID_ALL_SQL)) {
            statement.setShort(1, ENTITY_ID);
            statement.executeUpdate();
          }
          for (int endpointId : endpoints) {
            sweep(connection, endpointId);
          }
          return Boolean.TRUE;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // shared with the delivery side

  /** True when {@code token} is the current token of the endpoint; takes the share lock (LF22). */
  static boolean holds(Connection connection, int endpointId, LeaseToken token)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FENCE_SQL)) {
      statement.setShort(1, ENTITY_ID);
      statement.setInt(2, endpointId);
      statement.setLong(3, token.restoreEpoch());
      statement.setLong(4, token.counter());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  /** The dictionary id of the endpoint, if it has one; reads never create a row. */
  static OptionalInt findEndpoint(Connection connection, EndpointKey endpoint) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ENDPOINT_SELECT_SQL)) {
      bindEndpoint(statement, endpoint);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalInt.of(rows.getInt(1)) : OptionalInt.empty();
      }
    }
  }

  /**
   * The dictionary id of the endpoint, created if it is new. Two units that create the same
   * endpoint at once agree: the loser of the insert waits for the winner and then reads its row.
   */
  static int resolveEndpoint(Connection connection, EndpointKey endpoint) throws SQLException {
    OptionalInt id = findEndpoint(connection, endpoint);
    if (id.isEmpty()) {
      try (PreparedStatement insert = connection.prepareStatement(ENDPOINT_INSERT_SQL)) {
        bindEndpoint(insert, endpoint);
        insert.executeUpdate();
      }
      id = findEndpoint(connection, endpoint);
    }
    return id.orElseThrow(() -> new LedgerException("the endpoint dictionary row is missing"));
  }

  /** Moves the {@code SENDING} deliveries of the endpoint to {@code IN_DOUBT} (takeover rule). */
  static void sweep(Connection connection, int endpointId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SWEEP_SQL)) {
      statement.setShort(1, Codes.of(DeliveryState.IN_DOUBT));
      statement.setInt(2, endpointId);
      statement.executeUpdate();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // internals

  private static void bindEndpoint(PreparedStatement statement, EndpointKey endpoint)
      throws SQLException {
    statement.setShort(1, ENTITY_ID);
    statement.setString(2, Objects.requireNonNull(endpoint.environment(), "environment"));
    statement.setString(3, Objects.requireNonNull(endpoint.submissionRoute(), "submissionRoute"));
    statement.setString(
        4, Objects.requireNonNull(endpoint.submittingEntityLei(), "submittingEntityLei"));
    statement.setString(
        5, Objects.requireNonNull(endpoint.destinationAuthority(), "destinationAuthority"));
  }

  private static void requireAcquire(EndpointKey endpoint, String holder, Duration ttl) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(holder, "holder");
    requirePositiveWholeMicros(ttl);
    if (holder.isBlank()) {
      throw new IllegalArgumentException("holder must not be blank");
    }
    KeyText.require("holder", holder);
  }

  static void requirePositiveWholeMicros(Duration ttl) {
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive: " + ttl);
    }
    if (ttl.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("ttl is not whole microseconds: " + ttl);
    }
    if (ttl.compareTo(LeaseLedger.MAX_TTL) > 0) {
      throw new IllegalArgumentException("ttl is longer than " + LeaseLedger.MAX_TTL);
    }
  }

  private static Instant expiry(Instant now, Duration ttl) {
    try {
      return now.plus(ttl);
    } catch (DateTimeException | ArithmeticException e) {
      throw new IllegalArgumentException("ttl is out of range: " + ttl);
    }
  }

  private LeaseResult acquireIn(
      Connection connection, EndpointKey endpoint, String holder, Duration ttl)
      throws SQLException {
    Instant now = clock.now();
    Instant expires = expiry(now, ttl);
    long epoch = readEpoch(connection, EPOCH_SHARE_SQL);
    int endpointId = resolveEndpoint(connection, endpoint);
    Optional<LeaseToken> token;
    try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_SQL)) {
      statement.setShort(1, ENTITY_ID);
      statement.setInt(2, endpointId);
      statement.setLong(3, epoch);
      statement.setString(4, holder);
      statement.setObject(5, PgTime.timestamp(expires));
      statement.setObject(6, PgTime.timestamp(now));
      try (ResultSet rows = statement.executeQuery()) {
        token =
            rows.next()
                ? Optional.of(new LeaseToken(rows.getLong(1), rows.getLong(2)))
                : Optional.empty();
      }
    }
    if (token.isPresent()) {
      // the lease row is ours and committed with this unit; now the deliveries it leaves behind
      sweep(connection, endpointId);
      return new LeaseResult.Granted(new LeaseGrant(token.get(), holder, expires));
    }
    return held(connection, endpointId);
  }

  private static LeaseResult held(Connection connection, int endpointId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(HELD_SQL)) {
      statement.setShort(1, ENTITY_ID);
      statement.setInt(2, endpointId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new LedgerException("the lease row of a held endpoint is missing");
        }
        return new LeaseResult.Held(
            rows.getString(1), PgTime.instant(rows.getObject(2, OffsetDateTime.class)));
      }
    }
  }

  private static long readEpoch(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new LedgerException("the restore epoch row is missing");
      }
      return rows.getLong(1);
    }
  }

  private static List<Integer> lockAllLeases(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(LOCK_ALL_SQL)) {
      statement.setShort(1, ENTITY_ID);
      try (ResultSet rows = statement.executeQuery()) {
        List<Integer> endpoints = new ArrayList<>();
        while (rows.next()) {
          endpoints.add(rows.getInt(1));
        }
        return endpoints;
      }
    }
  }
}
