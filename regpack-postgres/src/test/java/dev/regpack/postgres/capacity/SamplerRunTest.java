package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sampler's life cycle (T21, H-4) against a fake server: the file is created, takes its header
 * from the first sample that holds the views, is flushed row by row and is still there after the
 * sampler stops; a query that fails is counted and the loop goes on.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SamplerRunTest {
  private static final long FAST = TimeUnit.MILLISECONDS.toNanos(50);

  @TempDir Path directory;

  /**
   * A data source whose statements answer with a canned result for each of the three views; {@code
   * fails} is asked with the 1-based number of the query and a true answer throws a SQLException.
   */
  private static DataSource server(AtomicInteger queries, IntPredicate fails) {
    return server(
        new AtomicInteger(),
        n -> false,
        queries,
        (connection, query) -> {
          if (fails.test(query)) {
            throw new SQLException("canned failure", "57014");
          }
        });
  }

  /** What a query does before it answers: nothing, throw, or block. */
  @FunctionalInterface
  private interface QueryHook {
    /** Called with the 1-based number of the connection and the 1-based number of the query. */
    void before(int connection, int query) throws Exception;
  }

  /**
   * A data source whose {@code connects} counts the {@code getConnection} calls ({@code
   * connectFails} is asked with the 1-based number of the call); each connection answers the three
   * views, after running the hook of the query.
   */
  private static DataSource server(
      AtomicInteger connects, IntPredicate connectFails, AtomicInteger queries, QueryHook hook) {
    return (DataSource)
        Proxy.newProxyInstance(
            SamplerRunTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getConnection" -> {
                    int number = connects.incrementAndGet();
                    if (connectFails.test(number)) {
                      throw new SQLException("canned connect failure", "08001");
                    }
                    yield connection(number, queries, hook);
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static Connection connection(int number, AtomicInteger queries, QueryHook hook) {
    Statement statement =
        (Statement)
            Proxy.newProxyInstance(
                SamplerRunTest.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "executeQuery" -> {
                        hook.before(number, queries.incrementAndGet());
                        yield answer((String) args[0]);
                      }
                      case "setQueryTimeout", "close" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    return (Connection)
        Proxy.newProxyInstance(
            SamplerRunTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "setAutoCommit", "setNetworkTimeout", "close" -> null;
                  case "createStatement" -> statement;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static ResultSet answer(String sql) {
    if (sql.contains("pg_stat_wal")) {
      return SamplerCsvTest.resultSet(
          List.of("wal_records", "wal_bytes"), List.<Object[]>of(new Object[] {10L, 4_096L}));
    }
    if (sql.contains("pg_stat_checkpointer")) {
      return SamplerCsvTest.resultSet(
          List.of("num_timed", "num_requested"), List.<Object[]>of(new Object[] {1L, 2L}));
    }
    return SamplerCsvTest.resultSet(
        List.of("backend_type", "object", "context", "reads"),
        List.<Object[]>of(
            new Object[] {"client backend", "relation", "normal", 5L},
            new Object[] {"client backend", "relation", "vacuum", 3L}));
  }

  private static void awaitRows(Sampler sampler, long rows) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (sampler.rows() < rows && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(20);
    }
    assertThat(sampler.rows()).as("rows written in time").isGreaterThanOrEqualTo(rows);
  }

  private static int columns(String line) {
    // the fake values hold no comma and no quote, so a plain split counts the fields
    return line.split(",", -1).length;
  }

  @Test
  void theFileHoldsTheHeaderOfTheFirstSampleAndEveryRowAndIsKeptAfterStop() throws Exception {
    Path file = directory.resolve("sampler.csv");
    Sampler sampler =
        new Sampler(
            server(new AtomicInteger(), n -> false), file, () -> Map.of("progress", "7"), FAST);

    sampler.start();
    awaitRows(sampler, 3);
    // rows are flushed as they are written: the file is readable while the sampler still runs
    assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).hasSizeGreaterThanOrEqualTo(2);
    sampler.stop();

    assertThat(Files.exists(file)).as("the file is kept after stop").isTrue();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    String header = lines.get(0);
    assertThat(header)
        .startsWith("t_ms,epoch_ms,progress,")
        .contains("wal.wal_records", "checkpointer.num_timed", "io.client_backend.relation.reads");
    assertThat(lines.size() - 1).isEqualTo((int) sampler.rows());
    assertThat(lines.size()).isGreaterThanOrEqualTo(4);
    assertThat(lines.subList(1, lines.size()))
        .allSatisfy(row -> assertThat(columns(row)).isEqualTo(columns(header)));
    assertThat(lines.get(1)).contains(",7,").contains(",10,4096,");
    assertThat(sampler.failures()).as("a clean stop misses no sample").isZero();
  }

  @Test
  void aQueryThatFailsIsCountedAndTheHeaderWaitsForASampleWithTheViews() throws Exception {
    Path file = directory.resolve("sampler.csv");
    AtomicInteger queries = new AtomicInteger();
    // the very first query fails: that sample has no view columns and must not fix the header
    Sampler sampler = new Sampler(server(queries, n -> n == 1), file, Map::of, FAST);

    sampler.start();
    awaitRows(sampler, 2);
    sampler.stop();

    assertThat(sampler.failures()).isEqualTo(1);
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertThat(lines.get(0))
        .contains("wal.wal_records", "checkpointer.num_timed", "io.client_backend.relation.reads");
    assertThat(lines.subList(1, lines.size()))
        .allSatisfy(row -> assertThat(columns(row)).isEqualTo(columns(lines.get(0))))
        .allSatisfy(row -> assertThat(row).contains(",10,4096,"));
  }

  @Test
  void aServerThatNeverAnswersStillLeavesAFileWithItsRowsAfterTenTries() throws Exception {
    Path file = directory.resolve("sampler.csv");
    Sampler sampler = new Sampler(server(new AtomicInteger(), n -> true), file, Map::of, FAST);

    sampler.start();
    awaitRows(sampler, 2);
    sampler.stop();

    // no row was written for the first nine samples; the tenth fixed the header without the views
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertThat(lines.get(0)).startsWith("t_ms,epoch_ms").doesNotContain("wal.");
    assertThat(sampler.failures()).isGreaterThanOrEqualTo(10);
    assertThat(lines.size() - 1).isEqualTo((int) sampler.rows());
  }

  @Test
  void aConnectThatFailsLeavesTheFileAtOnceAndIsRetriedOnTheNextTick() throws Exception {
    Path file = directory.resolve("sampler.csv");
    AtomicInteger connects = new AtomicInteger();
    // the first two getConnection calls fail (CAPACITY-1): the file must exist all the same
    Sampler sampler =
        new Sampler(
            server(connects, n -> n <= 2, new AtomicInteger(), (connection, query) -> {}),
            file,
            Map::of,
            FAST);

    sampler.start();
    assertThat(Files.exists(file)).as("the file exists before any connection").isTrue();
    awaitRows(sampler, 2);
    sampler.stop();

    assertThat(sampler.failures()).isGreaterThanOrEqualTo(2);
    assertThat(connects.get()).as("the third connect succeeded and was kept").isEqualTo(3);
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertThat(lines.get(0)).contains("wal.wal_records", "checkpointer.num_timed");
    assertThat(sampler.rowsWithViews()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void aConnectionThatDiesIsReplacedAndTheViewColumnsComeBack() throws Exception {
    Path file = directory.resolve("sampler.csv");
    AtomicInteger connects = new AtomicInteger();
    // connection 1 answers its first sample (three queries) and then dies with 57P01, as after
    // the restart of a memory-limited container; connection 2 is healthy
    Sampler sampler =
        new Sampler(
            server(
                connects,
                n -> false,
                new AtomicInteger(),
                (connection, query) -> {
                  if (connection == 1 && query > 3) {
                    throw new SQLException("terminating connection", "57P01");
                  }
                }),
            file,
            Map::of,
            FAST);

    sampler.start();
    awaitRows(sampler, 6);
    sampler.stop();

    assertThat(connects.get()).as("one replacement connection").isEqualTo(2);
    assertThat(sampler.failures()).isEqualTo(1);
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    // the last rows carry fresh values from the replacement connection
    assertThat(lines.get(lines.size() - 1)).contains(",10,4096,");
    assertThat(sampler.rowsWithViews()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void aStuckQueryDoesNotStopTheRowsAndTheViewsGetOld() throws Exception {
    Path file = directory.resolve("sampler.csv");
    CountDownLatch release = new CountDownLatch(1);
    Sampler sampler =
        new Sampler(
            server(
                new AtomicInteger(),
                n -> false,
                new AtomicInteger(),
                (connection, query) -> {
                  if (query > 3) {
                    release.await(); // the server stalls after the first sample
                  }
                }),
            file,
            () -> Map.of("progress", "7"),
            FAST);
    try {
      sampler.start();
      awaitRows(sampler, 12); // the rows go on although the query never returns
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      List<String> header = List.of(lines.get(0).split(",", -1));
      int age = header.indexOf("views_age_ms");
      assertThat(age).as("the age of the views is a column").isGreaterThanOrEqualTo(0);
      long lastAge = Long.parseLong(lines.get(lines.size() - 1).split(",", -1)[age]);
      assertThat(lastAge).as("the views of the stalled server are old").isGreaterThan(200);
      assertThat(sampler.rowsWithViews())
          .as("only the first rows hold fresh views")
          .isLessThan(sampler.rows());
    } finally {
      release.countDown();
      sampler.stop();
    }
  }

  @Test
  void aRuntimeExceptionIsCountedAndTheSamplerGoesOn() throws Exception {
    Path file = directory.resolve("sampler.csv");
    AtomicInteger calls = new AtomicInteger();
    Sampler sampler =
        new Sampler(
            server(
                new AtomicInteger(),
                n -> false,
                new AtomicInteger(),
                (connection, query) -> {
                  if (query == 1) {
                    throw new IllegalStateException("not an SQLException");
                  }
                }),
            file,
            () -> {
              if (calls.incrementAndGet() == 2) {
                throw new IllegalArgumentException("the extras fail once");
              }
              return Map.of();
            },
            FAST);

    sampler.start();
    awaitRows(sampler, 4);
    sampler.stop();

    assertThat(sampler.failures()).isGreaterThanOrEqualTo(2);
    assertThat(sampler.rows()).isGreaterThanOrEqualTo(4);
  }

  @Test
  void theCgroupFilesOfTheContainerAreColumnsWhenTheyExistAndNothingWhenTheyDoNot()
      throws Exception {
    Path cgroup = Files.createDirectory(directory.resolve("cgroup"));
    Files.writeString(cgroup.resolve("memory.current"), "123456\n", StandardCharsets.UTF_8);
    Files.writeString(
        cgroup.resolve("memory.events"),
        "low 0\nhigh 0\nmax 4\noom 1\noom_kill 2\n",
        StandardCharsets.UTF_8);
    Path with = directory.resolve("with.csv");
    Sampler sampler =
        new Sampler(server(new AtomicInteger(), n -> false), with, Map::of, FAST, cgroup);
    sampler.start();
    awaitRows(sampler, 2);
    sampler.stop();
    List<String> lines = Files.readAllLines(with, StandardCharsets.UTF_8);
    assertThat(lines.get(0))
        .contains(
            "cgroup.memory_current", "cgroup.memory_events.oom_kill", "cgroup.memory_events.max");
    assertThat(lines.get(1)).contains(",123456,");

    Path without = directory.resolve("without.csv");
    Sampler absent =
        new Sampler(
            server(new AtomicInteger(), n -> false),
            without,
            Map::of,
            FAST,
            directory.resolve("no-such-cgroup"));
    absent.start();
    awaitRows(absent, 2);
    absent.stop();
    assertThat(Files.readAllLines(without, StandardCharsets.UTF_8).get(0))
        .doesNotContain("cgroup.");
    assertThat(absent.failures()).as("an absent cgroup is not a failure").isZero();
  }

  @Test
  void cgroupAndCpuLinesAreParsed() {
    assertThat(Sampler.parseCgroup(List.of("123"), List.of("low 0", "oom_kill 3", "junk")))
        .containsExactly(
            Map.entry("cgroup.memory_current", "123"),
            Map.entry("cgroup.memory_events.low", "0"),
            Map.entry("cgroup.memory_events.oom_kill", "3"));
    assertThat(Sampler.parseCgroup(List.of(), List.of())).isEmpty();
    assertThat(Sampler.parseCgroup(List.of("max"), List.of()))
        .as("memory.current is a number or nothing")
        .isEmpty();

    List<String> cpuinfo =
        List.of(
            "processor\t: 0",
            "model name\t: AMD EPYC 7763 64-Core Processor",
            "processor\t: 1",
            "model name\t: AMD EPYC 7763 64-Core Processor");
    assertThat(Sampler.parseCpuModel(cpuinfo))
        .isEqualTo("AMD EPYC 7763 64-Core Processor (2 cpus)");
    assertThat(Sampler.parseCpuModel(List.of("processor\t: 0")))
        .as("no model name line, as on some ARM kernels")
        .isEmpty();
    assertThat(Sampler.parseCpuModel(List.of())).isEmpty();
  }

  @Test
  void stopBeforeTheFirstSampleLeavesAnEmptyFileAndNoFailure() throws Exception {
    Path file = directory.resolve("sampler.csv");
    Sampler sampler = new Sampler(server(new AtomicInteger(), n -> false), file, Map::of);

    sampler.start();
    sampler.stop();

    assertThat(Files.exists(file)).isTrue();
    assertThat(sampler.failures()).isZero();
  }
}
