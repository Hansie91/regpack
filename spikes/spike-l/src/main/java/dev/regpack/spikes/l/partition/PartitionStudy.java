package dev.regpack.spikes.l.partition;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.stats.Stats;
import dev.regpack.spikes.l.partition.PartitionPlan.Bound;
import dev.regpack.spikes.l.partition.PartitionPlan.Granularity;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.postgresql.PGStatement;

/**
 * The partition study (L-9, L-10): what the granularity of {@code decision} costs a lookup, and
 * what detaching and dropping a partition costs. For each daily partition count and for the monthly
 * count the study installs the sketch with the partitions of the plan, fills the most recent {@code
 * populatedPartitions} days with {@code rowsPerPopulatedPartition} rows each (the older partitions
 * stay empty), analyzes, and times two lookups in {@code blocks} blocks of {@code lookupsPerBlock}:
 *
 * <ul>
 *   <li><b>by event identity without the partition key</b> ({@code WHERE event_identity = ?}): the
 *       planner has to consider every partition and the executor probes the index of each, so the
 *       cost grows with the partition count (L-10). It runs over empty and small indexes, so a PASS
 *       bounds planner and probe overhead only; a FAIL is decisive.
 *   <li><b>through {@code decision_head}, then a pruned fetch</b>: the head gives the decision id
 *       and the {@code as_at} date, the fetch by {@code (decision_id, as_at)} touches one partition
 *       (L-9, the decision-relevant path).
 * </ul>
 *
 * <p><b>Finding for the P3 schema design.</b> {@code decision_head} of the sketch has no {@code
 * as_at} column, so a head alone cannot prune the partitions of {@code decision}. The study adds
 * {@code as_at date} to the head of its own schema and says so; without it the "head then fetch"
 * path would be the unpruned probe of the first lookup. Statements are sent unnamed ({@code
 * prepareThreshold 0}), so every lookup is planned with its actual parameters: the planner cost
 * that L-10 is about is in every sample.
 */
public final class PartitionStudy {
  /** The last day of every plan: the populated days end here. */
  static final LocalDate LAST_DAY = LocalDate.of(2026, 9, 30);

  /** The identity of a row, as the insert computes it. */
  static final String IDENTITY_EXPR_ROWS = "substr(md5(p.d::text || ':' || g::text), 1, 16)";

  /** The same identity, as the key query computes it from {@code (d, g)} pairs. */
  static final String IDENTITY_EXPR_KEYS = "substr(md5(t.d::text || ':' || t.g::text), 1, 16)";

  static final String INSERT_DECISIONS_SQL =
      "INSERT INTO decision (decision_id, regime, event_identity, decision_seq,"
          + " decision_context_id, input_digest, as_at, outcome, body)"
          + " SELECT (p.d::bigint << 32) + g, 'EMIR', "
          + IDENTITY_EXPR_ROWS
          + ", 1, 1, sha256(convert_to(p.d::text || ':' || g::text, 'UTF8')), p.day, 1,"
          + " sha256(convert_to('b:' || g::text, 'UTF8'))"
          + " FROM (SELECT ?::int AS d, ?::date AS day, ?::int AS n) p,"
          + " generate_series(1, p.n) AS g";

  static final String INSERT_HEADS_SQL =
      "INSERT INTO decision_head (regime, event_identity, decision_seq, decision_id, as_at)"
          + " SELECT regime, event_identity, decision_seq, decision_id, as_at"
          + " FROM decision WHERE as_at = ?::date";

  /** The keys to look up: identity, decision id and day of the rows named by (d, g) pairs. */
  static final String KEYS_SQL =
      "SELECT "
          + IDENTITY_EXPR_KEYS
          + ", (t.d::bigint << 32) + t.g, ?::date + t.d"
          + " FROM unnest(?::int[], ?::int[]) WITH ORDINALITY AS t(d, g, n) ORDER BY t.n";

  private static final String LOOKUP_SQL =
      "SELECT decision_id, as_at FROM decision WHERE event_identity = ?";
  private static final String HEAD_SQL =
      "SELECT decision_id, as_at FROM decision_head WHERE regime = 'EMIR' AND event_identity = ?";
  private static final String FETCH_SQL =
      "SELECT decision_seq, outcome, body FROM decision WHERE decision_id = ? AND as_at = ?";

  private static final long SEED = 20_261_015L;
  private static final int WARMUP_BLOCKS = 2;

  private PartitionStudy() {}

