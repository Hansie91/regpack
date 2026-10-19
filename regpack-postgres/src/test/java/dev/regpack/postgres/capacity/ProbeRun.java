package dev.regpack.postgres.capacity;

import dev.regpack.ports.Clock;
import dev.regpack.postgres.PgLedgerStore;
import dev.regpack.postgres.PgTestDatabase;
import dev.regpack.postgres.SqlIdentifier;
import dev.regpack.postgres.migrate.Migrator;
import java.io.IOException;
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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * One run of the capacity probe for one batch size, shared by the capacity job ({@code
 * LedgerBurstProbeIT}, hours) and by the smoke test that runs the same code for five seconds
 * ({@code LedgerBurstProbeSmokeIT}): migrate a schema of its own, start the {@link Sampler}, drive
 * the {@link BurstLoad} through the production adapter, then write the read-out ({@link
 * ProbeSummary}).
 *
 * <p>What the run keeps must survive a server that is gone after the load (CAPACITY-2): the
 * read-out is written first from the batch observations, with the sizes unknown, and the file of
 * the per-batch observations next to it; only then are the size and settings queries tried, each
 * guarded, and the read-out is written again with their answers or with the reason they are not
 * there. A dropped schema that cannot be dropped never replaces the error of the run.
 *
 * <p>The files in the directory are {@code sampler-<tag>.csv}, {@code batches-<tag>.csv} and {@code
 * summary-<tag>.txt}, where the tag is the variant and the batch size.
 */
final class ProbeRun {
  /**
   * The only timeout raised from the adapter defaults (Convention 11 allows the statement timeout).
   * The lock timeout stays at the adapter default of five seconds, so that the read-out shows what
   * the default timeouts do under the load: a unit that waits for a lock longer than that is a
   * result of the probe, not something to hide.
   */
  private static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(5);

  /** The queries after the run get a bounded wait: a server that stalled must not hang them. */
  private static final int AFTER_RUN_QUERY_TIMEOUT_SECONDS = 30;

  private static final int AFTER_RUN_NETWORK_TIMEOUT_MILLIS = 60_000;

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

  /** What is asked of the server after the load. */
  interface Queries {
    /** The sizes of the tables of the run. */
    ProbeSummary.Sizes sizes() throws SQLException;

    /** The server version and the settings that matter, as lines of the read-out. */
    List<String> serverSettings() throws SQLException;
  }

  /**
   * What a run left behind.
   *
   * @param tag the variant and the batch size, as in the file names
   * @param summaryFile the read-out
   * @param samplerFile the sampler file
   * @param batchesFile the per-batch observations
   * @param lines the lines of the read-out as written
   * @param outcome what the load observed
   * @param sampler the sampler's counters
   * @param samplerProblem why the sampler file cannot serve as evidence, if it cannot
   */
  record Result(
      String tag,
      Path summaryFile,
      Path samplerFile,
      Path batchesFile,
      List<String> lines,
      BurstLoad.Outcome outcome,
      ProbeSummary.SamplerFile sampler,
      Optional<String> samplerProblem) {
    Result {
      lines = List.copyOf(lines);
    }
  }

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

  private ProbeRun() {}

  /**
   * Runs the load of {@code batchSize} against {@code server} in a schema of its own and writes the
   * files into {@code directory}. Returns when the run is clean; throws an {@code AssertionError},
   * after the read-out is written, when the load stopped on an error or the sampler did not record
   * the run.
   */
  static Result run(DataSource server, ProbeSettings settings, int batchSize, Path directory)
      throws Exception {
    String schema = PgTestDatabase.freshSchema();
    Throwable primary = null;
    try {
      Migrator.migrate(server, schema);
      String tag = tag(settings, batchSize);
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
      Sampler sampler =
          new Sampler(
              server,
              directory.resolve(samplerName(tag)),
              load::progressColumns,
              TimeUnit.SECONDS.toNanos(1),
              settings.cgroupDirectory());

      sampler.start();
      BurstLoad.Outcome outcome;
      try {
        outcome = load.run();
      } finally {
        sampler.stop();
      }
      ProbeSummary.SamplerFile counters =
          new ProbeSummary.SamplerFile(
              samplerName(tag), sampler.rows(), sampler.failures(), sampler.rowsWithViews());
      Result result =
          finish(
              directory, settings, batchSize, outcome, counters, new ServerQueries(server, schema));
      raise(result);
      return result;
    } catch (Throwable t) {
      primary = t;
      throw t;
    } finally {
      cleanUp(primary, () -> PgTestDatabase.dropSchema(schema));
    }
  }

