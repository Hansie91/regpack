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
    Statement statement =
        (Statement)
            Proxy.newProxyInstance(
                SamplerRunTest.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "executeQuery" -> {
                        if (fails.test(queries.incrementAndGet())) {
                          throw new SQLException("canned failure", "57014");
                        }
                        yield answer((String) args[0]);
                      }
                      case "close" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                SamplerRunTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "setAutoCommit", "close" -> null;
                      case "createStatement" -> statement;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    return (DataSource)
        Proxy.newProxyInstance(
            SamplerRunTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getConnection" -> connection;
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
  void stopBeforeTheFirstSampleLeavesAnEmptyFileAndNoFailure() throws Exception {
    Path file = directory.resolve("sampler.csv");
    Sampler sampler = new Sampler(server(new AtomicInteger(), n -> false), file, Map::of);

    sampler.start();
    sampler.stop();

    assertThat(Files.exists(file)).isTrue();
    assertThat(sampler.failures()).isZero();
  }
}
