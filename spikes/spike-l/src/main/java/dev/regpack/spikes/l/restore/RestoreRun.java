package dev.regpack.spikes.l.restore;

import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.spikes.l.load.BurstRunner;
import dev.regpack.spikes.l.load.BurstRunner.Monitor;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.RunResult;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One restore point: the time to bring a ledger of {@code keys} keys back from a base backup, the
 * WAL archive and the gap objects of the evidence journal, cold cache, fsync on (spec 11.4; review
 * focus RF13). The point is a measurement of the whole sequence of spec 11.4, step by step:
 *
 * <ol>
 *   <li>load {@code keys} keys server side (a decision and a head each, a report subject and
 *       version for every second key) into the schema {@code sp_restore}; {@code CHECKPOINT};
 *   <li>{@code pg-standby.sh drop} (frees the disk), {@code CHECKPOINT}, {@code pg-basebackup.sh}
 *       (uncompressed, then {@code sync});
 *   <li>the gap: ingest through the burst runner (3,470 records/s, archive on), then the content
 *       counts and digest are taken, {@code pg_switch_wal()} and the wait until the WAL archiver
 *       has archived it;
 *   <li>{@code pg-restore.sh}: stop, remove, drop the cache (before the timer); copy, sync, start
 *       and recovery to its end (inside it);
 *   <li>{@code SHOW fsync}; apply the gap objects of the journal (idempotent by key); {@code
 *       ANALYZE}; compare the counts and the digest with those of step 3 and apply the gap objects
 *       a second time (it must add nothing).
 * </ol>
 *
 * The fixture must have been started fresh ({@code pg-restart.sh sp-l-restore}) before a point: a
 * point drops the standby and the replica, so the cluster runs without a synchronous standby
 * afterwards.
 */
public final class RestoreRun {
  /** The schema of the restore study. */
  public static final String SCHEMA = "sp_restore";

  /** The rate of the gap ingest, records per second (the burst of spec 5.6). */
  public static final double GAP_RECORDS_PER_SECOND = 3_470.0;

  /** The micro-batch size of the gap ingest. */
  public static final int GAP_BATCH = 5_000;

  /** Class (b) journal objects per micro-batch of the gap (one of each of four types). */
  static final int OBJECTS_PER_GAP_BATCH = 4;

  /** The keys that one decision id range can hold before it meets the ids of the gap ingest. */
  static final long MAX_KEYS = 16_000_000L;

  private static final String BACKUP = "/mnt/spike-pg/backup";
  private static final String ARCHIVE = "/mnt/spike-pg/archive";
  private static final long GAP_SEED = 20_261_012L;
  private static final long OBJECT_SEED = 20_261_013L;
  private static final int DAYS = 30;
  private static final long CHUNK = 1_000_000L;
  private static final Duration ARCHIVE_WAIT = Duration.ofMinutes(5);

  private RestoreRun() {}

  /** What a point is worth. */
  public enum Quality {
    /** Cold cache, fsync on: the point may feed L-6 and L-7. */
    VALID,
    /**
     * The page cache was not dropped: the time is a lower bound, the point never feeds L-6 or L-7.
     */
    INCONCLUSIVE,
    /** fsync was off: the time is not a restore time. */
    INVALID
  }

  /**
   * What {@code pg-restore.sh} printed.
   *
   * @param cacheDropped whether the page cache was dropped before the timer
   * @param copyNanos copy, ownership change and sync (contains {@code syncNanos})
   * @param syncNanos the sync alone
   * @param replayNanos from the end of the sync to the end of recovery
   */
  public record ScriptTimes(
      boolean cacheDropped, long copyNanos, long syncNanos, long replayNanos) {}

