package dev.regpack.postgres;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Test support: a {@link DataSource} whose connections and statements are recording proxies. Over a
 * real data source it counts the statements an adapter sends (the tripwire against per-row round
 * trips); over none it is a fake server that answers every statement with an empty result, so the
 * unit-of-work prologue and its error paths can be tested without a database.
 */
final class SqlSpy {
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final AtomicInteger statements = new AtomicInteger();
  private final AtomicInteger opened = new AtomicInteger();
  private final AtomicInteger closed = new AtomicInteger();
  private volatile SQLException failOpen;
  private volatile SQLException failCommit;

  /** The data source to hand to the adapter; {@code real} may be null (a fake server). */
  DataSource over(DataSource real) {
    return (DataSource)
        Proxy.newProxyInstance(
            SqlSpy.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getConnection")) {
                if (failOpen != null) {
                  throw failOpen;
                }
                opened.incrementAndGet();
                events.add("open");
                Connection connection = real == null ? null : (Connection) call(real, method, args);
                return connectionProxy(connection);
              }
              if (real == null) {
                return defaultFor(method);
              }
              return call(real, method, args);
            });
  }

  /** Every {@code getConnection} fails with this exception. */
  SqlSpy failOpenWith(SQLException exception) {
    this.failOpen = exception;
    return this;
  }

  /** Every {@code commit} fails with this exception (after it is recorded). */
  SqlSpy failCommitWith(SQLException exception) {
    this.failCommit = exception;
    return this;
  }

  /** Marks a point of the test body in the event list. */
  void mark(String event) {
    events.add(event);
  }

  /** The recorded events in order: open, setAutoCommit(..), execute: .., bind .., commit, ... */
  List<String> events() {
    return List.copyOf(events);
  }

  /** The number of statement executions (execute, executeQuery, executeUpdate, executeBatch). */
  int statements() {
    return statements.get();
  }

  /** Connections opened and not yet closed. */
  int openConnections() {
    return opened.get() - closed.get();
  }

  /**
   * A data source whose every connection first runs the given SQL statements (session defaults),
   * the way a pool or a connection URL can pre-set a session.
   */
  static DataSource withSessionSetup(DataSource real, String... setup) {
    return (DataSource)
        Proxy.newProxyInstance(
            SqlSpy.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = call(real, method, args);
              if (method.getName().equals("getConnection")) {
                Connection connection = (Connection) result;
                try (Statement statement = connection.createStatement()) {
                  for (String sql : setup) {
                    statement.execute(sql);
                  }
                }
              }
              return result;
            });
  }

  private Connection connectionProxy(Connection real) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          String name = method.getName();
          if (name.equals("setAutoCommit")) {
            events.add("setAutoCommit(" + args[0] + ")");
          } else if (name.equals("commit")) {
            events.add("commit");
            if (failCommit != null) {
              throw failCommit;
            }
          } else if (name.equals("rollback")) {
            events.add("rollback");
          } else if (name.equals("close")) {
            events.add("close");
            closed.incrementAndGet();
          }
          Object result = real == null ? defaultFor(method) : call(real, method, args);
          if (name.equals("createStatement")) {
            return statementProxy((Statement) result, Statement.class, null);
          }
          if (name.equals("prepareStatement")) {
            return statementProxy((PreparedStatement) result, PreparedStatement.class, sql(args));
          }
          return result;
        };
    return (Connection)
        Proxy.newProxyInstance(
            SqlSpy.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  private Object statementProxy(Object real, Class<?> type, String preparedSql) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          String name = method.getName();
          if (name.startsWith("execute")) {
            statements.incrementAndGet();
            String sql =
                args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
            events.add("execute: " + sql);
          } else if (name.equals("setString")) {
            events.add("bind " + args[0] + "=" + args[1]);
          }
          if (real == null) {
            return name.equals("executeQuery") ? resultSetProxy() : defaultFor(method);
          }
          return call(real, method, args);
        };
    return Proxy.newProxyInstance(SqlSpy.class.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static ResultSet resultSetProxy() {
    return (ResultSet)
        Proxy.newProxyInstance(
            SqlSpy.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> defaultFor(method));
  }

  private static String sql(Object[] args) {
    return args != null && args.length > 0 && args[0] instanceof String s ? s : "?";
  }

  private static Object call(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** What a fake server returns for a call: false, zero, or nothing. */
  private static Object defaultFor(Method method) {
    Class<?> type = method.getReturnType();
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == double.class) {
      return 0.0;
    }
    if (type == float.class) {
      return 0.0f;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    return null;
  }
}
