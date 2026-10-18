package dev.regpack.spikes.l.load;

import dev.regpack.bench.proto.NanoClock;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.pg.SyncPrecondition;
import dev.regpack.spikes.l.pg.SyncPrecondition.Sample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples the fixture once a second during a burst run: {@code pg_stat_replication} (the standby
 * precondition of RF14), the WAL, I/O and checkpoint counters, the CPU jiffies of the machine and
 * the usable bytes of the disk. The first sample that violates {@link SyncPrecondition}, or that
 * cannot be taken at all (the standby cannot then be verified), marks the run {@link #invalid()}
 * for good; usable bytes under 512 MiB make it {@link #diskLow()}.
 *
 * <p>Only the replication query is mandatory. The counter queries are best effort: a failure shows
 * up as a rising {@code sample_errors} counter, not as an invalid run.
 */
public final class BurstSampler implements Runnable, BurstRunner.Monitor {
  /** Below this many usable bytes the run stops as an environment fault. */
  public static final long MIN_USABLE_BYTES = 512L * 1024 * 1024;

  private static final long SECOND = 1_000_000_000L;
  private static final int MAX_REASONS = 100;

  /**
   * One reading of the fixture.
   *
   * @param replication the rows of {@code pg_stat_replication}
   * @param counters cumulative counters by name
   * @param usableBytes usable bytes of the data disk, negative when unknown
   */
  public record Reading(List<Sample> replication, Map<String, Long> counters, long usableBytes) {
    public Reading {
      replication = List.copyOf(replication);
      counters = Map.copyOf(counters);
    }
  }

  /**
   * One sample, as kept.
   *
   * @param offsetNanos nanoseconds since the sampler was created
   * @param perSecond the counters' growth per second since the previous sample (empty for the
   *     first)
   * @param usableBytes usable bytes of the data disk, negative when unknown
   * @param violations the {@link SyncPrecondition} violations of this sample
   */
  public record Row(
      long offsetNanos, Map<String, Long> perSecond, long usableBytes, List<String> violations) {}

  /**
   * Where the readings come from; tests script it. A source that holds a connection releases it in
   * {@link #close()}, which the caller invokes once the sampler thread has ended.
   */
  @FunctionalInterface
  public interface Source extends AutoCloseable {
    Reading read() throws Exception;

    @Override
    default void close() {
      // nothing to release by default
    }
  }

  private final Source source;
  private final NanoClock clock;
  private final long created;
  private final List<Row> rows = new ArrayList<>();
  private final List<String> reasons = new ArrayList<>();
  private long sampleFailures;
  private Map<String, Long> previousCounters = Map.of();
  private long previousNanos;
  private boolean havePrevious;
  private volatile boolean invalid;
  private volatile boolean diskLow;
  private volatile boolean stopped;

  public BurstSampler(Source source, NanoClock clock) {
    this.source = source;
    this.clock = clock;
    this.created = clock.nanoTime();
  }

  /** Takes one sample now. */
  public synchronized void sampleOnce() {
    long now = clock.nanoTime();
    long offset = now - created;
    Reading reading;
    try {
      reading = source.read();
    } catch (Exception e) {
      invalid = true;
      sampleFailures++;
      addReason(
          "SAMPLE_FAILED at "
              + offset / SECOND
              + " s: "
              + e.getClass().getSimpleName()
              + ": "
              + String.valueOf(e.getMessage()));
      return;
    }
    List<String> violations = SyncPrecondition.violations(reading.replication());
    if (!violations.isEmpty()) {
      invalid = true;
      for (String violation : violations) {
        addReason(violation + " at " + offset / SECOND + " s");
      }
    }
    if (reading.usableBytes() >= 0 && reading.usableBytes() < MIN_USABLE_BYTES) {
      diskLow = true;
      addReason("DISK_LOW at " + offset / SECOND + " s: " + reading.usableBytes() + " bytes");
    }
    Map<String, Long> perSecond = new LinkedHashMap<>();
    if (havePrevious && now > previousNanos) {
      double seconds = (now - previousNanos) / (double) SECOND;
      for (Map.Entry<String, Long> e : reading.counters().entrySet()) {
        Long before = previousCounters.get(e.getKey());
        if (before != null) {
          perSecond.put(e.getKey(), Math.round((e.getValue() - before) / seconds));
        }
      }
    }
    rows.add(
        new Row(offset, Collections.unmodifiableMap(perSecond), reading.usableBytes(), violations));
    previousCounters = reading.counters();
    previousNanos = now;
    havePrevious = true;
  }

  private void addReason(String reason) {
    if (reasons.size() < MAX_REASONS) {
      reasons.add(reason);
    }
  }

  /** Samples once a second until {@link #stop()} or an interrupt. */
  @Override
  public void run() {
    while (!stopped) {
      long begin = clock.nanoTime();
      sampleOnce();
      long remaining = SECOND - (clock.nanoTime() - begin);
      if (remaining > 0) {
        try {
          Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /** Ends {@link #run()} after the sample in progress. */
  public void stop() {
    stopped = true;
  }

  @Override
  public boolean invalid() {
    return invalid;
  }

  @Override
  public boolean diskLow() {
    return diskLow;
  }

  @Override
  public synchronized List<String> reasons() {
    return List.copyOf(reasons);
  }

  /**
   * The samples that could not be taken at all. The reasons are capped, so a long outage must be
   * counted here, not read off {@link #reasons()}.
   */
  public synchronized long sampleFailures() {
    return sampleFailures;
  }

  /** The samples taken so far. */
  public synchronized List<Row> rows() {
    return List.copyOf(rows);
  }

  /** The volume of the clusters on the runner; the temporary directory anywhere else. */
  public static Path dataDisk() {
    Path clusters = Path.of("/mnt/spike-pg");
    return Files.isDirectory(clusters) ? clusters : Path.of(System.getProperty("java.io.tmpdir"));
  }

  /**
   * A source that reads the fixture's primary on one connection of its own and the disk that holds
   * the clusters. Closing the source closes the connection.
   *
   * @param disk any path on the data volume, for the usable bytes
   */
  public static Source pgSource(PgFixture pg, Path disk) throws SQLException {
    return pgSource(pg.primary(), disk);
  }

  static Source pgSource(Connection connection, Path disk) throws SQLException {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException | RuntimeException e) {
      try {
        connection.close();
      } catch (SQLException closing) {
        e.addSuppressed(closing);
      }
      throw e;
    }
    long[] sampleErrors = {0};
    return new Source() {
      @Override
      public Reading read() throws SQLException {
        return reading(connection, sampleErrors, disk);
      }

      @Override
      public void close() {
        try {
          connection.close();
        } catch (SQLException e) {
          // closing is best effort: the connection is gone either way
        }
      }
    };
  }

  private static Reading reading(Connection connection, long[] sampleErrors, Path disk)
      throws SQLException {
    List<Sample> replication = new ArrayList<>();
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT application_name, sync_state, state FROM pg_stat_replication"
                    + " ORDER BY application_name")) {
      while (rs.next()) {
        replication.add(new Sample(text(rs, 1), text(rs, 2), text(rs, 3)));
      }
    }
    Map<String, Long> counters = new LinkedHashMap<>();
    counters.putAll(
        optional(
            connection,
            sampleErrors,
            "SELECT COALESCE(sum(write_bytes), 0)::bigint, COALESCE(sum(fsyncs), 0)::bigint"
                + " FROM pg_stat_io WHERE object = 'wal'",
            "wal_write_bytes",
            "wal_fsyncs"));
    counters.putAll(
        optional(
            connection,
            sampleErrors,
            "SELECT wal_bytes::bigint, wal_records FROM pg_stat_wal",
            "wal_bytes",
            "wal_records"));
    counters.putAll(
        optional(
            connection,
            sampleErrors,
            "SELECT num_timed, num_requested FROM pg_stat_checkpointer",
            "checkpoints_timed",
            "checkpoints_requested"));
    counters.putAll(cpu());
    counters.put("sample_errors", sampleErrors[0]);
    return new Reading(replication, counters, usable(disk));
  }

  private static String text(ResultSet rs, int column) throws SQLException {
    String value = rs.getString(column);
    return value == null ? "" : value;
  }

  private static Map<String, Long> optional(
      Connection connection, long[] errors, String sql, String first, String second) {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      Map<String, Long> out = new LinkedHashMap<>();
      if (rs.next()) {
        out.put(first, rs.getLong(1));
        out.put(second, rs.getLong(2));
      }
      return out;
    } catch (SQLException e) {
      errors[0]++;
      return Map.of();
    }
  }

  /** The first line of {@code /proc/stat}: jiffies of the whole machine; empty off Linux. */
  private static Map<String, Long> cpu() {
    Path stat = Path.of("/proc/stat");
    try {
      if (!Files.isReadable(stat)) {
        return Map.of();
      }
      String first = Files.readAllLines(stat).get(0);
      String[] parts = first.trim().split("\\s+");
      if (parts.length < 9 || !parts[0].equals("cpu")) {
        return Map.of();
      }
      long total = 0;
      for (int i = 1; i <= 8; i++) {
        total += Long.parseLong(parts[i]);
      }
      long idle = Long.parseLong(parts[4]);
      long iowait = Long.parseLong(parts[5]);
      long steal = Long.parseLong(parts[8]);
      Map<String, Long> out = new LinkedHashMap<>();
      out.put("cpu_total_jiffies", total);
      out.put("cpu_busy_jiffies", total - idle - iowait);
      out.put("cpu_iowait_jiffies", iowait);
      out.put("cpu_steal_jiffies", steal);
      return out;
    } catch (IOException | RuntimeException e) {
      return Map.of();
    }
  }

  private static long usable(Path disk) {
    try {
      return Files.getFileStore(disk).getUsableSpace();
    } catch (IOException e) {
      return -1;
    }
  }
}