  /**
   * Writes the read-out and the per-batch file. The read-out is written first with the sizes
   * unknown, so that the measurement is on disk before the server is asked anything; then the
   * queries are tried, and the read-out is written again with their answers, or with the reason a
   * query failed.
   */
  static Result finish(
      Path directory,
      ProbeSettings settings,
      int batchSize,
      BurstLoad.Outcome outcome,
      ProbeSummary.SamplerFile sampler,
      Queries queries)
      throws IOException {
    String tag = tag(settings, batchSize);
    Path summaryFile = directory.resolve("summary-" + tag + ".txt");
    Path batchesFile = directory.resolve(batchesName(tag));

    ProbeSummary.Sizes unread = ProbeSummary.Sizes.unavailable("sizes unavailable (not read yet)");
    List<String> first = lines(settings, batchSize, outcome, sampler, unread, List.of());
    Files.write(summaryFile, first, StandardCharsets.UTF_8);
    Files.write(
        batchesFile, ProbeSummary.batchLines(outcome.observations()), StandardCharsets.UTF_8);

    ProbeSummary.Sizes sizes;
    try {
      sizes = queries.sizes();
    } catch (SQLException | RuntimeException e) {
      sizes = ProbeSummary.Sizes.unavailable(ProbeSummary.unavailable("sizes", e));
    }
    List<String> serverLines;
    try {
      serverLines = queries.serverSettings();
    } catch (SQLException | RuntimeException e) {
      serverLines = List.of(ProbeSummary.unavailable("server settings", e));
    }
    List<String> lines = lines(settings, batchSize, outcome, sampler, sizes, serverLines);
    Files.write(summaryFile, lines, StandardCharsets.UTF_8);
    lines.forEach(System.out::println);

    return new Result(
        tag,
        summaryFile,
        directory.resolve(sampler.name()),
        batchesFile,
        lines,
        outcome,
        sampler,
        ProbeSummary.samplerProblem(sampler, Math.round(outcome.coveredNanos() / 1e9)));
  }

  /** Throws the error of a run that stopped or whose sampler did not record it. */
  static void raise(Result result) {
    if (result.outcome().failure().isPresent()) {
      Throwable failure = result.outcome().failure().get();
      AssertionError error =
          new AssertionError(ProbeSummary.loadStoppedMessage(result.tag(), failure), failure);
      result
          .samplerProblem()
          .ifPresent(
              problem ->
                  error.addSuppressed(
                      new AssertionError(
                          ProbeSummary.samplerStoppedMessage(result.tag(), problem))));
      throw error;
    }
    result
        .samplerProblem()
        .ifPresent(
            problem -> {
              throw new AssertionError(ProbeSummary.samplerStoppedMessage(result.tag(), problem));
            });
  }

