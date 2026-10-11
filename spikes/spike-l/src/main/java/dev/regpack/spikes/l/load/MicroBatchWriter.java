package dev.regpack.spikes.l.load;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.spikes.l.encoding.PathCodec;
import dev.regpack.spikes.l.load.LedgerLoadModel.Candidate;
import dev.regpack.spikes.l.load.LedgerLoadModel.SyntheticPath;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes one micro-batch of candidates the way spec 7.2 step 2, 7.8 and 11.4 prescribe, in three
 * transactions:
 *
 * <ol>
 *   <li><b>synchronous</b>: the ingest rows (partitioned by trade date), the unpartitioned ingest
 *       registry, the stream checkpoint and one outbox row;
 *   <li><b>synchronous</b>: the decisions of the REPORT candidates with their {@code decision_head}
 *       (a compare-and-set on the decision sequence), the new report subjects, the report versions
 *       and the delivery rows, then the outbox item is completed;
 *   <li><b>asynchronous commit</b> ({@code SET LOCAL synchronous_commit = off}): the decisions of
 *       the candidates that are not reported, in bulk.
 * </ol>
 *
 * The time of a transaction (its statements and the commit that waits for the WAL flush and the
 * synchronous standby) is what {@link Timings} reports as its commit time, because that is what a
 * micro-batch costs the pipeline (L-2). A writer owns one connection and is not thread-safe.
 *
 * <p>Encodings: {@code E1_COMPACT} stores the packed path of the deciding table in {@code body}
 * ({@link PathCodec}); {@code E3_VERBOSE} stores every evaluated step as a {@code path jsonb}
 * document and leaves {@code body} NULL. The two are alternatives: the size of a variant is its own
 * and is compared with the other as it is.
 *
 * <p>Ids: the decision id of candidate {@code i} of batch {@code b} is {@code b << 24 | i} (so a
 * batch has fewer than 16.7 million candidates and batch ids are unique within a schema); the
 * delivery id is the decision id times 8 plus the leg; a report subject id is the event identity
 * with its three low bits replaced by the leg. Decisions of a superseding report carry decision
 * sequence 2 and report version 2: an identity is superseded at most once per run.
 */
public final class MicroBatchWriter implements BurstRunner.BatchSink {
  /** How rows reach PostgreSQL. */
  public enum Method {
    COPY,
    BATCHED_INSERT
  }

  /**
   * Time and size of one batch.
   *
   * @param ingestCommitNanos transaction 1, synchronous
   * @param reportCommitNanos transaction 2, synchronous
   * @param asyncCommitNanos transaction 3, asynchronous commit
   * @param rows rows written by the three transactions (head upserts, the checkpoint and the outbox
   *     row included, the outbox completion not)
   */
  public record Timings(
      long ingestCommitNanos, long reportCommitNanos, long asyncCommitNanos, long rows) {}

  /** A value that is a jsonb document, so that the loaders can tell it from text. */
  record Jsonb(String text) {}

  static final String REGIME = "EMIR";
  private static final long PATH_SEED = 17L;
  private static final long CONTEXT_ID = 1L;
  private static final int SOURCE_ID = 1;
  private static final int STREAMS = 4;
  private static final int ENDPOINTS = 4;
  private static final int MAX_BATCH = 1 << 24;
  private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  private static final List<String> INGEST_COLUMNS =
      List.of("source_id", "record_id", "version", "trade_date", "content_hash", "batch_id");
  private static final List<String> REGISTRY_COLUMNS =
      List.of("source_id", "record_id", "version", "content_hash");
  private static final List<String> DECISION_COLUMNS =
      List.of(
          "decision_id",
          "regime",
          "event_identity",
          "decision_seq",
          "decision_context_id",
          "input_digest",
          "as_at",
          "outcome",
          "body");
  private static final List<String> SUBJECT_COLUMNS =
      List.of("subject_id", "regime", "event_identity", "leg");
  private static final List<String> VERSION_COLUMNS =
      List.of("subject_id", "version_seq", "decision_id", "state");
  private static final List<String> DELIVERY_COLUMNS =
      List.of("delivery_id", "endpoint_id", "business_key", "state", "gate_released");

