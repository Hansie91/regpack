package dev.regpack.evidence.s3.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A JDK HTTP server on the loopback address only that answers from a script (one reply per request,
 * in order) and records every raw request: method, request target as the client wrote it, header
 * names in lower case, and body. A request that arrives when the script is used up is answered 599.
 * One server per test; {@link #close()} stops it.
 */
final class ScriptedHttpServer implements AutoCloseable {
  /**
   * One scripted answer; {@code hang} holds the connection open without answering, a {@code
   * stallAfter} of 0 or more sends the headers (with the whole body length declared) and that many
   * body bytes and then holds the connection open.
   */
  record Reply(int status, Map<String, String> headers, byte[] body, boolean hang, int stallAfter) {
    static Reply of(int status, String body) {
      return new Reply(status, Map.of(), body.getBytes(UTF_8), false, -1);
    }

    static Reply silence() {
      return new Reply(0, Map.of(), new byte[0], true, -1);
    }

    /** Headers and {@code sent} bytes of {@code body} arrive; the rest never does. */
    static Reply stalledBody(int status, String body, int sent) {
      byte[] bytes = body.getBytes(UTF_8);
      if (sent < 0 || sent >= bytes.length) {
        throw new IllegalArgumentException("sent must leave part of the body unsent");
      }
      return new Reply(status, Map.of(), bytes, false, sent);
    }

    Reply with(String name, String value) {
      Map<String, String> next = new LinkedHashMap<>(headers);
      next.put(name, value);
      return new Reply(status, next, body, hang, stallAfter);
    }
  }

  /** One request as the server read it. */
  record Recorded(String method, String target, Map<String, String> headers, byte[] body) {
    String requestLine() {
      return method + " " + target;
    }

    String path() {
      int q = target.indexOf('?');
      return q < 0 ? target : target.substring(0, q);
    }

    String rawQuery() {
      int q = target.indexOf('?');
      return q < 0 ? "" : target.substring(q + 1);
    }

    String bodyText() {
      return new String(body, UTF_8);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final Queue<Reply> script = new ConcurrentLinkedQueue<>();
  private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
  private final CountDownLatch release = new CountDownLatch(1);

  private ScriptedHttpServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  static ScriptedHttpServer start() throws IOException {
    HttpServer http =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    ScriptedHttpServer scripted = new ScriptedHttpServer(http, executor);
    http.createContext("/", scripted::handle);
    http.setExecutor(executor);
    http.start();
    return scripted;
  }

  InetSocketAddress address() {
    return server.getAddress();
  }

  /** {@code http://<loopback address>:<port>}, the form a client endpoint takes. */
  URI endpoint() {
    String host = address().getAddress().getHostAddress();
    String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    return URI.create("http://" + authority + ":" + address().getPort());
  }

  ScriptedHttpServer reply(Reply... replies) {
    for (Reply reply : replies) {
      script.add(reply);
    }
    return this;
  }

  ScriptedHttpServer reply(int status, String body) {
    return reply(Reply.of(status, body));
  }

  List<Recorded> requests() {
    return List.copyOf(recorded);
  }

  /**
   * Waits until at least {@code n} requests are recorded (the server records on its own thread, so
   * a caller that returns first must not assume the record is there yet).
   */
  boolean awaitRequests(int n, Duration max) throws InterruptedException {
    long deadline = System.nanoTime() + max.toNanos();
    while (recorded.size() < n) {
      if (System.nanoTime() - deadline >= 0) {
        return false;
      }
      Thread.sleep(5);
    }
    return true;
  }

  Recorded only() {
    List<Recorded> all = requests();
    if (all.size() != 1) {
      throw new AssertionError("expected one recorded request, found " + all.size());
    }
    return all.get(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      byte[] in = exchange.getRequestBody().readAllBytes();
      Map<String, String> headers = new TreeMap<>();
      exchange
          .getRequestHeaders()
          .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), join(values)));
      recorded.add(
          new Recorded(
              exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers, in));
      Reply reply = script.poll();
      if (reply == null) {
        reply = Reply.of(599, "script exhausted");
      }
      if (reply.hang()) {
        holdOpen();
        return;
      }
      for (Map.Entry<String, String> h : reply.headers().entrySet()) {
        exchange.getResponseHeaders().add(h.getKey(), h.getValue());
      }
      if (reply.stallAfter() >= 0) {
        exchange.sendResponseHeaders(reply.status(), reply.body().length);
        OutputStream out = exchange.getResponseBody();
        out.write(reply.body(), 0, reply.stallAfter());
        out.flush();
        holdOpen();
        return;
      }
      boolean head = "HEAD".equals(exchange.getRequestMethod());
      if (reply.body().length == 0 || head) {
        exchange.sendResponseHeaders(reply.status(), -1);
      } else {
        exchange.sendResponseHeaders(reply.status(), reply.body().length);
        exchange.getResponseBody().write(reply.body());
      }
    }
  }

  private void holdOpen() {
    try {
      release.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String join(List<String> values) {
    return String.join(", ", values);
  }

  @Override
  public void close() {
    release.countDown();
    server.stop(0);
    executor.shutdownNow();
  }
}
