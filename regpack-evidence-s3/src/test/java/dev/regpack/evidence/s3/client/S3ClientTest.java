package dev.regpack.evidence.s3.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Client.VersionPage;
import dev.regpack.evidence.s3.client.ScriptedHttpServer.Recorded;
import dev.regpack.evidence.s3.client.ScriptedHttpServer.Reply;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.client.SigV4.Param;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client against a scripted loopback server (what goes on the wire) and against capturing
 * transports (what the transport seam receives).
 */
class S3ClientTest {
  private static final String REGION = "us-east-1";
  private static final String SECRET = "client-test-secret-0123456789abcdef";
  private static final Credentials CREDS = new Credentials("AKIACLIENTTEST0001", SECRET);
  private static final Instant T0 = Instant.parse("2040-03-01T00:00:00Z");
  private static final String EMPTY_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final Pattern AUTHORIZATION =
      Pattern.compile(
          "AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/s3/aws4_request, "
              + "SignedHeaders=([a-z0-9;-]+), Signature=([0-9a-f]{64})");
  private static final byte[] NO_BODY = new byte[0];

  private final ManualClock clock = new ManualClock(T0);
  private ScriptedHttpServer server;
  private S3Client client;

  @BeforeEach
  void start() throws IOException {
    server = ScriptedHttpServer.start();
    client = newClient(server.endpoint(), new JdkTransport(Duration.ofSeconds(10)));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private S3Client newClient(URI endpoint, S3Transport transport) {
    return new S3Client(endpoint, REGION, CREDS, clock, transport);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String md5Base64(byte[] bytes) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Reply ok() {
    return Reply.of(200, "");
  }

  private static Reply okWithVersion(String versionId) {
    return Reply.of(200, "").with("x-amz-version-id", versionId);
  }

  private static String error(String code) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>"
        + code
        + "</Code><Message>MARKER-MESSAGE</Message><Resource>MARKER-RESOURCE</Resource>"
        + "<RequestId>MARKER-REQUEST</RequestId><HostId>MARKER-HOST</HostId></Error>";
  }

  /** Signed header names of an Authorization value. */
  private static Set<String> signedNames(String authorization) {
    Matcher m = AUTHORIZATION.matcher(authorization);
    assertThat(m.matches()).as("authorization shape").isTrue();
    return Set.of(m.group(4).split(";"));
  }

  /** Recomputes the signature of a recorded request from its raw parts; it must be the sent one. */
  private void assertSignedCorrectly(Recorded r) {
    String authorization = r.headers().get("authorization");
    Matcher m = AUTHORIZATION.matcher(authorization);
    assertThat(m.matches()).as("authorization shape").isTrue();
    assertThat(m.group(1)).isEqualTo(CREDS.accessKeyId());
    assertThat(m.group(3)).isEqualTo(REGION);
    Map<String, String> signed = new TreeMap<>();
    for (String name : m.group(4).split(";")) {
      assertThat(r.headers()).as("signed header " + name + " is sent").containsKey(name);
      if (!name.equals("host") && !name.equals("x-amz-date")) {
        signed.put(name, r.headers().get(name));
      }
    }
    List<Param> query = new ArrayList<>();
    if (!r.rawQuery().isEmpty()) {
      for (String part : r.rawQuery().split("&", -1)) {
        int eq = part.indexOf('=');
        String name = eq < 0 ? part : part.substring(0, eq);
        String value = eq < 0 ? "" : part.substring(eq + 1);
        query.add(new Param(URLDecoder.decode(name, UTF_8), URLDecoder.decode(value, UTF_8)));
      }
    }
    Map<String, String> expected =
        SigV4.sign(
            CREDS,
            REGION,
            "s3",
            clock.now(),
            r.method(),
            r.headers().get("host"),
            r.path(),
            query,
            signed,
            r.headers().get("x-amz-content-sha256"));
    assertThat(r.headers().get("x-amz-date")).isEqualTo(expected.get("x-amz-date"));
    assertThat(authorization).isEqualTo(expected.get("authorization"));
  }

  // ---- the request

  @Test
  void thePathIsBucketThenTheEncodedKey() {
    server.reply(okWithVersion("v1"), okWithVersion("v2"), ok());

    client.putObject("my-bucket", "dir/a b.txt", "x".getBytes(UTF_8), Map.of());
    client.putObject("my-bucket", "café/€", "x".getBytes(UTF_8), Map.of());
    client.send(
        "GET",
        "my-bucket",
        Optional.empty(),
        List.of(new Param("versions", "")),
        Map.of(),
        NO_BODY);

    assertThat(server.requests())
        .extracting(Recorded::requestLine)
        .containsExactly(
            "PUT /my-bucket/dir/a%20b.txt",
            "PUT /my-bucket/caf%C3%A9/%E2%82%AC", "GET /my-bucket?versions");
    server.requests().forEach(this::assertSignedCorrectly);
  }

  @Test
  void theCanonicalPathEncodesPlusEqualsAndComma() {
    server.reply(okWithVersion("v1"), ok());

    client.putObject("my-bucket", "a+b=c,d$e~f.g_h-i", NO_BODY, Map.of());
    client.get("my-bucket", "a+b=c,d$e~f.g_h-i", "v+1=");

    Recorded put = server.requests().get(0);
    assertThat(put.path()).contains("%2B").contains("%3D").contains("%2C");
    assertThat(put.path()).isEqualTo("/my-bucket/a%2Bb%3Dc%2Cd%24e~f.g_h-i");
    assertThat(server.requests().get(1).requestLine())
        .isEqualTo("GET /my-bucket/a%2Bb%3Dc%2Cd%24e~f.g_h-i?versionId=v%2B1%3D");
    server.requests().forEach(this::assertSignedCorrectly);
  }

  @Test
  void everyXAmzHeaderAndContentMd5AreSigned() {
    server.reply(okWithVersion("v1"));
    Map<String, String> headers = new TreeMap<>();
    headers.put("x-amz-meta-regpack-sha256", "ab");
    headers.put("x-amz-object-lock-mode", "GOVERNANCE");
    headers.put("x-amz-object-lock-retain-until-date", "2040-03-01T00:00:01Z");
    headers.put("content-type", "application/octet-stream");

    client.putObject("b", "k", "body".getBytes(UTF_8), headers);

    Recorded r = server.only();
    Set<String> signed = signedNames(r.headers().get("authorization"));
    assertThat(signed)
        .contains("host", "x-amz-date", "x-amz-content-sha256", "content-md5", "content-type")
        .containsAll(headers.keySet());
    r.headers().keySet().stream()
        .filter(name -> name.startsWith("x-amz-"))
        .forEach(name -> assertThat(signed).as(name).contains(name));
    assertSignedCorrectly(r);
  }

  @Test
  void everyHeaderTheClientAddsIsSignedExceptTheOnesTheJdkSets() {
    List<S3Request> seen = new ArrayList<>();
    S3Transport capture =
        request -> {
          seen.add(request);
          return new S3Response(200, Map.of("x-amz-version-id", "v1"), NO_BODY);
        };
    S3Client c = newClient(URI.create("http://s3.example.test:9000"), capture);

    c.putObject(
        "b", "k", "body".getBytes(UTF_8), Map.of("x-amz-meta-a", "1", "content-type", "t/x"));
    c.putRetention("b", "k", "v1", "GOVERNANCE", "2040-03-01T00:00:01Z");
    c.putLegalHold("b", "k", "v1", true);
    c.putTags("b", "k", "v1", Map.of("a", "b"));
    c.head("b", "k", "v1");
    c.get("b", "k", "v1");
    c.createBucket("b", true);

    assertThat(seen).hasSize(7);
    for (S3Request request : seen) {
      Set<String> signed = signedNames(request.headers().get("authorization"));
      for (String name : request.headers().keySet()) {
        if (!name.equals("authorization") && !name.equals("content-length")) {
          assertThat(signed).as(request.method() + " " + name).contains(name);
        }
      }
    }
  }

  @Test
  void xmlBodiesAndPutObjectCarryContentMd5() {
    server.reply(okWithVersion("v1"), okWithVersion("v1"), ok(), ok(), ok(), ok());
    byte[] body = "payload".getBytes(UTF_8);

    client.putObject("b", "k", body, Map.of());
    client.putObject("b", "k", NO_BODY, Map.of());
    client.putRetention("b", "k", "v1", "GOVERNANCE", "2040-03-01T00:00:01Z");
    client.putLegalHold("b", "k", "v1", true);
    client.putTags("b", "k", "v1", Map.of("regpack-retain-until", "2040-03-01T00:00:00.123456Z"));
    client.send("PUT", "b", Optional.of("k"), List.of(), Map.of(), body);

    List<Recorded> all = server.requests();
    assertThat(all)
        .extracting(Recorded::requestLine)
        .containsExactly(
            "PUT /b/k",
            "PUT /b/k",
            "PUT /b/k?retention&versionId=v1",
            "PUT /b/k?legal-hold&versionId=v1",
            "PUT /b/k?tagging&versionId=v1",
            "PUT /b/k");
    for (Recorded r : all.subList(0, 5)) {
      assertThat(r.headers().get("content-md5")).as(r.requestLine()).isEqualTo(md5Base64(r.body()));
      assertThat(signedNames(r.headers().get("authorization"))).contains("content-md5");
      assertSignedCorrectly(r);
    }
    assertThat(all.get(1).headers().get("content-md5")).isEqualTo("1B2M2Y8AsgTpgAmY7PhCfg==");
    // the raw call adds nothing of its own: the probe decides what an XML body needs
    assertThat(all.get(5).headers()).doesNotContainKey("content-md5");
    assertSignedCorrectly(all.get(5));
    // fixed templates
    assertThat(all.get(2).bodyText())
        .isEqualTo(
            "<Retention xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Mode>GOVERNANCE</Mode>"
                + "<RetainUntilDate>2040-03-01T00:00:01Z</RetainUntilDate></Retention>");
    assertThat(all.get(3).bodyText())
        .isEqualTo(
            "<LegalHold xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Status>ON</Status>"
                + "</LegalHold>");
    assertThat(all.get(4).bodyText())
        .isEqualTo(
            "<Tagging xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><TagSet><Tag>"
                + "<Key>regpack-retain-until</Key><Value>2040-03-01T00:00:00.123456Z</Value>"
                + "</Tag></TagSet></Tagging>");
  }

  @Test
  void theHostHeaderKeepsANonDefaultPort() {
    server.reply(ok());
    client.send("GET", "b", Optional.empty(), List.of(), Map.of(), NO_BODY);
    Recorded live = server.only();
    assertThat(live.headers().get("host"))
        .isEqualTo(
            server.address().getAddress().getHostAddress() + ":" + server.address().getPort());
    assertSignedCorrectly(live);

    Map<String, String> expectedHosts = new TreeMap<>();
    expectedHosts.put("http://s3.example.test", "s3.example.test");
    expectedHosts.put("http://s3.example.test:80", "s3.example.test");
    expectedHosts.put("http://s3.example.test:9000", "s3.example.test:9000");
    expectedHosts.put("https://s3.example.test", "s3.example.test");
    expectedHosts.put("https://s3.example.test:443", "s3.example.test");
    expectedHosts.put("https://s3.example.test:80", "s3.example.test:80");
    expectedHosts.put("http://s3.example.test:443", "s3.example.test:443");
    expectedHosts.put("http://[::1]:9000", "[::1]:9000");
    expectedHosts.put("http://127.0.0.1:8000", "127.0.0.1:8000");
    for (Map.Entry<String, String> e : expectedHosts.entrySet()) {
      List<S3Request> seen = new ArrayList<>();
      S3Client c =
          newClient(
              URI.create(e.getKey()),
              request -> {
                seen.add(request);
                return new S3Response(200, Map.of(), NO_BODY);
              });
      c.send("GET", "b", Optional.of("k"), List.of(), Map.of(), NO_BODY);
      assertThat(seen.get(0).headers()).as(e.getKey()).containsEntry("host", e.getValue());
      assertThat(seen.get(0).uri().getScheme()).isEqualTo(URI.create(e.getKey()).getScheme());
      assertThat(seen.get(0).uri().getRawPath()).isEqualTo("/b/k");
    }
  }

  @Test
  void aBodyHasItsSha256InXAmzContentSha256AndAnEmptyBodyTheEmptyHash() {
    server.reply(okWithVersion("v1"), ok(), ok());
    byte[] body = "hello".getBytes(UTF_8);

    client.putObject("b", "k", body, Map.of());
    client.get("b", "k", "v1");
    client.send("PUT", "b", Optional.of("k"), List.of(), Map.of(), body);

    List<Recorded> all = server.requests();
    assertThat(all.get(0).headers().get("x-amz-content-sha256")).isEqualTo(sha256Hex(body));
    assertThat(all.get(0).headers().get("x-amz-content-sha256"))
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    assertThat(all.get(1).headers().get("x-amz-content-sha256")).isEqualTo(EMPTY_SHA256);
    assertThat(all.get(2).headers().get("x-amz-content-sha256")).isEqualTo(sha256Hex(body));
    all.forEach(this::assertSignedCorrectly);
  }

  @Test
  void aCallerSuppliedPayloadHashIsSentAndSignedAsGiven() {
    server.reply(ok());
    String wrong = "0".repeat(64);

    client.send(
        "PUT",
        "b",
        Optional.of("k"),
        List.of(),
        Map.of("x-amz-content-sha256", wrong),
        "x".getBytes(UTF_8));

    Recorded r = server.only();
    assertThat(r.headers().get("x-amz-content-sha256")).isEqualTo(wrong);
    assertSignedCorrectly(r);
  }

  @Test
  void theRequestsAreSignedWithTheSigningClock() {
    server.reply(ok(), ok(), ok());

    client.send("GET", "b", Optional.empty(), List.of(), Map.of(), NO_BODY);
    clock.advance(Duration.ofHours(1).plusMinutes(2).plusSeconds(3));
    client.send("GET", "b", Optional.empty(), List.of(), Map.of(), NO_BODY);
    clock.set(Instant.parse("2041-12-31T23:59:59Z"));
    client.send("GET", "b", Optional.empty(), List.of(), Map.of(), NO_BODY);

    List<Recorded> all = server.requests();
    assertThat(all.get(0).headers().get("x-amz-date")).isEqualTo("20400301T000000Z");
    assertThat(all.get(1).headers().get("x-amz-date")).isEqualTo("20400301T010203Z");
    assertThat(all.get(2).headers().get("x-amz-date")).isEqualTo("20411231T235959Z");
    assertThat(all.get(0).headers().get("authorization"))
        .contains("/20400301/us-east-1/s3/aws4_request");
    assertThat(all.get(2).headers().get("authorization"))
        .contains("/20411231/us-east-1/s3/aws4_request");
    assertThat(all.get(0).headers().get("authorization"))
        .isNotEqualTo(all.get(1).headers().get("authorization"));
  }

  // ---- the answer

  @Test
  void sendReturnsEvery2xxAndThrowsForTheRest() {
    server.reply(
        Reply.of(200, "body").with("X-Custom", "yes"),
        Reply.of(204, ""),
        Reply.of(301, ""),
        Reply.of(304, ""),
        Reply.of(412, error("PreconditionFailed")),
        Reply.of(503, "<html>down</html>"));

    S3Response first = client.send("GET", "b", Optional.of("k"), List.of(), Map.of(), NO_BODY);
    assertThat(first.status()).isEqualTo(200);
    assertThat(new String(first.body(), UTF_8)).isEqualTo("body");
    assertThat(first.headers()).containsEntry("x-custom", "yes");
    assertThat(client.send("GET", "b", Optional.of("k"), List.of(), Map.of(), NO_BODY).status())
        .isEqualTo(204);

    for (int expected : new int[] {301, 304, 412, 503}) {
      assertThatThrownBy(
              () -> client.send("GET", "b", Optional.of("k"), List.of(), Map.of(), NO_BODY))
          .isInstanceOfSatisfying(
              S3Exception.class, e -> assertThat(e.status()).isEqualTo(expected));
    }
  }

  @Test
  void a404IsEmptyForGetAndHeadAndAnExceptionElsewhere() {
    // an object (or version) that is not there: GET and HEAD answer empty
    server.reply(404, error("NoSuchKey"));
    assertThat(client.get("b", "k", "v1")).isEmpty();
    server.reply(404, error("NoSuchVersion"));
    assertThat(client.get("b", "k", "v1")).isEmpty();
    server.reply(404, "");
    assertThat(client.get("b", "k", "v1")).isEmpty();
    server.reply(404, "");
    assertThat(client.head("b", "k", "v1")).isEmpty();
    // HEAD has no body to tell a missing bucket from a missing key: the caller lists first
    server.reply(404, "");
    assertThat(client.head("b", "k", "v1")).isEmpty();

    // a missing bucket, or a 404 that cannot be read, is never an absent object (D-W7); nor is
    // an error document without a code
    for (String body :
        new String[] {
          error("NoSuchBucket"),
          "<html>not found</html>",
          error("Odd Code"),
          "<Error><Message>x</Message></Error>",
          "<Error><Code></Code></Error>"
        }) {
      server.reply(404, body);
      assertThatThrownBy(() -> client.get("b", "k", "v1"))
          .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    // elsewhere a 404 is an exception
    List<Runnable> elsewhere =
        List.of(
            () -> client.putObject("b", "k", NO_BODY, Map.of()),
            () -> client.listVersions("b", "k", Optional.empty(), Optional.empty(), 10),
            () -> client.putRetention("b", "k", "v1", "GOVERNANCE", "2040-03-01T00:00:01Z"),
            () -> client.putLegalHold("b", "k", "v1", false),
            () -> client.tags("b", "k", "v1"),
            () -> client.putTags("b", "k", "v1", Map.of("a", "b")),
            () -> client.versioningEnabled("b"),
            () -> client.objectLockEnabled("b"),
            () -> client.createBucket("b", true));
    for (Runnable call : elsewhere) {
      server.reply(404, error("NoSuchKey"));
      assertThatThrownBy(call::run)
          .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isEqualTo(404));
    }
  }

  @Test
  void aStatusTheCallDoesNotExpectIsAnS3ExceptionEvenWhenItIsA2xx() {
    server.reply(
        Reply.of(204, ""),
        Reply.of(206, "x"),
        Reply.of(304, ""),
        Reply.of(200, ""),
        Reply.of(201, ""));

    assertThatThrownBy(() -> client.putObject("b", "k", NO_BODY, Map.of()))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(204);
              assertThat(e.getMessage()).isEqualTo("S3 putObject status=204 code=none");
            });
    assertThatThrownBy(() -> client.get("b", "k", "v1"))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isEqualTo(206));
    assertThatThrownBy(() -> client.head("b", "k", "v1"))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isEqualTo(304));
    // a 200 listing that is empty is not a listing
    assertThatThrownBy(() -> client.listVersions("b", "", Optional.empty(), Optional.empty(), 10))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e ->
                assertThat(e.getMessage())
                    .isEqualTo("S3 listObjectVersions status=200 code=unparsable"));
    assertThatThrownBy(() -> client.createBucket("b", true))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isEqualTo(201));
  }

  @Test
  void errorMessagesCarryOnlyStatusAndCode() {
    server.reply(Reply.of(403, error("AccessDenied")));

    assertThatThrownBy(() -> client.get("bucket-name", "evidence/key-1", "version-1"))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.getMessage()).isEqualTo("S3 getObject status=403 code=AccessDenied");
              assertThat(e.status()).isEqualTo(403);
              assertThat(e.code()).isEqualTo("AccessDenied");
              assertThat(e.getCause()).isNull();
              assertThat(e.getSuppressed()).isEmpty();
              String everything = e + " " + e.getMessage() + " " + e.getLocalizedMessage();
              assertThat(everything)
                  .doesNotContain("MARKER")
                  .doesNotContain(SECRET)
                  .doesNotContain(CREDS.accessKeyId())
                  .doesNotContain("127.0.0.1")
                  .doesNotContain("bucket-name")
                  .doesNotContain("evidence/key-1");
            });
  }

  @Test
  void everyOperationNamesItselfInItsErrors() {
    Map<String, Runnable> calls = new TreeMap<>();
    calls.put("createBucket", () -> client.createBucket("b", true));
    calls.put("getBucketVersioning", () -> client.versioningEnabled("b"));
    calls.put("getObjectLockConfiguration", () -> client.objectLockEnabled("b"));
    calls.put("putObject", () -> client.putObject("b", "k", NO_BODY, Map.of()));
    calls.put("headObject", () -> client.head("b", "k", "v"));
    calls.put("getObject", () -> client.get("b", "k", "v"));
    calls.put(
        "listObjectVersions",
        () -> client.listVersions("b", "", Optional.empty(), Optional.empty(), 5));
    calls.put(
        "putObjectRetention",
        () -> client.putRetention("b", "k", "v", "GOVERNANCE", "2040-03-01T00:00:01Z"));
    calls.put("putObjectLegalHold", () -> client.putLegalHold("b", "k", "v", true));
    calls.put("getObjectTagging", () -> client.tags("b", "k", "v"));
    calls.put("putObjectTagging", () -> client.putTags("b", "k", "v", Map.of("a", "b")));
    calls.put(
        "PATCH", () -> client.send("PATCH", "b", Optional.empty(), List.of(), Map.of(), NO_BODY));
    for (Map.Entry<String, Runnable> call : calls.entrySet()) {
      server.reply(Reply.of(500, error("InternalError")));
      assertThatThrownBy(call.getValue()::run)
          .as(call.getKey())
          .isInstanceOfSatisfying(
              S3Exception.class,
              e -> {
                // a HEAD answer has no body, so it has no code
                String code = call.getKey().equals("headObject") ? "none" : "InternalError";
                assertThat(e.getMessage())
                    .isEqualTo("S3 " + call.getKey() + " status=500 code=" + code);
              });
    }
  }

  @Test
  void aCodeWithControlCharactersOrOverLengthIsNotCopiedIntoTheMessage() {
    String[] bad = {
      "Access Denied",
      "Access\tDenied",
      "Line\nBreak",
      "::error::injected",
      "Café",
      "A".repeat(65),
      "x-amz"
    };
    for (String code : bad) {
      server.reply(Reply.of(403, "<Error><Code>" + code + "</Code></Error>"));
      assertThatThrownBy(() -> client.get("b", "k", "v"))
          .isInstanceOfSatisfying(
              S3Exception.class,
              e -> {
                assertThat(e.getMessage()).isEqualTo("S3 getObject status=403 code=invalid");
                assertThat(e.code()).isEqualTo("invalid");
                assertThat(e.getMessage()).doesNotContain("error").doesNotContain("Denied");
              });
    }
    server.reply(Reply.of(403, "<Error><Code>" + "A".repeat(64) + "</Code></Error>"));
    assertThatThrownBy(() -> client.get("b", "k", "v"))
        .isInstanceOfSatisfying(
            S3Exception.class, e -> assertThat(e.code()).isEqualTo("A".repeat(64)));
    // a body that is not XML (a proxy page), a hostile one, and no body at all
    server.reply(
        Reply.of(502, "<html>Bad Gateway MARKER</html>"),
        Reply.of(500, "<!DOCTYPE Error [<!ENTITY e \"x\">]><Error><Code>X</Code></Error>"),
        Reply.of(500, ""));
    for (String expected : new String[] {"unparsable", "unparsable", "none"}) {
      assertThatThrownBy(() -> client.get("b", "k", "v"))
          .isInstanceOfSatisfying(
              S3Exception.class,
              e -> {
                assertThat(e.code()).isEqualTo(expected);
                assertThat(e.getMessage()).doesNotContain("MARKER");
              });
    }
    // the exception type does not trust its caller either
    S3Exception direct = new S3Exception("opName", 500, "bad code\n::error::x");
    assertThat(direct.code()).isEqualTo("invalid");
    assertThat(direct.getMessage()).isEqualTo("S3 opName status=500 code=invalid");
    assertThatThrownBy(() -> new S3Exception("bad op", 500, "Code"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aTransportFailureIsNotChainedAndNamesOnlyItsClass() {
    String leak =
        "connect to https://secret-host.example:9000/bucket failed; Authorization: AWS4-HMAC-SHA256 "
            + "Credential="
            + CREDS.accessKeyId()
            + "/x, Signature=abcd "
            + SECRET;
    Map<String, IOException> failures = new TreeMap<>();
    failures.put("IOException", new IOException(leak));
    failures.put("ConnectException", new ConnectException(leak));
    failures.put("HttpTimeoutException", new HttpTimeoutException(leak));
    failures.put("SocketTimeoutException", new java.net.SocketTimeoutException(leak));
    for (Map.Entry<String, IOException> failure : failures.entrySet()) {
      S3Client c =
          newClient(
              URI.create("http://secret-host.example:9000"),
              request -> {
                throw failure.getValue();
              });
      assertThatThrownBy(() -> c.putObject("secret-bucket", "secret-key", NO_BODY, Map.of()))
          .as(failure.getKey())
          .isInstanceOfSatisfying(
              S3Exception.class,
              e -> {
                assertThat(e.getMessage())
                    .isEqualTo("S3 putObject status=0 code=" + failure.getKey());
                assertThat(e.status()).isZero();
                assertThat(e.code()).isEqualTo(failure.getKey());
                assertThat(e.getCause()).isNull();
                assertThat(e.getSuppressed()).isEmpty();
                assertThat(e.toString())
                    .doesNotContain("secret")
                    .doesNotContain(SECRET)
                    .doesNotContain(CREDS.accessKeyId());
              });
    }
    // an exception class without a usable simple name is reported as invalid, not named
    S3Client anonymous =
        newClient(
            URI.create("http://secret-host.example:9000"),
            request -> {
              throw new IOException("x") {
                private static final long serialVersionUID = 1L;
              };
            });
    assertThatThrownBy(() -> anonymous.head("b", "k", "v"))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> assertThat(e.getMessage()).isEqualTo("S3 headObject status=0 code=invalid"));
  }

  @Test
  void aStatusOutsideTheHttpRangeIsAnS3ExceptionWithStatusZeroAndNoLeak() {
    for (int status : new int[] {600, 999}) {
      server.reply(Reply.of(status, "MARKER-BODY").with("X-Marker", "MARKER-HEADER"));
      assertThatThrownBy(() -> client.versioningEnabled("bucket1"))
          .as("status " + status)
          .isInstanceOfSatisfying(
              S3Exception.class,
              e -> {
                assertThat(e.getMessage())
                    .isEqualTo("S3 getBucketVersioning status=0 code=ProtocolException");
                assertThat(e.status()).isZero();
                assertThat(e.getCause()).isNull();
                assertThat(e.getSuppressed()).isEmpty();
              });
    }
    // the raw call maps it the same way
    server.reply(700, "");
    assertThatThrownBy(
            () -> client.send("GET", "bucket1", Optional.of("k"), List.of(), Map.of(), new byte[0]))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> assertThat(e.getMessage()).isEqualTo("S3 GET status=0 code=ProtocolException"));
    assertThat(server.requests()).hasSize(3);
  }

  @Test
  void anEndpointPortOutsideOneToSixtyFiveThousandFiveHundredThirtyFiveIsRefused() {
    for (String endpoint :
        new String[] {
          "http://s3.example.test:0",
          "http://s3.example.test:65536",
          "http://s3.example.test:99999",
          "https://s3.example.test:0",
          "http://s3.example.test:-5",
          "http://s3.example.test:80800"
        }) {
      assertThatThrownBy(
              () ->
                  newClient(
                      URI.create(endpoint), request -> new S3Response(200, Map.of(), NO_BODY)))
          .as(endpoint)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e ->
                  assertThat(String.valueOf(e.getMessage()))
                      .doesNotContain("example")
                      .doesNotContain("99999"));
    }
    // the edges are fine
    newClient(URI.create("http://s3.example.test:1"), request -> null);
    newClient(URI.create("http://s3.example.test:65535"), request -> null);
  }

  @Test
  void requestAndResponseToStringHoldNoHeaderValueAndNoBody() {
    S3Request request =
        new S3Request(
            "PUT",
            URI.create(
                "https://host-marker.example:9000/the-bucket/the-key?versionId=VERSION-MARKER"),
            Map.of(
                "authorization", "AWS4-HMAC-SHA256 Credential=AKIAMARKER/x, Signature=SIG-MARKER",
                "x-amz-meta-a", "VALUE-MARKER"),
            "BODY-MARKER".getBytes(UTF_8));
    S3Response response =
        new S3Response(
            200,
            Map.of("x-amz-version-id", "HEADER-MARKER", "etag", "ETAG-MARKER"),
            "BODY-MARKER".getBytes(UTF_8));

    assertThat(request.toString())
        .isEqualTo(
            "S3Request[method=PUT, path=/the-bucket/the-key, "
                + "headers=[authorization, x-amz-meta-a], bodyLength=11]");
    assertThat(response.toString())
        .isEqualTo("S3Response[status=200, headers=[etag, x-amz-version-id], bodyLength=11]");
    assertThat(request.toString() + response)
        .doesNotContain("MARKER")
        .doesNotContain("host-")
        .doesNotContain("Credential");
  }

  @Test
  void requestAndResponseAreImmutableAndNullFree() {
    byte[] body = "abc".getBytes(UTF_8);
    Map<String, String> headers = new TreeMap<>(Map.of("a", "1"));
    S3Request request = new S3Request("GET", URI.create("http://h/p"), headers, body);
    S3Response response = new S3Response(200, headers, body);

    body[0] = 'X';
    headers.put("b", "2");
    request.body()[1] = 'Y';
    response.body()[2] = 'Z';

    assertThat(request.body()).isEqualTo("abc".getBytes(UTF_8));
    assertThat(response.body()).isEqualTo("abc".getBytes(UTF_8));
    assertThat(request.headers()).isEqualTo(Map.of("a", "1"));
    assertThat(response.headers()).isEqualTo(Map.of("a", "1"));
    assertThat(request)
        .isEqualTo(
            new S3Request(
                "GET", URI.create("http://h/p"), Map.of("a", "1"), "abc".getBytes(UTF_8)));
    assertThat(response).isEqualTo(new S3Response(200, Map.of("a", "1"), "abc".getBytes(UTF_8)));
    assertThatThrownBy(() -> request.headers().put("c", "3"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> new S3Request("GET", URI.create("http://h/p"), Map.of("Upper", "1"), NO_BODY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Response(200, Map.of("Upper", "1"), NO_BODY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Request("get", URI.create("http://h/p"), Map.of(), NO_BODY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Response(99, Map.of(), NO_BODY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3Response(200, Map.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  // ---- the listing

  private static String listing(
      boolean truncated, String nextKey, String nextVersion, String... entries) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        + "<Name>b</Name><Prefix>k</Prefix><MaxKeys>1000</MaxKeys>"
        + "<IsTruncated>"
        + truncated
        + "</IsTruncated>"
        + (nextKey.isEmpty() ? "" : "<NextKeyMarker>" + nextKey + "</NextKeyMarker>")
        + (nextVersion.isEmpty()
            ? ""
            : "<NextVersionIdMarker>" + nextVersion + "</NextVersionIdMarker>")
        + String.join("", entries)
        + "</ListVersionsResult>";
  }

  private static String version(String key, String versionId, boolean latest) {
    return "<Version><Key>"
        + key
        + "</Key><VersionId>"
        + versionId
        + "</VersionId><IsLatest>"
        + latest
        + "</IsLatest><LastModified>2040-03-01T00:00:00.000Z</LastModified>"
        + "<ETag>&quot;e&quot;</ETag><Size>3</Size><StorageClass>STANDARD</StorageClass></Version>";
  }

  private static String marker(String key, String versionId, boolean latest) {
    return "<DeleteMarker><Key>"
        + key
        + "</Key><VersionId>"
        + versionId
        + "</VersionId><IsLatest>"
        + latest
        + "</IsLatest><LastModified>2040-03-01T00:00:00.000Z</LastModified></DeleteMarker>";
  }

  @Test
  void listVersionsParsesVersionsAndDeleteMarkersInOrder() {
    server.reply(
        200,
        listing(
            true,
            "k2",
            "v9",
            version("k1", "v3", true),
            marker("k1", "v2", false),
            version("k1", "v1", false),
            marker("k2", "v8", true)));

    VersionPage page = client.listVersions("b", "k", Optional.empty(), Optional.empty(), 1000);

    assertThat(page.entries())
        .containsExactly(
            new VersionEntry("k1", "v3", true, false),
            new VersionEntry("k1", "v2", false, true),
            new VersionEntry("k1", "v1", false, false),
            new VersionEntry("k2", "v8", true, true));
    assertThat(page.truncated()).isTrue();
    assertThat(page.nextKeyMarker()).contains("k2");
    assertThat(page.nextVersionIdMarker()).contains("v9");
    assertThat(server.only().requestLine()).isEqualTo("GET /b?max-keys=1000&prefix=k&versions");
    assertSignedCorrectly(server.only());
    assertThatThrownBy(() -> page.entries().add(new VersionEntry("x", "y", true, false)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void listVersionsSendsItsMarkersAndOmitsAnEmptyPrefix() {
    server.reply(200, listing(false, "", ""));
    server.reply(200, listing(false, "", ""));

    VersionPage last = client.listVersions("b", "", Optional.of("a/k+1"), Optional.of("v 1"), 7);
    client.listVersions("b", "p/", Optional.of("a"), Optional.empty(), 1);

    assertThat(last.truncated()).isFalse();
    assertThat(last.nextKeyMarker()).isEmpty();
    assertThat(last.nextVersionIdMarker()).isEmpty();
    assertThat(last.entries()).isEmpty();
    assertThat(server.requests())
        .extracting(Recorded::requestLine)
        .containsExactly(
            "GET /b?key-marker=a%2Fk%2B1&max-keys=7&version-id-marker=v%201&versions",
            "GET /b?key-marker=a&max-keys=1&prefix=p%2F&versions");
    server.requests().forEach(this::assertSignedCorrectly);
  }

  // ---- the other operations

  @Test
  void createBucketSendsTheObjectLockHeaderOnlyWhenAsked() {
    server.reply(ok(), ok());

    client.createBucket("with-lock", true);
    client.createBucket("without-lock", false);

    List<Recorded> all = server.requests();
    assertThat(all)
        .extracting(Recorded::requestLine)
        .containsExactly("PUT /with-lock", "PUT /without-lock");
    assertThat(all.get(0).headers()).containsEntry("x-amz-bucket-object-lock-enabled", "true");
    assertThat(all.get(1).headers()).doesNotContainKey("x-amz-bucket-object-lock-enabled");
    assertThat(all.get(0).body()).isEmpty();
    all.forEach(this::assertSignedCorrectly);
  }

  @Test
  void versioningAndObjectLockAreReadFromTheirConfigurations() {
    server.reply(
        Reply.of(
            200, "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>"),
        Reply.of(200, "<VersioningConfiguration/>"),
        Reply.of(
            200, "<VersioningConfiguration><Status>Suspended</Status></VersioningConfiguration>"),
        Reply.of(
            200,
            "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>"),
        Reply.of(404, error("ObjectLockConfigurationNotFoundError")),
        Reply.of(404, error("NoSuchBucket")),
        Reply.of(403, error("AccessDenied")));

    assertThat(client.versioningEnabled("b")).isTrue();
    assertThat(client.versioningEnabled("b")).isFalse();
    assertThat(client.versioningEnabled("b")).isFalse();
    assertThat(client.objectLockEnabled("b")).isTrue();
    assertThat(client.objectLockEnabled("b")).isFalse();
    assertThatThrownBy(() -> client.objectLockEnabled("b"))
        .isInstanceOfSatisfying(
            S3Exception.class,
            e ->
                assertThat(e.getMessage())
                    .isEqualTo("S3 getObjectLockConfiguration status=404 code=NoSuchBucket"));
    assertThatThrownBy(() -> client.objectLockEnabled("b"))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.status()).isEqualTo(403));

    assertThat(server.requests())
        .extracting(Recorded::requestLine)
        .containsExactly(
            "GET /b?versioning",
            "GET /b?versioning",
            "GET /b?versioning",
            "GET /b?object-lock",
            "GET /b?object-lock",
            "GET /b?object-lock",
            "GET /b?object-lock");
    server.requests().forEach(this::assertSignedCorrectly);
  }

  @Test
  void getAndHeadReturnTheirVersionWithLowerCasedHeaders() {
    server.reply(
        Reply.of(200, "the-bytes")
            .with("X-Amz-Version-Id", "v1")
            .with("X-Amz-Meta-Regpack-Sha256", "abc")
            .with("X-Amz-Object-Lock-Legal-Hold", "ON"),
        Reply.of(200, "").with("X-Amz-Version-Id", "v1"));

    S3Response got = client.get("b", "dir/k", "v1").orElseThrow();
    S3Response head = client.head("b", "dir/k", "v1").orElseThrow();

    assertThat(new String(got.body(), UTF_8)).isEqualTo("the-bytes");
    assertThat(got.headers())
        .containsEntry("x-amz-version-id", "v1")
        .containsEntry("x-amz-meta-regpack-sha256", "abc")
        .containsEntry("x-amz-object-lock-legal-hold", "ON");
    assertThat(head.body()).isEmpty();
    assertThat(head.headers()).containsEntry("x-amz-version-id", "v1");
    assertThat(server.requests())
        .extracting(Recorded::requestLine)
        .containsExactly("GET /b/dir/k?versionId=v1", "HEAD /b/dir/k?versionId=v1");
    server.requests().forEach(this::assertSignedCorrectly);
  }

  @Test
  void putObjectReturnsTheVersionIdOrNothing() {
    server.reply(okWithVersion("3/L4kqtJlcpXroDTDmJ+rmSpXd3dIbrHY"), ok());

    assertThat(client.putObject("b", "k", NO_BODY, Map.of()))
        .contains("3/L4kqtJlcpXroDTDmJ+rmSpXd3dIbrHY");
    assertThat(client.putObject("b", "k", NO_BODY, Map.of())).isEmpty();
  }

  @Test
  void tagsAreReadAndWrittenAsAMap() {
    server.reply(
        200,
        "<Tagging><TagSet><Tag><Key>regpack-retain-until</Key><Value>2040-03-01T00:00:00.123456Z</Value>"
            + "</Tag><Tag><Key>b</Key><Value></Value></Tag></TagSet></Tagging>");
    server.reply(ok());

    Map<String, String> tags = client.tags("b", "k", "v1");
    client.putTags("b", "k", "v1", tags);

    assertThat(tags)
        .isEqualTo(Map.of("regpack-retain-until", "2040-03-01T00:00:00.123456Z", "b", ""));
    assertThatThrownBy(() -> tags.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    assertThat(server.requests())
        .extracting(Recorded::requestLine)
        .containsExactly("GET /b/k?tagging&versionId=v1", "PUT /b/k?tagging&versionId=v1");
    server.requests().forEach(this::assertSignedCorrectly);
  }

  // ---- arguments

  @Test
  void argumentsThatCouldBendTheRequestAreRefusedBeforeAnythingIsSent() {
    AtomicInteger sent = new AtomicInteger();
    S3Client c =
        newClient(
            URI.create("http://s3.example.test"),
            request -> {
              sent.incrementAndGet();
              return new S3Response(200, Map.of(), NO_BODY);
            });
    String m = "MARKER";
    List<Runnable> bad = new ArrayList<>();
    for (String bucket :
        new String[] {"", m + "/x", "." + m, "..", m + " b", m + "?x", m + "ü", "-" + m}) {
      bad.add(() -> c.get(bucket, "k", "v"));
    }
    bad.add(() -> c.get("b", "", "v"));
    bad.add(() -> c.get("b", "k", ""));
    bad.add(() -> c.get("b", m + (char) 0xD800, "v"));
    bad.add(() -> c.head("b", "k", ""));
    bad.add(() -> c.listVersions("b", "", Optional.empty(), Optional.empty(), 0));
    bad.add(() -> c.listVersions("b", "", Optional.empty(), Optional.empty(), 1001));
    bad.add(() -> c.listVersions("b", "", Optional.empty(), Optional.of(m), 10));
    bad.add(() -> c.listVersions("b", "", Optional.of(""), Optional.empty(), 10));
    bad.add(() -> c.putRetention("b", "k", "v", m + "MODE", "2040-03-01T00:00:01Z"));
    bad.add(() -> c.putRetention("b", "k", "v", "GOVERNANCE", "<" + m + ">"));
    bad.add(() -> c.putRetention("b", "k", "v", "GOVERNANCE", ""));
    bad.add(() -> c.putTags("b", "k", "v", Map.of()));
    bad.add(() -> c.putTags("b", "k", "v", Map.of("", m)));
    bad.add(() -> c.send("get", "b", Optional.empty(), List.of(), Map.of(), NO_BODY));
    bad.add(() -> c.send("GET", "b", Optional.of(""), List.of(), Map.of(), NO_BODY));
    bad.add(
        () -> c.send("GET", "b", Optional.empty(), List.of(new Param("", m)), Map.of(), NO_BODY));
    for (String header :
        new String[] {
          "host", "x-amz-date", "authorization", "content-length", "Upper-" + m, m + " name", ""
        }) {
      bad.add(() -> c.send("GET", "b", Optional.empty(), List.of(), Map.of(header, m), NO_BODY));
    }
    for (String value : new String[] {m + "é", m + "\r\nb: c", m + (char) 0x7F}) {
      bad.add(
          () ->
              c.send(
                  "GET", "b", Optional.empty(), List.of(), Map.of("x-amz-meta-a", value), NO_BODY));
    }
    bad.add(() -> c.putObject("b", "k", NO_BODY, Map.of("content-md5", m)));
    bad.add(() -> c.putObject("b", "k", NO_BODY, Map.of("x-amz-content-sha256", "0".repeat(64))));
    for (Runnable call : bad) {
      assertThatThrownBy(call::run)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(m));
    }
    assertThat(sent).hasValue(0);
  }

  @Test
  void anEndpointIsSchemeHostAndOptionalPortOnly() {
    for (String endpoint :
        new String[] {
          "http://user:password@s3.example.test",
          "http://s3.example.test/path",
          "http://s3.example.test/",
          "http://s3.example.test?x=1",
          "http://s3.example.test#fragment",
          "ftp://s3.example.test",
          "s3.example.test:9000",
          "mailto:someone@example.test",
          "http:///nohost"
        }) {
      assertThatThrownBy(
              () ->
                  newClient(
                      URI.create(endpoint), request -> new S3Response(200, Map.of(), NO_BODY)))
          .as(endpoint)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e ->
                  assertThat(String.valueOf(e.getMessage()))
                      .doesNotContain("password")
                      .doesNotContain("example"));
    }
    assertThatThrownBy(
            () ->
                new S3Client(
                    URI.create("http://h"),
                    "bad region",
                    CREDS,
                    clock,
                    request -> new S3Response(200, Map.of(), NO_BODY)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theScriptedServerListensOnALoopbackAddressOnly() {
    assertThat(server.address().getAddress().isLoopbackAddress()).isTrue();
    assertThat(server.endpoint().getHost()).isIn("127.0.0.1", "[::1]");
  }
}
