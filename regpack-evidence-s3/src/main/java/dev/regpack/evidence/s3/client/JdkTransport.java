package dev.regpack.evidence.s3.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The transport on the JDK HTTP client. Every request gets a fresh client that is closed after the
 * answer: HTTP/1.1, redirects are never followed (a redirect answer is returned as it is, so a
 * signed request is never sent anywhere else), one deadline of the given timeout for the whole
 * exchange, from the call until the last byte of the body has arrived (the JDK's own request
 * timeout ends when the response headers arrive, so a body that stalls after them is cut off here
 * instead; the deadline is an {@link HttpTimeoutException}), and the platform's default TLS
 * settings and proxy selection (no custom {@code SSLContext}, trust manager or proxy selector). An
 * interrupt restores the flag and surfaces as an {@link InterruptedIOException}. A status outside
 * 100 to 599 (the JDK parser accepts any three digits) is a {@link ProtocolException}, not an
 * answer. The module issues no retry of its own; the JDK HttpClient may retry an idempotent request
 * once when the connection is closed before any byte of the answer (recollection of the JDK
 * behaviour, not pinned by a test).
 */
public final class JdkTransport implements S3Transport {
  private final Duration timeout;

  public JdkTransport(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    this.timeout = timeout;
  }

  @Override
  public S3Response send(S3Request request) throws IOException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri());
    for (Map.Entry<String, String> header : request.headers().entrySet()) {
      String name = header.getKey();
      // the JDK sets these two from the URI and the body
      if (!name.equals("host") && !name.equals("content-length")) {
        builder.header(name, header.getValue());
      }
    }
    byte[] body = request.body();
    builder.method(
        request.method(),
        body.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    HttpRequest httpRequest = builder.build();

    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout)
            .build()) {
      HttpResponse<byte[]> response;
      CompletableFuture<HttpResponse<byte[]>> pending =
          client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
      try {
        response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        abandon(client, pending);
        throw new HttpTimeoutException("timeout");
      } catch (InterruptedException e) {
        abandon(client, pending);
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted");
      } catch (ExecutionException e) {
        throw failure(e.getCause());
      }
      int status = response.statusCode();
      if (status < 100 || status > 599) {
        // a status no HTTP service sends is a failed exchange, not an answer; the text names
        // nothing
        throw new ProtocolException("status outside 100 to 599");
      }
      Map<String, String> headers = new TreeMap<>();
      response
          .headers()
          .map()
          .forEach(
              (name, values) ->
                  headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values)));
      return new S3Response(status, headers, response.body());
    }
  }

  /** Cancels the call and closes its connections, so that closing the client does not wait. */
  private static void abandon(HttpClient client, CompletableFuture<?> pending) {
    pending.cancel(true);
    client.shutdownNow();
  }

  /** What the synchronous JDK call would have thrown for the same failure. */
  private static IOException failure(Throwable cause) {
    if (cause instanceof IOException io) {
      return io;
    }
    if (cause instanceof RuntimeException re) {
      throw re;
    }
    if (cause instanceof Error err) {
      throw err;
    }
    return new IOException("request failed");
  }
}
