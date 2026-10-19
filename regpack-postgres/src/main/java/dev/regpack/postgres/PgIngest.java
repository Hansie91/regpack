package dev.regpack.postgres;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.IngestLedger.Commit;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import dev.regpack.ports.ledger.IngestLedger.Result;
import dev.regpack.ports.ledger.IngestLedger.StoredIngest;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.postgres.IngestDispositions.EventVersion;
import dev.regpack.postgres.IngestDispositions.Facts;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The ingest side of the ledger on PostgreSQL (spec 7.2 step 2, 10 rule 6; T15).
 *
 * <p>{@code commitIngest} is one unit. It takes the advisory lock of its source (Convention 12,
 * purpose 3; one lock per commit, held through the commit, so that the batches of one source run
 * strictly one after another and the reads below cannot be overtaken), then inserts the batch row
 * {@code ON CONFLICT DO NOTHING}: a conflict is a redelivery and returns the stored result. Every
 * disposition is decided from set-based reads over the whole commit before anything is inserted
 * ({@link IngestDispositions}); only the new entries are inserted, in one statement; the outbox
 * item, the checkpoint and the stored result follow in the same transaction.
 *
 * <p>The constraints stay the backstop (I2, D-L3): the insert names its arbiter {@code ingest_pk},
 * and a clash on {@code ingest_event_version_key} raises 23505, because the reads have already
 * decided every case that the keys could reject. A new entry that the insert skips anyway, or a
 * count that differs, fails the unit instead of being reported as stored.
 *
 * <p>The source is a dictionary row ({@code source}); its id is looked up once per unit.
 */
final class PgIngest {
  /** Advisory-lock purpose of the ingest source lock (Convention 12). */
  private static final int SOURCE_LOCK_PURPOSE = 3;

  private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(?, hashtext(?))";

  private static final String SOURCE_SELECT_SQL =
      "SELECT s.source_id FROM source s WHERE s.source = ?";

  private static final String SOURCE_INSERT_SQL =
      "INSERT INTO source (source) VALUES (?) ON CONFLICT ON CONSTRAINT source_name_key DO NOTHING";

  private static final String BATCH_INSERT_SQL =
      "INSERT INTO ingest_batch (batch_id, source_id, result, created_at) VALUES (?, ?, ?, ?)"
          + " ON CONFLICT ON CONSTRAINT ingest_batch_id_key DO NOTHING RETURNING batch_no";

  private static final String BATCH_UPDATE_SQL =
      "UPDATE ingest_batch SET result = ?, outbox_id = ? WHERE batch_no = ?";

  private static final String BATCH_SELECT_SQL =
      "SELECT s.source, b.result, b.outbox_id FROM ingest_batch b"
          + " JOIN source s ON s.source_id = b.source_id WHERE b.batch_id = ?";

  /** The exact keys of the commit that are stored (the primary key {@code ingest_pk}). */
  private static final String BY_KEY_SQL =
      "SELECT i.source_record_id, i.source_version, encode(i.content_hash, 'hex')"
          + " FROM unnest(?::text[], ?::int8[]) AS t(rec, ver)"
          + " JOIN ingest i ON i.source_record_id = t.rec AND i.source_version = t.ver"
          + " WHERE i.source_id = ?";

  /** The (event, source, version) pairs of the commit that are stored, under any record id. */
  private static final String BY_EVENT_VERSION_SQL =
      "SELECT i.event_identity, i.source_version, encode(i.content_hash, 'hex')"
          + " FROM unnest(?::text[], ?::int8[]) AS t(ev, ver)"
          + " JOIN ingest i ON i.event_identity = t.ev AND i.source_version = t.ver"
          + " WHERE i.source_id = ?";

  /** The events of the commit that another source has stored versions of. */
  private static final String OTHER_SOURCE_SQL =
      "SELECT t.ev FROM unnest(?::text[]) AS t(ev)"
          + " WHERE EXISTS (SELECT 1 FROM ingest i WHERE i.event_identity = t.ev"
          + " AND i.source_id <> ?)";

  /** The highest stored version per event of the commit for this source. */
  private static final String HIGHEST_SQL =
      "SELECT t.ev, (SELECT max(i.source_version) FROM ingest i"
          + " WHERE i.event_identity = t.ev AND i.source_id = ?)"
          + " FROM unnest(?::text[]) AS t(ev)";

