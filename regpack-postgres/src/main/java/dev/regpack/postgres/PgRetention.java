package dev.regpack.postgres;

import dev.regpack.model.Regime;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The {@code retention} rows on PostgreSQL (spec 11.6; migration V003, D2, D3 and D6 of its
 * document; P3c1 D-K4, D-K8): one row per (artifact, regime, anchor event), a date that can only
 * grow and never goes back to unknown, one legal hold per artifact that a new row of the artifact
 * takes, and the list of rows that are due. The key columns are compared byte by byte ({@code
 * COLLATE "C"}), so the pages come in the order of the in-memory ledger; a trigger refuses an
 * update that shortens a date or changes the key, regime, anchor or class of a row, and so is a
 * second defence behind the {@code GREATEST} of the upsert.
 *
 * <p><b>Writing.</b> {@link #recordRetention} sorts the claims by (artifact, regime, anchor) and
 * then, in one unit and with constant statements, (1) takes the artifact lock of every distinct
 * artifact it touches, one statement per artifact in the order of the artifacts ({@link
 * #ARTIFACT_LOCK_SQL}), and only then (2) writes every claim with {@link #RECORD_SQL}: an insert
 * whose legal hold is that of the artifact (a row of the artifact is held) and whose conflict on
 * {@code retention_pk} updates the date to the later of the two ({@code GREATEST} ignores a NULL on
 * either side: manual, Conditional Expressions) and nothing else, so the class and {@code
 * recorded_at} of a stored row stay. The constraint is the arbiter, so a schema without it fails
 * with a missing-arbiter error and not with a silent second row (the schema mutant {@code
 * NoRetentionKeyPgLedgerContractIT}). The claims are written in key order, like the locks, so that
 * two calls with overlapping claims never wait for each other in a circle.
 *
 * <p><b>The artifact lock (task note T11-2, decision D28).</b> Under {@code READ COMMITTED} each
 * statement takes its own snapshot. A hold that is one {@code UPDATE} and a claim for a new regime
 * of the same artifact that is one {@code INSERT} can run at the same time: the update does not see
 * the row that the insert is making and the insert reads the hold before the update has committed,
 * so the new row stays unheld. Both therefore take the same transaction-level advisory lock first
 * ({@code pg_advisory_xact_lock(purpose, hashtext(schema ':' artifact))}, the form of Convention 12
 * of the P3a plan, purpose {@value #RETENTION_LOCK_PURPOSE}), in a statement of its own and as the
 * first statement of the unit; the second to arrive waits for the first to commit, and its write is
 * a later statement with a new snapshot that sees what the first one committed. A hold that finds
 * the lock taken by a claim holds the rows that the claim wrote, and a claim that finds it taken by
 * a hold reads the hold. The lock does not depend on a row, so it serialises the first claims of a
 * new artifact too, and a hold of an artifact that has no row takes it, finds no row to update and
 * fails with the refusal of the port. It is released with the transaction, whether it commits or
 * not. {@link #recordRetention} and {@link #setRetentionHold} take it for every distinct artifact
 * of the call, two claims of one artifact lock once; the reads ({@link #retentionRows}, {@link
 * #expiredRetention}) take none.
 *
 * <p>The one honest limit: the lock is named by the 32-bit hash of the text, so two different
 * artifacts whose hashes collide share one lock. That costs a wait. If two calls took a colliding
 * pair in different orders it could also be a deadlock; the server detects it and ends one call
 * with SQLSTATE 40P01, which comes out as the ledger exception of any statement failure, after a
 * rollback, with nothing written. The case is recorded, not designed away.
 *
 * <p><b>What the lock does and does not do.</b> It serialises the writers that take it, and only
 * those. Every other writer of retention rows (the purge of P4, a tool for holds, a loader) must
 * take {@link #ARTIFACT_LOCK_SQL} first, with purpose {@value #RETENTION_LOCK_PURPOSE} and the text
 * schema ':' artifact, in key order, in the unit that writes, or the window that D28 closed
 * returns: a hold that misses an uncommitted row, a claim that reads a stale hold. The lock is
 * released when the transaction ends, and a commit with {@code synchronous_commit} on waits for the
 * synchronous standby before it releases its locks. So while the standby is slow or away, the next
 * call for the same artifact waits up to {@code lock_timeout} and fails with SQLSTATE 55P03, with
 * nothing written (fail closed, per artifact; the job {@code ledger-pg} has no standby and cannot
 * show it). {@code recorded_at} is read before the unit, that is before any wait for the lock.
 *
 * <p><b>Cost.</b> The lock takes one entry of the server's shared lock table for each distinct
 * artifact of a call until the commit. Advisory and ordinary locks share one pool of {@code
 * max_locks_per_transaction} times ({@code max_connections} plus {@code max_prepared_transactions})
 * entries, 64 times 100 by default (manual: Server Configuration, Lock Management; Explicit
 * Locking, Advisory Locks). The port makes a call all or nothing, so the adapter cannot split one,
 * and a bulk caller chunks its claims (note P4-3). A call that meets the limit fails with SQLSTATE
 * 53200, which comes out as the ledger exception of any statement failure, after a rollback, with
 * nothing written. {@code RetentionLedgerContract#theLimitIsCappedAtOneThousand} (1,100 distinct
 * artifacts in one call) is the first call of that size on a server (CP-1).
 *
 * <p><b>A call that writes nothing still waits for the standby (I1).</b> There is no such call:
 * {@link #recordRetention} writes a row version for every claim (a conflict updates the date
 * through the {@code DO UPDATE}, also to the value it has [R]) and {@link #setRetentionHold}
 * updates every row of the artifact or fails. So no durability barrier is taken.
 *
 * <p><b>Reading.</b> {@link #retentionRows} is the range on the primary key of {@code PgManifest}
 * over the artifacts, with the rows of at most {@code limit} artifacts: the keys are chosen by a
 * {@code DISTINCT} sub-select with the range and the limit, and the rows of those keys come back in
 * one statement and one snapshot, so the rows of an artifact are never split across two calls.
 * {@link #expiredRetention} lists due rows that are not held and not under {@link
 * RetentionLedger#EXEMPT_PREFIX}, in the order (retain_until, artifact, regime, anchor), after a
 * row by a row comparison. The two text parameters of that comparison carry {@code COLLATE "C"}
 * (deviation D30 from the statement shape of the plan): the explicit collation equals that of the
 * column and wins over any implicit one, so it cannot conflict, and the result does not depend on
 * how the server pairs the collations of the two sides of a row comparison. The scalar comparisons
 * name no collation: a column's own collation beats the default one of a bound parameter (manual,
 * Collation Support, Collation Concepts). That the pages come in byte order is still shown at CP-1
 * by the contract tests, on a cluster whose default collation is {@code en_US.utf8}.
 *
 * <p>Every value is a bind parameter, the prefix of the epoch markers included, and no message
 * carries a value (Convention 12). A retain-until is bound only through {@link
 * PgTime#timestamp(Instant)} as an {@link OffsetDateTime} (an open anchor as a typed null), never
 * as text and never through a function of the statement (task note T11-5). The texts of the
 * refusals are those of the in-memory ledger.
 */
final class PgRetention {
  /**
   * The advisory-lock purpose of the artifact lock of a retention write (Convention 12 of the P3a
   * plan). The purposes in use: 1 the migrator, 2 the creation of a partition, 3 the source of an
   * ingest commit, 4 this one. {@code PgHarnessIT#advisoryKeysDifferBetweenSchemas} holds them
   * together.
   */
  private static final int RETENTION_LOCK_PURPOSE = 4;

  /** Parameters: the purpose (an int) and the text schema + ':' + the artifact. */
  static final String ARTIFACT_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, hashtext(?))";

  /**
   * Parameters: key, regime, anchor code, class, retain-until (nullable), key again (the artifact
   * whose hold the row takes), recorded at.
   */
  static final String RECORD_SQL =
      "INSERT INTO retention (object_key, regime, anchor_event, entity_id, retention_class,"
          + " retain_until, legal_hold, recorded_at) VALUES (?, ?, ?, 1, ?, ?,"
          + " EXISTS (SELECT 1 FROM retention h WHERE h.object_key = ? AND h.legal_hold), ?)"
          + " ON CONFLICT ON CONSTRAINT retention_pk DO UPDATE SET retain_until ="
          + " GREATEST(retention.retain_until, EXCLUDED.retain_until)";

  /** Parameters: the hold, the artifact. */
  static final String HOLD_SQL = "UPDATE retention SET legal_hold = ? WHERE object_key = ?";

  private static final String COLUMNS =
      "object_key, regime, anchor_event, retention_class, retain_until, legal_hold, recorded_at";

  /**
   * Parameters: the prefix, its upper bound, the artifact after which to list (or ''), the limit of
   * artifacts.
   */
  static final String ROWS_SQL =
      "SELECT "
          + COLUMNS
          + " FROM retention WHERE object_key IN (SELECT DISTINCT object_key FROM retention"
          + " WHERE object_key >= ? AND object_key < ? AND object_key > ?"
          + " ORDER BY object_key LIMIT ?) ORDER BY object_key, regime, anchor_event";

  private static final String EXPIRED_HEAD =
      "SELECT "
          + COLUMNS
          + " FROM retention WHERE retain_until IS NOT NULL AND retain_until <= ?"
          + " AND NOT legal_hold AND NOT starts_with(object_key, ?)";

  private static final String EXPIRED_TAIL =
      " ORDER BY retain_until, object_key, regime, anchor_event LIMIT ?";

  /** Parameters: as of, the prefix of the epoch markers, the limit of rows. */
  static final String EXPIRED_SQL = EXPIRED_HEAD + EXPIRED_TAIL;

  /**
   * Parameters: as of, the prefix of the epoch markers, the retain-until, key, regime and anchor
   * code of the row after which to list, the limit of rows.
   */
  static final String EXPIRED_AFTER_SQL =
      EXPIRED_HEAD
          + " AND (retain_until, object_key, regime, anchor_event) >"
          + " (?, ? COLLATE \"C\", ? COLLATE \"C\", ?)"
          + EXPIRED_TAIL;

  /**
   * The order in which artifacts are locked and rows are written, and so the order in which two
   * calls meet: artifact and regime by their text (for the alphabets of a key and of a regime id
   * the order of the {@code "C"} collation), then the anchor by its declaration, which is its code.
   */
  private static final Comparator<RetentionClaim> KEY_ORDER =
      Comparator.<RetentionClaim, String>comparing(claim -> claim.artifact().path())
          .thenComparing(claim -> claim.regime().id())
          .thenComparing(RetentionClaim::anchor);

  /** One claim as it is bound: nothing here can fail inside the unit. */
  private record Write(
      String artifact,
      String regime,
      short anchor,
      String retentionClass,
      Optional<OffsetDateTime> retainUntil) {}

  private final DataSource dataSource;
  private final Clock clock;
  private final Config config;

  PgRetention(DataSource dataSource, Clock clock, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.config = Objects.requireNonNull(config, "config");
  }

  // ---------------------------------------------------------------------------------------------
  // the port

  void recordRetention(List<RetentionClaim> claims) {
    Objects.requireNonNull(claims, "claims");
    List<RetentionClaim> copy = List.copyOf(claims);
    if (copy.isEmpty()) {
      return;
    }
    requireDistinct(copy);
    List<RetentionClaim> inKeyOrder = new ArrayList<>(copy);
    inKeyOrder.sort(KEY_ORDER);
    List<Write> writes = new ArrayList<>();
    List<String> artifacts = new ArrayList<>();
    for (RetentionClaim claim : inKeyOrder) {
      String artifact = claim.artifact().path();
      if (artifacts.isEmpty() || !artifacts.get(artifacts.size() - 1).equals(artifact)) {
        artifacts.add(artifact);
      }
      writes.add(
          new Write(
              artifact,
              claim.regime().id(),
              Codes.of(claim.anchor()),
              claim.retentionClass(),
              claim.retainUntil().map(PgTime::timestamp)));
    }
    OffsetDateTime recordedAt = PgTime.timestamp(clock.now());
    Unit.inUnit(
        dataSource,
        config,
        connection -> {
          lockArtifacts(connection, artifacts);
          write(connection, writes, recordedAt);
          return Boolean.TRUE;
        });
  }

  void setRetentionHold(EvidenceKey artifact, boolean hold) {
    Objects.requireNonNull(artifact, "artifact");
    Unit.inUnit(
        dataSource,
        config,
        connection -> {
          lockArtifacts(connection, List.of(artifact.path()));
          try (PreparedStatement update = connection.prepareStatement(HOLD_SQL)) {
            update.setBoolean(1, hold);
            update.setString(2, artifact.path());
            if (update.executeUpdate() == 0) {
              throw new IllegalArgumentException("the artifact has no retention row");
            }
          }
          return Boolean.TRUE;
        });
  }

  List<RetentionRow> retentionRows(String prefix, Optional<EvidenceKey> after, int limit) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    int max = Math.min(limit, RetentionLedger.MAX_RETENTION_ROWS_PER_CALL);
    // no key holds a NUL and the server cannot hold one in a text: nothing can match
    if (max == 0 || prefix.indexOf('\0') >= 0) {
      return List.of();
    }
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(ROWS_SQL)) {
            statement.setString(1, prefix);
            statement.setString(2, PgManifest.upperBound(prefix));
            statement.setString(3, after.map(EvidenceKey::path).orElse(""));
            statement.setLong(4, max);
            return readAll(statement);
          }
        });
  }

  List<RetentionRow> expiredRetention(Instant asOf, Optional<RetentionRow> after, int limit) {
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(after, "after");
    // the rule and the texts of the port, before the instant is bound (PgTime has its own text)
    RetentionClaim.requireStorable("asOf", asOf);
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    if (after.isPresent() && after.get().claim().retainUntil().isEmpty()) {
      throw new IllegalArgumentException("a row without a retain-until is not in the expiry list");
    }
    int max = Math.min(limit, RetentionLedger.MAX_RETENTION_ROWS_PER_CALL);
    if (max == 0) {
      return List.of();
    }
    OffsetDateTime bound = PgTime.timestamp(asOf);
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          String sql = after.isPresent() ? EXPIRED_AFTER_SQL : EXPIRED_SQL;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = 1;
            statement.setObject(next++, bound);
            statement.setString(next++, RetentionLedger.EXEMPT_PREFIX);
            if (after.isPresent()) {
              RetentionClaim row = after.get().claim();
              statement.setObject(next++, PgTime.timestamp(row.retainUntil().orElseThrow()));
              statement.setString(next++, row.artifact().path());
              statement.setString(next++, row.regime().id());
              statement.setShort(next++, Codes.of(row.anchor()));
            }
            statement.setLong(next, max);
            return readAll(statement);
          }
        });
  }

  // ---------------------------------------------------------------------------------------------
  // internals

  private static void requireDistinct(List<RetentionClaim> claims) {
    Set<List<Object>> seen = new HashSet<>();
    for (RetentionClaim claim : claims) {
      // the identity of a row inside one call: (artifact text, regime id text, anchor)
      if (!seen.add(List.of(claim.artifact().path(), claim.regime().id(), claim.anchor()))) {
        throw new IllegalArgumentException("two claims of one call have one artifact and anchor");
      }
    }
  }

  /**
   * The first statements of a unit that writes (see the class comment): the lock of each artifact,
   * one statement per artifact, in the order of {@code artifacts} (distinct, in key order). The key
   * is the schema and the artifact in one bind value, as {@code PgIngest#lockSource} does it for a
   * source, and the lock is held to the end of the transaction.
   */
  private void lockArtifacts(Connection connection, List<String> artifacts) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement(ARTIFACT_LOCK_SQL)) {
      for (String artifact : artifacts) {
        lock.setInt(1, RETENTION_LOCK_PURPOSE);
        lock.setString(2, config.schema() + ":" + artifact);
        lock.execute();
      }
    }
  }

  /** The claims, already in key order, as later statements than the locks. */
  private static void write(Connection connection, List<Write> writes, OffsetDateTime recordedAt)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(RECORD_SQL)) {
      for (Write each : writes) {
        insert.setString(1, each.artifact());
        insert.setString(2, each.regime());
        insert.setShort(3, each.anchor());
        insert.setString(4, each.retentionClass());
        if (each.retainUntil().isPresent()) {
          insert.setObject(5, each.retainUntil().get());
        } else {
          insert.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        insert.setString(6, each.artifact());
        insert.setObject(7, recordedAt);
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private static List<RetentionRow> readAll(PreparedStatement statement) throws SQLException {
    try (ResultSet rows = statement.executeQuery()) {
      List<RetentionRow> found = new ArrayList<>();
      while (rows.next()) {
        found.add(read(rows));
      }
      return List.copyOf(found);
    }
  }

  private static RetentionRow read(ResultSet rows) throws SQLException {
    try {
      EvidenceKey artifact = new EvidenceKey(rows.getString(1));
      Regime regime = new Regime(rows.getString(2));
      AnchorEvent anchor = Codes.anchorEvent(rows.getShort(3));
      String retentionClass = rows.getString(4);
      OffsetDateTime retainUntil = rows.getObject(5, OffsetDateTime.class);
      boolean legalHold = rows.getBoolean(6);
      Instant recordedAt = PgTime.instant(rows.getObject(7, OffsetDateTime.class));
      RetentionClaim claim =
          new RetentionClaim(
              artifact,
              regime,
              anchor,
              retainUntil == null ? Optional.empty() : Optional.of(PgTime.instant(retainUntil)),
              retentionClass);
      return new RetentionRow(claim, legalHold, recordedAt);
    } catch (IllegalArgumentException e) {
      throw new LedgerException("stored retention row is malformed", e);
    }
  }
}
