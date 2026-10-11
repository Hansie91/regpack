package dev.regpack.spikes.l.load;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A connection that counts its {@code close()} calls and can be told to fail one method, so that
 * the tests can see that nothing leaks: every other call is accepted and does nothing.
 */
final class TrackedConnection {
  private final AtomicInteger closes = new AtomicInteger();
  private final String failingMethod;

  TrackedConnection() {
    this("");
  }

  /**
   * @param failingMethod the name of the method that throws a {@link SQLException}
   */
  TrackedConnection(String failingMethod) {
    this.failingMethod = failingMethod;
  }

  boolean closed() {
    return closes.get() > 0;
  }

  int closes() {
    return closes.get();
  }

  Connection connection() {
    return (Connection)
        Proxy.newProxyInstance(
            TrackedConnection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals(failingMethod)) {
                throw new SQLException("scripted failure in " + name);
              }
              if (name.equals("close")) {
                closes.incrementAndGet();
              }
              if (name.equals("isClosed")) {
                return closed();
              }
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
              return null;
            });
  }
}