  private static final String INSERT_NEW_SQL =
      "INSERT INTO ingest (source_id, source_record_id, source_version, event_identity,"
          + " content_hash, batch_no, landed_on)"
          + " SELECT ?, t.rec, t.ver, t.ev, decode(t.hx, 'hex'), ?, ?"
          + " FROM unnest(?::text[], ?::int8[], ?::text[], ?::text[]) AS t(rec, ver, ev, hx)"
          + " ON CONFLICT ON CONSTRAINT ingest_pk DO NOTHING";

  private static final String OUTBOX_INSERT_SQL =
      "INSERT INTO outbox (kind, subject_id, state) VALUES (?, ?, ?) RETURNING outbox_id";

  private static final String CHECKPOINT_UPSERT_SQL =
      "INSERT INTO ingest_checkpoint (source_id, stream, state, batch_no) VALUES (?, ?, ?, ?)"
          + " ON CONFLICT ON CONSTRAINT ingest_checkpoint_pk DO UPDATE"
          + " SET state = EXCLUDED.state, batch_no = EXCLUDED.batch_no";

  private static final String FIND_SQL =
      "SELECT i.event_identity, encode(i.content_hash, 'hex'), b.batch_id FROM source s"
          + " JOIN ingest i ON i.source_id = s.source_id"
          + " JOIN ingest_batch b ON b.batch_no = i.batch_no"
          + " WHERE s.source = ? AND i.source_record_id = ? AND i.source_version = ?";

  private static final String CHECKPOINT_SELECT_SQL =
      "SELECT c.state FROM source s JOIN ingest_checkpoint c ON c.source_id = s.source_id"
          + " WHERE s.source = ? AND c.stream = ?";

  private final DataSource dataSource;
  private final Clock clock;
  private final Config config;

