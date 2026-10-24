package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.WormConfig.LockMode;
import dev.regpack.evidence.s3.WormConfig.Verification;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of {@link WormConfig} (D-W9): the endpoint, the lock mode against the verification, the
 * declared durability, the bucket name, the secret in {@code toString}, and the grade string.
 */
class WormConfigTest {
  private static final Credentials CREDENTIALS =
      new Credentials("AKIATESTKEYID00000001", "test-secret-0123456789abcdef");
  private static final Durability ONE_DOMAIN = new Durability(true, 1);
  private static final String BUCKET = "rp-0123456789ab";

  private static WormConfig governance(String endpoint) {
    return WormConfig.governance(
        URI.create(endpoint), "us-east-1", BUCKET, CREDENTIALS, ONE_DOMAIN);
  }

  private static WormConfig config(
      String endpoint, LockMode mode, Verification verification, Durability durability) {
    return new WormConfig(
        URI.create(endpoint),
        "us-east-1",
        BUCKET,
        CREDENTIALS,
        mode,
        verification,
        durability,
        WormConfig.DEFAULT_MAX_LOCK_DATE,
        WormConfig.DEFAULT_REQUEST_TIMEOUT);
  }

  private static WormConfig withBucket(String bucket) {
    return WormConfig.governance(
        URI.create("http://127.0.0.1:8000"), "us-east-1", bucket, CREDENTIALS, ONE_DOMAIN);
  }

