package dev.regpack.postgres;

import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ManifestConflictException;
import dev.regpack.ports.ledger.ManifestLedger;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The object manifest on PostgreSQL (spec 5.4 and 11.1; migration V002, D-E6): for every object the
 * ledger has written to the evidence tier, its key and the hash of the bytes as stored at write
 * time. One key has one hash for ever. The table has the key as its primary key, compares it byte
 * by byte ({@code COLLATE "C"}), is insert-only (a trigger) and has no foreign key to the attempts.
 *
 * <p><b>Writing.</b> {@link #record} inserts every entry with {@code ON CONFLICT ON CONSTRAINT
 * object_manifest_pk DO NOTHING} (the constraint is the arbiter, so a schema without it fails with
 * a missing-arbiter error and not with a silent second row: the schema mutant {@code
 * NoManifestKeyPgLedgerContractIT}), reads the stored hashes of the keys of the call and compares
 * them, in Java, with the hashes of the entries. The comparison is all Java does: that a key has
 * one row is the constraint's (D-L3, LF1). A key that is stored with another hash is a {@link
 * ManifestConflictException} and the caller's unit rolls back, so nothing of the call is stored; a
 * key that is stored with the same hash is left as it is (the first row wins). The entries are
 * inserted in key order, so that two units that write overlapping sets of keys never wait for each
 * other in a circle. The read that follows the insert is a new statement of a {@code READ
 * COMMITTED} transaction, so it sees a row that a concurrent unit committed while this unit waited
 * for it.
 *
 * <p><b>Reading.</b> {@link #manifestRows} is a range on the primary key: {@code object_key >=
 * prefix AND object_key < prefix || U+007F AND object_key > after ORDER BY object_key LIMIT n}.
 * Every key is made of characters below U+007F ({@link EvidenceKey}), so the range holds exactly
 * the keys that start with the prefix; in the {@code "C"} collation the order is that of the bytes,
 * which for these characters is the code-unit order of {@code EvidenceStore#list}.
 *
 * <p>Every value is a bind parameter and no message carries a value (Convention 12); a conflict
 * names the key path only (Convention 8). The methods that take a {@link Connection} work in the
 * caller's transaction and commit nothing.
 */
final class PgManifest {
  /** Parameters: key, class, hash, size, provenance, attempt (nullable), written at. */
  static final String RECORD_SQL =
      "INSERT INTO object_manifest (object_key, entity_id, object_class, sha256, size_bytes,"
          + " provenance, attempt_id, written_at) VALUES (?, 1, ?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT ON CONSTRAINT object_manifest_pk DO NOTHING";

  /** Parameter: a {@code text[]} of the keys of the call. */
  static final String STORED_SQL =
      "SELECT object_key, sha256 FROM object_manifest WHERE object_key = ANY (?)";

  private static final String COLUMNS =
      "object_key, object_class, sha256, size_bytes, provenance, attempt_id, written_at";

  /** Parameter: the key. */
  static final String ROW_SQL = "SELECT " + COLUMNS + " FROM object_manifest WHERE object_key = ?";

  /** Parameters: the prefix, its upper bound, the key after which to list (or ''), the limit. */
  static final String ROWS_SQL =
      "SELECT "
          + COLUMNS
          + " FROM object_manifest WHERE object_key >= ? AND object_key < ? AND object_key > ?"
          + " ORDER BY object_key LIMIT ?";

  /**
   * The character that ends the range of a prefix: above every character of an {@link EvidenceKey}
   * (the largest of them is {@code z}, U+007A) and one byte in UTF-8, so in the byte order of the
   * {@code "C"} collation the keys that start with a prefix {@code p} are exactly those in {@code
   * [p, p + U+007F)}.
   */
  private static final char RANGE_END = (char) 0x7F;

  private final DataSource dataSource;
  private final Clock clock;
  private final Config config;

  PgManifest(DataSource dataSource, Clock clock, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.config = Objects.requireNonNull(config, "config");
  }

  // ---------------------------------------------------------------------------------------------
  // the port

  void recordObjects(
      List<ObjectEntry> entries, Provenance provenance, Optional<AttemptId> attempt) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(attempt, "attempt");
    List<ObjectEntry> copy = List.copyOf(entries);
    requireDistinctKeys(copy);
    if (copy.isEmpty()) {
      return;
    }
    OffsetDateTime writtenAt = PgTime.timestamp(clock.now());
    Unit.inUnit(
        dataSource,
        config,
        connection -> {
          record(connection, writtenAt, copy, provenance, attempt);
          return Boolean.TRUE;
        });
  }

  Optional<ManifestRow> manifestRow(EvidenceKey key) {
    Objects.requireNonNull(key, "key");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(ROW_SQL)) {
            statement.setString(1, key.path());
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
          }
        });
  }

  List<ManifestRow> manifestRows(String prefix, Optional<EvidenceKey> after, int limit) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    int max = Math.min(limit, ManifestLedger.MAX_ROWS_PER_CALL);
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
            statement.setString(2, upperBound(prefix));
            statement.setString(3, after.map(EvidenceKey::path).orElse(""));
            statement.setLong(4, max);
            try (ResultSet rows = statement.executeQuery()) {
              List<ManifestRow> found = new ArrayList<>();
              while (rows.next()) {
                found.add(read(rows));
              }
              return List.copyOf(found);
            }
          }
        });
  }

  // ---------------------------------------------------------------------------------------------
  // in the caller's unit

  /**
   * Records {@code entries} in the caller's transaction (see the class comment). The keys of the
   * list must be distinct: {@link #recordObjects} checks it, an {@code AttemptIntent} guarantees
   * it.
   *
   * @param writtenAt the ledger time of a new row; one value for the whole call
   * @throws ManifestConflictException when a key is stored with another hash; the caller must roll
   *     its transaction back, which removes the rows that this call inserted before it found out
   */
  static void record(
      Connection connection,
      OffsetDateTime writtenAt,
      List<ObjectEntry> entries,
      Provenance provenance,
      Optional<AttemptId> attempt)
      throws SQLException {
    if (entries.isEmpty()) {
      return;
    }
    short provenanceCode = Codes.of(provenance);
    List<ObjectEntry> inKeyOrder = new ArrayList<>(entries);
    inKeyOrder.sort(Comparator.comparing(entry -> entry.key().path()));
    try (PreparedStatement insert = connection.prepareStatement(RECORD_SQL)) {
      for (ObjectEntry entry : inKeyOrder) {
        insert.setString(1, entry.key().path());
        insert.setShort(2, Codes.of(entry.objectClass()));
        insert.setBytes(3, hashBytes(entry.sha256()));
        insert.setLong(4, entry.sizeBytes());
        insert.setShort(5, provenanceCode);
        if (attempt.isPresent()) {
          insert.setLong(6, attempt.get().value());
        } else {
          insert.setNull(6, Types.BIGINT);
        }
        insert.setObject(7, writtenAt);
        insert.addBatch();
      }
      insert.executeBatch();
    }
    Map<String, byte[]> stored = storedHashes(connection, entries);
    for (ObjectEntry entry : entries) {
      byte[] hash = stored.get(entry.key().path());
      if (hash == null) {
        // cannot happen: after the insert every key has a row, ours or an earlier one
        throw new LedgerException(
            "object key has no manifest row after its insert: " + entry.key().path());
      }
      if (!Arrays.equals(hash, hashBytes(entry.sha256()))) {
        throw new ManifestConflictException(
            "object key is recorded with another hash: " + entry.key().path());
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // internals

  /** The upper bound (exclusive) of the keys that start with {@code prefix}. */
  static String upperBound(String prefix) {
    return prefix + RANGE_END;
  }

  private static void requireDistinctKeys(List<ObjectEntry> entries) {
    Set<EvidenceKey> keys = new HashSet<>();
    for (ObjectEntry entry : entries) {
      if (!keys.add(entry.key())) {
        throw new IllegalArgumentException("two entries of one call have one key");
      }
    }
  }

  private static Map<String, byte[]> storedHashes(Connection connection, List<ObjectEntry> entries)
      throws SQLException {
    String[] paths = new String[entries.size()];
    for (int i = 0; i < paths.length; i++) {
      paths[i] = entries.get(i).key().path();
    }
    Map<String, byte[]> stored = new HashMap<>();
    try (PreparedStatement select = connection.prepareStatement(STORED_SQL)) {
      Array keys = connection.createArrayOf("text", paths);
      select.setArray(1, keys);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          stored.put(rows.getString(1), rows.getBytes(2));
        }
      }
    }
    return stored;
  }

  private static ManifestRow read(ResultSet rows) throws SQLException {
    try {
      EvidenceKey key = new EvidenceKey(rows.getString(1));
      ObjectClass objectClass = Codes.objectClass(rows.getShort(2));
      Sha256 sha256 = new Sha256(HexFormat.of().formatHex(rows.getBytes(3)));
      long sizeBytes = rows.getLong(4);
      Provenance provenance = Codes.provenance(rows.getShort(5));
      long attemptId = rows.getLong(6);
      Optional<AttemptId> attempt =
          rows.wasNull() ? Optional.empty() : Optional.of(new AttemptId(attemptId));
      Instant writtenAt = PgTime.instant(rows.getObject(7, OffsetDateTime.class));
      return new ManifestRow(
          new ObjectEntry(key, objectClass, sha256, sizeBytes), provenance, attempt, writtenAt);
    } catch (IllegalArgumentException e) {
      throw new LedgerException("stored manifest row is malformed", e);
    }
  }

  private static byte[] hashBytes(Sha256 sha256) {
    return HexFormat.of().parseHex(sha256.hex());
  }
}