  PgIngest(DataSource dataSource, Clock clock, Config config) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.config = config;
  }

  Result commitIngest(Commit commit) {
    Objects.requireNonNull(commit, "commit");
    validate(commit);
    Instant now = clock.now();
    return Unit.inUnit(dataSource, config, connection -> commitIn(connection, commit, now));
  }

  Optional<Result> ingestResult(BatchId batchId) {
    Objects.requireNonNull(batchId, "batchId");
    return Unit.inUnit(dataSource, config, connection -> storedResult(connection, batchId, false));
  }

  Optional<StoredIngest> findIngest(IngestKey key) {
    Objects.requireNonNull(key, "key");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, key.sourceId());
            statement.setString(2, key.sourceRecordId());
            statement.setLong(3, key.sourceVersion());
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.empty();
              }
              return Optional.of(
                  new StoredIngest(
                      key,
                      new EventIdentity(rows.getString(1)),
                      new Sha256(rows.getString(2)),
                      new BatchId(rows.getString(3))));
            }
          }
        });
  }

  Optional<Checkpoint> latestCheckpoint(String sourceId, String stream) {
    KeyText.require("sourceId", sourceId);
    KeyText.require("stream", stream);
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(CHECKPOINT_SELECT_SQL)) {
            statement.setString(1, sourceId);
            statement.setString(2, stream);
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.empty();
              }
              return Optional.of(new Checkpoint(stream, rows.getBytes(1)));
            }
          }
        });
  }

  /** The port's argument rules (M35, M39 and duplicate keys), before a connection is opened. */
  private static void validate(Commit commit) {
    if (commit.sourceId().isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    Set<IngestKey> seen = new HashSet<>();
    for (Entry entry : commit.entries()) {
      if (!entry.key().sourceId().equals(commit.sourceId())) {
        throw new IllegalArgumentException(
            "entry source " + entry.key().sourceId() + " differs from commit source");
      }
      if (!seen.add(entry.key())) {
        throw new IllegalArgumentException("duplicate ingest key in one commit: " + entry.key());
      }
    }
  }

  private Result commitIn(Connection connection, Commit commit, Instant now) throws SQLException {
    lockSource(connection, commit.sourceId());
    short sourceId = resolveSource(connection, commit.sourceId());
    OffsetDateTime at = PgTime.timestamp(now);
    OptionalLong inserted = insertBatch(connection, commit.batchId(), sourceId, at);
    if (inserted.isEmpty()) {
      // the batch id is taken: a redelivery writes nothing and returns the stored result
      return storedResult(connection, commit.batchId(), true)
          .orElseThrow(() -> new LedgerException("a batch id conflicted but no batch is stored"));
    }
    long batchNo = inserted.getAsLong();
    List<Entry> entries = commit.entries();
    List<Disposition> dispositions =
        IngestDispositions.decide(
            entries, loadFacts(connection, commit.sourceId(), sourceId, entries));
    insertNew(connection, sourceId, batchNo, at, entries, dispositions);
    long outboxId = insertOutbox(connection, commit.batchId());
    if (commit.checkpoint().isPresent()) {
      upsertCheckpoint(connection, sourceId, commit.checkpoint().get(), batchNo);
    }
    List<EntryResult> results = new ArrayList<>(entries.size());
    for (int i = 0; i < entries.size(); i++) {
      results.add(new EntryResult(entries.get(i).key(), dispositions.get(i)));
    }
    updateBatch(connection, batchNo, IngestResultCodec.encode(results), outboxId);
    return new Result(commit.batchId(), false, results, Optional.of(new OutboxId(outboxId)));
  }

  private void lockSource(Connection connection, String sourceName) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
      statement.setInt(1, SOURCE_LOCK_PURPOSE);
      statement.setString(2, config.schema() + ":" + sourceName);
      statement.execute();
    }
  }

  /** The dictionary id of the source, inserted on first use (the source lock is held). */
  private static short resolveSource(Connection connection, String sourceName) throws SQLException {
    OptionalInt id = selectSource(connection, sourceName);
    if (id.isEmpty()) {
      try (PreparedStatement insert = connection.prepareStatement(SOURCE_INSERT_SQL)) {
        insert.setString(1, sourceName);
        insert.executeUpdate();
      }
      id = selectSource(connection, sourceName);
    }
    return (short)
        id.orElseThrow(() -> new LedgerException("the source dictionary row is missing"));
  }

  private static OptionalInt selectSource(Connection connection, String sourceName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SOURCE_SELECT_SQL)) {
      statement.setString(1, sourceName);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalInt.of(rows.getShort(1)) : OptionalInt.empty();
      }
    }
  }

  /** The batch number of the new batch row, or empty when the batch id is already stored. */
  private static OptionalLong insertBatch(
      Connection connection, BatchId batchId, short sourceId, OffsetDateTime at)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(BATCH_INSERT_SQL)) {
      statement.setString(1, batchId.value());
      statement.setShort(2, sourceId);
      // the result is filled in when the dispositions are known (the only update of the row)
      statement.setBytes(3, new byte[0]);
      statement.setObject(4, at);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
      }
    }
  }

  private static void updateBatch(Connection connection, long batchNo, byte[] result, long outboxId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(BATCH_UPDATE_SQL)) {
      statement.setBytes(1, result);
      statement.setLong(2, outboxId);
      statement.setLong(3, batchNo);
      if (statement.executeUpdate() != 1) {
        throw new LedgerException("the batch row to complete was not found");
      }
    }
  }

  /** What the ledger holds for the entries of the commit, in four set-based reads. */
  private static Facts loadFacts(
      Connection connection, String sourceName, short sourceId, List<Entry> entries)
      throws SQLException {
    if (entries.isEmpty()) {
      return new Facts(Map.of(), Map.of(), Set.of(), Map.of());
    }
    int size = entries.size();
    String[] records = new String[size];
    Long[] versions = new Long[size];
    String[] events = new String[size];
    Set<String> distinctEvents = new LinkedHashSet<>();
    for (int i = 0; i < size; i++) {
      Entry entry = entries.get(i);
      records[i] = entry.key().sourceRecordId();
      versions[i] = entry.key().sourceVersion();
      events[i] = entry.eventIdentity().value();
      distinctEvents.add(events[i]);
    }
    Array recordArray = connection.createArrayOf("text", records);
    Array versionArray = connection.createArrayOf("int8", versions);
    Array eventArray = connection.createArrayOf("text", events);
    Array distinctEventArray =
        connection.createArrayOf("text", distinctEvents.toArray(String[]::new));

    Map<IngestKey, Sha256> byKey = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(BY_KEY_SQL)) {
      statement.setArray(1, recordArray);
      statement.setArray(2, versionArray);
      statement.setShort(3, sourceId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          byKey.put(
              new IngestKey(sourceName, rows.getString(1), rows.getLong(2)),
              new Sha256(rows.getString(3)));
        }
      }
    }
    Map<EventVersion, Sha256> byEventVersion = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(BY_EVENT_VERSION_SQL)) {
      statement.setArray(1, eventArray);
      statement.setArray(2, versionArray);
      statement.setShort(3, sourceId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          byEventVersion.put(
              new EventVersion(new EventIdentity(rows.getString(1)), rows.getLong(2)),
              new Sha256(rows.getString(3)));
        }
      }
    }
    Set<EventIdentity> ownedByAnotherSource = new HashSet<>();
    try (PreparedStatement statement = connection.prepareStatement(OTHER_SOURCE_SQL)) {
      statement.setArray(1, distinctEventArray);
      statement.setShort(2, sourceId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          ownedByAnotherSource.add(new EventIdentity(rows.getString(1)));
        }
      }
    }
    Map<EventIdentity, Long> highest = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(HIGHEST_SQL)) {
      statement.setShort(1, sourceId);
      statement.setArray(2, distinctEventArray);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          long version = rows.getLong(2);
          if (!rows.wasNull()) {
            highest.put(new EventIdentity(rows.getString(1)), version);
          }
        }
      }
    }
    return new Facts(byKey, byEventVersion, ownedByAnotherSource, highest);
  }

  /** Stores the new entries in one statement; any other entry stores nothing. */
  private static void insertNew(
      Connection connection,
      short sourceId,
      long batchNo,
      OffsetDateTime at,
      List<Entry> entries,
      List<Disposition> dispositions)
      throws SQLException {
    List<Entry> created = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      if (dispositions.get(i) == Disposition.NEW_VERSION) {
        created.add(entries.get(i));
      }
    }
    if (created.isEmpty()) {
      return;
    }
    int size = created.size();
    String[] records = new String[size];
    Long[] versions = new Long[size];
    String[] events = new String[size];
    String[] hashes = new String[size];
    for (int i = 0; i < size; i++) {
      Entry entry = created.get(i);
      records[i] = entry.key().sourceRecordId();
      versions[i] = entry.key().sourceVersion();
      events[i] = entry.eventIdentity().value();
      hashes[i] = entry.contentHash().hex();
    }
    try (PreparedStatement statement = connection.prepareStatement(INSERT_NEW_SQL)) {
      statement.setShort(1, sourceId);
      statement.setLong(2, batchNo);
      statement.setObject(3, at);
      statement.setArray(4, connection.createArrayOf("text", records));
      statement.setArray(5, connection.createArrayOf("int8", versions));
      statement.setArray(6, connection.createArrayOf("text", events));
      statement.setArray(7, connection.createArrayOf("text", hashes));
      if (statement.executeUpdate() != size) {
        // the reads under the source lock decided that every one of these is new
        throw new LedgerException("an ingest insert stored fewer rows than were decided as new");
      }
    }
  }

  private static long insertOutbox(Connection connection, BatchId batchId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(OUTBOX_INSERT_SQL)) {
      statement.setShort(1, Codes.of(OutboxKind.DECIDE_BATCH));
      statement.setString(2, batchId.value());
      statement.setShort(3, Codes.of(OutboxState.PENDING));
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new LedgerException("the outbox insert returned no id");
        }
        return rows.getLong(1);
      }
    }
  }

  private static void upsertCheckpoint(
      Connection connection, short sourceId, Checkpoint checkpoint, long batchNo)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CHECKPOINT_UPSERT_SQL)) {
      statement.setShort(1, sourceId);
      statement.setString(2, checkpoint.stream());
      statement.setBytes(3, checkpoint.state());
      statement.setLong(4, batchNo);
      statement.executeUpdate();
    }
  }

  /** The stored result of a batch, rebuilt from {@code ingest_batch}; empty if there is none. */
  private static Optional<Result> storedResult(
      Connection connection, BatchId batchId, boolean redelivery) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(BATCH_SELECT_SQL)) {
      statement.setString(1, batchId.value());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        String sourceName = rows.getString(1);
        byte[] stored = rows.getBytes(2);
        long outboxId = rows.getLong(3);
        boolean hasOutbox = !rows.wasNull();
        List<EntryResult> entries;
        try {
          entries = IngestResultCodec.decode(sourceName, stored);
        } catch (IllegalArgumentException e) {
          throw new LedgerException("a stored ingest result cannot be read", e);
        }
        return Optional.of(
            new Result(
                batchId,
                redelivery,
                entries,
                hasOutbox ? Optional.of(new OutboxId(outboxId)) : Optional.empty()));
      }
    }
  }
}