  /**
   * One restore point.
   *
   * @param keys the number of keys that were restored
   * @param cacheDropped whether the page cache was dropped before the copy
   * @param fsyncOn whether {@code SHOW fsync} said on after the restore
   * @param copyNanos copy, ownership change and sync; it <b>includes</b> {@code syncNanos}
   * @param syncNanos the sync alone
   * @param replayNanos start-up and WAL replay to the end of recovery
   * @param gapObjectsNanos applying the gap objects of the evidence journal
   * @param analyzeNanos {@code ANALYZE}
   * @param verifyNanos comparing counts and digest with the values taken before the stop
   */
  public record Point(
      long keys,
      boolean cacheDropped,
      boolean fsyncOn,
      long copyNanos,
      long syncNanos,
      long replayNanos,
      long gapObjectsNanos,
      long analyzeNanos,
      long verifyNanos) {
    public Point {
      if (keys <= 0) {
        throw new IllegalArgumentException("keys must be positive: " + keys);
      }
      if (copyNanos < 0
          || syncNanos < 0
          || replayNanos < 0
          || gapObjectsNanos < 0
          || analyzeNanos < 0
          || verifyNanos < 0) {
        throw new IllegalArgumentException("a time must not be negative");
      }
      if (syncNanos > copyNanos) {
        throw new IllegalArgumentException(
            "the sync (" + syncNanos + " ns) is part of the copy (" + copyNanos + " ns)");
      }
    }

    /**
     * The restore time: copy (which includes the sync), replay, gap objects, analyze and verify.
     */
    public long rtoNanos() {
      return Math.addExact(
          Math.addExact(Math.addExact(copyNanos, replayNanos), gapObjectsNanos),
          Math.addExact(analyzeNanos, verifyNanos));
    }

    /** INVALID without fsync, else INCONCLUSIVE without a dropped cache, else VALID. */
    public Quality quality() {
      if (!fsyncOn) {
        return Quality.INVALID;
      }
      return cacheDropped ? Quality.VALID : Quality.INCONCLUSIVE;
    }

    /** Why the point is not VALID, or an empty text. */
    public String reason() {
      return switch (quality()) {
        case INVALID -> "fsync was off after the restore: the time is not a restore time";
        case INCONCLUSIVE ->
            "the page cache was not dropped before the copy: the time is a lower bound (cold cache is required)";
        case VALID -> "";
      };
    }
  }

  /**
   * A point with the size of the gap that it replayed.
   *
   * @param point the restore point
   * @param gapRecords records ingested between the backup and the stop (the replay rate is {@code
   *     replayNanos / gapRecords})
   * @param gapObjects journal objects that were applied from the evidence journal
   */
  public record Outcome(Point point, long gapRecords, long gapObjects) {}

  // ------------------------------------------------------------------ pure parts

  /**
   * Reads the lines that {@code pg-restore.sh} printed; the last value of each key counts and other
   * lines are ignored.
   *
   * @throws IllegalArgumentException if a value is missing or malformed
   */
  public static ScriptTimes parseRestoreOutput(List<String> lines) {
    String cache = lastValue(lines, "cache_dropped");
    if (!cache.equals("0") && !cache.equals("1")) {
      throw new IllegalArgumentException("cache_dropped must be 0 or 1: " + cache);
    }
    return new ScriptTimes(
        cache.equals("1"),
        nanos(lines, "copy_nanos"),
        nanos(lines, "sync_nanos"),
        nanos(lines, "replay_nanos"));
  }

