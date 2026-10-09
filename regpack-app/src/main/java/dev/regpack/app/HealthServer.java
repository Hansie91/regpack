package dev.regpack.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal health endpoint on the JDK HTTP server. {@code GET /health/live} is 200 while the server
 * runs; {@code GET /health/ready} is 503 {@code STARTING} until {@link #markReady()}, 200 after it
 * and 503 {@code STOPPING} after {@link #markStopping()}.
 */
public final class HealthServer implements AutoCloseable {
  private static final String LIVE_PATH = "/health/live";
  private static final String READY_PATH = "/health/ready";
  private static final int STOP_GRACE_SECONDS = 1;

  private enum State {
    STARTING("STARTING", 503),
    READY("UP", 200),
    STOPPING("STOPPING", 503);

    private final String label;
    private final int httpStatus;

    State(String label, int httpStatus) {
      this.label = label;
      this.httpStatus = httpStatus;
    }
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private volatile State state = State.STARTING;
  private volatile boolean closed;

  private HealthServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  /** Starts the server on {@code bind:port}; port 0 asks the operating system for a free port. */
  public static HealthServer start(String bind, int port) {
    Objects.requireNonNull(bind, "bind");
    HttpServer httpServer;
    try {
      httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot bind health server to " + bind + ":" + port, e);
    }
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    HealthServer health = new HealthServer(httpServer, executor);
    httpServer.createContext("/", health::handle);
    httpServer.setExecutor(executor);
    httpServer.start();
    return health;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public InetSocketAddress boundAddress() {
    return server.getAddress();
  }

  public void markReady() {
    if (state == State.STARTING) {
      state = State.READY;
    }
  }

  public void markStopping() {
    state = State.STOPPING;
  }

  /** Stops accepting, waits up to one second for running exchanges, then ends the executor. */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    server.stop(STOP_GRACE_SECONDS);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getRequestBody().readAllBytes();
      if (!exchange.getRequestMethod().equals("GET")) {
        exchange.getResponseHeaders().set("Allow", "GET");
        respond(exchange, 405, "{\"status\":\"METHOD_NOT_ALLOWED\"}");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.equals(LIVE_PATH)) {
        respond(exchange, 200, body("UP"));
      } else if (path.equals(READY_PATH)) {
        State current = state;
        respond(exchange, current.httpStatus, body(current.label));
      } else {
        respond(exchange, 404, "{\"status\":\"NOT_FOUND\"}");
      }
    }
  }

  private static String body(String status) {
    return "{\"status\":\"" + status + "\"}";
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