  /**
   * Runs {@code cleanup}; a failure of it is added to {@code primary} as suppressed when there is
   * one, so that it cannot replace the error of the run, and is thrown only when the run was clean.
   */
  static void cleanUp(Throwable primary, Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException e) {
      if (primary == null) {
        throw e;
      }
      primary.addSuppressed(e);
    }
  }

  static String tag(ProbeSettings settings, int batchSize) {
    return settings.variant() + "-n" + batchSize;
  }

  static String samplerName(String tag) {
    return "sampler-" + tag + ".csv";
  }

  static String batchesName(String tag) {
    return "batches-" + tag + ".csv";
  }

  private static List<String> lines(
      ProbeSettings settings,
      int batchSize,
      BurstLoad.Outcome outcome,
      ProbeSummary.SamplerFile sampler,
      ProbeSummary.Sizes sizes,
      List<String> serverLines) {
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
    ProbeSummary.Inputs inputs =
        new ProbeSummary.Inputs(
            settings.variant(),
            batchSize,
            settings.writers(), // one source id for each writer
            settings.ratePerSecond(),
            outcome.scheduledBatches(),
            outcome.coveredNanos(),
            sizes,
            memory,
            settings.forceSynchronousCommit(),
            sampler,
            outcome.failure().map(ProbeSummary::describe),
            Sampler.cpuModel());
    // the cause of a run that stopped is one of the first five lines (the annotations)
    List<String> lines = new ArrayList<>(ProbeSummary.of(inputs, outcome.observations()).lines());
    lines.addAll(serverLines);
    return lines;
  }

  /** The size and settings queries against the real server. */
  static final class ServerQueries implements Queries {
    private final DataSource server;
    private final String schema;

    ServerQueries(DataSource server, String schema) {
      this.server = server;
      this.schema = SqlIdentifier.require(schema);
    }

    @Override
    public ProbeSummary.Sizes sizes() throws SQLException {
      long decisionRows = count("SELECT count(*) FROM " + schema + ".decision");
      long decisionBytes =
          number(
              "SELECT coalesce(sum(pg_total_relation_size(i.inhrelid)), 0) FROM pg_inherits i"
                  + " WHERE i.inhparent = to_regclass(?)",
              schema + ".decision");
      long ledgerBytes =
          number(
              "SELECT coalesce(sum(pg_total_relation_size(c.oid)), 0) FROM pg_class c"
                  + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                  + " WHERE n.nspname = ? AND c.relkind = 'r'",
              schema);
      return new ProbeSummary.Sizes(decisionRows, decisionBytes, ledgerBytes);
    }

    private Connection connect() throws SQLException {
      Connection connection = server.getConnection();
      try {
        connection.setNetworkTimeout(Runnable::run, AFTER_RUN_NETWORK_TIMEOUT_MILLIS);
        return connection;
      } catch (SQLException | RuntimeException e) {
        connection.close();
        throw e;
      }
    }

    private long count(String sql) throws SQLException {
      try (Connection connection = connect();
          Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(AFTER_RUN_QUERY_TIMEOUT_SECONDS);
        try (ResultSet rows = statement.executeQuery(sql)) {
          return rows.next() ? rows.getLong(1) : 0;
        }
      }
    }

    private long number(String sql, String parameter) throws SQLException {
      try (Connection connection = connect();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(AFTER_RUN_QUERY_TIMEOUT_SECONDS);
        statement.setString(1, parameter);
        try (ResultSet rows = statement.executeQuery()) {
          return rows.next() ? rows.getLong(1) : 0;
        }
      }
    }

    @Override
    public List<String> serverSettings() throws SQLException {
      List<String> lines = new ArrayList<>();
      try (Connection connection = connect()) {
        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(AFTER_RUN_QUERY_TIMEOUT_SECONDS);
          try (ResultSet rows = statement.executeQuery("SHOW server_version")) {
            lines.add("server version " + (rows.next() ? rows.getString(1) : "unknown"));
          }
        }
        Array names = connection.createArrayOf("text", SERVER_SETTINGS);
        // current_setting gives the formatted value ('340MB', '15min'); the raw setting of
        // pg_settings is in units of the server (shared_buffers: 8kB pages), which misreads as
        // bytes
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT name, current_setting(name) FROM pg_settings"
                    + " WHERE name = ANY(?) ORDER BY name")) {
          statement.setQueryTimeout(AFTER_RUN_QUERY_TIMEOUT_SECONDS);
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
}
