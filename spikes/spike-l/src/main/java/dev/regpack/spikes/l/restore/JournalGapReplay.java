package dev.regpack.spikes.l.restore;

import dev.regpack.bench.data.SplitMix64;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The gap objects of a restore: class (b) journal objects (spec 11.2) that were shipped to the
 * evidence tier after the last backup and so are not in the restored database. The restore applies
 * them in {@code (scope, epoch, seq)} order, idempotently by key, and re-establishes the counter
 * row of each (scope, epoch) at the largest sequence number replayed, so that allocation resumes
 * above it.
 *
 * <p>The objects are written to {@code journal_applied}, a table that the restore study adds to the
 * sketch schema (the sketch has no unique key on the journal; this keeps the replay idempotent
 * without changing it), and the counters to {@code seq_counter} of the sketch.
 */
public final class JournalGapReplay {
  /**
   * One class (b) object.
   *
   * @param scope the endpoint of the object
   * @param epoch the restore epoch
   * @param seq the per-(scope, epoch) sequence number
   * @param kind the journal type (1 to 4)
   */
  public record Obj(int scope, int epoch, long seq, int kind) {}

  /** The order of application. */
  public static final Comparator<Obj> ORDER =
      Comparator.comparingInt(Obj::scope).thenComparingInt(Obj::epoch).thenComparingLong(Obj::seq);

  /** An insert that skips the keys that are already there. {@code {schema}} is replaced. */
  static final String INSERT_SQL =
      "INSERT INTO {schema}.journal_applied (scope, epoch, seq, kind)"
          + " SELECT u.scope, u.epoch, u.seq, u.kind"
          + " FROM unnest(?::int[], ?::int[], ?::bigint[], ?::smallint[]) AS u(scope, epoch, seq, kind)"
          + " WHERE true ORDER BY u.scope, u.epoch, u.seq"
          + " ON CONFLICT (scope, epoch, seq) DO NOTHING";

  /** Raises the counter of each (scope, epoch) to the largest sequence number of the batch. */
  static final String COUNTER_SQL =
      "INSERT INTO {schema}.seq_counter AS sc (endpoint_id, restore_epoch, seq)"
          + " SELECT u.scope, u.epoch, max(u.seq)"
          + " FROM unnest(?::int[], ?::int[], ?::bigint[]) AS u(scope, epoch, seq)"
          + " WHERE true GROUP BY u.scope, u.epoch"
          + " ON CONFLICT (endpoint_id, restore_epoch) DO UPDATE"
          + " SET seq = GREATEST(sc.seq, EXCLUDED.seq)";

  private static final int BATCH = 1_000;
  private static final int SCOPES = 4;
  private static final int FIRST_SEQ = 1_000;
  private static final int KINDS = 4;
  private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  private JournalGapReplay() {}

  /** The statement that creates the table of applied objects in {@code schema}. */
  static String createTableSql(String schema) {
    return "CREATE TABLE IF NOT EXISTS "
        + quote(schema)
        + ".journal_applied (scope int NOT NULL, epoch int NOT NULL, seq bigint NOT NULL,"
        + " kind smallint NOT NULL, PRIMARY KEY (scope, epoch, seq))";
  }

  /** Creates the table of applied objects in {@code schema}. */
  public static void createTable(Connection c, String schema) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(createTableSql(schema));
    }
  }

  /**
   * {@code objects} gap objects, deterministic in {@code seed}: four scopes, restore epoch 0,
   * contiguous sequence numbers per scope, delivered in an order that is not the key order (they
   * arrive from several shippers), so that {@link #apply} has to sort.
   */
  public static List<Obj> generate(long seed, int objects) {
    if (objects < 0) {
      throw new IllegalArgumentException("the number of objects must not be negative: " + objects);
    }
    SplitMix64 rng = new SplitMix64(seed);
    long[] next = new long[SCOPES + 1];
    java.util.Arrays.fill(next, FIRST_SEQ);
    List<Obj> out = new ArrayList<>(objects);
    for (int i = 0; i < objects; i++) {
      int scope = 1 + rng.nextInt(SCOPES);
      out.add(new Obj(scope, 0, next[scope]++, 1 + rng.nextInt(KINDS)));
    }
    for (int i = out.size() - 1; i > 0; i--) {
      int j = rng.nextInt(i + 1);
      Obj swap = out.get(i);
      out.set(i, out.get(j));
      out.set(j, swap);
    }
    return List.copyOf(out);
  }

  /**
   * Applies the objects in {@code (scope, epoch, seq)} order, a batch at a time, idempotently by
   * key: a key that is already there is skipped, so applying a gap twice, or a list that holds a
   * key twice, changes nothing the second time. The connection is used in its current commit mode.
   *
   * @return the number of objects that were new
   */
  public static long apply(Connection c, String schema, List<Obj> objects) throws SQLException {
    List<Obj> sorted = new ArrayList<>(objects);
    sorted.sort(ORDER);
    String insert = INSERT_SQL.replace("{schema}", quote(schema));
    String counter = COUNTER_SQL.replace("{schema}", quote(schema));
    long inserted = 0;
    for (int from = 0; from < sorted.size(); from += BATCH) {
      List<Obj> batch = sorted.subList(from, Math.min(sorted.size(), from + BATCH));
      int n = batch.size();
      Integer[] scopes = new Integer[n];
      Integer[] epochs = new Integer[n];
      Long[] seqs = new Long[n];
      Integer[] kinds = new Integer[n];
      for (int i = 0; i < n; i++) {
        Obj o = batch.get(i);
        scopes[i] = o.scope();
        epochs[i] = o.epoch();
        seqs[i] = o.seq();
        kinds[i] = o.kind();
      }
      Array scopeArray = c.createArrayOf("int4", scopes);
      Array epochArray = c.createArrayOf("int4", epochs);
      Array seqArray = c.createArrayOf("int8", seqs);
      Array kindArray = c.createArrayOf("int4", kinds);
      try (PreparedStatement ps = c.prepareStatement(insert)) {
        ps.setArray(1, scopeArray);
        ps.setArray(2, epochArray);
        ps.setArray(3, seqArray);
        ps.setArray(4, kindArray);
        inserted += ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement(counter)) {
        ps.setArray(1, scopeArray);
        ps.setArray(2, epochArray);
        ps.setArray(3, seqArray);
        ps.executeUpdate();
      }
    }
    return inserted;
  }

  private static String quote(String schema) {
    if (!SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "a schema name must match [a-z][a-z0-9_]{0,62}: '" + schema + "'");
    }
    return "\"" + schema + "\"";
  }
}
