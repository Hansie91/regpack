package dev.regpack.postgres.capacity;

import dev.regpack.ports.Clock;
import dev.regpack.postgres.PgLedgerStore;
import dev.regpack.postgres.PgTestDatabase;
import dev.regpack.postgres.SqlIdentifier;
import dev.regpack.postgres.migrate.Migrator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The capacity probe for the L-4 consequence (T21): information, not a verdict.
 *
 * <p>It drives the production adapter at the SP-L load shape (micro-batches of N, four writers with
 * one source id each, open-loop arrivals at 3,470 records/s for a configured number of seconds,
 * each batch one {@code commitIngest} and one {@code commitReportable}, half of the decisions
 * REPORT with one version each) against the server of {@code REGPACK_PG_URL}, keeps the per-second
 * sampler file and writes the read-out ({@link ProbeSummary}). The variants A, B and C of the CI
 * job differ in the server, not in this code: see {@link ProbeSettings} for what the workflow sets.
 *
 * <p>The class is tagged {@code capacity} and the module excludes that group from {@code verify};
 * the workflow selects it with {@code -Dgroups=capacity -DexcludedGroups=none
 * -Dit.test=LedgerBurstProbeIT}. Without {@code REGPACK_PG_URL} it is skipped, with {@code
 * REGPACK_PG_REQUIRED=true} and no server it fails. It runs the batch sizes one after the other,
 * each in a schema of its own that is dropped at the end, and writes in {@code target/capacity/}:
 * {@code sampler-<variant>-n<N>.csv} and {@code summary-<variant>-n<N>.txt}.
 */
@Tag("capacity")
class LedgerBurstProbeIT {
  /**
   * The only timeout raised from the adapter defaults (Convention 11 allows the statement timeout).
   * The lock timeout stays at the adapter default of five seconds, so that the read-out shows what
   * the default timeouts do under the load: a unit that waits for a lock longer than that is a
   * result of the probe, not something to hide.
   */
  private static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(5);

  /** The settings that are recorded in the read-out, so that it states the server it ran on. */
  private static final String[] SERVER_SETTINGS = {
    "shared_buffers",
    "effective_cache_size",
    "synchronous_commit",
    "max_wal_size",
    "checkpoint_timeout",
    "checkpoint_completion_target",
    "wal_level",
    "wal_compression",
    "wal_buffers",
    "fsync",
    "full_page_writes",
    "track_io_timing",
    "track_wal_io_timing",
    "max_connections",
    "work_mem",
    "maintenance_work_mem"
  };

