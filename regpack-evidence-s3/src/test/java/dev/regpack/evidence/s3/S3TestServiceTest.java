package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.regpack.evidence.s3.S3TestService.S3Settings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/**
 * The harness decision of the service tests, without a service: an unset service skips unless it is
 * required, a required one that is missing or silent fails, and no message repeats the secret.
 */
class S3TestServiceTest {
  private static final String KEY_ID = "REGPACKUNITKEYID01";
  private static final String SECRET = "unit-test-secret-do-not-repeat-0123456789";

  /** An endpoint on a loopback port that nothing listens on: the connection is refused. */
  private static String refusedEndpoint() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }
  }

  private static Map<String, String> env(String endpoint) {
    Map<String, String> env = new HashMap<>();
    env.put("REGPACK_S3_ENDPOINT", endpoint);
    env.put("REGPACK_S3_REGION", "us-east-1");
    env.put("REGPACK_S3_ACCESS_KEY", KEY_ID);
    env.put("REGPACK_S3_SECRET_KEY", SECRET);
    return env;
  }

  @Test
  void anUnsetServiceSkipsUnlessRequired() {
    assertThatThrownBy(() -> S3TestService.requireOrAbort(Map.of(), false))
        .isInstanceOf(TestAbortedException.class)
        .hasMessageContaining("REGPACK_S3_ENDPOINT is not set");
    // a service that is only partly described is an unset service
    Map<String, String> partial = env("http://127.0.0.1:1");
    partial.remove("REGPACK_S3_SECRET_KEY");
    assertThatThrownBy(() -> S3TestService.requireOrAbort(partial, false))
        .isInstanceOf(TestAbortedException.class)
        .hasMessageContaining("REGPACK_S3_SECRET_KEY is not set");
  }

  @Test
  void requiredWithoutAServiceFails() {
    assertThatThrownBy(() -> S3TestService.requireOrAbort(Map.of(), true))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("REGPACK_S3_REQUIRED=true")
        .hasMessageContaining("REGPACK_S3_ENDPOINT is not set");
    Map<String, String> blank = env("   ");
    assertThatThrownBy(() -> S3TestService.requireOrAbort(blank, true))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("REGPACK_S3_ENDPOINT is not set");
  }

  @Test
  void aServiceThatDoesNotAnswerFailsWhenRequired() throws IOException {
    Map<String, String> refused = env(refusedEndpoint());
    assertThatThrownBy(() -> S3TestService.requireOrAbort(refused, true))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("REGPACK_S3_REQUIRED=true")
        .hasMessageContaining("does not answer");
    // the same service, not required: the test is skipped
    assertThatThrownBy(() -> S3TestService.requireOrAbort(refused, false))
        .isInstanceOf(TestAbortedException.class)
        .hasMessageContaining("does not answer");
  }

  @Test
  void anEndpointThatIsNotAnHttpUriFailsWhenRequired() {
    for (String bad : new String[] {"not a uri at all", "ftp://127.0.0.1:21", "http://"}) {
      Map<String, String> env = env(bad);
      assertThatThrownBy(() -> S3TestService.requireOrAbort(env, true))
          .as(bad)
          .isInstanceOf(AssertionFailedError.class)
          .hasMessageContaining("REGPACK_S3_ENDPOINT is not an http or https URI")
          .hasMessageNotContaining(bad);
    }
  }

  @Test
  void noMessageRepeatsTheSecret() throws IOException {
    String endpoint = refusedEndpoint();
    Map<String, String> refused = env(endpoint);
    Throwable required =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> S3TestService.requireOrAbort(refused, true));
    Throwable skipped =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> S3TestService.requireOrAbort(refused, false));
    for (Throwable t : new Throwable[] {required, skipped}) {
      assertThat(t).hasMessageNotContaining(SECRET).hasMessageNotContaining(KEY_ID);
      assertThat(t).hasMessageNotContaining(endpoint);
    }
    // nor does the description of the settings of a service that answers
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();
    try {
      S3Settings settings =
          S3TestService.requireOrAbort(
              env("http://127.0.0.1:" + server.getAddress().getPort()), true);
      assertThat(settings.toString()).doesNotContain(SECRET).doesNotContain(KEY_ID);
      assertThat(settings.credentials().toString()).doesNotContain(SECRET);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aServiceThatAnswersWithAnyStatusYieldsItsSettings() throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();
    try {
      int port = server.getAddress().getPort();
      S3Settings settings = S3TestService.requireOrAbort(env("http://127.0.0.1:" + port), true);
      assertThat(settings.endpoint().toString()).isEqualTo("http://127.0.0.1:" + port);
      assertThat(settings.region()).isEqualTo("us-east-1");
      assertThat(settings.credentials().accessKeyId()).isEqualTo(KEY_ID);
      assertThat(settings.credentials().secretAccessKey()).isEqualTo(SECRET);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void requiredIsTrueOnlyForTheWordTrue() {
    assertThat(S3TestService.isRequired(Map.of("REGPACK_S3_REQUIRED", "true"))).isTrue();
    assertThat(S3TestService.isRequired(Map.of("REGPACK_S3_REQUIRED", "TRUE"))).isFalse();
    assertThat(S3TestService.isRequired(Map.of("REGPACK_S3_REQUIRED", "1"))).isFalse();
    assertThat(S3TestService.isRequired(Map.of())).isFalse();
  }

  @Test
  void theSigningClockIsWholeMicroseconds() {
    assertThat(S3TestService.realClock().now().getNano() % 1000).isZero();
  }
}