  @Test
  void governanceBuildsAnUnverifiedGovernanceStoreWithTheDefaults() {
    WormConfig config = governance("http://127.0.0.1:8000");
    assertThat(config.mode()).isEqualTo(LockMode.GOVERNANCE);
    assertThat(config.verification()).isEqualTo(Verification.UNVERIFIED_NON_AWS);
    assertThat(config.declaredDurability()).isEqualTo(ONE_DOMAIN);
    assertThat(config.maxLockDate()).isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));
    assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.bucket()).isEqualTo(BUCKET);
    assertThat(config.region()).isEqualTo("us-east-1");
  }

  @Test
  void plainHttpIsRefusedForANonLoopbackHost() {
    List<String> hosts =
        List.of(
            "example.com",
            "localhost.example.com",
            "127.0.0.1.example.com",
            "10.0.0.5",
            "192.168.1.20",
            "128.0.0.1",
            "126.255.255.255",
            "[2001:db8::1]",
            "[::2]",
            "minio");
    for (String host : hosts) {
      assertThatThrownBy(() -> governance("http://" + host + ":9000"))
          .as(host)
          .isInstanceOf(IllegalArgumentException.class)
          // no message names the host
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain(host));
    }
  }

  @Test
  void httpIsAcceptedForLoopbackAddresses() {
    for (String endpoint :
        List.of(
            "http://127.0.0.1",
            "http://127.0.0.1:8000",
            "http://127.255.255.254:9000",
            "http://127.1.2.3",
            "http://[::1]",
            "http://[::1]:8000",
            "http://localhost",
            "http://localhost:9000")) {
      assertThat(governance(endpoint).endpoint()).as(endpoint).isEqualTo(URI.create(endpoint));
    }
  }

  @Test
  void httpsIsAcceptedForAnyHostAndTheSchemeMustBeHttpOrHttps() {
    assertThat(governance("https://s3.example.com").endpoint())
        .isEqualTo(URI.create("https://s3.example.com"));
    assertThat(governance("https://storage.internal:9443").endpoint().getPort()).isEqualTo(9443);
    for (String endpoint : List.of("ftp://127.0.0.1", "file:///tmp/x", "s3://bucket")) {
      assertThatThrownBy(() -> governance(endpoint))
          .as(endpoint)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void anEndpointWithUserinfoQueryOrFragmentIsRefused() {
    assertEndpointsRefusedWithoutEcho(
        List.of(
            "https://user@s3.example.com",
            "https://user:pass@s3.example.com",
            "https://s3.example.com?x=1",
            "https://s3.example.com#frag"));
  }

  @Test
  void anEndpointWithAPathIsRefused() {
    assertEndpointsRefusedWithoutEcho(
        List.of(
            "https://s3.example.com/path", "https://s3.example.com/", "http://127.0.0.1:8000/"));
  }

  private static void assertEndpointsRefusedWithoutEcho(List<String> endpoints) {
    for (String endpoint : endpoints) {
      assertThatThrownBy(() -> governance(endpoint))
          .as(endpoint)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e -> {
                assertThat(e.getMessage()).doesNotContain("pass");
                assertThat(e.getMessage()).doesNotContain("s3.example.com");
              });
    }
  }

  @Test
  void anEndpointWithoutAHostIsRefused() {
    assertThatThrownBy(() -> governance("https:///nohost"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> governance("mailto:someone@example.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void awsS3NeedsAnAmazonawsHost() {
    for (String endpoint :
        List.of(
            "https://s3.amazonaws.com",
            "https://s3.eu-west-1.amazonaws.com",
            "https://s3.us-east-1.amazonaws.com:443",
            "https://s3.amazonaws.com.cn",
            "https://s3.cn-north-1.amazonaws.com.cn")) {
      WormConfig config =
          config(endpoint, LockMode.GOVERNANCE, Verification.AWS_S3, new Durability(true, 3));
      assertThat(config.verification()).as(endpoint).isEqualTo(Verification.AWS_S3);
    }
    for (String endpoint :
        List.of(
            "https://example.com",
            "https://s3.amazonaws.com.evil.example",
            "https://evilamazonaws.com",
            "https://s3.eu-west-1.amazonaws.com.evil.example",
            "https://bucket.s3.amazonaws.com",
            "https://amazonaws.com",
            "https://s3.amazonaws.org",
            "https://s3..amazonaws.com",
            "https://s3.a.b.amazonaws.com",
            "http://s3.amazonaws.com",
            "http://127.0.0.1:8000")) {
      assertThatThrownBy(
              () ->
                  config(
                      endpoint, LockMode.GOVERNANCE, Verification.AWS_S3, new Durability(true, 3)))
          .as(endpoint)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void complianceModeNeedsAnAwsOrAnSpOVerifiedStore() {
    assertThatThrownBy(
            () ->
                config(
                    "http://127.0.0.1:8000",
                    LockMode.COMPLIANCE,
                    Verification.UNVERIFIED_NON_AWS,
                    ONE_DOMAIN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            config(
                    "https://s3.amazonaws.com",
                    LockMode.COMPLIANCE,
                    Verification.AWS_S3,
                    new Durability(true, 3))
                .mode())
        .isEqualTo(LockMode.COMPLIANCE);
    assertThat(
            config(
                    "https://storage.internal",
                    LockMode.COMPLIANCE,
                    Verification.SP_O_VERIFIED,
                    new Durability(true, 2))
                .mode())
        .isEqualTo(LockMode.COMPLIANCE);
  }

  @Test
  void aStoreMustDeclareSynchronousAcknowledgementAndOneFailureDomain() {
    for (Verification verification : Verification.values()) {
      String endpoint =
          verification == Verification.AWS_S3
              ? "https://s3.amazonaws.com"
              : "http://127.0.0.1:8000";
      // asynchronous acknowledgement counts zero, and so does a store with no domain
      for (Durability bad :
          List.of(new Durability(false, 1), new Durability(false, 0), new Durability(true, 0))) {
        assertThatThrownBy(() -> config(endpoint, LockMode.GOVERNANCE, verification, bad))
            .as("%s %s", verification, bad)
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThat(config(endpoint, LockMode.GOVERNANCE, verification, ONE_DOMAIN).verification())
          .isEqualTo(verification);
    }
  }

  @Test
  void anUnverifiedStoreCannotDeclareMoreThanOneFailureDomain() {
    for (int domains : new int[] {2, 3, 10}) {
      assertThatThrownBy(
              () ->
                  config(
                      "http://127.0.0.1:8000",
                      LockMode.GOVERNANCE,
                      Verification.UNVERIFIED_NON_AWS,
                      new Durability(true, domains)))
          .as("domains %d", domains)
          .isInstanceOf(IllegalArgumentException.class);
    }
    // a larger count needs an SP-O result (a declaration in a test, not a verified store) or AWS
    assertThat(
            config(
                    "http://127.0.0.1:8000",
                    LockMode.GOVERNANCE,
                    Verification.SP_O_VERIFIED,
                    new Durability(true, 3))
                .declaredDurability())
        .isEqualTo(new Durability(true, 3));
    assertThat(
            config(
                    "https://s3.amazonaws.com",
                    LockMode.GOVERNANCE,
                    Verification.AWS_S3,
                    new Durability(true, 3))
                .declaredDurability())
        .isEqualTo(new Durability(true, 3));
  }

  @Test
  void bucketNamesFollowTheS3Rules() {
    String sixtyThree = "a".repeat(63);
    for (String good :
        List.of("abc", "rp-0123456789ab", "my.bucket-1", "0abc9", "a-b.c-d", sixtyThree)) {
      assertThat(withBucket(good).bucket()).isEqualTo(good);
    }
    for (String bad :
        List.of(
            "",
            "ab",
            "a".repeat(64),
            "UPPER",
            "Mixed-Case",
            "under_score",
            "-abc",
            "abc-",
            ".abc",
            "abc.",
            "a..b",
            "192.168.5.4",
            "xn--abc",
            "sthree-abc",
            "bucket-s3alias",
            "bucket--ol-s3",
            "a b",
            "a/b",
            "bücket")) {
      assertThatThrownBy(() -> withBucket(bad))
          .as("bucket '%s'", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theRegionMustBeUsableInACredentialScope() {
    for (String bad : List.of("", "us east", "us/east", "a".repeat(65))) {
      assertThatThrownBy(
              () ->
                  WormConfig.governance(
                      URI.create("http://127.0.0.1:8000"), bad, BUCKET, CREDENTIALS, ONE_DOMAIN))
          .as("region '%s'", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theMaximumLockDateIsWholeSecondsNoLaterThanTheLastSecondOfYear9999() {
    URI endpoint = URI.create("http://127.0.0.1:8000");
    Instant lower = Instant.parse("2100-01-01T00:00:00Z");
    WormConfig config =
        new WormConfig(
            endpoint,
            "us-east-1",
            BUCKET,
            CREDENTIALS,
            LockMode.GOVERNANCE,
            Verification.UNVERIFIED_NON_AWS,
            ONE_DOMAIN,
            lower,
            Duration.ofSeconds(5));
    assertThat(config.maxLockDate()).isEqualTo(lower);
    assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
    for (Instant bad :
        List.of(
            Instant.parse("2100-01-01T00:00:00.5Z"),
            Instant.parse("+10000-01-01T00:00:00Z"),
            Instant.EPOCH,
            Instant.parse("1960-01-01T00:00:00Z"))) {
      assertThatThrownBy(
              () ->
                  new WormConfig(
                      endpoint,
                      "us-east-1",
                      BUCKET,
                      CREDENTIALS,
                      LockMode.GOVERNANCE,
                      Verification.UNVERIFIED_NON_AWS,
                      ONE_DOMAIN,
                      bad,
                      Duration.ofSeconds(5)))
          .as("max lock date %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theRequestTimeoutMustBePositive() {
    for (Duration bad : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
      assertThatThrownBy(
              () ->
                  new WormConfig(
                      URI.create("http://127.0.0.1:8000"),
                      "us-east-1",
                      BUCKET,
                      CREDENTIALS,
                      LockMode.GOVERNANCE,
                      Verification.UNVERIFIED_NON_AWS,
                      ONE_DOMAIN,
                      WormConfig.DEFAULT_MAX_LOCK_DATE,
                      bad))
          .as("timeout %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void nothingIsNull() {
    URI endpoint = URI.create("http://127.0.0.1:8000");
    assertThatThrownBy(() -> WormConfig.governance(null, "r", BUCKET, CREDENTIALS, ONE_DOMAIN))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> WormConfig.governance(endpoint, null, BUCKET, CREDENTIALS, ONE_DOMAIN))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> WormConfig.governance(endpoint, "r", null, CREDENTIALS, ONE_DOMAIN))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> WormConfig.governance(endpoint, "r", BUCKET, null, ONE_DOMAIN))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> WormConfig.governance(endpoint, "r", BUCKET, CREDENTIALS, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void toStringHidesTheSecret() {
    String text = hostedConfig().toString();
    assertThat(text).doesNotContain(CREDENTIALS.secretAccessKey()).contains("***");
  }

  @Test
  void toStringHidesTheKeyIdAndTheHost() {
    assertThat(hostedConfig().toString())
        .doesNotContain(CREDENTIALS.accessKeyId())
        .doesNotContain("storage-host");
  }

  private static WormConfig hostedConfig() {
    return WormConfig.governance(
        URI.create("https://storage-host.internal:9443"),
        "us-east-1",
        BUCKET,
        CREDENTIALS,
        ONE_DOMAIN);
  }

  @Test
  void theGradeOfAnUnverifiedStoreSaysUnverified() {
    assertThat(governance("http://127.0.0.1:8000").gradeMode())
        .isEqualTo("governance (unverified)");
    assertThat(
            config(
                    "https://s3.amazonaws.com",
                    LockMode.GOVERNANCE,
                    Verification.AWS_S3,
                    new Durability(true, 3))
                .gradeMode())
        .isEqualTo("governance");
    assertThat(
            config(
                    "https://storage.internal",
                    LockMode.GOVERNANCE,
                    Verification.SP_O_VERIFIED,
                    new Durability(true, 3))
                .gradeMode())
        .isEqualTo("governance");
    assertThat(
            config(
                    "https://s3.amazonaws.com",
                    LockMode.COMPLIANCE,
                    Verification.AWS_S3,
                    new Durability(true, 3))
                .gradeMode())
        .isEqualTo("compliance");
  }
}
