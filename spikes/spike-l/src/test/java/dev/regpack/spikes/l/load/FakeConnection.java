package dev.regpack.spikes.l.load;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A connection that records what the writer does and answers nothing but what the writer needs: an
 * outbox id for the RETURNING statement. The bulk loader is replaced by {@link #loader()} so that
 * no COPY protocol is needed.
 */
final class FakeConnection {
  final List<String> log = Collections.synchronizedList(new ArrayList<>());
  final Map<String, List<Object[]>> loaded = new LinkedHashMap<>();
  final Map<String, List<String>> loadedColumns = new LinkedHashMap<>();
  private final String failOnTable;

  FakeConnection() {
    this("");
  }

  FakeConnection(String failOnTable) {
    this.failOnTable = failOnTable;
  }

  BulkLoader loader() {
    return (table, columns, rows) -> {
      String name = table.substring(table.lastIndexOf('.') + 1);
      log.add("load " + name + " rows=" + rows.size());
      if (name.equals(failOnTable)) {
        throw new java.sql.SQLException("scripted failure while loading " + name);
      }
      loaded.computeIfAbsent(name, k -> new ArrayList<>()).addAll(rows);
      loadedColumns.put(name, List.copyOf(columns));
    };
  }

  Connection connection() {
    return (Connection)
        Proxy.newProxyInstance(
            FakeConnection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "setAutoCommit" -> log.add("autocommit=" + args[0]);
                case "commit" -> log.add("commit");
                case "rollback" -> log.add("rollback");
                case "createStatement" -> {
                  return statement();
                }
                case "prepareStatement" -> {
                  return prepared((String) args[0]);
                }
                case "createArrayOf" -> {
                  return array();
                }
                case "isClosed" -> {
                  return false;
                }
                default -> {
                  // every other call is irrelevant to the writer
                }
              }
              return defaultValue(method.getReturnType());
            });
  }

  private Statement statement() {
    return (Statement)
        Proxy.newProxyInstance(
            FakeConnection.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, args) -> {
              if (method.getName().equals("execute")) {
                log.add("exec " + args[0]);
                return false;
              }
              return defaultValue(method.getReturnType());
            });
  }

  private PreparedStatement prepared(String sql) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "executeQuery" -> {
              log.add("query " + sql);
              return outboxId();
            }
            case "executeUpdate" -> {
              log.add("update " + sql);
              return 1;
            }
            case "execute" -> {
              log.add("update " + sql);
              return false;
            }
            default -> {
              return defaultValue(method.getReturnType());
            }
          }
        };
    return (PreparedStatement)
        Proxy.newProxyInstance(
            FakeConnection.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            handler);
  }

  private static ResultSet outboxId() {
    boolean[] first = {true};
    return (ResultSet)
        Proxy.newProxyInstance(
            FakeConnection.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "next" -> {
                  boolean has = first[0];
                  first[0] = false;
                  return has;
                }
                case "getLong" -> {
                  return 42L;
                }
                default -> {
                  return defaultValue(method.getReturnType());
                }
              }
            });
  }

  private static Array array() {
    return (Array)
        Proxy.newProxyInstance(
            FakeConnection.class.getClassLoader(),
            new Class<?>[] {Array.class},
            (proxy, method, args) -> defaultValue(method.getReturnType()));
  }

  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}
