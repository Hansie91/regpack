package dev.regpack.postgres;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  private volatile SQLWarning commitWarning;
  private volatile SQLException failWarnings;
  private volatile Fault fault;
  private volatile Answers answers;
  private volatile int updateCount;
  private volatile int batchCount = 1;

  /**
   * The warning chain of the connection, as {@code getWarnings} sees it. One chain for all the
   * connections of this spy: a test works with one connection at a time, and a chain that is there
   * before a unit starts is the leftover of an earlier use of a pooled connection.
   */
  private final AtomicReference<SQLWarning> warnings = new AtomicReference<>();

  /** A statement whose text contains {@code fragment} fails with {@code exception}. */
  private record Fault(String fragment, SQLException exception, AtomicInteger remaining) {}

  /**
   * What the fake server answers to a query: the rows (values by column, 1-based in the result set)
   * for the text of the statement, given the events recorded so far, so that an answer can depend
   * on what was executed before. An empty list is a result without rows.
   */
  @FunctionalInterface
  interface Answers {
    List<Object[]> rows(String sql, List<String> events);
  }

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

  /**
   * Every {@code commit} raises this warning on the connection, as the driver does with the {@code
   * WARNING} that the server sends when a wait for a synchronous standby is cancelled: after the
   * commit has returned, {@code getWarnings} holds it, and {@code clearWarnings} drops it.
   */
  SqlSpy warnOnCommit(SQLWarning warning) {
    this.commitWarning = warning;
    return this;
  }

  /**
   * A warning arrives on the connection now, for example the notice of a statement. It is added to
   * the end of the chain that {@code getWarnings} returns until {@code clearWarnings} drops it.
   */
  void raise(SQLWarning warning) {
    warnings.updateAndGet(
        chain -> {
          if (chain == null) {
            return warning;
          }
          if (chain != warning) {
            chain.setNextWarning(warning);
          }
          return chain;
        });
  }

  /** {@code getWarnings} fails with this exception (a connection that broke after its commit). */
  SqlSpy failGetWarningsWith(SQLException exception) {
    this.failWarnings = exception;
    return this;
  }

  /**
   * Every execution of a statement whose text contains {@code sqlFragment} (execute, executeQuery,
   * executeUpdate, executeBatch) fails with this exception, after it is recorded. For a batch the
   * exception is what the driver throws from {@code executeBatch}, for example a {@link
   * java.sql.BatchUpdateException}.
   */
  SqlSpy failStatementWith(String sqlFragment, SQLException exception) {
    return failStatementWith(sqlFragment, exception, Integer.MAX_VALUE);
  }

  /**
   * Like {@link #failStatementWith(String, SQLException)}, but only the first {@code times}
   * executions fail: the later ones are answered as if there were no fault (a transient failure).
   */
  SqlSpy failStatementWith(String sqlFragment, SQLException exception, int times) {
    this.fault = new Fault(sqlFragment, exception, new AtomicInteger(times));
    return this;
  }

  /**
   * A fake server that answers the queries of prepared statements and of plain statements; it
   * applies only over no real data source ({@code over(null)}).
   */
  SqlSpy answering(Answers answers) {
    this.answers = answers;
    return this;
  }

  /**
   * The row count that a fake server returns from {@code executeUpdate} (default 0: nothing
   * changed). It applies only over no real data source.
   */
  SqlSpy updatingRows(int count) {
    this.updateCount = count;
    return this;
  }

  /**
   * The count that a fake server returns for each statement of an {@code executeBatch} (default 1:
   * every statement of the batch stored a row; 0 is a batch of {@code ON CONFLICT DO NOTHING}
   * inserts that all conflicted). It applies only over no real data source.
   */
  SqlSpy batchingRows(int count) {
    this.batchCount = count;
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
            if (commitWarning != null) {
              raise(commitWarning);
            }
          } else if (name.equals("clearWarnings")) {
            events.add("clearWarnings");
            warnings.set(null);
          } else if (name.equals("getWarnings")) {
            events.add("getWarnings");
            if (failWarnings != null) {
              throw failWarnings;
            }
            SQLWarning chain = warnings.get();
            if (chain != null || real == null) {
              return chain;
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
    AtomicInteger batched = new AtomicInteger();
    InvocationHandler handler =
        (proxy, method, args) -> {
          String name = method.getName();
          if (name.equals("addBatch") && (args == null || args.length == 0)) {
            batched.incrementAndGet();
          }
          if (name.startsWith("execute")) {
            statements.incrementAndGet();
            String sql =
                args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
            events.add("execute: " + sql);
            Fault failing = fault;
            if (failing != null
                && sql != null
                && sql.contains(failing.fragment())
                && failing.remaining().getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
              throw failing.exception();
            }
            if (real == null && name.equals("executeQuery") && answers != null) {
              return resultSetOf(answers.rows(sql, List.copyOf(events)));
            }
          } else if (name.equals("setString")) {
            events.add("bind " + args[0] + "=" + args[1]);
          }
          if (real == null) {
            if (name.equals("executeBatch")) {
              int[] counts = new int[batched.getAndSet(0)];
              Arrays.fill(counts, batchCount);
              return counts;
            }
            if (name.equals("executeUpdate")) {
              return updateCount;
            }
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

  /** A result set over the given rows: next, wasNull and the typed getters by column index. */
  private static ResultSet resultSetOf(List<Object[]> rows) {
    AtomicInteger cursor = new AtomicInteger(-1);
    AtomicReference<Object> last = new AtomicReference<>();
    return (ResultSet)
        Proxy.newProxyInstance(
            SqlSpy.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals("next")) {
                return cursor.incrementAndGet() < rows.size();
              }
              if (name.equals("wasNull")) {
                return last.get() == null;
              }
              if (name.startsWith("get") && args != null && args[0] instanceof Integer column) {
                Object value = rows.get(cursor.get())[column - 1];
                last.set(value);
                return typed(name, value, method);
              }
              return defaultFor(method);
            });
  }

  private static Object typed(String getter, Object value, Method method) {
    if (value == null) {
      return defaultFor(method);
    }
    return switch (getter) {
      case "getShort" -> ((Number) value).shortValue();
      case "getInt" -> ((Number) value).intValue();
      case "getLong" -> ((Number) value).longValue();
      case "getBoolean" -> (Boolean) value;
      case "getString" -> value.toString();
      default -> value; // getBytes, getObject(column, type)
    };
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
