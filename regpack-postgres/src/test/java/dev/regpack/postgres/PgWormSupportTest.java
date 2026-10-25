package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import dev.regpack.postgres.PgWormSupport.S3Settings;
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
 * The harness decision of the PostgreSQL-plus-S3 tests, without a service (the twin of {@code
 * S3TestServiceTest} of the S3 module): an unset service skips unless it is required, a required
 * one that is missing or silent fails, and no message repeats the secret. This class is not tagged
 * and runs in every job; the integration tests that use the harness are tagged {@code worm}.
 */
class PgWormSupportTest {
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

  private static HttpServer answering(int status) throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  @Test
  void anUnsetServiceSkipsUnlessRequired() {
    assertThatThrownBy(() -> PgWormSupport.requireOrAbort(Map.of(), false))
        .isInstanceOf(TestAbortedException.class)
        .hasMessageContaining("REGPACK_S3_ENDPOINT is not set");
    // a service that is only partly described is an unset service
    Map<String, String> partial = env("http://127.0.0.1:1");
    partial.remove("REGPACK_S3_SECRET_KEY");
    assertThatThrownBy(() -> PgWormSupport.requireOrAbort(partial, false))
        .isInstanceOf(TestAbortedException.class)
        .hasMessageContaining("REGPACK_S3_SECRET_KEY is not set");
  }

  @Test
  void requiredWithoutAServiceFails() {
    assertThatThrownBy(() -> PgWormSupport.requireOrAbort(Map.of(), true))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("REGPACK_S3_REQUIRED=true")
        .hasMessageContaining("REGPACK_S3_ENDPOINT is not set");
    Map<String, String> blank = env("   ");
    assertThatThrownBy(() -> PgWormSupport.requireOrAbort(blank, true))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("REGPACK_S3_ENDPOINT is not set");
  }

  @Test
  void aServiceThatDoesNotAnswerFailsWhenRequired() throws IOException {
    Map<String, String> refused = env(refusedEndpoint());
    assertThatThrownBy(() -> PgWormSupport.requireOrAbort(refused, true))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("REGPACK_S3_REQUIRED=true")
        .hasMessageContaining("does not answer");
    // the same service, not required: the test is skipped
    assertThatThrownBy(() -> PgWormSupport.requireOrAbort(refused, false))
        .isInstanceOf(TestAbortedException.class)
        .hasMessageContaining("does not answer");
  }

  @Test
  void anEndpointThatIsNotAnHttpUriFailsWhenRequired() {
    for (String bad : new String[] {"not a uri at all", "ftp://127.0.0.1:21", "http://"}) {
      Map<String, String> env = env(bad);
      assertThatThrownBy(() -> PgWormSupport.requireOrAbort(env, true))
          .as(bad)
          .isInstanceOf(AssertionFailedError.class)
          .hasMessageContaining("REGPACK_S3_ENDPOINT is not an http or https URI")
          .hasMessageNotContaining(bad);
    }
  }

  @Test
  void anEndpointThatIsNotPlainHttpOnLoopbackIsRefusedBeforeAnyRequest() {
    for (String remote :
        new String[] {
          "http://s3.example.test:9000",
          "http://10.0.0.5:9000",
          "http://192.168.1.2",
          "http://128.0.0.1:8000",
          "http://127.0.0.1.example.test:8000",
          "http://localhost.example.test",
          "http://[2001:db8::1]:9000",
          "https://s3.amazonaws.com",
          "https://127.0.0.1:8000",
          "https://localhost:8000"
        }) {
      Map<String, String> env = env(remote);
      for (boolean required : new boolean[] {true, false}) {
        assertThatThrownBy(() -> PgWormSupport.requireOrAbort(env, required))
            .as(remote + " required=" + required)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REGPACK_S3_ENDPOINT is not a loopback http endpoint")
            .hasMessageNotContaining("example")
            .hasMessageNotContaining("amazonaws")
            .hasMessageNotContaining("10.0.0.5")
            .hasMessageNotContaining(SECRET)
            .hasMessageNotContaining(KEY_ID);
      }
    }
  }

  @Test
  void loopbackHttpEndpointsPassTheGuardAndReachTheAnswerCheck() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    for (String host : new String[] {"127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "[::1]"}) {
      Map<String, String> env = env("http://" + host + ":" + port);
      assertThatThrownBy(() -> PgWormSupport.requireOrAbort(env, true))
          .as(host)
          .isInstanceOf(AssertionFailedError.class)
          .hasMessageContaining("does not answer");
    }
  }

  @Test
  void noMessageRepeatsTheSecret() throws IOException {
    String endpoint = refusedEndpoint();
    Map<String, String> refused = env(endpoint);
    Throwable required = catchThrowable(() -> PgWormSupport.requireOrAbort(refused, true));
    Throwable skipped = catchThrowable(() -> PgWormSupport.requireOrAbort(refused, false));
    for (Throwable t : new Throwable[] {required, skipped}) {
      assertThat(t).hasMessageNotContaining(SECRET).hasMessageNotContaining(KEY_ID);
      assertThat(t).hasMessageNotContaining(endpoint);
    }
    // nor does the description of the settings of a service that answers
    HttpServer server = answering(403);
    try {
      S3Settings settings =
          PgWormSupport.requireOrAbort(
              env("http://127.0.0.1:" + server.getAddress().getPort()), true);
      assertThat(settings.toString()).doesNotContain(SECRET).doesNotContain(KEY_ID);
      assertThat(settings.credentials().toString()).doesNotContain(SECRET);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aServiceThatAnswersWithAnyStatusYieldsItsSettings() throws IOException {
    HttpServer server = answering(403);
    try {
      int port = server.getAddress().getPort();
      S3Settings settings = PgWormSupport.requireOrAbort(env("http://127.0.0.1:" + port), true);
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
    assertThat(PgWormSupport.isRequired(Map.of("REGPACK_S3_REQUIRED", "true"))).isTrue();
    assertThat(PgWormSupport.isRequired(Map.of("REGPACK_S3_REQUIRED", "TRUE"))).isFalse();
    assertThat(PgWormSupport.isRequired(Map.of("REGPACK_S3_REQUIRED", "1"))).isFalse();
    assertThat(PgWormSupport.isRequired(Map.of())).isFalse();
  }

  @Test
  void theSigningClockIsWholeMicroseconds() {
    assertThat(PgWormSupport.signingClock().now().getNano() % 1000).isZero();
  }
}