  private static long nanos(List<String> lines, String key) {
    String value = lastValue(lines, key);
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " is not a number: " + value, e);
    }
  }

  private static String lastValue(List<String> lines, String key) {
    String found = null;
    for (String line : lines) {
      String t = line.strip();
      if (t.startsWith(key + "=")) {
        found = t.substring(key.length() + 1).strip();
      }
    }
    if (found == null) {
      throw new IllegalArgumentException("pg-restore.sh printed no " + key);
    }
    return found;
  }

  /**
   * Builds a point from what the script printed, {@code SHOW fsync} and the times of the three
   * steps that run after the restore.
   */
  public static Point point(
      long keys,
      ScriptTimes script,
      String showFsync,
      long gapObjectsNanos,
      long analyzeNanos,
      long verifyNanos) {
    return new Point(
        keys,
        script.cacheDropped(),
        "on".equals(showFsync.strip().toLowerCase(Locale.ROOT)),
        script.copyNanos(),
        script.syncNanos(),
        script.replayNanos(),
        gapObjectsNanos,
        analyzeNanos,
        verifyNanos);
  }

  /** The number of gap objects of a gap of {@code records} records. */
  static int gapObjects(long records) {
    long batches = Math.floorDiv(records + GAP_BATCH - 1, GAP_BATCH);
    return Math.toIntExact(batches * OBJECTS_PER_GAP_BATCH);
  }

  // ------------------------------------------------------------------ the run

  /** The point, without the size of the gap. See {@link #runWithGap}. */
  public static Point run(PgFixture pg, long keys, Duration gapIngest)
      throws SQLException, IOException, InterruptedException {
    return runWithGap(pg, keys, gapIngest).point();
  }

  /**
   * Runs one restore point on the fixture and returns it with the size of its gap.
   *
   * @throws IllegalStateException if the restored content differs from the content before the stop
   *     (a restore that loses data is a failure, not a slow point)
   */
  public static Outcome runWithGap(PgFixture pg, long keys, Duration gapIngest)
      throws SQLException, IOException, InterruptedException {
    return execute(pg, keys, gapIngest, Shell.system(), NanoClock.system());
  }

  static Outcome execute(PgFixture pg, long keys, Duration gapIngest, Shell shell, NanoClock clock)
      throws SQLException, IOException, InterruptedException {
    Objects.requireNonNull(pg, "pg");
    if (keys <= 0 || keys > MAX_KEYS) {
      throw new IllegalArgumentException("keys must be in [1, " + MAX_KEYS + "]: " + keys);
    }
    if (gapIngest.isZero() || gapIngest.isNegative()) {
      throw new IllegalArgumentException("the gap ingest must take time: " + gapIngest);
    }
    LocalDate day = new TradeRecordGen(1).next().tradeDate();

    Content before;
    long gapRecords;
    try (Connection c = pg.primary()) {
      c.setAutoCommit(true);
      SchemaInstaller.drop(c, SCHEMA);
      SchemaInstaller.install(c, SCHEMA, Variant.E1_COMPACT, day, DAYS);
      JournalGapReplay.createTable(c, SCHEMA);
      loadKeys(c, keys, day);
      checkpoint(c);

      shell.script("pg-standby.sh", "drop");
      checkpoint(c);
      shell.script("pg-basebackup.sh", BACKUP);

      RunResult gap = ingestGap(pg, day, gapIngest, clock);
      gapRecords = gap.records();
      before = content(c);
      archiveCurrentWal(c, clock);
    }

    List<String> printed = shell.script("pg-restore.sh", BACKUP, ARCHIVE);
    ScriptTimes script = parseRestoreOutput(printed);

    List<JournalGapReplay.Obj> objects =
        JournalGapReplay.generate(OBJECT_SEED, gapObjects(gapRecords));
    long gapObjectsNanos;
    long analyzeNanos;
    long verifyNanos;
    String fsync;
    try (Connection c = pg.primary()) {
      c.setAutoCommit(true);
      try (Statement st = c.createStatement()) {
        st.execute("SET search_path TO \"" + SCHEMA + "\"");
      }
      fsync = show(c, "fsync");

      long t0 = clock.nanoTime();
      long applied = JournalGapReplay.apply(c, SCHEMA, objects);
      gapObjectsNanos = clock.nanoTime() - t0;

      t0 = clock.nanoTime();
      try (Statement st = c.createStatement()) {
        st.execute("ANALYZE");
      }
      analyzeNanos = clock.nanoTime() - t0;

      t0 = clock.nanoTime();
      Content after = content(c);
      long again = JournalGapReplay.apply(c, SCHEMA, objects);
      verifyNanos = clock.nanoTime() - t0;

      List<String> problems = new ArrayList<>(before.differences(after));
      if (applied != objects.size()) {
        problems.add("gap objects applied: " + applied + ", expected " + objects.size());
      }
      if (again != 0) {
        problems.add("a second replay of the gap objects added " + again + " (not idempotent)");
      }
      if (!problems.isEmpty()) {
        throw new IllegalStateException(
            "the restored cluster differs from the cluster before the stop: " + problems);
      }
    }
    Point point = point(keys, script, fsync, gapObjectsNanos, analyzeNanos, verifyNanos);
    return new Outcome(point, gapRecords, objects.size());
  }

  /**
   * The bytes that one key takes in the cluster with its indexes: the size of the leaf tables of
   * {@code schema} over {@code keys}, rounded up. The caller loads a size and reads this before
   * {@link DiskPlan#choose} decides the larger ones.
   */
  public static long bytesPerKey(Connection c, String schema, long keys) throws SQLException {
    if (keys <= 0) {
      throw new IllegalArgumentException("keys must be positive: " + keys);
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT coalesce(sum(pg_total_relation_size(c.oid)), 0)"
                + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind = 'r'")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        long bytes = rs.getLong(1);
        return Math.floorDiv(bytes + keys - 1, keys);
      }
    }
  }

  // ------------------------------------------------------------------ steps

  /** The server-side load: a decision and a head per key, a subject and a version per even key. */
  static void loadKeys(Connection c, long keys, LocalDate day) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("SET search_path TO \"" + SCHEMA + "\"");
    }
    for (long start = 1; start <= keys; start += CHUNK) {
      long from = start;
      long to = Math.min(keys, start + CHUNK - 1);
      execute(
          c,
          DECISIONS_SQL,
          ps -> {
            ps.setObject(1, day);
            ps.setLong(2, from);
            ps.setLong(3, to);
          });
      for (String sql : List.of(HEADS_SQL, SUBJECTS_SQL, VERSIONS_SQL)) {
        execute(
            c,
            sql,
            ps -> {
              ps.setLong(1, from);
              ps.setLong(2, to);
            });
      }
    }
  }

  /** The identity of key {@code o}; the same text on every statement of the load. */
  private static final String IDENTITY = "substr(md5('k:' || o::text), 1, 16)";

  static final String DECISIONS_SQL =
      "INSERT INTO decision (decision_id, regime, event_identity, decision_seq,"
          + " decision_context_id, input_digest, as_at, outcome, body)"
          + " SELECT o, 'EMIR', "
          + IDENTITY
          + ", 1, 1, sha256(convert_to('k:' || o::text, 'UTF8')), ?::date + (o % "
          + DAYS
          + ")::int, (1 + o % 3)::smallint, sha256(convert_to('b:' || o::text, 'UTF8'))"
          + " FROM generate_series(?::bigint, ?::bigint) AS o";

  static final String HEADS_SQL =
      "INSERT INTO decision_head (regime, event_identity, decision_seq, decision_id)"
          + " SELECT 'EMIR', "
          + IDENTITY
          + ", 1, o FROM generate_series(?::bigint, ?::bigint) AS o";

  static final String SUBJECTS_SQL =
      "INSERT INTO report_subject (subject_id, regime, event_identity, leg)"
          + " SELECT o, 'EMIR', "
          + IDENTITY
          + ", 0 FROM generate_series(?::bigint, ?::bigint) AS o WHERE o % 2 = 0";

  static final String VERSIONS_SQL =
      "INSERT INTO report_version (subject_id, version_seq, decision_id, state)"
          + " SELECT o, 1, o, 0 FROM generate_series(?::bigint, ?::bigint) AS o WHERE o % 2 = 0";

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  private static void execute(Connection c, String sql, Binder binder) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      ps.executeUpdate();
    }
  }

  private static void checkpoint(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("CHECKPOINT");
    }
  }

  /**
   * The gap after the backup: the burst runner into the restore schema at the burst rate. The
   * standby is gone, so the run is not a sustain measurement and the monitor is the null one.
   */
  private static RunResult ingestGap(
      PgFixture pg, LocalDate day, Duration duration, NanoClock clock) {
    BurstRunner.Config config =
        new BurstRunner.Config(
            duration,
            Long.MAX_VALUE,
            GAP_RECORDS_PER_SECOND,
            GAP_BATCH,
            4,
            Method.COPY,
            Variant.E1_COMPACT,
            false);
    RunResult result =
        BurstRunner.runInto(
            pg, SCHEMA, day, config, new TradeRecordGen(GAP_SEED), clock, Monitor.none());
    if (result.stoppedBecause() == BurstRunner.Stop.ERROR || result.records() <= 0) {
      throw new IllegalStateException(
          "the gap ingest failed after " + result.records() + " records: " + result.reasons());
    }
    return result;
  }

  /** Switches the WAL segment and waits until the archiver has archived the one that was closed. */
  private static void archiveCurrentWal(Connection c, NanoClock clock) throws SQLException {
    String closed;
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT pg_walfile_name(pg_switch_wal() - 1::numeric)")) {
      rs.next();
      closed = rs.getString(1);
    }
    long deadline = clock.nanoTime() + ARCHIVE_WAIT.toNanos();
    while (true) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT coalesce(last_archived_wal >= ?, false) FROM pg_stat_archiver")) {
        ps.setString(1, closed);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getBoolean(1)) {
            return;
          }
        }
      }
      if (clock.nanoTime() > deadline) {
        throw new IllegalStateException(
            "the archiver did not archive " + closed + " within " + ARCHIVE_WAIT);
      }
      try {
        Thread.sleep(1_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the archiver", e);
      }
    }
  }

  private static String show(Connection c, String name) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SHOW " + name)) {
      rs.next();
      return rs.getString(1);
    }
  }

  // ------------------------------------------------------------------ content

  /** Counts and an order-independent digest of the ledger tables of the restore schema. */
  record Content(
      long decisions,
      String decisionDigest,
      long heads,
      String headDigest,
      long subjects,
      long versions,
      long deliveries,
      long ingest,
      long registry,
      long outboxDone) {
    List<String> differences(Content other) {
      List<String> out = new ArrayList<>();
      compare(out, "decisions", decisions, other.decisions);
      compare(out, "decision digest", decisionDigest, other.decisionDigest);
      compare(out, "heads", heads, other.heads);
      compare(out, "head digest", headDigest, other.headDigest);
      compare(out, "report subjects", subjects, other.subjects);
      compare(out, "report versions", versions, other.versions);
      compare(out, "deliveries", deliveries, other.deliveries);
      compare(out, "ingest rows", ingest, other.ingest);
      compare(out, "ingest registry rows", registry, other.registry);
      compare(out, "completed outbox items", outboxDone, other.outboxDone);
      return out;
    }

    private static void compare(List<String> out, String what, Object before, Object after) {
      if (!before.equals(after)) {
        out.add(what + ": " + before + " before the stop, " + after + " after the restore");
      }
    }
  }

  static final String CONTENT_SQL =
      "SELECT (SELECT count(*) FROM decision),"
          + " (SELECT coalesce(sum(hashtextextended(event_identity || ':' || decision_id::text"
          + " || ':' || decision_seq::text || ':' || outcome::text, 0)::numeric), 0) FROM decision),"
          + " (SELECT count(*) FROM decision_head),"
          + " (SELECT coalesce(sum(hashtextextended(event_identity || ':' || decision_id::text"
          + " || ':' || decision_seq::text, 0)::numeric), 0) FROM decision_head),"
          + " (SELECT count(*) FROM report_subject),"
          + " (SELECT count(*) FROM report_version),"
          + " (SELECT count(*) FROM delivery),"
          + " (SELECT count(*) FROM ingest),"
          + " (SELECT count(*) FROM ingest_registry),"
          + " (SELECT count(*) FROM outbox WHERE state = 1)";

  /** Reads the content with the search path of the connection set to the restore schema. */
  static Content content(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("SET search_path TO \"" + SCHEMA + "\"");
      try (ResultSet rs = st.executeQuery(CONTENT_SQL)) {
        rs.next();
        return new Content(
            rs.getLong(1),
            rs.getString(2),
            rs.getLong(3),
            rs.getString(4),
            rs.getLong(5),
            rs.getLong(6),
            rs.getLong(7),
            rs.getLong(8),
            rs.getLong(9),
            rs.getLong(10));
      }
    }
  }
}
