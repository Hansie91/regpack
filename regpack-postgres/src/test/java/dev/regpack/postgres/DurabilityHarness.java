package dev.regpack.postgres;

import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.postgres.migrate.Migrator;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

/**
 * Test support of the two integration tests of the job {@code ledger-durability} (P3b1 T17; tagged
 * {@code durability}, excluded from {@code verify}). The Up phase has a streaming standby and works
 * through {@link AbstractPgStoreTest}; this harness is the fixture of the Down phase, in which the
 * standby has been stopped, and holds what both need.
 *
 * <p><b>The Down fixture.</b> A primary whose {@code synchronous_standby_names} names a standby
 * that is not there makes every commit that writes wait for ever when it commits with {@code
 * synchronous_commit = on}. So the fixture has two sessions' worth of settings:
 *
 * <ul>
 *   <li>the <em>setup</em> store and every fixture and the teardown commit locally (the data source
 *       starts its sessions with {@code -c synchronous_commit=local}, and the setup store does not
 *       force the setting), so that creating a lease, a delivery or an attempt id never waits;
 *   <li>the store <em>under test</em> starts with the same local default and forces {@code
 *       synchronous_commit = on} in the prologue of each of its units ({@code
 *       forceSynchronousCommit}), so that only the unit under test waits for the standby. Its
 *       statement timeout is 2 seconds, as in the plan.
 * </ul>
 *
 * <p><b>Ending a wait.</b> A commit that waits for a standby that is not there waits until it is
 * cancelled. A statement timeout does not end it: the server switches the timeout off before it
 * commits (postgres.c, {@code finish_xact_command}: "cancel any active statement timeout before
 * committing"), so the 2 seconds above bound the statements before the COMMIT and not the COMMIT.
 * The wait ends when the backend is cancelled or terminated, and a cancel is exactly the event that
 * the commit-warning rule is for: the server answers with the WARNING "canceling wait for
 * synchronous replication due to user request" and the commit returns. {@link
 * #callWhileTheCommitWaits} runs a call in a thread of its own, waits until a backend of the
 * database waits for synchronous replication, gives a statement timeout of the unit a fair chance
 * to end the wait first (so that the run says which of the two did), and then cancels the backend
 * with {@code pg_cancel_backend} from a second connection.
 */
final class DurabilityHarness implements AutoCloseable {
  /** The session default of the fixtures: a commit never waits for a standby. */
  static final String LOCAL_COMMIT = "-c synchronous_commit=local";

  /** The statement timeout of the store under test (2 seconds, as in the plan). */
  static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(2);

  /** How long a wait must have lasted before the harness cancels it: more than the timeout. */
  private static final Duration MINIMUM_WAIT = Duration.ofSeconds(6);

  /** The longest a call may take, from its start to its end. */
  private static final Duration OVERALL_LIMIT = Duration.ofSeconds(90);

  private static final int STANDBY_POLLS = 60;

  /** The backends of this database that are waiting for a standby, or are committing just now. */
  private static final String WAITERS_SQL =
      "SELECT pid FROM pg_stat_activity WHERE datname = current_database()"
          + " AND pid <> pg_backend_pid()"
          + " AND (wait_event = 'SyncRep' OR (state = 'active' AND query = 'COMMIT'))";

  /** What a call did, and how its wait ended. */
  record Waited(Throwable thrown, boolean cancelledByTheHarness) {}

  private final DataSource primary;
  private final String schema;
  private final ManualClock clock;
  private final PgLedgerStore setup;
  private final PgLedgerStore underTest;

  private DurabilityHarness(
      DataSource primary,
      String schema,
      ManualClock clock,
      PgLedgerStore setup,
      PgLedgerStore underTest) {
    this.primary = primary;
    this.schema = schema;
    this.clock = clock;
    this.setup = setup;
    this.underTest = underTest;
  }

  /**
   * Waits until no standby streams (a walsender may be listed for a moment after the container
   * stopped), then creates, migrates and opens a fresh schema through a session that commits
   * locally. Skips like {@link PgTestDatabase#dataSource()} without a server.
   */
  static DurabilityHarness open() {
    DataSource primary = PgTestDatabase.dataSource();
    awaitNoStreamingStandby(primary);
    DataSource local = PgTestDatabase.dataSourceWithOptions(LOCAL_COMMIT);
    String schema = PgTestDatabase.freshSchema();
    try {
      Migrator.migrate(local, schema);
      ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
      PgLedgerStore setup =
          new PgLedgerStore(
              local, clock, Config.forSchema(schema).withForceSynchronousCommit(false));
      PgLedgerStore underTest =
          new PgLedgerStore(
              local, clock, Config.forSchema(schema).withStatementTimeout(STATEMENT_TIMEOUT));
      return new DurabilityHarness(primary, schema, clock, setup, underTest);
    } catch (RuntimeException | Error e) {
      PgTestDatabase.dropSchema(schema);
      throw e;
    }
  }

  /** The store that fixtures use: it commits locally, whatever the standby does. */
  PgLedgerStore setup() {
    return setup;
  }

  /** The store under test: every unit of it commits with {@code synchronous_commit = on}. */
  PgLedgerStore underTest() {
    return underTest;
  }

  ManualClock clock() {
    return clock;
  }

  String schema() {
    return schema;
  }

  /**
   * Drops the schema through the fixed {@link PgTestDatabase#dropSchema}, which commits locally.
   */
  @Override
  public void close() {
    PgTestDatabase.dropSchema(schema);
  }

  // ---------------------------------------------------------------------------------------------
  // the standby

