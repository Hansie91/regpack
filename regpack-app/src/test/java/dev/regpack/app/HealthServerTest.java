package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthServerTest {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HttpResponse<String> send(HealthServer server, String method, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(HealthServer server, String path)
      throws IOException, InterruptedException {
    return send(server, "GET", path);
  }

  @Test
  void bindsLoopbackByDefault() {
    AppConfig config = AppConfig.parse(List.of("--port", "0"), Map.of());
    assertThat(config.bind()).isEqualTo("127.0.0.1");
    try (HealthServer server = HealthServer.start(config.bind(), 0)) {
      assertThat(server.boundAddress().getAddress().isLoopbackAddress()).isTrue();
      assertThat(server.port()).isPositive();
    }
  }

  @Test
  void readyIs503WhileStarting() throws Exception {
    try (HealthServer server = HealthServer.start("127.0.0.1", 0)) {
      HttpResponse<String> ready = get(server, "/health/ready");
      assertThat(ready.statusCode()).isEqualTo(503);
      assertThat(ready.body()).isEqualTo("{\"status\":\"STARTING\"}");
      assertThat(get(server, "/health/live").statusCode()).isEqualTo(200);
    }
  }

  @Test
  void liveAndReadyReturnJson200AfterMarkReady() throws Exception {
    try (HealthServer server = HealthServer.start("127.0.0.1", 0)) {
      server.markReady();
      for (String path : List.of("/health/live", "/health/ready")) {
        HttpResponse<String> response = get(server, path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
      }
    }
  }

  @Test
  void readyReturns503WhileStopping() throws Exception {
    try (HealthServer server = HealthServer.start("127.0.0.1", 0)) {
      server.markReady();
      server.markStopping();
      HttpResponse<String> ready = get(server, "/health/ready");
      assertThat(ready.statusCode()).isEqualTo(503);
      assertThat(ready.body()).isEqualTo("{\"status\":\"STOPPING\"}");
      HttpResponse<String> live = get(server, "/health/live");
      assertThat(live.statusCode()).isEqualTo(200);
      assertThat(live.body()).isEqualTo("{\"status\":\"UP\"}");
    }
  }

  @Test
  void unknownPathIs404AndPostIs405() throws Exception {
    try (HealthServer server = HealthServer.start("127.0.0.1", 0)) {
      server.markReady();
      assertThat(get(server, "/nope").statusCode()).isEqualTo(404);
      assertThat(get(server, "/health").statusCode()).isEqualTo(404);
      HttpResponse<String> post = send(server, "POST", "/health/live");
      assertThat(post.statusCode()).isEqualTo(405);
      assertThat(post.headers().firstValue("Allow")).contains("GET");
    }
  }

  @Test
  void aRequestBodyDoesNotChangeTheAnswerOrBreakLaterRequests() throws Exception {
    try (HealthServer server = HealthServer.start("127.0.0.1", 0)) {
      server.markReady();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health/live"))
              .timeout(Duration.ofSeconds(5))
              .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[32 * 1024]))
              .build();
      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(405);
      assertThat(get(server, "/health/live").statusCode()).isEqualTo(200);
    }
  }

  @Test
  void serverCanBeClosedWithoutHanging() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          HealthServer server = HealthServer.start("127.0.0.1", 0);
          server.markReady();
          assertThat(get(server, "/health/live").statusCode()).isEqualTo(200);
          server.close();
        });
  }
}
