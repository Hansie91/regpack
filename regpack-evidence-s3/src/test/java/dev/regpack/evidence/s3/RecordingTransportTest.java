package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.RecordingTransport.Kind;
import dev.regpack.evidence.s3.RecordingTransport.Segment;
import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The recording transport of OQ-1 (P3c1 T15-2): it labels every request with a kind from a closed
 * list and keeps nothing else of it, it changes nothing about the exchange, and a failure of its
 * own never reaches the caller.
 */
class RecordingTransportTest {
  private static final S3Response OK = new S3Response(200, Map.of(), new byte[0]);
  private static final String HOST = "http://secret-host.example:9000";
  private static final String KEY = "/bkt/sealed/ab/cd%0Aabcdef";

  private static S3Request request(String method, String pathAndQuery) {
    return new S3Request(method, URI.create(HOST + pathAndQuery), Map.of(), new byte[0]);
  }

  private static RecordingTransport recorder() {
    return new RecordingTransport(r -> OK);
  }

  private static Kind kindOf(String method, String pathAndQuery) {
    return RecordingTransport.classify(request(method, pathAndQuery));
  }

  @Test
  void everyRequestShapeOfTheAdapterHasItsKind() {
    assertThat(kindOf("PUT", "/bkt")).isEqualTo(Kind.PUT_BUCKET);
    assertThat(kindOf("GET", "/bkt?versioning")).isEqualTo(Kind.GET_VERSIONING);
    assertThat(kindOf("GET", "/bkt?object-lock")).isEqualTo(Kind.GET_OBJECT_LOCK);
    assertThat(kindOf("GET", "/bkt?max-keys=1000&prefix=sealed%2F&versions"))
        .isEqualTo(Kind.GET_VERSIONS);
    assertThat(kindOf("GET", "/bkt?key-marker=a&max-keys=2&version-id-marker=b&versions"))
        .isEqualTo(Kind.GET_VERSIONS);
    assertThat(kindOf("PUT", KEY)).isEqualTo(Kind.PUT_OBJECT);
    assertThat(kindOf("GET", KEY + "?versionId=3sL4kqtJ")).isEqualTo(Kind.GET_OBJECT);
    assertThat(kindOf("HEAD", KEY + "?versionId=3sL4kqtJ")).isEqualTo(Kind.HEAD_OBJECT);
    assertThat(kindOf("GET", KEY + "?tagging&versionId=3sL4kqtJ")).isEqualTo(Kind.GET_TAGGING);
    assertThat(kindOf("PUT", KEY + "?retention&versionId=3sL4kqtJ")).isEqualTo(Kind.PUT_RETENTION);
    assertThat(kindOf("PUT", KEY + "?tagging&versionId=3sL4kqtJ")).isEqualTo(Kind.PUT_TAGGING);
    assertThat(kindOf("PUT", KEY + "?legal-hold&versionId=3sL4kqtJ"))
        .isEqualTo(Kind.PUT_LEGAL_HOLD);
  }