  /**
   * One measured point.
   *
   * @param granularity daily or monthly
   * @param partitions the number of partitions of {@code decision}
   * @param lookupP99NanosPerBlock p99 of the lookup by event identity without the partition key,
   *     one value per block (the fork-like series of L-10)
   * @param headThenPrunedFetchP99NanosPerBlock p99 of the head lookup followed by the pruned fetch,
   *     one value per block (the series of L-9)
   * @param detachNanos {@code DETACH PARTITION} of the oldest populated partition
   * @param dropNanos {@code DROP TABLE} of the detached partition
   */
  public record Point(
      Granularity granularity,
      int partitions,
      double[] lookupP99NanosPerBlock,
      double[] headThenPrunedFetchP99NanosPerBlock,
      long detachNanos,
      long dropNanos) {}

  /** The first of the populated days. */
  static LocalDate windowStart(int populatedDays) {
    return LAST_DAY.minusDays(populatedDays - 1L);
  }

  static String schemaName(Granularity granularity, int partitions) {
    return "sp_part_" + (granularity == Granularity.DAILY ? "d" : "m") + partitions;
  }

  /**
   * Checks the arguments of {@link #run}.
   *
   * @throws IllegalArgumentException for an empty or too small plan, or a non-positive size
   */
  static void validate(
      int[] dailyCounts,
      int monthlyCount,
      int populatedPartitions,
      int rowsPerPopulatedPartition,
      int blocks,
      int lookupsPerBlock) {
    if (dailyCounts.length == 0) {
      throw new IllegalArgumentException("at least one daily partition count is needed");
    }
    if (populatedPartitions <= 0 || rowsPerPopulatedPartition <= 0) {
      throw new IllegalArgumentException(
          "populated partitions and rows per partition must be positive");
    }
    if (blocks <= 0 || lookupsPerBlock <= 0) {
      throw new IllegalArgumentException("blocks and lookups per block must be positive");
    }
    for (int count : dailyCounts) {
      if (count < populatedPartitions) {
        throw new IllegalArgumentException(
            "a daily plan of " + count + " cannot hold " + populatedPartitions + " populated days");
      }
    }
    LocalDate firstMonth = LAST_DAY.withDayOfMonth(1).minusMonths(monthlyCount - 1L);
    if (monthlyCount <= 0 || windowStart(populatedPartitions).isBefore(firstMonth)) {
      throw new IllegalArgumentException(
          "the monthly plan of "
              + monthlyCount
              + " months does not reach back over the "
              + populatedPartitions
              + " populated days");
    }
  }

  /**
   * Runs the study: one point per daily count and one monthly point.
   *
   * @param dailyCounts the daily partition counts (30, 365, 1,826 in the plan)
   * @param monthlyCount the number of monthly partitions (60)
   * @param populatedPartitions the days that hold rows, ending on the last day of the plan (30)
   * @param rowsPerPopulatedPartition rows per populated day (300,000)
   * @param blocks blocks of lookups per series (15)
   * @param lookupsPerBlock lookups per block (200)
   */
  public static List<Point> run(
      PgFixture pg,
      int[] dailyCounts,
      int monthlyCount,
      int populatedPartitions,
      int rowsPerPopulatedPartition,
      int blocks,
      int lookupsPerBlock)
      throws SQLException {
    validate(
        dailyCounts,
        monthlyCount,
        populatedPartitions,
        rowsPerPopulatedPartition,
        blocks,
        lookupsPerBlock);
    List<Point> out = new ArrayList<>();
    for (int count : dailyCounts) {
      out.add(
          point(
              pg,
              Granularity.DAILY,
              count,
              populatedPartitions,
              rowsPerPopulatedPartition,
              blocks,
              lookupsPerBlock));
    }
    out.add(
        point(
            pg,
            Granularity.MONTHLY,
            monthlyCount,
            populatedPartitions,
            rowsPerPopulatedPartition,
            blocks,
            lookupsPerBlock));
    return List.copyOf(out);
  }

  /**
   * Checks the arguments of {@link #runPoint}: the same rules as {@link #validate}, for one point.
   *
   * @throws IllegalArgumentException for a plan that cannot hold the populated days or a
   *     non-positive size
   */
  static void validatePoint(
      Granularity granularity,
      int count,
      int populatedPartitions,
      int rowsPerPopulatedPartition,
      int blocks,
      int lookupsPerBlock) {
    if (count <= 0) {
      throw new IllegalArgumentException("the partition count must be positive: " + count);
    }
    if (populatedPartitions <= 0 || rowsPerPopulatedPartition <= 0) {
      throw new IllegalArgumentException(
          "populated partitions and rows per partition must be positive");
    }
    if (blocks <= 0 || lookupsPerBlock <= 0) {
      throw new IllegalArgumentException("blocks and lookups per block must be positive");
    }
    if (granularity == Granularity.DAILY) {
      if (count < populatedPartitions) {
        throw new IllegalArgumentException(
            "a daily plan of " + count + " cannot hold " + populatedPartitions + " populated days");
      }
    } else if (windowStart(populatedPartitions)
        .isBefore(LAST_DAY.withDayOfMonth(1).minusMonths(count - 1L))) {
      throw new IllegalArgumentException(
          "the monthly plan of "
              + count
              + " months does not reach back over the "
              + populatedPartitions
              + " populated days");
    }
  }

