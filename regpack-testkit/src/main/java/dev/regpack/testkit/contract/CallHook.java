package dev.regpack.testkit.contract;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test support for {@link SealScenarioContract}: wraps a port in a proxy that runs one action
 * immediately before or after a named method, counting the calls of that method from 1. The faults
 * of {@code FaultInjector} cannot express "another holder takes the lease between the second put
 * and the commit" or "all racers wait here"; this can. The action runs on the calling thread, an
 * exception of the action propagates, and nothing else about the call changes.
 */
final class CallHook {
  /** The action; {@code hit} is the number of this call of the method, from 1. */
  @FunctionalInterface
  interface Action {
    void run(int hit);
  }

  private CallHook() {}

  /** Runs {@code action} before every call of {@code method}. */
  static <T> T before(Class<T> port, T delegate, String method, Action action) {
    return wrap(port, delegate, method, Objects.requireNonNull(action, "action"), null);
  }

  /** Runs {@code action} after every call of {@code method} that returned. */
  static <T> T after(Class<T> port, T delegate, String method, Action action) {
    return wrap(port, delegate, method, null, Objects.requireNonNull(action, "action"));
  }

  private static <T> T wrap(Class<T> port, T delegate, String method, Action before, Action after) {
    Objects.requireNonNull(port, "port");
    Objects.requireNonNull(delegate, "delegate");
    Objects.requireNonNull(method, "method");
    if (!port.isInterface()) {
      throw new IllegalArgumentException("port must be an interface: " + port.getName());
    }
    AtomicInteger hits = new AtomicInteger();
    ClassLoader loader =
        port.getClassLoader() != null ? port.getClassLoader() : CallHook.class.getClassLoader();
    InvocationHandler handler =
        (proxy, called, args) -> {
          if (called.getDeclaringClass() == Object.class) {
            return switch (called.getName()) {
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              default -> String.valueOf(delegate);
            };
          }
          boolean mine = called.getName().equals(method);
          int hit = mine ? hits.incrementAndGet() : 0;
          if (mine && before != null) {
            before.run(hit);
          }
          Object result;
          try {
            result = called.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
          if (mine && after != null) {
            after.run(hit);
          }
          return result;
        };
    return port.cast(Proxy.newProxyInstance(loader, new Class<?>[] {port}, handler));
  }
}