  @Test
  void aShapeThatTheAdapterNeverSendsIsOther() {
    // a method that is not used, a subresource that is not used, a missing version id, an extra
    // name, a bucket-level call with a name of its own
    assertThat(kindOf("DELETE", KEY + "?versionId=3sL4kqtJ")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("POST", "/bkt?delete")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", KEY)).isEqualTo(Kind.OTHER);
    assertThat(kindOf("PUT", KEY + "?acl&versionId=1")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", KEY + "?versionId=1&tagging&retention")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", "/bkt?lifecycle")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", "/bkt?versioning&object-lock")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("PUT", "/bkt?versioning")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", "/bkt?versions&acl")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", KEY + "?%76ersionId=1")).isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", "/bkt?")).isEqualTo(Kind.OTHER);
  }

  @Test
  void aHostileKeyHostOrQueryValueChangesNothingAboutTheKind() {
    String hostile = "%0A%0D%25%3A%3Aerror%3A%3Ainjected";
    assertThat(kindOf("GET", "/b" + hostile + "?versionId=" + hostile + "&tagging"))
        .as("the bucket name has no key, so a tagging call on it is not an object call")
        .isEqualTo(Kind.OTHER);
    assertThat(kindOf("GET", "/bkt/" + hostile + "?versionId=" + hostile))
        .isEqualTo(Kind.GET_OBJECT);
    assertThat(kindOf("GET", "/bkt/" + hostile + "?tagging&versionId=" + hostile))
        .isEqualTo(Kind.GET_TAGGING);
    assertThat(kindOf("PUT", "/bkt/" + hostile + "?" + hostile + "&versionId=1"))
        .isEqualTo(Kind.OTHER);
  }

  @Test
  void theLabelsAreTheClosedVocabularyAndAreDistinct() {
    List<String> labels = new ArrayList<>();
    for (Kind kind : Kind.values()) {
      labels.add(kind.label());
      assertThat(kind.label()).matches("[A-Za-z]+(-[A-Za-z]+)*");
    }
    assertThat(labels).doesNotHaveDuplicates().hasSize(12).contains("other");
    assertThat(labels)
        .containsExactly(
            "PUT-bucket",
            "GET-versioning",
            "GET-object-lock",
            "GET-versions",
            "PUT-object",
            "GET-object",
            "HEAD-object",
            "GET-tagging",
            "PUT-retention",
            "PUT-tagging",
            "PUT-legal-hold",
            "other");
  }

  @Test
  void onlyTheRequestsOfAnOpenSegmentAreCountedAndTheyAreCountedByKind() throws IOException {
    RecordingTransport recording = recorder();
    recording.send(request("GET", KEY + "?versionId=1"));
    assertThat(recording.segments()).as("no segment is open yet").isEmpty();

    recording.mark("first");
    recording.send(request("GET", "/bkt?versions"));
    recording.send(request("GET", "/bkt?versions"));
    recording.send(request("PUT", KEY));
    recording.stop();
    recording.send(request("PUT", KEY));

    assertThat(recording.segments()).hasSize(1);
    Segment first = recording.segments().get(0);
    assertThat(first.name()).isEqualTo("first");
    assertThat(first.requests()).isEqualTo(3);
    assertThat(first.counts())
        .containsOnly(Map.entry(Kind.GET_VERSIONS, 2), Map.entry(Kind.PUT_OBJECT, 1));
    assertThat(first.byLabel())
        .containsOnly(Map.entry("GET-versions", 2), Map.entry("PUT-object", 1));
    assertThat(recording.intact()).isTrue();
  }

  @Test
  void markEndsTheOpenSegmentAndStartsTheNextAndStopWithoutOneChangesNothing() throws IOException {
    RecordingTransport recording = recorder();
    recording.stop();
    recording.mark("a");
    recording.send(request("GET", "/bkt?versions"));
    recording.mark("b");
    recording.send(request("HEAD", KEY + "?versionId=1"));
    recording.send(request("HEAD", KEY + "?versionId=1"));
    recording.mark("c");
    recording.stop();
    recording.stop();

    List<Segment> segments = recording.segments();
    assertThat(segments).extracting(Segment::name).containsExactly("a", "b", "c");
    assertThat(segments).extracting(Segment::requests).containsExactly(1, 2, 0);
    assertThat(segments.get(1).counts()).containsOnly(Map.entry(Kind.HEAD_OBJECT, 2));
    assertThat(segments.get(2).counts()).isEmpty();
  }

  @Test
  void aSegmentTakesItsTimeFromTheMonotonicClock() throws IOException {
    S3Transport slow =
        r -> {
          long until = System.nanoTime() + Duration.ofMillis(3).toNanos();
          while (System.nanoTime() - until < 0) {
            Thread.onSpinWait();
          }
          return OK;
        };
    RecordingTransport recording = new RecordingTransport(slow);
    recording.mark("slow");
    recording.send(request("GET", "/bkt?versions"));
    recording.stop();

    assertThat(recording.segments().get(0).elapsedNanos()).isGreaterThanOrEqualTo(3_000_000L);
    assertThat(recording.segments().get(0).elapsedNanos())
        .isLessThan(Duration.ofMinutes(1).toNanos());
  }

  @Test
  void theAnswerAndTheFailureOfTheDelegateReachTheCallerUntouched() throws IOException {
    S3Response answer = new S3Response(404, Map.of("x-test", "1"), new byte[] {1, 2, 3});
    RecordingTransport answering = new RecordingTransport(r -> answer);
    answering.mark("x");
    assertThat(answering.send(request("GET", "/bkt?versions"))).isSameAs(answer);

    IOException failure = new IOException("connection refused");
    RecordingTransport failing =
        new RecordingTransport(
            r -> {
              throw failure;
            });
    failing.mark("y");
    assertThatThrownBy(() -> failing.send(request("GET", "/bkt?versions"))).isSameAs(failure);
    failing.stop();
    assertThat(failing.segments().get(0).requests())
        .as("a request that failed was still a request")
        .isEqualTo(1);
  }

  @Test
  void aRecorderThatFailsNeverChangesTheExchangeAndSaysSo() throws IOException {
    RecordingTransport recording =
        new RecordingTransport(
            r -> OK,
            r -> {
              throw new IllegalStateException("the classifier broke on " + r.uri());
            });
    recording.mark("broken");

    assertThat(recording.send(request("GET", "/bkt?versions"))).isSameAs(OK);
    recording.stop();

    assertThat(recording.intact()).isFalse();
    assertThat(recording.segments()).hasSize(1);
    assertThat(recording.segments().get(0).requests())
        .as("the failed record counted nothing, and intact() says the figure is not whole")
        .isZero();
  }

  @Test
  void aRecorderThatFailsOnceStaysBrokenForTheRestOfItsLife() throws IOException {
    int[] calls = {0};
    RecordingTransport recording =
        new RecordingTransport(
            r -> OK,
            r -> {
              if (++calls[0] == 1) {
                throw new IllegalStateException("once");
              }
              return Kind.OTHER;
            });
    recording.mark("s");
    recording.send(request("GET", "/bkt?versions"));
    recording.send(request("GET", "/bkt?versions"));
    recording.stop();

    assertThat(recording.intact()).isFalse();
    assertThat(recording.segments().get(0).requests()).isEqualTo(1);
  }

  @Test
  void concurrentSendersAreAllCounted() throws Exception {
    RecordingTransport recording = recorder();
    recording.mark("busy");
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> done = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        done.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 100; i++) {
                    recording.send(
                        request(
                            i % 2 == 0 ? "PUT" : "HEAD", i % 2 == 0 ? KEY : KEY + "?versionId=1"));
                  }
                  return null;
                }));
      }
      for (Future<?> f : done) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    recording.stop();

    assertThat(recording.segments().get(0).counts())
        .containsOnly(Map.entry(Kind.PUT_OBJECT, 400), Map.entry(Kind.HEAD_OBJECT, 400));
  }

  @Test
  void nothingOfARequestIsKeptButItsKind() throws IOException {
    RecordingTransport recording = recorder();
    recording.mark("seg");
    recording.send(
        new S3Request(
            "GET",
            URI.create(HOST + KEY + "?versionId=SECRETVERSION"),
            Map.of("authorization", "AWS4-HMAC-SHA256 Credential=SECRETKEYID/x"),
            new byte[] {9, 9, 9}));
    recording.stop();

    String everything = recording.segments().toString() + recording;
    assertThat(everything)
        .doesNotContain("secret-host")
        .doesNotContain("sealed")
        .doesNotContain("SECRETVERSION")
        .doesNotContain("SECRETKEYID")
        .doesNotContain("AWS4");
    assertThat(everything).contains("GET_OBJECT");
  }

  @Test
  void theKindsAreWhatTheFakeServiceReceivedFromTheClient() {
    try (FakeWormBackend backend = new FakeWormBackend()) {
      S3Settings settings = backend.settings();
      RecordingTransport recording =
          new RecordingTransport(new JdkTransport(Duration.ofSeconds(10)));
      S3Client client =
          new S3Client(
              settings.endpoint(),
              settings.region(),
              settings.credentials(),
              backend.signingClock(),
              recording);
      String bucket = "rp-recording01";
      String key = "sealed/ab/cd/recorded-key";
      String until = "2041-03-01T00:00:00Z";

      recording.mark("all");
      client.createBucket(bucket, true);
      client.versioningEnabled(bucket);
      client.objectLockEnabled(bucket);
      String version = client.putObject(bucket, key, new byte[] {1, 2, 3}, Map.of()).orElseThrow();
      client.get(bucket, key, version);
      client.head(bucket, key, version);
      client.listVersions(bucket, "", Optional.empty(), Optional.empty(), 1000);
      client.putRetention(bucket, key, version, "GOVERNANCE", until);
      client.tags(bucket, key, version);
      client.putTags(bucket, key, version, Map.of("a", "b"));
      client.putLegalHold(bucket, key, version, true);
      recording.stop();

      Segment segment = recording.segments().get(0);
      List<String> lines = backend.server().requestLines();
      assertThat(segment.requests())
          .as("the recorder counts what the service received")
          .isEqualTo(lines.size())
          .isEqualTo(11);
      Map<Kind, Integer> expected = new EnumMap<>(Kind.class);
      for (Kind kind : Kind.values()) {
        if (kind != Kind.OTHER) {
          expected.put(kind, 1);
        }
      }
      assertThat(segment.counts()).as("one of each kind and nothing else").isEqualTo(expected);
      assertThat(recording.intact()).isTrue();
      // and the method of the kind is the method of the line
      List<String> methods = lines.stream().map(l -> l.substring(0, l.indexOf(' '))).toList();
      assertThat(methods)
          .containsExactly(
              "PUT", "GET", "GET", "PUT", "GET", "HEAD", "GET", "PUT", "GET", "PUT", "PUT");
    }
  }
}
