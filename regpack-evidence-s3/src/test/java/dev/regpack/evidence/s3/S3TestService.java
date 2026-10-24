package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.ports.Clock;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * The real-store test harness of the S3 module, the one class of its tests that reads the process
 * environment (the allow-list of {@code NoAmbientEnvironmentTest}). The service is described by
 * {@code REGPACK_S3_ENDPOINT} ({@code scheme://host[:port]}), {@code REGPACK_S3_REGION}, {@code
 * REGPACK_S3_ACCESS_KEY} and {@code REGPACK_S3_SECRET_KEY}. Unset, or not answering: the calling
 * test is skipped by an assumption. With {@code REGPACK_S3_REQUIRED=true} an unset or silent
 * service is a failure instead, so a CI job that must run the real service never passes by skipping
 * (review focus WF14), as {@code PgTestDatabase} does for PostgreSQL.
 *
 * <p>No message of this class repeats the endpoint, the key id or the secret; a message names the
 * variable and what is wrong with it.
 */
final class S3TestService {
  static final String ENDPOINT_VARIABLE = "REGPACK_S3_ENDPOINT";
  static final String REGION_VARIABLE = "REGPACK_S3_REGION";
  static final String ACCESS_KEY_VARIABLE = "REGPACK_S3_ACCESS_KEY";
  static final String SECRET_KEY_VARIABLE = "REGPACK_S3_SECRET_KEY";
  static final String REQUIRED_VARIABLE = "REGPACK_S3_REQUIRED";

  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(10);

  private S3TestService() {}

  /** What a test needs to talk to the service. The secret is never part of the description. */
  record S3Settings(URI endpoint, String region, Credentials credentials) {
    S3Settings {
      Objects.requireNonNull(endpoint, "endpoint");
      Objects.requireNonNull(region, "region");
      Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public String toString() {
      return "S3Settings[region=" + region + ", endpoint=set, credentials=***]";
    }
  }

  /** True when {@code REGPACK_S3_REQUIRED} is exactly {@code true}. */
  static boolean isRequired(Map<String, String> env) {
    return "true".equals(env.get(REQUIRED_VARIABLE));
  }

  /**
   * The service of the process environment. Skips the calling test when there is no usable service
   * and the real service is not required; fails it otherwise.
   */
  static S3Settings settings() {
    Map<String, String> env = System.getenv();
    return requireOrAbort(env, isRequired(env));
  }

  /**
   * The pure decision of the harness: the settings of the service described by {@code env} when it
   * answers (any HTTP status counts as an answer; the probe and the tests sign their own requests);
   * otherwise a failure naming {@code REGPACK_S3_REQUIRED} when {@code required} is set, and a skip
   * by assumption when it is not. It reads no environment variable and keeps no state, so it can be
   * tested without a service.
   */
  static S3Settings requireOrAbort(Map<String, String> env, boolean required) {
    for (String name :
        new String[] {
          ENDPOINT_VARIABLE, REGION_VARIABLE, ACCESS_KEY_VARIABLE, SECRET_KEY_VARIABLE
        }) {
      String value = env.get(name);
      if (value == null || value.isBlank()) {
        return unavailable(name + " is not set", required);
      }
    }
    URI endpoint;
    try {
      endpoint = URI.create(env.get(ENDPOINT_VARIABLE).strip());
    } catch (IllegalArgumentException e) {
      // the message of the exception repeats the text
      return unavailable(ENDPOINT_VARIABLE + " is not an http or https URI", required);
    }
    String scheme =
        endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || endpoint.getHost() == null) {
      return unavailable(ENDPOINT_VARIABLE + " is not an http or https URI", required);
    }
    String silence = silence(endpoint);
    if (silence != null) {
      return unavailable(silence, required);
    }
    return new S3Settings(
        endpoint,
        env.get(REGION_VARIABLE).strip(),
        new Credentials(
            env.get(ACCESS_KEY_VARIABLE).strip(), env.get(SECRET_KEY_VARIABLE).strip()));
  }

  /** Null when the endpoint answers an unsigned GET with any status; else the reason it cannot. */
  private static String silence(URI endpoint) {
    int port = endpoint.getPort();
    URI root =
        URI.create(
            endpoint.getScheme().toLowerCase(Locale.ROOT)
                + "://"
                + endpoint.getHost()
                + (port == -1 ? "" : ":" + port)
                + "/");
    try {
      new JdkTransport(ANSWER_TIMEOUT).send(new S3Request("GET", root, Map.of(), new byte[0]));
      return null;
    } catch (IOException e) {
      // not chained and not quoted: its message may name the host
      return ENDPOINT_VARIABLE
          + " is set but the service does not answer ("
          + e.getClass().getSimpleName()
          + ")";
    }
  }

  private static S3Settings unavailable(String reason, boolean required) {
    if (required) {
      Assertions.fail(REQUIRED_VARIABLE + "=true and " + reason);
    }
    return Assumptions.abort("real-service test skipped: " + reason);
  }

  // ---- the seam of T8a (T9, T10) over the real service

  /** The raw client of the service: the test credentials, real time, the JDK transport. */
  static S3Client rawClient(S3Settings settings) {
    return new S3Client(
        settings.endpoint(),
        settings.region(),
        settings.credentials(),
        signingClock(),
        new JdkTransport(Duration.ofSeconds(60)));
  }

  /**
   * A fresh bucket with object lock on: {@code rp-} and 12 hex digits of a random UUID (test code
   * only, D-W13). Buckets are never emptied: the container is discarded with the job.
   */
  static String newBucket(S3Client raw) {
    String bucket = "rp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    raw.createBucket(bucket, true);
    return bucket;
  }

  /** The clock of {@code X-Amz-Date} against the service: real time. */
  static Clock signingClock() {
    return realClock();
  }

  /**
   * Real time for {@code X-Amz-Date}: a server refuses a request more than 15 minutes off [R], so
   * the signing clock of a test against the service is never a manual clock. Whole microseconds, as
   * the port says.
   */
  static Clock realClock() {
    return new Clock() {
      @Override
      public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
      }

      @Override
      public long monotonicNanos() {
        return System.nanoTime();
      }
    };
  }
}
