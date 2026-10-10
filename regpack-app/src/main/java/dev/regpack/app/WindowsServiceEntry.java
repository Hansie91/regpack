package dev.regpack.app;

import java.util.List;
import java.util.Objects;

/**
 * Entry points for the Windows service wrapper (Apache Commons Daemon procrun, JVM mode: {@code
 * StartMethod=start}, {@code StopMethod=stop}). The wrapper expects {@link #start} not to return
 * until {@link #stop} has been called.
 */
public final class WindowsServiceEntry {
  private static final Object LOCK = new Object();
  private static Service current;

  private WindowsServiceEntry() {}

  /** Parses the flags, starts a {@link Service} and blocks until {@link #stop} is called. */
  public static void start(String[] args) {
    Objects.requireNonNull(args, "args");
    AppConfig config = AppConfig.parse(List.of(args), System.getenv());
    Service service;
    synchronized (LOCK) {
      service = Service.start(config, System.out);
      current = service;
    }
    try {
      service.awaitStop();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      service.stop();
    } finally {
      synchronized (LOCK) {
        if (current == service) {
          current = null;
        }
      }
    }
  }

  /** Stops the service started by {@link #start}; does nothing when none is running. */
  public static void stop(String[] args) {
    Objects.requireNonNull(args, "args");
    Service service;
    synchronized (LOCK) {
      service = current;
    }
    if (service != null) {
      service.stop();
    }
  }
}