  /**
   * Runs one point of the study on its own, so that a caller can look at the disk, or restart the
   * cluster, between points. The cleaning of the schema is the same as in {@link #run}.
   *
   * @param granularity daily or monthly
   * @param count the number of partitions of {@code decision}
   */
  public static Point runPoint(
      PgFixture pg,
      Granularity granularity,
      int count,
      int populatedPartitions,
      int rowsPerPopulatedPartition,
      int blocks,
      int lookupsPerBlock)
      throws SQLException {
    validatePoint(
        granularity,
        count,
        populatedPartitions,
        rowsPerPopulatedPartition,
        blocks,
        lookupsPerBlock);
    return point(
        pg,
        granularity,
        count,
        populatedPartitions,
        rowsPerPopulatedPartition,
        blocks,
        lookupsPerBlock);
  }

  private static Point point(
      PgFixture pg,
      Granularity granularity,
      int count,
      int populated,
      int rows,
      int blocks,
      int lookups)
      throws SQLException {
    String schema = schemaName(granularity, count);
    LocalDate first =
        granularity == Granularity.DAILY
            ? LAST_DAY.minusDays(count - 1L)
            : LAST_DAY.withDayOfMonth(1).minusMonths(count - 1L);
    List<Bound> plan = PartitionPlan.plan(first, count, granularity);
    LocalDate start = windowStart(populated);
    try (Connection c = pg.primary()) {
      c.setAutoCommit(true);
      SchemaInstaller.drop(c, schema);
      try {
        SchemaInstaller.installTables(c, schema, Variant.E1_COMPACT);
        try (Statement st = c.createStatement()) {
          // the finding of the class comment: the head must carry the partition key to prune
          st.execute("ALTER TABLE decision_head ADD COLUMN as_at date");
          for (Bound bound : plan) {
            st.execute(PartitionPlan.createStatement(bound));
          }
        }
        load(c, start, populated, rows);
        try (Statement st = c.createStatement()) {
          st.execute("ANALYZE decision");
          st.execute("ANALYZE decision_head");
        }
        Key[] keys = keys(c, start, populated, rows, (WARMUP_BLOCKS + blocks) * lookups);
        double[] byIdentity = measureIdentityLookups(c, keys, blocks, lookups);
        double[] viaHead = measureHeadThenFetch(c, keys, blocks, lookups);
        Bound oldest =
            plan.stream()
                .filter(b -> !start.isBefore(b.from()) && start.isBefore(b.to()))
                .findFirst()
                .orElseThrow();
        long detach;
        long drop;
        try (Statement st = c.createStatement()) {
          NanoClock clock = NanoClock.system();
          long t0 = clock.nanoTime();
          st.execute("ALTER TABLE decision DETACH PARTITION " + oldest.name());
          long t1 = clock.nanoTime();
          st.execute("DROP TABLE " + oldest.name());
          long t2 = clock.nanoTime();
          detach = t1 - t0;
          drop = t2 - t1;
        }
        return new Point(granularity, count, byIdentity, viaHead, detach, drop);
      } catch (SQLException e) {
        if ("53200".equals(e.getSQLState())) {
          throw new SQLException(
              "out of shared memory at "
                  + count
                  + " "
                  + granularity.name().toLowerCase(Locale.ROOT)
                  + " partitions: raise max_locks_per_transaction on the fixture (a lookup without the"
                  + " partition key locks every partition and its indexes)",
              e.getSQLState(),
              e);
        }
        throw e;
      } finally {
        SchemaInstaller.drop(c, schema);
      }
    }
  }

