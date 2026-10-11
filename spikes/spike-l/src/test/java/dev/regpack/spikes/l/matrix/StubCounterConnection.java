package dev.regpack.spikes.l.matrix;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connections that answer what {@link HotRowCounter#allocate} asks (a counter that counts up, an
 * insert that affects one row) and can be told to block in {@code commit} until {@code abort}, as a
 * commit does that waits for a synchronous standby that is gone.
 */
final class StubCounterConnection {
  private final AtomicLong counter = new AtomicLong();
  private final AtomicInteger aborts = new AtomicInteger();
  private final AtomicInteger closes = new AtomicInteger();
  private final CountDownLatch released = new CountDownLatch(1);
  private final boolean blockInCommit;

  StubCounterConnection(boolean blockInCommit) {
    this.blockInCommit = blockInCommit;
  }

  int aborts() {
    return aborts.get();
  }

  int closes() {
    return closes.get();
  }

  Connection open() {
    return (Connection)
        Proxy.newProxyInstance(
            StubCounterConnection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "prepareStatement" -> {
                  return statement();
                }
                case "commit" -> {
                  if (blockInCommit) {
                    released.await();
                  }
                  return null;
                }
                case "abort" -> {
                  aborts.incrementAndGet();
                  released.countDown();
                  return null;
                }
                case "close" -> {
                  closes.incrementAndGet();
                  return null;
                }
                default -> {
                  return defaultValue(method.getReturnType());
                }
              }
            });
  }

  private PreparedStatement statement() {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            StubCounterConnection.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "executeQuery" -> {
                  return counterRow();
                }
                case "executeUpdate" -> {
                  return 1;
                }
                default -> {
                  return defaultValue(method.getReturnType());
                }
              }
            });
  }

  private ResultSet counterRow() {
    boolean[] first = {true};
    return (ResultSet)
        Proxy.newProxyInstance(
            StubCounterConnection.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "next" -> {
                  boolean has = first[0];
                  first[0] = false;
                  return has;
                }
                case "getLong" -> {
                  return counter.incrementAndGet();
                }
                default -> {
                  return defaultValue(method.getReturnType());
                }
              }
            });
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
