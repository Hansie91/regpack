package dev.regpack.evidence.s3.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.ScriptedHttpServer.Recorded;
import dev.regpack.evidence.s3.client.ScriptedHttpServer.Reply;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class JdkTransportTest {
  private static final Duration LONG = Duration.ofSeconds(10);

  private static S3Request request(
      String method, URI base, String path, Map<String, String> headers, byte[] body) {
    return new S3Request(method, URI.create(base + path), headers, body);
  }

  @Test
  void redirectsAreNotFollowed() throws Exception {
    JdkTransport transport = new JdkTransport(LONG);
    for (int status : new int[] {301, 302, 303, 307, 308}) {
      try (ScriptedHttpServer first = ScriptedHttpServer.start();
          ScriptedHttpServer target = ScriptedHttpServer.start()) {
        first.reply(Reply.of(status, "").with("Location", target.endpoint() + "/elsewhere"));
        target.reply(200, "reached");

        S3Response response =
            transport.send(request("GET", first.endpoint(), "/b/k", Map.of(), new byte[0]));

        assertThat(response.status()).isEqualTo(status);
        assertThat(response.headers()).containsEntry("location", target.endpoint() + "/elsewhere");
        assertThat(first.requests()).hasSize(1);
        assertThat(target.requests()).as("status " + status).isEmpty();
      }
    }
  }

  @Test
  void aRefusedConnectionAndATimeoutAreIOExceptions() throws Exception {
    // the port was bound by a server of this JVM a moment ago and is closed; another process
    // could in theory take it in between, which is a risk accepted for a loopback test
    URI gone;
    try (ScriptedHttpServer closed = ScriptedHttpServer.start()) {
      gone = closed.endpoint();
    }
    assertThatThrownBy(
            () -> new JdkTransport(LONG).send(request("GET", gone, "/b", Map.of(), new byte[0])))
        .isInstanceOf(IOException.class);

    try (ScriptedHttpServer silent = ScriptedHttpServer.start()) {
      silent.reply(Reply.silence());
      assertThatThrownBy(
              () ->
                  new JdkTransport(Duration.ofSeconds(1))
                      .send(request("GET", silent.endpoint(), "/b/k", Map.of(), new byte[0])))
          .isInstanceOf(HttpTimeoutException.class);
      // the server records on its own thread: wait for the record instead of racing it
      assertThat(silent.awaitRequests(1, Duration.ofSeconds(10))).isTrue();
      assertThat(silent.requests()).hasSize(1);
    }
  }

  @Test
  void aStatusOutsideTheHttpRangeIsAProtocolExceptionThatNamesNeitherHostNorBody()
      throws Exception {
    for (int status : new int[] {600, 999}) {
      try (ScriptedHttpServer odd = ScriptedHttpServer.start()) {
        odd.reply(Reply.of(status, "MARKER-BODY"));
        assertThatThrownBy(
                () ->
                    new JdkTransport(LONG)
                        .send(request("GET", odd.endpoint(), "/b/k", Map.of(), new byte[0])))
            .as("status " + status)
            .isInstanceOf(ProtocolException.class)
            .satisfies(
                e ->
                    assertThat(String.valueOf(e.getMessage()))
                        .doesNotContain("MARKER")
                        .doesNotContain("127.0.0.1"));
        assertThat(odd.requests()).hasSize(1);
      }
    }
  }

  @Test
  @Timeout(30)
  void aBodyThatStallsAfterTheHeadersEndsWithinTheTimeoutAsAnIOException() throws Exception {
    try (ScriptedHttpServer stalled = ScriptedHttpServer.start()) {
      // the headers declare 20 bytes, 5 arrive, the connection stays open
      stalled.reply(Reply.stalledBody(200, "0123456789abcdefghij", 5));
      long start = System.nanoTime();

      assertThatThrownBy(
              () ->
                  new JdkTransport(Duration.ofSeconds(1))
                      .send(request("GET", stalled.endpoint(), "/b/k", Map.of(), new byte[0])))
          .isInstanceOf(HttpTimeoutException.class);

      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      assertThat(elapsed).as("the deadline covers the body").isLessThan(Duration.ofSeconds(10));
      assertThat(stalled.awaitRequests(1, Duration.ofSeconds(10))).isTrue();
    }
  }

  @Test
  void anInterruptIsRestoredAndBecomesAnInterruptedIOException() throws Exception {
    try (ScriptedHttpServer silent = ScriptedHttpServer.start()) {
      silent.reply(Reply.silence());
      Thread.currentThread().interrupt();
      try {
        assertThatThrownBy(
                () ->
                    new JdkTransport(LONG)
                        .send(request("GET", silent.endpoint(), "/b/k", Map.of(), new byte[0])))
            .isInstanceOf(InterruptedIOException.class);
        assertThat(Thread.currentThread().isInterrupted()).as("the flag is restored").isTrue();
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void headersAndBodyAreSentExceptHostAndContentLengthAndTheAnswerIsLowerCased() throws Exception {
    try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
      server.reply(
          Reply.of(200, "answer")
              .with("X-Amz-Version-Id", "v1")
              .with("ETag", "\"abc\"")
              .with("Content-Type", "application/xml"));
      byte[] body = "payload-bytes".getBytes(UTF_8);

      S3Response response =
          new JdkTransport(LONG)
              .send(
                  request(
                      "PUT",
                      server.endpoint(),
                      "/b/k%2B1?versionId=v",
                      Map.of(
                          "host", "wrong.example",
                          "content-length", "999",
                          "x-amz-meta-a", "A-b",
                          "authorization", "AWS4-HMAC-SHA256 test"),
                      body));

      Recorded recorded = server.only();
      assertThat(recorded.requestLine()).isEqualTo("PUT /b/k%2B1?versionId=v");
      assertThat(recorded.headers())
          .containsEntry("x-amz-meta-a", "A-b")
          .containsEntry("authorization", "AWS4-HMAC-SHA256 test")
          .containsEntry("content-length", String.valueOf(body.length))
          .containsEntry(
              "host",
              server.address().getAddress().getHostAddress() + ":" + server.address().getPort());
      assertThat(recorded.body()).isEqualTo(body);
      // HTTP/1.1 only: without the pinned version the client adds an h2c upgrade offer
      assertThat(recorded.headers()).doesNotContainKeys("upgrade", "http2-settings", "connection");

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("answer".getBytes(UTF_8));
      assertThat(response.headers())
          .containsEntry("x-amz-version-id", "v1")
          .containsEntry("etag", "\"abc\"")
          .containsKey("content-type");
      assertThat(response.headers().keySet())
          .allMatch(name -> name.equals(name.toLowerCase(Locale.ROOT)));
    }
  }

  @Test
  void aHeadAnswerHasHeadersAndNoBody() throws Exception {
    try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
      server.reply(Reply.of(200, "").with("x-amz-version-id", "v7"));

      S3Response response =
          new JdkTransport(LONG)
              .send(request("HEAD", server.endpoint(), "/b/k", Map.of(), new byte[0]));

      assertThat(server.only().method()).isEqualTo("HEAD");
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.headers()).containsEntry("x-amz-version-id", "v7");
      assertThat(response.body()).isEmpty();
    }
  }

  @Test
  void aNonPositiveTimeoutIsRefused() {
    assertThatThrownBy(() -> new JdkTransport(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JdkTransport(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