  private final NanoClock clock;
  private final Connection connection;
  private final Variant variant;
  private final BulkLoader loader;
  private final String qualified;
  private final String checkpointSql;
  private final String outboxInsertSql;
  private final String outboxCompleteSql;
  private final String headSql;
  private final List<String> decisionColumns;

  /** A writer of the E1 (compact) encoding. */
  public MicroBatchWriter(Connection connection, String schema, Method method) throws SQLException {
    this(connection, schema, method, Variant.E1_COMPACT);
  }

  /**
   * A writer into the tables of {@code schema} (see {@link
   * dev.regpack.spikes.l.schema.SchemaInstaller}); puts the connection into manual commit mode.
   *
   * @throws IllegalArgumentException if the schema name is not a lower-case identifier
   */
  public MicroBatchWriter(Connection connection, String schema, Method method, Variant variant)
      throws SQLException {
    this(
        connection,
        schema,
        method,
        variant,
        method == Method.COPY ? BulkLoader.copy(connection) : BulkLoader.batchedInsert(connection));
  }

  MicroBatchWriter(
      Connection connection, String schema, Method method, Variant variant, BulkLoader loader)
      throws SQLException {
    this(connection, schema, method, variant, loader, NanoClock.system());
  }

  MicroBatchWriter(
      Connection connection,
      String schema,
      Method method,
      Variant variant,
      BulkLoader loader,
      NanoClock clock)
      throws SQLException {
    if (!SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "a schema name must match [a-z][a-z0-9_]{0,62}: '" + schema + "'");
    }
    this.clock = clock;
    this.connection = connection;
    this.variant = variant;
    this.loader = loader;
    this.qualified = "\"" + schema + "\".";
    List<String> columns = new ArrayList<>(DECISION_COLUMNS);
    if (variant == Variant.E3_VERBOSE) {
      columns.add("path");
    }
    this.decisionColumns = List.copyOf(columns);
    this.checkpointSql =
        "INSERT INTO "
            + qualified
            + "ingest_checkpoint (source_id, stream, stream_offset, batch_id) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (source_id, stream) DO UPDATE"
            + " SET stream_offset = EXCLUDED.stream_offset, batch_id = EXCLUDED.batch_id"
            + " WHERE ingest_checkpoint.stream_offset < EXCLUDED.stream_offset";
    this.outboxInsertSql =
        "INSERT INTO " + qualified + "outbox (kind, batch_id) VALUES (1, ?) RETURNING outbox_id";
    this.outboxCompleteSql =
        "UPDATE " + qualified + "outbox SET state = 1 WHERE outbox_id = ? AND state = 0";
    // the compare-and-set of the head: a decision only replaces the head when its sequence is newer
    this.headSql =
        "INSERT INTO "
            + qualified
            + "decision_head (regime, event_identity, decision_seq, decision_id)"
            + " SELECT '"
            + REGIME
            + "', u.event_identity, u.decision_seq, u.decision_id"
            + " FROM unnest(?::text[], ?::bigint[], ?::bigint[])"
            + " AS u(event_identity, decision_seq, decision_id)"
            + " WHERE true ORDER BY u.event_identity"
            + " ON CONFLICT (regime, event_identity) DO UPDATE"
            + " SET decision_seq = EXCLUDED.decision_seq, decision_id = EXCLUDED.decision_id"
            + " WHERE decision_head.decision_seq < EXCLUDED.decision_seq";
    connection.setAutoCommit(false);
  }

  /**
   * Writes the batch in its three transactions.
   *
   * @param batchId unique within the schema; names the stream, the outbox row and the ids
   * @throws SQLException if a statement fails; the open transaction is rolled back first (the
   *     transactions that already committed stay committed)
   */
  @Override
  public Timings write(long batchId, List<Candidate> batch) throws SQLException {
    if (batch.size() >= MAX_BATCH) {
      throw new IllegalArgumentException("a batch holds fewer than " + MAX_BATCH + " candidates");
    }
    try {
      Counter rows = new Counter();
      long ingest = ingestTransaction(batchId, batch, rows);
      long report = reportTransaction(batchId, batch, rows);
      long async = asyncTransaction(batchId, batch, rows);
      return new Timings(ingest, report, async, rows.value);
    } catch (SQLException | RuntimeException e) {
      try {
        connection.rollback();
      } catch (SQLException rollback) {
        e.addSuppressed(rollback);
      }
      throw e;
    }
  }

  private static final class Counter {
    long value;
    long outboxId;
  }

  private long ingestTransaction(long batchId, List<Candidate> batch, Counter counter)
      throws SQLException {
    List<Object[]> ingest = new ArrayList<>(batch.size());
    List<Object[]> registry = new ArrayList<>(batch.size());
    for (Candidate c : batch) {
      byte[] hash = hash32(Long.parseUnsignedLong(c.sourceRecordId(), 16) ^ 0xC0DEC0DEL);
      int version = (int) c.record().sourceVersion();
      ingest.add(
          new Object[] {
            SOURCE_ID, c.sourceRecordId(), version, c.record().tradeDate(), hash, batchId
          });
      registry.add(new Object[] {SOURCE_ID, c.sourceRecordId(), version, hash});
    }
    long t0 = clock.nanoTime(); // the rows are built: the timer covers statements and commit only
    load("ingest", INGEST_COLUMNS, ingest, counter);
    load("ingest_registry", REGISTRY_COLUMNS, registry, counter);
    try (PreparedStatement ps = connection.prepareStatement(checkpointSql)) {
      ps.setString(2, "feed-" + (batchId % STREAMS));
      ps.setLong(3, batchId * MAX_BATCH + batch.size());
      ps.setLong(4, batchId);
      ps.setInt(1, SOURCE_ID);
      ps.executeUpdate();
    }
    counter.value++;
    try (PreparedStatement ps = connection.prepareStatement(outboxInsertSql)) {
      ps.setLong(1, batchId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new SQLException("the outbox insert returned no id");
        }
        counter.outboxId = rs.getLong(1);
      }
    }
    counter.value++;
    connection.commit();
    return clock.nanoTime() - t0;
  }

  private long reportTransaction(long batchId, List<Candidate> batch, Counter counter)
      throws SQLException {
    List<Object[]> decisions = new ArrayList<>();
    List<Object[]> subjects = new ArrayList<>();
    List<Object[]> versions = new ArrayList<>();
    List<Object[]> deliveries = new ArrayList<>();
    List<long[]> heads = new ArrayList<>(); // {decision_seq, decision_id} per REPORT candidate
    List<String> headIdentities = new ArrayList<>();
    for (int i = 0; i < batch.size(); i++) {
      Candidate c = batch.get(i);
      if (!c.report()) {
        continue;
      }
      long decisionId = decisionId(batchId, i);
      decisions.add(decisionRow(c, decisionId));
      long identity = Long.parseUnsignedLong(c.eventIdentity(), 16);
      int versionSeq = c.superseding() ? 2 : 1;
      for (int leg = 0; leg < c.legs(); leg++) {
        long subjectId = subjectId(identity, leg);
        if (!c.superseding()) {
          subjects.add(new Object[] {subjectId, REGIME, c.eventIdentity(), leg});
        }
        versions.add(new Object[] {subjectId, versionSeq, decisionId, 0});
        deliveries.add(
            new Object[] {
              decisionId * 8 + leg,
              1 + (int) ((identity >>> 8) & (ENDPOINTS - 1)),
              c.eventIdentity() + "-" + leg,
              "QUEUED",
              false
            });
      }
      heads.add(new long[] {versionSeq, decisionId});
      headIdentities.add(c.eventIdentity());
    }
    long t0 = clock.nanoTime(); // the rows are built: the timer covers statements and commit only
    load("decision", decisionColumns, decisions, counter);
    upsertHeads(headIdentities, heads);
    counter.value += heads.size();
    load("report_subject", SUBJECT_COLUMNS, subjects, counter);
    load("report_version", VERSION_COLUMNS, versions, counter);
    load("delivery", DELIVERY_COLUMNS, deliveries, counter);
    try (PreparedStatement ps = connection.prepareStatement(outboxCompleteSql)) {
      ps.setLong(1, counter.outboxId);
      ps.executeUpdate();
    }
    connection.commit();
    return clock.nanoTime() - t0;
  }

  private long asyncTransaction(long batchId, List<Candidate> batch, Counter counter)
      throws SQLException {
    List<Object[]> decisions = new ArrayList<>();
    for (int i = 0; i < batch.size(); i++) {
      Candidate c = batch.get(i);
      if (!c.report()) {
        decisions.add(decisionRow(c, decisionId(batchId, i)));
      }
    }
    long t0 = clock.nanoTime(); // the rows are built: the timer covers statements and commit only
    try (Statement st = connection.createStatement()) {
      st.execute("SET LOCAL synchronous_commit = off");
    }
    load("decision", decisionColumns, decisions, counter);
    connection.commit();
    return clock.nanoTime() - t0;
  }

  /** Sorted by identity so that two batches that touch the same heads lock them in one order. */
  private void upsertHeads(List<String> identities, List<long[]> heads) throws SQLException {
    int n = identities.size();
    if (n == 0) {
      return;
    }
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    Arrays.sort(order, Comparator.comparing(identities::get));
    String[] ids = new String[n];
    Long[] seqs = new Long[n];
    Long[] decisionIds = new Long[n];
    for (int i = 0; i < n; i++) {
      int source = order[i];
      ids[i] = identities.get(source);
      if (i > 0 && ids[i].equals(ids[i - 1])) {
        throw new IllegalArgumentException("event identity twice in one batch: " + ids[i]);
      }
      seqs[i] = heads.get(source)[0];
      decisionIds[i] = heads.get(source)[1];
    }
    Array idArray = connection.createArrayOf("text", ids);
    Array seqArray = connection.createArrayOf("int8", seqs);
    Array decisionArray = connection.createArrayOf("int8", decisionIds);
    try (PreparedStatement ps = connection.prepareStatement(headSql)) {
      ps.setArray(1, idArray);
      ps.setArray(2, seqArray);
      ps.setArray(3, decisionArray);
      ps.executeUpdate();
    }
  }

  private void load(String table, List<String> columns, List<Object[]> rows, Counter counter)
      throws SQLException {
    if (rows.isEmpty()) {
      return;
    }
    loader.load(qualified + table, columns, rows);
    counter.value += rows.size();
  }

  private Object[] decisionRow(Candidate c, long decisionId) {
    SyntheticPath path = LedgerLoadModel.syntheticPath(PATH_SEED, c.record().seq());
    // E1 keeps the packed path in body; E3 keeps the verbose path in path and no body, so that the
    // size of a variant is its own and the two are alternatives, not E1 plus something
    byte[] body =
        variant == Variant.E1_COMPACT
            ? PathCodec.encode(path.ruleOrdinals(), path.matched(), path.costUnits())
            : null;
    long sourceId = Long.parseUnsignedLong(c.sourceRecordId(), 16);
    LocalDate asAt = c.record().tradeDate();
    long seq = c.superseding() ? 2L : 1L;
    int outcome = c.report() ? 1 : 2 + (int) (c.record().seq() % 3);
    Object[] base = {
      decisionId,
      REGIME,
      c.eventIdentity(),
      seq,
      CONTEXT_ID,
      hash32(sourceId ^ 0xD16E57D16E57L),
      asAt,
      outcome,
      body
    };
    if (variant == Variant.E1_COMPACT) {
      return base;
    }
    Object[] verbose = Arrays.copyOf(base, base.length + 1);
    verbose[base.length] = new Jsonb(path.verboseJson());
    return verbose;
  }

  /** The decision id of candidate {@code index} of batch {@code batchId}. */
  static long decisionId(long batchId, int index) {
    return (batchId << 24) | index;
  }

  /** The id of the report subject of leg {@code leg} (0 to 7) of an event identity. */
  static long subjectId(long identity, int leg) {
    return (identity & ~7L) | leg;
  }

  /** 32 pseudo-random bytes derived from {@code value} (a stand-in for a SHA-256). */
  static byte[] hash32(long value) {
    SplitMix64 rng = new SplitMix64(value);
    byte[] out = new byte[32];
    for (int i = 0; i < 4; i++) {
      long word = rng.nextLong();
      for (int b = 0; b < 8; b++) {
        out[i * 8 + b] = (byte) (word >>> (56 - 8 * b));
      }
    }
    return out;
  }
}
