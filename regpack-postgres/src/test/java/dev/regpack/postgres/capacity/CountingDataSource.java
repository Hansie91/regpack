package dev.regpack.postgres.capacity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that counts the statements the adapter sends, per calling thread (T21, the
 * statements per batch of the read-out). It counts every {@code execute}, {@code executeQuery},
 * {@code executeUpdate} and {@code executeBatch} call as one execution, and every {@code addBatch}
 * as one batched row, so that a per-row round trip shows up as a count that grows with the batch.
 * Counts are kept per thread so that four writers do not count each other's statements.
 */
final class CountingDataSource {
  /** What one thread has sent so far. */
  record Counts(long executions, long batchedRows) {
    Counts minus(Counts earlier) {
      return new Counts(executions - earlier.executions, batchedRows - earlier.batchedRows);
    }
  }

  private static final class Tally {
    long executions;
    long batchedRows;
  }

  private final ThreadLocal<Tally> tally = ThreadLocal.withInitial(Tally::new);
  private final DataSource wrapped;

  private CountingDataSource(DataSource real) {
    this.wrapped = wrap(Objects.requireNonNull(real, "real"));
  }

  static CountingDataSource over(DataSource real) {
    return new CountingDataSource(real);
  }

  /** The data source to hand to the adapter. */
  DataSource dataSource() {
    return wrapped;
  }

  /** The counts of the calling thread. */
  Counts currentThread() {
    Tally mine = tally.get();
    return new Counts(mine.executions, mine.batchedRows);
  }

  private DataSource wrap(DataSource real) {
    return (DataSource)
        Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(real, method, args);
              if (method.getName().equals("getConnection") && result instanceof Connection c) {
                return connection(c);
              }
              return result;
            });
  }

  private Connection connection(Connection real) {
    return (Connection)
        Proxy.newProxyInstance(
            CountingDataSource.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = invoke(real, method, args);
              if (method.getName().equals("createStatement") && result instanceof Statement s) {
                return statement(s, Statement.class);
              }
              if (method.getName().equals("prepareStatement")
                  && result instanceof PreparedStatement p) {
                return statement(p, PreparedStatement.class);
              }
              return result;
            });
  }

  private Object statement(Object real, Class<?> type) {
    return Proxy.newProxyInstance(
        CountingDataSource.class.getClassLoader(),
        new Class<?>[] {type},
        (proxy, method, args) -> {
          String name = method.getName();
          if (name.startsWith("execute")) {
            tally.get().executions++;
          } else if (name.equals("addBatch")) {
            tally.get().batchedRows++;
          }
          return invoke(real, method, args);
        });
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
