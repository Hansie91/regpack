package dev.regpack.app;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * One running process: the health server plus the lifecycle log lines. In P0 only the health
 * endpoint runs; the role workers arrive with later plans.
 */
public final class Service {
  private final HealthServer health;
  private final PrintStream log;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final AtomicBoolean stopping = new AtomicBoolean();

  private Service(HealthServer health, PrintStream log) {
    this.health = health;
    this.log = log;
  }

  /** Starts the health server, marks it ready and logs the lifecycle lines. */
  public static Service start(AppConfig config, PrintStream log) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(log, "log");
    String roles = config.roles().roles().stream().map(Role::flag).collect(Collectors.joining(","));
    line(log, "starting roles=" + roles + " bind=" + config.bind() + " port=" + config.port());
    HealthServer server = HealthServer.start(config.bind(), config.port());
    Service service = new Service(server, log);
    server.markReady();
    line(log, "ready port=" + server.port());
    return service;
  }

  public int port() {
    return health.port();
  }

  /** Blocks until {@link #stop()} has finished. */
  public void awaitStop() throws InterruptedException {
    stopped.await();
  }

  /** Marks the service stopping, closes the server and releases {@link #awaitStop()}. */
  public void stop() {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    try {
      health.markStopping();
      line(log, "stopping");
      health.close();
      line(log, "stopped cleanly");
    } finally {
      stopped.countDown();
    }
  }

  private static void line(PrintStream log, String text) {
    log.print("regpack lifecycle: " + text + "\n");
    log.flush();
  }
}
