package dev.regpack.spikes.l.matrix;

import dev.regpack.bench.proto.NanoClock;
import dev.regpack.spikes.l.load.BurstSampler;
import dev.regpack.spikes.l.load.WindowRates;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The class (b) hot row of spec 11.2 (criterion L-11): one {@code seq_counter} row per endpoint and
 * restore epoch is incremented by every journal event, in a synchronous transaction that also
 * inserts the {@code delivery_event} row. The row lock serialises the writers, so the rate is
 * bounded by the commit latency of one transaction (with a live synchronous standby); the question
 * is whether the best writer count still gives the 100 events a second that 3,470 records a second
 * need (10 times the 9.94 of {@link #requiredEventsPerSecond}).
 */
public final class HotRowCounter {
  /** The schema that {@link #install} creates. */
  public static final String SCHEMA = "sp_hot";

  /** The writer counts of the search. */
  public static final int[] WRITERS = {1, 2, 4, 8, 16};

  private static final int WINDOWS = 15;
  private static final long SECOND = 1_000_000_000L;
  private static final long WINDOW_NANOS = 10 * SECOND;
  private static final int GRACE_SECONDS = 30;
  private static final int ENDPOINT = 1;
  private static final int EPOCH = 1;

  private static final String ALLOCATE =
      "UPDATE \""
          + SCHEMA
          + "\".seq_counter SET seq = seq + 1 WHERE endpoint_id = ? AND restore_epoch = ?"
          + " RETURNING seq";
  private static final String EVENT =
      "INSERT INTO \"" + SCHEMA + "\".delivery_event (endpoint_id, seq, kind) VALUES (?, ?, 1)";

  /**
   * The rate of one writer count.
   *
   * @param writers writer threads
   * @param events committed events
   * @param nanos duration of the measurement
   */
  public record Point(int writers, long events, long nanos) {
    public double eventsPerSecond() {
      return events / (nanos / 1e9);
    }
  }

  /**
   * The result of a run.
   *
   * @param points the rate per writer count
   * @param bestWriters the writer count with the highest rate
   * @param windowRates events per second of fifteen consecutive 10 s windows at the best count (the
   *     L-11 series)
   * @param violations the standby-precondition violations seen while any point or the series ran
   *     (RF14, L-13), each prefixed with the point; empty when a synchronous standby was live
   *     throughout. A point with a violation is not a valid L-11 measurement.
   */
  public record Result(
      List<Point> points, int bestWriters, double[] windowRates, List<String> violations) {
    public Result {
      violations = List.copyOf(violations);
    }
  }

  /** Opens a connection for a writer thread. */
  @FunctionalInterface
  interface Connections {
    Connection open() throws SQLException;
  }

  /**
   * The completions of one drive.
   *
   * @param offsets per writer, the nanoseconds from the start at which each of its events committed
   * @param violations the reasons that the watch gave
   */
  record Driven(long[][] offsets, List<String> violations) {}

  private HotRowCounter() {}

  /** The events a second that the journal needs: file events at {@code recordsPerSecond}. */
  public static double requiredEventsPerSecond(
      double recordsPerSecond, double reportsPerFile, double otherEventsPerSecond) {
    return recordsPerSecond / reportsPerFile + otherEventsPerSecond;
  }

  /** Creates the schema {@link #SCHEMA} with the sketch and the counter row at 0. */
  public static void install(PgFixture pg) throws SQLException {
    try (Connection c = pg.primary()) {
      SchemaInstaller.drop(c, SCHEMA);
      SchemaInstaller.install(c, SCHEMA, Variant.E1_COMPACT, LocalDate.of(2026, 10, 9), 1);
      try (Statement st = c.createStatement()) {
        st.execute(
            "INSERT INTO \""
                + SCHEMA
                + "\".seq_counter (endpoint_id, restore_epoch, seq) VALUES ("
                + ENDPOINT
                + ", "
                + EPOCH
                + ", 0)");
      }
    }
  }

  /**
   * Allocates the next sequence number in one synchronous transaction: the counter update with
   * RETURNING, the journal event insert, commit. The connection must be in manual commit mode.
   */
  public static long allocate(Connection c) throws SQLException {
    try {
      long seq;
      try (PreparedStatement ps = c.prepareStatement(ALLOCATE)) {
        ps.setInt(1, ENDPOINT);
        ps.setInt(2, EPOCH);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            throw new SQLException("the counter row is missing: call install first");
          }
          seq = rs.getLong(1);
        }
      }
      try (PreparedStatement ps = c.prepareStatement(EVENT)) {
        ps.setInt(1, ENDPOINT);
        ps.setLong(2, seq);
        ps.executeUpdate();
      }
      c.commit();
      return seq;
    } catch (SQLException | RuntimeException e) {
      try {
        c.rollback();
      } catch (SQLException rollback) {
        e.addSuppressed(rollback);
      }
      throw e;
    }
  }

  /**
   * Measures every writer count for {@code each}, then runs the best count for 150 s and cuts the
   * completions into fifteen 10 s windows. Needs the standby live: every commit is synchronous.
   */
  public static Result run(PgFixture pg, int[] writers, Duration each)
      throws SQLException, InterruptedException {
    return run(pg, writers, each, WINDOWS);
  }

  /**
   * As {@link #run(PgFixture, int[], Duration)} with a series of {@code windows} windows of 10 s
   * (fifteen is the L-11 series; a smoke test takes one).
   *
   * <p>The standby precondition is sampled once a second while every point and the series run, and
   * once more when each ends (RF14, L-13): {@link Result#violations()} lists what was seen. A
   * writer that is still blocked in a commit 30 s after its time is over (a synchronous standby
   * that is gone) ends the run with an exception.
   */
  public static Result run(PgFixture pg, int[] writers, Duration each, int windows)
      throws SQLException, InterruptedException {
    install(pg);
    List<String> violations = new ArrayList<>();
    List<Point> points = new ArrayList<>();
    for (int w : writers) {
      Driven driven = watched(pg, w, each.toNanos());
      long events = 0;
      for (long[] o : driven.offsets()) {
        events += o.length;
      }
      points.add(new Point(w, events, each.toNanos()));
      driven.violations().forEach(v -> violations.add("writers=" + w + ": " + v));
    }
    Point best = points.get(0);
    for (Point p : points) {
      if (p.eventsPerSecond() > best.eventsPerSecond()) {
        best = p;
      }
    }
    Driven seriesRun = watched(pg, best.writers(), windows * WINDOW_NANOS);
    long[][] series = seriesRun.offsets();
    int seriesWriters = best.writers();
    seriesRun.violations().forEach(v -> violations.add("series of " + seriesWriters + ": " + v));
    int total = 0;
    for (long[] o : series) {
      total += o.length;
    }
    long[] all = new long[total];
    int at = 0;
    for (long[] o : series) {
      System.arraycopy(o, 0, all, at, o.length);
      at += o.length;
    }
    long[] ones = new long[total];
    Arrays.fill(ones, 1L);
    double[] windowRates = WindowRates.recordsPerSecond(all, ones, WINDOW_NANOS, windows);
    return new Result(List.copyOf(points), best.writers(), windowRates, violations);
  }

  /** One drive on the fixture with its own watch of the standby, closed when the drive is over. */
  private static Driven watched(PgFixture pg, int writers, long nanos)
      throws SQLException, InterruptedException {
    try (BurstSampler.Source source = BurstSampler.pgSource(pg, BurstSampler.dataDisk())) {
      return drive(
          pg::primary,
          new BurstSampler(source, NanoClock.system()),
          writers,
          nanos,
          GRACE_SECONDS * SECOND);
    }
  }

  /**
   * Runs {@code writers} threads for {@code nanos} while {@code watch} samples the standby; returns
   * each thread's completion offsets and the violations of the watch (one sample is taken when the
   * writers start and one when they are done).
   *
   * @param graceNanos how long after {@code nanos} a writer may still be in a commit: a commit that
   *     waits for a synchronous standby that is gone never returns, so the connections are aborted
   *     and the drive throws instead of hanging
   */
  static Driven drive(
      Connections opener, BurstSampler watch, int writers, long nanos, long graceNanos)
      throws SQLException, InterruptedException {
    List<Connection> connections = new ArrayList<>();
    Thread watching = null;
    try {
      for (int i = 0; i < writers; i++) {
        Connection c = opener.open();
        connections.add(c);
        c.setAutoCommit(false);
      }
      long[][] results = new long[writers][];
      AtomicBoolean failed = new AtomicBoolean();
      List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
      List<Thread> threads = new ArrayList<>();
      watching = Thread.ofPlatform().daemon().name("hot-row-watch").start(watch);
      long start = System.nanoTime();
      for (int i = 0; i < writers; i++) {
        int index = i;
        threads.add(
            Thread.ofPlatform()
                .daemon()
                .name("hot-row-" + i)
                .start(
                    () -> {
                      long[] buffer = new long[1 << 12];
                      int count = 0;
                      try {
                        Connection c = connections.get(index);
                        while (!failed.get() && System.nanoTime() - start < nanos) {
                          allocate(c);
                          if (count == buffer.length) {
                            buffer = Arrays.copyOf(buffer, count * 2);
                          }
                          buffer[count++] = System.nanoTime() - start;
                        }
                      } catch (SQLException | RuntimeException e) {
                        failed.set(true);
                        errors.add(e);
                      }
                      results[index] = Arrays.copyOf(buffer, count);
                    }));
      }
      long deadline = start + nanos + graceNanos;
      boolean blocked = false;
      for (Thread t : threads) {
        if (!t.join(Duration.ofNanos(Math.max(1L, deadline - System.nanoTime())))) {
          blocked = true;
        }
      }
      if (blocked) {
        failed.set(true);
        for (Connection c : connections) {
          abort(c); // unblocks a commit that waits for a standby that is gone
        }
        for (Thread t : threads) {
          t.join(Duration.ofSeconds(5));
        }
        throw new SQLException(
            "a hot-row writer was still in a commit "
                + graceNanos / SECOND
                + " s after its time was over: a commit waits for a synchronous standby that is"
                + " gone? "
                + watch.reasons());
      }
      watch.stop();
      watching.join(Duration.ofSeconds(5));
      watch.sampleOnce();
      if (!errors.isEmpty()) {
        throw new SQLException("a hot-row writer failed", errors.get(0));
      }
      return new Driven(results, watch.reasons());
    } finally {
      watch.stop();
      if (watching != null) {
        watching.interrupt();
      }
      for (Connection c : connections) {
        try {
          c.close();
        } catch (SQLException e) {
          // closing is best effort: the connection is gone either way
        }
      }
    }
  }

  private static void abort(Connection c) {
    try {
      c.abort(Runnable::run);
    } catch (SQLException | RuntimeException e) {
      // the connection is closed in the caller's finally in any case
    }
  }
}
