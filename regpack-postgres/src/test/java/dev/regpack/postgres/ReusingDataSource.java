package dev.regpack.postgres;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A minimal pool for tests that make thousands of short calls: a connection that is closed goes
 * back to a stack and the next call gets it again, so the cost of opening a session (about a
 * quarter of a second on the development machine) is paid a few times and not for every unit. A
 * unit of the adapter keeps no state in the session ({@link Unit}: every setting is transaction
 * local), so a reused connection is as good as a new one; a connection that comes back with an open
 * or aborted transaction is rolled back and put in auto-commit mode first. Not thread safe: it
 * serves one test thread, which can still hold more than one connection at a time.
 */
final class ReusingDataSource implements DataSource, AutoCloseable {
  private final DataSource origin;
  private final Deque<Connection> idle = new ArrayDeque<>();
  private final List<Connection> opened = new ArrayList<>();

  ReusingDataSource(DataSource origin) {
    this.origin = Objects.requireNonNull(origin, "origin");
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection physical = idle.poll();
    if (physical == null) {
      physical = origin.getConnection();
      opened.add(physical);
    }
    return lend(physical);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return getConnection();
  }

  /** A connection whose {@code close} gives the physical one back instead of closing it. */
  private Connection lend(Connection physical) {
    boolean[] open = {true};
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("close") && method.getParameterCount() == 0) {
                if (open[0]) {
                  open[0] = false;
                  giveBack(physical);
                }
                return null;
              }
              if (method.getName().equals("isClosed") && method.getParameterCount() == 0) {
                return !open[0] || physical.isClosed();
              }
              if (!open[0]) {
                throw new SQLException("this connection was closed");
              }
              try {
                return method.invoke(physical, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private void giveBack(Connection physical) throws SQLException {
    if (!physical.getAutoCommit()) {
      physical.rollback();
      physical.setAutoCommit(true);
    }
    idle.push(physical);
  }

  /** Closes every physical connection that this pool opened. */
  @Override
  public void close() {
    for (Connection physical : opened) {
      try {
        physical.close();
      } catch (SQLException ignored) {
        // the test is over; the server drops the session anyway
      }
    }
    opened.clear();
    idle.clear();
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
    // no log
  }

  @Override
  public void setLoginTimeout(int seconds) {
    // the origin decides
  }

  @Override
  public int getLoginTimeout() {
    return 0;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    throw new SQLException("this data source wraps nothing");
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return false;
  }
}