  /** The wall clock at whole microseconds; the production clock has no other consumer yet (A10). */
  private static final class WallClock implements Clock {
    @Override
    public Instant now() {
      return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public long monotonicNanos() {
      return System.nanoTime();
    }
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.HOURS)
  void burstAtTheSpLLoadShape() throws Exception {
    DataSource server = PgTestDatabase.dataSource(); // skips, or fails when the store is required
    ProbeSettings settings = ProbeSettings.parse(System.getenv());
    Path directory = Path.of(System.getProperty("regpack.build.dir", "target"), "capacity");
    Files.createDirectories(directory);

    List<Throwable> failures = new ArrayList<>();
    for (int batchSize : settings.batchSizes()) {
      try {
        runOnce(server, settings, batchSize, directory);
      } catch (InterruptedException e) {
        throw e; // a timeout or a cancel ends the whole probe
      } catch (Exception | AssertionError e) {
        // one batch size failing does not stop the next one: the sizes are independent runs
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      // the cause is in the message: the failure annotation shows only its first line
      AssertionError error =
          new AssertionError(
              ProbeSummary.probeStoppedMessage(failures.size(), failures.get(0)), failures.get(0));
      failures.stream().skip(1).forEach(error::addSuppressed);
      throw error;
    }
  }

  private static void runOnce(
      DataSource server, ProbeSettings settings, int batchSize, Path directory) throws Exception {
    String schema = PgTestDatabase.freshSchema();
    Migrator.migrate(server, schema);
    try {
      String tag = settings.variant() + "-n" + batchSize;
      String samplerName = "sampler-" + tag + ".csv";
      PgLedgerStore.Config config =
          PgLedgerStore.Config.forSchema(schema)
              .withForceSynchronousCommit(settings.forceSynchronousCommit())
              .withStatementTimeout(STATEMENT_TIMEOUT);
      CountingDataSource counting = CountingDataSource.over(server);
      PgLedgerStore store = new PgLedgerStore(counting.dataSource(), new WallClock(), config);
      BurstLoad load =
          new BurstLoad(
              store,
              new BurstLoad.Settings(
                  batchSize, settings.writers(), settings.ratePerSecond(), settings.seconds(), tag),
              counting::currentThread);
      Sampler sampler = new Sampler(server, directory.resolve(samplerName), load::progressColumns);

      sampler.start();
      BurstLoad.Outcome outcome;
      try {
        outcome = load.run();
      } finally {
        sampler.stop();
      }

      // the cause of a run that stopped is one of the first five lines (the annotations)
      ProbeSummary.Inputs inputs = inputs(server, schema, settings, batchSize, outcome, sampler);
      List<String> lines = new ArrayList<>(ProbeSummary.of(inputs, outcome.observations()).lines());
      lines.addAll(serverSettings(server));
      Files.write(directory.resolve("summary-" + tag + ".txt"), lines, StandardCharsets.UTF_8);
      lines.forEach(System.out::println);
      if (outcome.failure().isPresent()) {
        throw new AssertionError(
            ProbeSummary.loadStoppedMessage(tag, outcome.failure().get()), outcome.failure().get());
      }
    } finally {
      PgTestDatabase.dropSchema(schema);
    }
  }

  private static ProbeSummary.Inputs inputs(
      DataSource server,
      String schema,
      ProbeSettings settings,
      int batchSize,
      BurstLoad.Outcome outcome,
      Sampler sampler)
      throws SQLException {
    long decisionRows =
        count(server, "SELECT count(*) FROM " + SqlIdentifier.require(schema) + ".decision");
    long decisionBytes =
        number(
            server,
            "SELECT coalesce(sum(pg_total_relation_size(i.inhrelid)), 0) FROM pg_inherits i"
                + " WHERE i.inhparent = to_regclass(?)",
            schema + ".decision");
    long ledgerBytes =
        number(
            server,
            "SELECT coalesce(sum(pg_total_relation_size(c.oid)), 0) FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind = 'r'",
            schema);
    OptionalLong limit = settings.memoryLimitBytes();
    ProbeSummary.Memory memory;
    if (limit.isPresent()) {
      memory = new ProbeSummary.Memory(limit, "container memory limit", true);
    } else {
      // no container limit (variant A): the memory of the runner, never comparable to SP-L's ratio
      OptionalLong runner = Sampler.machineMemoryBytes();
      memory =
          new ProbeSummary.Memory(
              runner,
              runner.isPresent() ? "runner memory, no container limit" : "not known",
              false);
    }
    return new ProbeSummary.Inputs(
        settings.variant(),
        batchSize,
        settings.writers(), // one source id for each writer
        settings.ratePerSecond(),
        outcome.scheduledBatches(),
        outcome.coveredNanos(),
        new ProbeSummary.Sizes(decisionRows, decisionBytes, ledgerBytes),
        memory,
        settings.forceSynchronousCommit(),
        new ProbeSummary.SamplerFile(
            "sampler-" + settings.variant() + "-n" + batchSize + ".csv",
            sampler.rows(),
            sampler.failures()),
        outcome.failure().map(ProbeSummary::describe));
  }

  private static long count(DataSource server, String sql) throws SQLException {
    try (Connection connection = server.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  private static long number(DataSource server, String sql, String parameter) throws SQLException {
    try (Connection connection = server.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getLong(1) : 0;
      }
    }
  }

  /** The server version and the settings that matter, as lines of the read-out. */
  private static List<String> serverSettings(DataSource server) throws SQLException {
    List<String> lines = new ArrayList<>();
    try (Connection connection = server.getConnection()) {
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SHOW server_version")) {
        lines.add("server version " + (rows.next() ? rows.getString(1) : "unknown"));
      }
      Array names = connection.createArrayOf("text", SERVER_SETTINGS);
      // current_setting gives the formatted value ('340MB', '15min'); the raw setting of
      // pg_settings is in units of the server (shared_buffers: 8kB pages), which misreads as bytes
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT name, current_setting(name) FROM pg_settings"
                  + " WHERE name = ANY(?) ORDER BY name")) {
        statement.setArray(1, names);
        StringBuilder line = new StringBuilder("server settings at the end of the run:");
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            line.append(' ').append(rows.getString(1)).append('=').append(rows.getString(2));
          }
        }
        lines.add(line.toString());
      }
    }
    return lines;
  }
}
