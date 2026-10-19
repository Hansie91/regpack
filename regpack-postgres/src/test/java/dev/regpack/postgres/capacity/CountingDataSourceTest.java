package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The counting wrapper of the capacity probe (T21): statements per batch, counted where the adapter
 * sends them, per thread, so that the four writers do not count each other's statements.
 */
class CountingDataSourceTest {
  /** A fake server: every call is accepted, an execute returns an update count of one. */
  private static DataSource fakeServer(AtomicInteger opened) {
    return (DataSource)
        Proxy.newProxyInstance(
            CountingDataSourceTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("getConnection")) {
                throw new UnsupportedOperationException(method.getName());
              }
              opened.incrementAndGet();
              return (Connection)
                  Proxy.newProxyInstance(
                      CountingDataSourceTest.class.getClassLoader(),
                      new Class<?>[] {Connection.class},
                      (connection, call, callArgs) ->
                          switch (call.getName()) {
                            case "createStatement" -> statement(Statement.class);
                            case "prepareStatement" -> statement(PreparedStatement.class);
                            case "close", "setAutoCommit", "commit" -> null;
                            default -> throw new UnsupportedOperationException(call.getName());
                          });
            });
  }

  private static Object statement(Class<?> type) {
    return Proxy.newProxyInstance(
        CountingDataSourceTest.class.getClassLoader(),
        new Class<?>[] {type},
        (proxy, method, args) -> {
          Class<?> returned = method.getReturnType();
          if (returned == int.class) {
            return 1;
          }
          if (returned == long.class) {
            return 1L;
          }
          if (returned == boolean.class) {
            return false;
          }
          if (returned == int[].class) {
            return new int[] {1, 1};
          }
          return null;
        });
  }

  @Test
  void countsExecutionsAndBatchedRowsOfTheCallingThread() throws Exception {
    AtomicInteger opened = new AtomicInteger();
    CountingDataSource counting = CountingDataSource.over(fakeServer(opened));
    CountingDataSource.Counts before = counting.currentThread();

    try (Connection connection = counting.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL TimeZone = UTC");
        statement.executeUpdate("UPDATE t SET a = 1");
      }
      try (PreparedStatement prepared = connection.prepareStatement("INSERT INTO t VALUES (?)")) {
        for (int i = 0; i < 5; i++) {
          prepared.setInt(1, i);
          prepared.addBatch();
        }
        prepared.executeBatch();
        prepared.executeQuery();
      }
      connection.commit();
    }

    CountingDataSource.Counts used = counting.currentThread().minus(before);
    assertThat(used.executions()).isEqualTo(4);
    assertThat(used.batchedRows()).isEqualTo(5);
    assertThat(opened).hasValue(1);
  }

  @Test
  void otherThreadsDoNotCountTowardsThisOne() throws Exception {
    CountingDataSource counting = CountingDataSource.over(fakeServer(new AtomicInteger()));
    CountingDataSource.Counts before = counting.currentThread();

    Thread other =
        new Thread(
            () -> {
              try (Connection connection = counting.dataSource().getConnection();
                  Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                statement.execute("SELECT 2");
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    other.start();
    other.join();

    assertThat(counting.currentThread().minus(before).executions()).isZero();
  }
}