  /** One statement per populated day: the rows of the day, then its heads. */
  private static void load(Connection c, LocalDate start, int populated, int rows)
      throws SQLException {
    for (int d = 0; d < populated; d++) {
      try (PreparedStatement ps = c.prepareStatement(INSERT_DECISIONS_SQL)) {
        ps.setInt(1, d);
        ps.setObject(2, start.plusDays(d));
        ps.setInt(3, rows);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement(INSERT_HEADS_SQL)) {
        ps.setObject(1, start.plusDays(d));
        ps.executeUpdate();
      }
    }
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM decision")) {
      rs.next();
      long expected = (long) populated * rows;
      if (rs.getLong(1) != expected) {
        throw new IllegalStateException(
            "the load put " + rs.getLong(1) + " rows into decision, expected " + expected);
      }
    }
  }

  /** A row to look up. */
  record Key(String identity, long decisionId, LocalDate asAt) {}

  /** {@code count} rows drawn at random from the populated rows. */
  private static Key[] keys(Connection c, LocalDate start, int populated, int rows, int count)
      throws SQLException {
    SplitMix64 rng = new SplitMix64(SEED);
    Integer[] days = new Integer[count];
    Integer[] ordinals = new Integer[count];
    for (int i = 0; i < count; i++) {
      days[i] = rng.nextInt(populated);
      ordinals[i] = 1 + rng.nextInt(rows);
    }
    Array dayArray = c.createArrayOf("int4", days);
    Array ordinalArray = c.createArrayOf("int4", ordinals);
    Key[] out = new Key[count];
    try (PreparedStatement ps = c.prepareStatement(KEYS_SQL)) {
      ps.setObject(1, start);
      ps.setArray(2, dayArray);
      ps.setArray(3, ordinalArray);
      try (ResultSet rs = ps.executeQuery()) {
        int i = 0;
        while (rs.next()) {
          out[i++] = new Key(rs.getString(1), rs.getLong(2), rs.getObject(3, LocalDate.class));
        }
        if (i != count) {
          throw new IllegalStateException(
              "the key query returned " + i + " keys, expected " + count);
        }
      }
    }
    return out;
  }

  private static double[] measureIdentityLookups(Connection c, Key[] keys, int blocks, int lookups)
      throws SQLException {
    long[] nanos = new long[blocks * lookups];
    NanoClock clock = NanoClock.system();
    try (PreparedStatement ps = c.prepareStatement(LOOKUP_SQL)) {
      ps.unwrap(PGStatement.class).setPrepareThreshold(0);
      for (int i = 0; i < WARMUP_BLOCKS * lookups + nanos.length; i++) {
        Key key = keys[i];
        long t0 = clock.nanoTime();
        ps.setString(1, key.identity());
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next() || rs.getLong(1) != key.decisionId()) {
            throw new IllegalStateException("lookup of " + key.identity() + " found the wrong row");
          }
        }
        long took = clock.nanoTime() - t0;
        int measured = i - WARMUP_BLOCKS * lookups;
        if (measured >= 0) {
          nanos[measured] = took;
        }
      }
    }
    return blockP99(nanos, blocks, lookups);
  }

  private static double[] measureHeadThenFetch(Connection c, Key[] keys, int blocks, int lookups)
      throws SQLException {
    long[] nanos = new long[blocks * lookups];
    NanoClock clock = NanoClock.system();
    try (PreparedStatement head = c.prepareStatement(HEAD_SQL);
        PreparedStatement fetch = c.prepareStatement(FETCH_SQL)) {
      head.unwrap(PGStatement.class).setPrepareThreshold(0);
      fetch.unwrap(PGStatement.class).setPrepareThreshold(0);
      for (int i = 0; i < WARMUP_BLOCKS * lookups + nanos.length; i++) {
        Key key = keys[i];
        long t0 = clock.nanoTime();
        head.setString(1, key.identity());
        long decisionId;
        LocalDate asAt;
        try (ResultSet rs = head.executeQuery()) {
          if (!rs.next()) {
            throw new IllegalStateException("no head for " + key.identity());
          }
          decisionId = rs.getLong(1);
          asAt = rs.getObject(2, LocalDate.class);
        }
        fetch.setLong(1, decisionId);
        fetch.setObject(2, asAt);
        try (ResultSet rs = fetch.executeQuery()) {
          if (!rs.next() || decisionId != key.decisionId()) {
            throw new IllegalStateException("fetch of " + key.identity() + " found the wrong row");
          }
          rs.getLong(1);
          rs.getBytes(3);
        }
        long took = clock.nanoTime() - t0;
        int measured = i - WARMUP_BLOCKS * lookups;
        if (measured >= 0) {
          nanos[measured] = took;
        }
      }
    }
    return blockP99(nanos, blocks, lookups);
  }

  /**
   * The nearest-rank p99 of each block of {@code perBlock} consecutive samples.
   *
   * @throws IllegalArgumentException unless there are exactly {@code blocks x perBlock} samples
   */
  static double[] blockP99(long[] nanos, int blocks, int perBlock) {
    if (nanos.length != blocks * perBlock) {
      throw new IllegalArgumentException(
          "expected " + blocks + " x " + perBlock + " samples, got " + nanos.length);
    }
    double[] out = new double[blocks];
    for (int b = 0; b < blocks; b++) {
      double[] block = new double[perBlock];
      for (int i = 0; i < perBlock; i++) {
        block[i] = nanos[b * perBlock + i];
      }
      out[b] = Stats.percentile(block, 99);
    }
    return out;
  }
}
