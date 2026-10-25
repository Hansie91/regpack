package dev.regpack.evidence.s3;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Client.VersionPage;
import dev.regpack.evidence.s3.client.S3Exception;
import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.evidence.s3.client.SigV4.Param;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The probe (plan section "The probe", items P1 to P17): what the S3-compatible service of the job
 * {@code evidence-worm} really does. It runs only in that job, against the pinned image, with
 * {@code -Dgroups=probe}; everywhere else the tag excludes it and an unset service skips it.
 *
 * <p>Each item is one {@link #probe} call that returns a status (the decisive request of the item),
 * the S3 code if one is worth keeping, and a short observed value. Nothing here is a judgement of
 * the service: a failing item is recorded as {@code ERROR} (or with the status it got) and the
 * probe goes on. {@link ProbeReport} turns the items into the one workflow command {@code ::warning
 * title=probe-results::} and into {@code target/probe-result.txt}. The test fails only when the
 * service does not answer (the harness) or when P16, the signed request of the client, is not a
 * 200.
 *
 * <p>Every bucket is new ({@code rp-} and 12 hex digits of a random UUID) and none is emptied: the
 * container is discarded with the job. The objects keep their locks; none of them is ever deleted
 * (the one DELETE of P7 adds a delete marker, the DELETE of a locked version is expected to be
 * refused).
 */
@Tag("probe")
class S3ServiceProbeIT {
  private static final String XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/";
  private static final String GOVERNANCE = "GOVERNANCE";
  private static final String COMPLIANCE = "COMPLIANCE";
  private static final String UNTIL = "2045-03-01T00:00:00Z";
  private static final String LATER = "2046-03-01T00:00:00Z";
  private static final String LATEST = "2047-03-01T00:00:00Z";
  private static final String SHORTER = "2044-03-01T00:00:00Z";
  private static final String MODE_HEADER = "x-amz-object-lock-mode";
  private static final String UNTIL_HEADER = "x-amz-object-lock-retain-until-date";
  private static final String HOLD_HEADER = "x-amz-object-lock-legal-hold";
  private static final String VERSION_HEADER = "x-amz-version-id";
  private static final String BYPASS_HEADER = "x-amz-bypass-governance-retention";
  private static final String META_PREFIX = "x-amz-meta-";
  private static final byte[] NO_BODY = new byte[0];
  private static final Pattern ERROR_CODE = Pattern.compile("<Code>([A-Za-z0-9]{1,64})</Code>");

  /** What one item observed: the decisive status, a code worth keeping (or null), a short text. */
  private record Result(int status, String code, String observed) {}

  /** One answer, read whole: a 2xx or the status and code of an {@link S3Exception}. */
  private record Seen(int status, String code, Map<String, String> headers, byte[] body) {
    boolean ok() {
      return status >= 200 && status < 300;
    }

    /** {@code 200}, or {@code 403/AccessDenied}. */
    String st() {
      return ok() || code.equals("none") ? Integer.toString(status) : status + "/" + code;
    }

    String header(String name) {
      return headers.getOrDefault(name, "absent");
    }
  }

  @FunctionalInterface
  private interface Body {
    Result run() throws Exception;
  }

  /** Keeps the last answer, so that the headers of a refused request can be read. */
  private static final class RecordingTransport implements S3Transport {
    private final S3Transport inner;
    private final AtomicReference<S3Response> last = new AtomicReference<>();

    RecordingTransport(S3Transport inner) {
      this.inner = inner;
    }

    @Override
    public S3Response send(S3Request request) throws IOException {
      S3Response answer = inner.send(request);
      last.set(answer);
      return answer;
    }

    void reset() {
      last.set(null);
    }

    S3Response last() {
      return last.get();
    }
  }

  private final ProbeReport report = new ProbeReport();
  private RecordingTransport recording;
  private S3Client client;
  private boolean hostHasPort;
  private String firstBucket;

  @Test
  void probeTheService() throws IOException {
    S3Settings settings = S3TestService.settings();
    recording = new RecordingTransport(new JdkTransport(Duration.ofSeconds(30)));
    client =
        new S3Client(
            settings.endpoint(),
            settings.region(),
            settings.credentials(),
            S3TestService.realClock(),
            recording);
    hostHasPort = settings.endpoint().getPort() != -1;

    probe(1, this::p1);
    probe(16, this::p16);
    probe(2, this::p2);
    probe(3, this::p3);
    probe(4, this::p4);
    probe(5, this::p5);
    probe(6, this::p6);
    probe(7, this::p7);
    probe(8, this::p8);
    probe(9, this::p9);
    probe(10, this::p10);
    probe(11, this::p11);
    probe(12, this::p12);
    probe(13, this::p13);
    probe(14, this::p14);
    probe(15, this::p15);
    probe(17, this::p17);

    // the one workflow command of the step, and the same items as a file
    System.out.println(report.line());
    Path file = Path.of(System.getProperty("regpack.build.dir", "target"), "probe-result.txt");
    Files.createDirectories(file.getParent());
    Files.write(file, report.fileLines(), UTF_8);

    assertThat(report.recorded()).hasSize(17);
    assertThat(report.status(16)).as("P16: the first signed GET of a bucket").isEqualTo("200");
  }

  private void probe(int number, Body body) {
    try {
      Result result = body.run();
      report.add(number, result.status(), result.code(), result.observed());
    } catch (S3Exception e) {
      report.addError(number, e.code(), "S3Exception " + e.operation() + " status=" + e.status());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      report.addError(number, null, "InterruptedException");
    } catch (Exception e) {
      report.addError(number, null, e.getClass().getSimpleName());
    }
  }

  // ------------------------------------------------------------------ P1, P16

  /** P1: a bucket made with object lock; its versioning and object-lock configuration. */
  private Result p1() {
    firstBucket = bucketName();
    Seen create =
        raw(
            "PUT",
            firstBucket,
            null,
            List.of(),
            Map.of("x-amz-bucket-object-lock-enabled", "true"),
            NO_BODY);
    if (!create.ok()) {
      return new Result(create.status(), create.code(), "create=" + create.st());
    }
    boolean versioning = client.versioningEnabled(firstBucket);
    boolean lock = client.objectLockEnabled(firstBucket);
    return new Result(
        create.status(),
        null,
        "create=" + create.st() + " versioning=" + versioning + " lock=" + lock);
  }

  /** P16: a signed path-style GET of the bucket with a Host that keeps its port. */
  private Result p16() {
    if (firstBucket == null) {
      throw new IllegalStateException("no bucket from P1");
    }
    Seen list = raw("GET", firstBucket, null, List.of(), Map.of(), NO_BODY);
    return new Result(
        list.status(),
        list.ok() ? null : list.code(),
        "get=" + list.st() + " hostHasPort=" + hostHasPort);
  }

  // ------------------------------------------------------------------ P2 to P6

  /** P2: governance lock headers on a PUT; HEAD returns mode, date text and a version id. */
  private Result p2() {
    String bucket = newBucket();
    Seen put = rawPut(bucket, "p2/a", bytes("p2"), lock(GOVERNANCE, UNTIL));
    requireOk("putObject", put);
    Seen head = raw("HEAD", bucket, "p2/a", List.of(), Map.of(), NO_BODY);
    return new Result(
        head.status(),
        head.ok() ? null : head.code(),
        "put="
            + put.st()
            + " mode="
            + head.header(MODE_HEADER)
            + " until="
            + head.header(UNTIL_HEADER)
            + " vid="
            + !head.header(VERSION_HEADER).equals("absent"));
  }

  /** P3: the worst case of A10 (1,676 bytes of user metadata) read back from HEAD and GET. */
  private Result p3() {
    String bucket = newBucket();
    Map<String, String> meta = new TreeMap<>();
    meta.put("regpack-sha256", sha256Hex(bytes("p3")));
    meta.put("regpack-written-at", "2040-03-01T00:00:00.123456Z");
    meta.put("regpack-retain-until", "2045-03-01T00:00:00.123456Z");
    meta.put("regpack-class", base64Url("€".repeat(100)));
    meta.put("regpack-media-type", base64Url("€".repeat(255)));
    Map<String, String> headers = new TreeMap<>();
    int size = 0;
    for (Map.Entry<String, String> e : meta.entrySet()) {
      headers.put(META_PREFIX + e.getKey(), e.getValue());
      size += (META_PREFIX + e.getKey()).length() + e.getValue().length();
    }
    Seen put = rawPut(bucket, "p3/a", bytes("p3"), headers);
    if (!put.ok()) {
      return new Result(put.status(), put.code(), "bytes=" + size + " put=" + put.st());
    }
    Seen head = raw("HEAD", bucket, "p3/a", List.of(), Map.of(), NO_BODY);
    Seen get = raw("GET", bucket, "p3/a", List.of(), Map.of(), NO_BODY);
    return new Result(
        put.status(),
        null,
        "bytes="
            + size
            + " put="
            + put.st()
            + " head="
            + equalMeta(meta, head)
            + "/5 get="
            + equalMeta(meta, get)
            + "/5");
  }

  private static int equalMeta(Map<String, String> sent, Seen answer) {
    int equal = 0;
    for (Map.Entry<String, String> e : sent.entrySet()) {
      if (e.getValue().equals(answer.headers().get(META_PREFIX + e.getKey()))) {
        equal++;
      }
    }
    return equal;
  }

  /** P4: a tag on a locked version, with the retain-until text of the adapter, read back. */
  private Result p4() {
    String bucket = newBucket();
    Seen put = rawPut(bucket, "p4/a", bytes("p4"), lock(GOVERNANCE, UNTIL));
    requireOk("putObject", put);
    String version = versionOf(put);
    String value = "2040-03-01T00:00:00.123456Z";
    client.putTags(bucket, "p4/a", version, Map.of("regpack-retain-until", value));
    Map<String, String> tags = client.tags(bucket, "p4/a", version);
    Seen get =
        raw("GET", bucket, "p4/a", List.of(new Param("versionId", version)), Map.of(), NO_BODY);
    return new Result(
        200,
        null,
        "tagged=ok verbatim="
            + value.equals(tags.get("regpack-retain-until"))
            + " tags="
            + tags.size()
            + " count="
            + get.header("x-amz-tagging-count"));
  }

  /** P5: extend, shorten with and without the bypass header; compliance; with and without MD5. */
  private Result p5() {
    String bucket = newBucket();
    Seen put = rawPut(bucket, "p5/g", bytes("p5"), lock(GOVERNANCE, UNTIL));
    requireOk("putObject", put);
    String version = versionOf(put);
    Seen extend = retention(bucket, "p5/g", version, GOVERNANCE, LATER, true, false);
    Seen extendNoMd5 = retention(bucket, "p5/g", version, GOVERNANCE, LATEST, false, false);
    Seen shorten = retention(bucket, "p5/g", version, GOVERNANCE, SHORTER, true, false);
    Seen shortenBypass = retention(bucket, "p5/g", version, GOVERNANCE, SHORTER, true, true);

    String compliance = newBucket();
    Seen putC = rawPut(compliance, "p5/c", bytes("p5c"), lock(COMPLIANCE, UNTIL));
    requireOk("putObject", putC);
    Seen shortenC = retention(compliance, "p5/c", versionOf(putC), COMPLIANCE, SHORTER, true, true);
    return new Result(
        shorten.status(),
        shorten.ok() ? null : shorten.code(),
        "ext="
            + extend.st()
            + " extNoMd5="
            + extendNoMd5.st()
            + " short="
            + shorten.st()
            + " shortBypass="
            + shortenBypass.st()
            + " compBypass="
            + shortenC.st());
  }

  /** P6: legal hold on and off; the header on HEAD and GET; the header before any hold. */
  private Result p6() {
    String bucket = newBucket();
    Seen put = rawPut(bucket, "p6/a", bytes("p6"), Map.of());
    requireOk("putObject", put);
    String version = versionOf(put);
    List<Param> byVersion = List.of(new Param("versionId", version));
    Seen before = raw("HEAD", bucket, "p6/a", byVersion, Map.of(), NO_BODY);
    client.putLegalHold(bucket, "p6/a", version, true);
    Seen headOn = raw("HEAD", bucket, "p6/a", byVersion, Map.of(), NO_BODY);
    Seen getOn = raw("GET", bucket, "p6/a", byVersion, Map.of(), NO_BODY);
    client.putLegalHold(bucket, "p6/a", version, false);
    Seen headOff = raw("HEAD", bucket, "p6/a", byVersion, Map.of(), NO_BODY);
    return new Result(
        headOn.status(),
        null,
        "before="
            + before.header(HOLD_HEADER)
            + " headOn="
            + headOn.header(HOLD_HEADER)
            + " getOn="
            + getOn.header(HOLD_HEADER)
            + " headOff="
            + headOff.header(HOLD_HEADER));
  }

  // ------------------------------------------------------------------ P7, P8, P9

  /** P7: a second PUT, the order of the version list, a delete marker, the DELETE of a version. */
  private Result p7() {
    String bucket = newBucket();
    Seen one = rawPut(bucket, "p7/k", bytes("one"), lock(GOVERNANCE, UNTIL));
    Seen two = rawPut(bucket, "p7/k", bytes("two"), lock(GOVERNANCE, UNTIL));
    requireOk("putObject", one);
    requireOk("putObject", two);
    List<VersionEntry> before = exactKey(bucket, "p7/k");
    boolean newestFirst =
        before.size() == 2
            && before.get(0).versionId().equals(versionOf(two))
            && before.get(0).latest();
    Seen delete = raw("DELETE", bucket, "p7/k", List.of(), Map.of(), NO_BODY);
    Seen get = raw("GET", bucket, "p7/k", List.of(), Map.of(), NO_BODY);
    Seen head = raw("HEAD", bucket, "p7/k", List.of(), Map.of(), NO_BODY);
    Seen byId =
        raw(
            "DELETE",
            bucket,
            "p7/k",
            List.of(new Param("versionId", versionOf(one))),
            Map.of(),
            NO_BODY);
    List<VersionEntry> after = exactKey(bucket, "p7/k");
    boolean markerFirst = !after.isEmpty() && after.get(0).deleteMarker() && after.get(0).latest();
    return new Result(
        delete.status(),
        null,
        "newestFirst="
            + newestFirst
            + " del="
            + delete.st()
            + " mk="
            + delete.header("x-amz-delete-marker")
            + " get="
            + get.st()
            + "/"
            + get.header("x-amz-delete-marker")
            + " head="
            + head.st()
            + "/"
            + head.header("x-amz-delete-marker")
            + " byId="
            + byId.st()
            + " listed="
            + after.size()
            + (markerFirst ? " mkFirst" : ""));
  }

  /** P8: 1,100 keys and a delete marker: pages, markers, order, a hidden key. */
  private Result p8() throws Exception {
    String bucket = newBucket();
    int total = 1100;
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<Optional<String>>> puts = new ArrayList<>();
      for (int i = 0; i < total; i++) {
        String key = String.format(Locale.ROOT, "p8/k%04d", i);
        puts.add(pool.submit(() -> client.putObject(bucket, key, bytes("x"), Map.of())));
      }
      for (Future<Optional<String>> put : puts) {
        put.get();
      }
    }
    Seen marker = raw("DELETE", bucket, "p8/k0500", List.of(), Map.of(), NO_BODY);

    List<VersionEntry> all = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    int shortPages = 0;
    Optional<String> keyMarker = Optional.empty();
    Optional<String> versionMarker = Optional.empty();
    for (int page = 0; page < 20; page++) {
      VersionPage p = client.listVersions(bucket, "p8/", keyMarker, versionMarker, 1000);
      all.addAll(p.entries());
      sizes.add(p.entries().size());
      if (p.truncated() && p.entries().size() < 1000) {
        shortPages++;
      }
      if (!p.truncated()) {
        break;
      }
      keyMarker = p.nextKeyMarker();
      versionMarker = p.nextVersionIdMarker();
    }
    Set<String> keys = new HashSet<>();
    boolean sorted = true;
    String previous = "";
    for (VersionEntry e : all) {
      keys.add(e.key());
      sorted &= previous.compareTo(e.key()) <= 0;
      previous = e.key();
    }
    long hiddenMarkers =
        all.stream().filter(e -> e.key().equals("p8/k0500") && e.deleteMarker()).count();
    long hiddenData =
        all.stream().filter(e -> e.key().equals("p8/k0500") && !e.deleteMarker()).count();

    // a key marker without a version-id marker starts strictly after that key
    VersionPage after =
        client.listVersions(bucket, "p8/", Optional.of("p8/k0099"), Optional.empty(), 7);
    boolean strictlyAfter =
        !after.entries().isEmpty() && after.entries().get(0).key().equals("p8/k0100");
    // the returned markers continue without a gap or a repeat
    VersionPage first = client.listVersions(bucket, "p8/", Optional.empty(), Optional.empty(), 7);
    VersionPage second =
        client.listVersions(bucket, "p8/", first.nextKeyMarker(), first.nextVersionIdMarker(), 7);
    Set<String> seen = new HashSet<>();
    for (VersionEntry e : first.entries()) {
      seen.add(e.key() + "@" + e.versionId());
    }
    for (VersionEntry e : second.entries()) {
      seen.add(e.key() + "@" + e.versionId());
    }
    return new Result(
        200,
        null,
        "mk="
            + marker.st()
            + " sizes="
            + sizes
            + " keys="
            + keys.size()
            + " entries="
            + all.size()
            + " sorted="
            + sorted
            + " strictAfter="
            + strictlyAfter
            + " m7="
            + first.entries().size()
            + "+"
            + second.entries().size()
            + " distinct="
            + seen.size()
            + " hidden="
            + hiddenMarkers
            + "+"
            + hiddenData
            + " short="
            + shortPages);
  }

  /** P9: keys with plus, equals, comma and dollar, and a key of 512 characters. */
  private Result p9() {
    String bucket = newBucket();
    String odd = "a+b=c,d$e";
    String longKey = "p9/" + "x".repeat(509);
    Seen putOdd = rawPut(bucket, odd, bytes("odd"), Map.of());
    Seen putLong = rawPut(bucket, longKey, bytes("long"), Map.of());
    requireOk("putObject", putOdd);
    requireOk("putObject", putLong);
    Seen getOdd = raw("GET", bucket, odd, List.of(), Map.of(), NO_BODY);
    Seen getLong = raw("GET", bucket, longKey, List.of(), Map.of(), NO_BODY);
    boolean oddListed =
        client.listVersions(bucket, "a", Optional.empty(), Optional.empty(), 10).entries().stream()
            .anyMatch(e -> e.key().equals(odd));
    boolean longListed =
        client
            .listVersions(bucket, "p9/", Optional.empty(), Optional.empty(), 10)
            .entries()
            .stream()
            .anyMatch(e -> e.key().equals(longKey));
    return new Result(
        getOdd.status(),
        null,
        "put="
            + putOdd.st()
            + " getOdd="
            + getOdd.st()
            + "/"
            + new String(getOdd.body(), UTF_8).equals("odd")
            + " listedOdd="
            + oddListed
            + " get512="
            + getLong.st()
            + "/"
            + new String(getLong.body(), UTF_8).equals("long")
            + " listed512="
            + longListed);
  }

  // ------------------------------------------------------------------ P10, P11, P12

  /** P10: a wrong payload hash and a wrong Content-MD5 on a PUT. */
  private Result p10() {
    String bucket = newBucket();
    byte[] payload = bytes("payload");
    String wrongHash = sha256Hex(bytes("another payload"));
    Seen badSha = rawPut(bucket, "p10/sha", payload, Map.of("x-amz-content-sha256", wrongHash));
    Seen badMd5 =
        rawPut(
            bucket, "p10/md5", payload, Map.of("content-md5", md5Base64(bytes("another payload"))));
    int stored =
        client
            .listVersions(bucket, "p10/", Optional.empty(), Optional.empty(), 10)
            .entries()
            .size();
    return new Result(
        badSha.status(),
        badSha.ok() ? null : badSha.code(),
        "badSha=" + badSha.st() + " badMd5=" + badMd5.st() + " stored=" + stored);
  }

  /** P11: XML bodies (retention, legal hold, tagging) without Content-MD5 and without checksum. */
  private Result p11() {
    String bucket = newBucket();
    Seen put = rawPut(bucket, "p11/a", bytes("p11"), lock(GOVERNANCE, UNTIL));
    requireOk("putObject", put);
    String version = versionOf(put);
    Seen retention = retention(bucket, "p11/a", version, GOVERNANCE, UNTIL, false, false);
    Seen hold =
        xmlPut(
            bucket,
            "p11/a",
            "legal-hold",
            version,
            "<LegalHold xmlns=\"" + XMLNS + "\"><Status>ON</Status></LegalHold>",
            false);
    Seen tagging =
        xmlPut(
            bucket,
            "p11/a",
            "tagging",
            version,
            "<Tagging xmlns=\""
                + XMLNS
                + "\"><TagSet><Tag><Key>k</Key><Value>v</Value></Tag></TagSet></Tagging>",
            false);
    Seen failing = !retention.ok() ? retention : !hold.ok() ? hold : tagging;
    return new Result(
        retention.status(),
        failing.ok() ? null : failing.code(),
        "retention=" + retention.st() + " hold=" + hold.st() + " tagging=" + tagging.st());
  }

  /** P12: whole seconds, six fractional digits, year 9999, the largest year, a past date. */
  private Result p12() {
    String bucket = newBucket();
    Seen seconds = rawPut(bucket, "p12/s", bytes("s"), lock(GOVERNANCE, UNTIL));
    Seen micros =
        rawPut(bucket, "p12/f", bytes("f"), lock(GOVERNANCE, "2045-03-01T00:00:00.123456Z"));
    Seen far = rawPut(bucket, "p12/y9999", bytes("y"), lock(GOVERNANCE, "9999-12-31T23:59:59Z"));
    Seen past = rawPut(bucket, "p12/past", bytes("p"), lock(GOVERNANCE, "2001-01-01T00:00:00Z"));
    String largest = far.ok() ? "9999" : "none";
    if (!far.ok()) {
      for (String year : List.of("5000", "3000", "2262", "2100")) {
        Seen s =
            rawPut(bucket, "p12/y" + year, bytes("y"), lock(GOVERNANCE, year + "-12-31T23:59:59Z"));
        if (s.ok()) {
          largest = year;
          break;
        }
      }
    }
    String headMicros =
        micros.ok()
            ? raw("HEAD", bucket, "p12/f", List.of(), Map.of(), NO_BODY).header(UNTIL_HEADER)
            : "n/a";
    Seen failing = !past.ok() ? past : !far.ok() ? far : micros;
    return new Result(
        micros.status(),
        failing.ok() ? null : failing.code(),
        "s="
            + seconds.st()
            + " f6="
            + micros.st()
            + " y9999="
            + far.st()
            + " max="
            + largest
            + " past="
            + past.st()
            + " hf="
            + headMicros);
  }

  // ------------------------------------------------------------------ P13 to P17

  /** P13: eight concurrent PUTs of different bytes to one key. */
  private Result p13() throws Exception {
    String bucket = newBucket();
    int writers = 8;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger finished = new AtomicInteger();
    int[] doneAt = new int[writers];
    String[] versions = new String[writers];
    int[] failed = {0};
    int[] failedStatus = {200};
    try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        int writer = i;
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    versions[writer] =
                        client
                            .putObject(bucket, "p13/k", bytes("writer-" + writer), Map.of())
                            .orElse("");
                  } catch (S3Exception e) {
                    synchronized (failed) {
                      failed[0]++;
                      failedStatus[0] = e.status();
                    }
                  }
                  doneAt[writer] = finished.incrementAndGet();
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> task : tasks) {
        task.get();
      }
    }
    List<VersionEntry> listed = exactKey(bucket, "p13/k");
    Map<String, Integer> writerOf = new HashMap<>();
    Set<String> distinct = new HashSet<>();
    for (int i = 0; i < writers; i++) {
      if (versions[i] != null && !versions[i].isEmpty()) {
        writerOf.put(versions[i], i);
        distinct.add(versions[i]);
      }
    }
    Integer newest = listed.isEmpty() ? null : writerOf.get(listed.get(0).versionId());
    Integer oldest =
        listed.isEmpty() ? null : writerOf.get(listed.get(listed.size() - 1).versionId());
    boolean newestIsLast = newest != null && doneAt[newest] == writers;
    boolean oldestIsFirst = oldest != null && doneAt[oldest] == 1;
    return new Result(
        failed[0] == 0 ? 200 : failedStatus[0],
        null,
        "ok="
            + (writers - failed[0])
            + " ids="
            + distinct.size()
            + " versions="
            + listed.size()
            + " newestIsLastDone="
            + newestIsLast
            + " oldestIsFirstDone="
            + oldestIsFirst);
  }

  /** P14: the body of an error answer. */
  private Result p14() {
    String bucket = newBucket();
    Seen missing = raw("GET", bucket, "p14/none", List.of(), Map.of(), NO_BODY);
    String text = new String(missing.body(), UTF_8);
    Matcher code = ERROR_CODE.matcher(text);
    return new Result(
        missing.status(),
        missing.ok() ? null : missing.code(),
        "status="
            + missing.status()
            + " errorElement="
            + text.contains("<Error>")
            + " code="
            + (code.find() ? code.group(1) : "none")
            + " message="
            + text.contains("<Message>"));
  }

  /** P15: what the API shows about durability: location, replication, storage class. */
  private Result p15() {
    String bucket = newBucket();
    Seen put = rawPut(bucket, "p15/a", bytes("p15"), Map.of());
    requireOk("putObject", put);
    Seen location = raw("GET", bucket, null, List.of(new Param("location", "")), Map.of(), NO_BODY);
    Seen replication =
        raw("GET", bucket, null, List.of(new Param("replication", "")), Map.of(), NO_BODY);
    Seen head = raw("HEAD", bucket, "p15/a", List.of(), Map.of(), NO_BODY);
    return new Result(
        location.status(),
        null,
        "location="
            + location.st()
            + " replication="
            + replication.st()
            + " storageClass="
            + head.header("x-amz-storage-class")
            + " replicationStatus="
            + head.header("x-amz-replication-status"));
  }

  /** P17: a PUT with {@code If-None-Match: *}, information only. */
  private Result p17() {
    String bucket = newBucket();
    Map<String, String> conditional = Map.of("if-none-match", "*");
    Seen first = rawPut(bucket, "p17/a", bytes("one"), conditional);
    Seen second = rawPut(bucket, "p17/a", bytes("two"), conditional);
    int versions = exactKey(bucket, "p17/a").size();
    return new Result(
        second.status(),
        second.ok() ? null : second.code(),
        "first=" + first.st() + " second=" + second.st() + " versions=" + versions);
  }

  // ------------------------------------------------------------------ machinery

  /** Signs and sends one request; a refusal is read, not thrown. */
  private Seen raw(
      String method,
      String bucket,
      String key,
      List<Param> query,
      Map<String, String> headers,
      byte[] body) {
    recording.reset();
    try {
      S3Response answer =
          client.send(method, bucket, Optional.ofNullable(key), query, headers, body);
      return new Seen(answer.status(), "none", answer.headers(), answer.body());
    } catch (S3Exception e) {
      S3Response last = recording.last();
      return new Seen(
          e.status(),
          e.code(),
          last == null ? Map.of() : last.headers(),
          last == null ? NO_BODY : last.body());
    }
  }

  /** A PutObject with a Content-MD5 (unless the caller gives one) and the usual content type. */
  private Seen rawPut(String bucket, String key, byte[] body, Map<String, String> extra) {
    Map<String, String> headers = new TreeMap<>(extra);
    headers.putIfAbsent("content-md5", md5Base64(body));
    headers.putIfAbsent("content-type", "application/octet-stream");
    return raw("PUT", bucket, key, List.of(), headers, body);
  }

  private Seen retention(
      String bucket,
      String key,
      String version,
      String mode,
      String until,
      boolean md5,
      boolean bypass) {
    byte[] body =
        ("<Retention xmlns=\""
                + XMLNS
                + "\"><Mode>"
                + mode
                + "</Mode><RetainUntilDate>"
                + until
                + "</RetainUntilDate></Retention>")
            .getBytes(UTF_8);
    Map<String, String> headers = new TreeMap<>();
    if (md5) {
      headers.put("content-md5", md5Base64(body));
    }
    if (bypass) {
      headers.put(BYPASS_HEADER, "true");
    }
    return raw(
        "PUT",
        bucket,
        key,
        List.of(new Param("retention", ""), new Param("versionId", version)),
        headers,
        body);
  }

  private Seen xmlPut(
      String bucket, String key, String subresource, String version, String xml, boolean md5) {
    byte[] body = xml.getBytes(UTF_8);
    Map<String, String> headers = new TreeMap<>();
    if (md5) {
      headers.put("content-md5", md5Base64(body));
    }
    return raw(
        "PUT",
        bucket,
        key,
        List.of(new Param(subresource, ""), new Param("versionId", version)),
        headers,
        body);
  }

  /** The entries of exactly this key, in the order the service lists them. */
  private List<VersionEntry> exactKey(String bucket, String key) {
    List<VersionEntry> entries = new ArrayList<>();
    VersionPage page = client.listVersions(bucket, key, Optional.empty(), Optional.empty(), 100);
    for (VersionEntry e : page.entries()) {
      if (e.key().equals(key)) {
        entries.add(e);
      }
    }
    return entries;
  }

  private String newBucket() {
    String bucket = bucketName();
    client.createBucket(bucket, true);
    return bucket;
  }

  private static String bucketName() {
    return "rp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static Map<String, String> lock(String mode, String until) {
    return Map.of(MODE_HEADER, mode, UNTIL_HEADER, until);
  }

  private static void requireOk(String operation, Seen answer) {
    if (!answer.ok()) {
      throw new S3Exception(operation, answer.status(), answer.code());
    }
  }

  private static String versionOf(Seen put) {
    String version = put.headers().get(VERSION_HEADER);
    if (version == null || version.isEmpty()) {
      throw new S3Exception("putObject", put.status(), "none");
    }
    return version;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  private static String base64Url(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(text));
  }

  private static String md5Base64(byte[] body) {
    return Base64.getEncoder().encodeToString(digest("MD5", body));
  }

  private static String sha256Hex(byte[] body) {
    return HexFormat.of().formatHex(digest("SHA-256", body));
  }

  private static byte[] digest(String algorithm, byte[] body) {
    try {
      return MessageDigest.getInstance(algorithm).digest(body);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
  }
}