  /**
   * Polls {@code pg_stat_replication} once a second, at most 60 times, until no standby is
   * streaming; fails with the last count.
   */
  static void awaitNoStreamingStandby(DataSource primary) {
    long streaming = -1;
    for (int poll = 0; poll < STANDBY_POLLS; poll++) {
      streaming =
          scalar(primary, "SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming'");
      if (streaming == 0) {
        return;
      }
      pause(Duration.ofSeconds(1));
    }
    throw new AssertionError(
        "a standby is still streaming after "
            + STANDBY_POLLS
            + " seconds (count "
            + streaming
            + "): the Down phase needs the standby stopped");
  }

  private static long scalar(DataSource source, String sql) {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw new AssertionError("no row: " + sql);
      }
      return rows.getLong(1);
    } catch (SQLException e) {
      throw new AssertionError("the query failed (sqlstate=" + e.getSQLState() + "): " + sql, e);
    }
  }

  private static void pause(Duration duration) {
    try {
      TimeUnit.MILLISECONDS.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // a commit that waits

  /**
   * Runs {@code call} in a thread of its own. Once a backend of the database has waited for the
   * standby for {@link #MINIMUM_WAIT}, the backends that wait are cancelled with {@code
   * pg_cancel_backend} from a connection of the harness, and the call is allowed to finish. If the
   * call finishes first, the server ended the wait itself (a statement timeout would do, if the
   * server applied it to the COMMIT).
   *
   * @return the exception the call ended with (null if it returned) and whether the harness
   *     cancelled a backend
   * @throws AssertionError when nothing waits and the call does not end, or the call does not end
   *     after the cancel
   */
  Waited callWhileTheCommitWaits(Runnable call) {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Throwable> running =
          pool.submit(
              () -> {
                try {
                  call.run();
                  return null;
                } catch (Throwable t) {
                  return t;
                }
              });
      long started = System.nanoTime();
      long waitingSince = -1;
      boolean cancelled = false;
      while (!running.isDone()) {
        if (System.nanoTime() - started > OVERALL_LIMIT.toNanos()) {
          throw new AssertionError(
              "the call did not end within " + OVERALL_LIMIT.toSeconds() + " seconds");
        }
        List<Integer> waiting = waitingBackends();
        if (!waiting.isEmpty()) {
          if (waitingSince < 0) {
            waitingSince = System.nanoTime();
          }
          if (System.nanoTime() - waitingSince >= MINIMUM_WAIT.toNanos()) {
            for (int pid : waiting) {
              cancel(pid);
            }
            cancelled = true;
            break;
          }
        } else {
          waitingSince = -1;
        }
        pause(Duration.ofMillis(200));
      }
      try {
        return new Waited(running.get(30, TimeUnit.SECONDS), cancelled);
      } catch (TimeoutException e) {
        throw new AssertionError("the call did not end 30 seconds after the cancel", e);
      } catch (ExecutionException e) {
        throw new AssertionError("the thread of the call failed", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private List<Integer> waitingBackends() {
    List<Integer> pids = new ArrayList<>();
    try (Connection connection = primary.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(WAITERS_SQL)) {
      while (rows.next()) {
        pids.add(rows.getInt(1));
      }
    } catch (SQLException e) {
      throw new AssertionError(
          "could not list the waiting backends (sqlstate=" + e.getSQLState() + ")", e);
    }
    return pids;
  }

  private void cancel(int pid) {
    try (Connection connection = primary.getConnection();
        PreparedStatement statement = connection.prepareStatement("SELECT pg_cancel_backend(?)")) {
      statement.setInt(1, pid);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
      }
    } catch (SQLException e) {
      throw new AssertionError("could not cancel a backend (sqlstate=" + e.getSQLState() + ")", e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // building blocks shared by the tests

  /**
   * A ledger that routes the named methods to {@code underTest} and every other method to {@code
   * setup}: the sender of a test then takes its lease and its attempt id from the store that
   * commits locally, and only the call that the test is about waits for the standby.
   */
  static LedgerStore routed(LedgerStore setup, LedgerStore underTest, String... underTestMethods) {
    Set<String> names = Set.of(underTestMethods);
    return (LedgerStore)
        Proxy.newProxyInstance(
            LedgerStore.class.getClassLoader(),
            new Class<?>[] {LedgerStore.class},
            (proxy, method, args) -> {
              LedgerStore target = names.contains(method.getName()) ? underTest : setup;
              try {
                return method.invoke(target, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /**
   * A P-SEALED store over two directories below {@code root}, whose probe puts them in two failure
   * domains (the production gate then has nothing to say against the evidence side).
   */
  static SealedEvidenceStore twoDomainEvidence(Path root, Clock clock) throws IOException {
    Path base = root.toRealPath();
    return SealedEvidenceStore.open(
        new SealedEvidenceStore.Config(
            base.resolve("vol-a"),
            base.resolve("vol-b"),
            "vol-a",
            "vol-b",
            clock,
            path -> path.getFileName().toString()));
  }

  /** Like {@link #twoDomainEvidence} but the probe puts both directories in one failure domain. */
  static SealedEvidenceStore oneDomainEvidence(Path root, Clock clock) throws IOException {
    Path base = root.toRealPath();
    return SealedEvidenceStore.open(
        new SealedEvidenceStore.Config(
            base.resolve("one-a"),
            base.resolve("one-b"),
            "one-a",
            "one-b",
            clock,
            path -> "one device"));
  }

  /**
   * One line for the log of the job, as a notice annotation ({@code ::notice}), which the owner can
   * read without signing in. The message must not contain a line break.
   */
  static void notice(String title, String message) {
    System.out.println("::notice title=" + title + "::" + message.replace('\n', ' '));
  }
}
