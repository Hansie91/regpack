package dev.regpack.testkit.fault;

import dev.regpack.model.Sha256;
import dev.regpack.testkit.clock.ManualClock;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Wraps port interfaces in {@link Proxy} instances that record a trace and inject the faults of a
 * {@link FaultPlan} at the call boundary before or after a method. A crash after a method leaves
 * its effect committed but unseen by the caller. Nothing reads the wall clock.
 */
public final class FaultInjector {
  private final FaultPlan plan;
  private final Optional<ManualClock> clock;
  private final Object lock = new Object();
  private final Map<String, Integer> hits = new HashMap<>();
  private final List<String> trace = new ArrayList<>();

  public FaultInjector(FaultPlan plan) {
    this.plan = Objects.requireNonNull(plan, "plan");
    this.clock = Optional.empty();
    if (plan.hasClockFaults()) {
      throw new IllegalArgumentException("a plan with advanceClockAt needs a ManualClock");
    }
  }

  public FaultInjector(FaultPlan plan, ManualClock clock) {
    this.plan = Objects.requireNonNull(plan, "plan");
    this.clock = Optional.of(Objects.requireNonNull(clock, "clock"));
  }

  /** Wraps {@code delegate}; {@code port} must be an interface. */
  public <T> T wrap(Class<T> port, T delegate) {
    Objects.requireNonNull(port, "port");
    Objects.requireNonNull(delegate, "delegate");
    if (!port.isInterface()) {
      throw new IllegalArgumentException("port must be an interface: " + port.getName());
    }
    String interfaceName = port.getSimpleName();
    ClassLoader loader =
        port.getClassLoader() != null
            ? port.getClassLoader()
            : FaultInjector.class.getClassLoader();
    InvocationHandler handler =
        (proxy, method, args) -> handle(interfaceName, delegate, proxy, method, args);
    return port.cast(Proxy.newProxyInstance(loader, new Class<?>[] {port}, handler));
  }

  /** {@code <Interface>.<method>:BEFORE#1}, {@code ...:AFTER#1}, ... in call order. */
  public List<String> trace() {
    synchronized (lock) {
      return List.copyOf(trace);
    }
  }

  /** SHA-256 of the trace lines joined with a newline. */
  public Sha256 traceDigest() {
    return Sha256.ofUtf8(String.join("\n", trace()));
  }

  private Object handle(
      String interfaceName, Object delegate, Object proxy, Method method, Object[] args)
      throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return handleObjectMethod(delegate, proxy, method, args);
    }
    String base = interfaceName + "." + method.getName();
    reach(base + ":BEFORE");
    Object result;
    try {
      result = method.invoke(delegate, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
    reach(base + ":AFTER");
    return result;
  }

  private static Object handleObjectMethod(
      Object delegate, Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      default -> String.valueOf(delegate);
    };
  }

  /** Counts a hit of {@code point}, records it and applies the faults for it. */
  private void reach(String point) {
    int n;
    synchronized (lock) {
      n = hits.merge(point, 1, Integer::sum);
      trace.add(point + "#" + n);
    }
    Optional<RuntimeException> toThrow = Optional.empty();
    for (FaultPlan.Fault fault : plan.faults()) {
      if (fault.nthHit() != n || !fault.point().equals(point)) {
        continue;
      }
      switch (fault.kind()) {
        case ADVANCE_CLOCK -> clock.orElseThrow().advance(fault.advance().orElseThrow());
        case CRASH -> {
          if (toThrow.isEmpty()) {
            toThrow = Optional.of(new SimulatedCrash(point + "#" + n));
          }
        }
        case FAIL -> {
          if (toThrow.isEmpty()) {
            toThrow = fault.toThrow();
          }
        }
      }
    }
    if (toThrow.isPresent()) {
      throw toThrow.get();
    }
  }
}
