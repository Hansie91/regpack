package dev.regpack.evidence.s3.fake;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.regpack.evidence.s3.client.SigV4;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.client.SigV4.Param;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * An in-JVM S3 service with object lock, for tests: a JDK {@code HttpServer} on the loopback
 * address, one per test. It recomputes the SigV4 signature of every request from the raw request
 * (with the signer of the client, whose vectors are pinned in {@code SigV4Test}) and answers 403
 * {@code SignatureDoesNotMatch} otherwise. The behaviours are those recorded in {@code
 * docs/evidence/p3b2-probe.md}, each marked {@code // probe: P<n>}; a rule that the probe did not
 * record is marked {@code // not probed} with its reason.
 *
 * <p>It is a model of what the adapter relies on and nothing more: path-style requests, buckets,
 * PutObject, GetObject, HeadObject, DeleteObject, ListObjectVersions, retention, legal hold,
 * tagging, bucket versioning, object-lock and location reads. Everything else is 501.
 * Simplifications that a test must know: every bucket keeps every version (a bucket whose
 * versioning was never switched on does not overwrite, and a DELETE without a version id always
 * adds a marker), and the clock for "a past date" and "an active lock" is the real wall clock,
 * never the clocks of the test (the signature is not checked against a clock, so a test may sign
 * with a manual one). All state is behind one lock.
 */
public final class FakeS3Server implements AutoCloseable {

  /** Switches that make the service misbehave, each on or off at any moment. */
  public enum Fault {
    /** A version listing holds at most two entries per page. */
    SHORT_VERSION_PAGES,
    /** A version listing always says it is truncated and hands back markers. */
    ENDLESS_TRUNCATION,
    /**
     * A PUT or DELETE that succeeds is stored, but its answer is never sent (the socket closes).
     */
    LOSE_RESPONSE_AFTER_STORE,
    /** Every request is answered 500 {@code InternalError} and changes nothing. */
    SERVER_ERROR,
    /** Every request is dropped without an answer and changes nothing. */
    DROP_CONNECTION,
    /** A GET returns the bytes with one bit of the first byte flipped (the stored bytes stay). */
    CORRUPT_BYTES,
    /** A PutObject is stored but its answer carries no {@code x-amz-version-id}. */
    NO_VERSION_ID,
    /** A PutObjectTagging answers 500 {@code InternalError} and changes nothing. */
    FAIL_TAGGING
  }

  private static final String XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/";
  private static final Pattern AUTHORIZATION =
      Pattern.compile(
          "AWS4-HMAC-SHA256 Credential=([^/,\\s]+)/(\\d{8})/([A-Za-z0-9._-]{1,64})/s3/aws4_request,"
              + " SignedHeaders=([a-z0-9;-]+), Signature=([0-9a-f]{64})");
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
  private static final DateTimeFormatter AMZ_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final String MODE_HEADER = "x-amz-object-lock-mode";
  private static final String UNTIL_HEADER = "x-amz-object-lock-retain-until-date";
  private static final String HOLD_HEADER = "x-amz-object-lock-legal-hold";
  private static final String META_PREFIX = "x-amz-meta-";
  private static final String BYPASS_HEADER = "x-amz-bypass-" + "governance-retention";
  private static final String MARKER_HEADER = "x-amz-delete-marker";
  private static final String VERSION_HEADER = "x-amz-version-id";
  private static final String GOVERNANCE = "GOVERNANCE";
  private static final String COMPLIANCE = "COMPLIANCE";
  private static final int MAX_KEYS = 1000;
  private static final Comparator<String> KEY_ORDER =
      (a, b) -> Arrays.compareUnsigned(a.getBytes(UTF_8), b.getBytes(UTF_8));

  private enum Hold {
    NEVER_SET,
    ON,
    OFF
  }

  /** One version of a key: data or a delete marker. Fields change only under the server lock. */
  private static final class Version {
    final String id;
    final boolean deleteMarker;
    final byte[] bytes;
    final String contentType;
    final Map<String, String> meta;
    final Instant lastModified;
    String lockMode;
    String retainUntil;
    Hold hold = Hold.NEVER_SET;
    Map<String, String> tags = new TreeMap<>();

    Version(
        String id,
        boolean deleteMarker,
        byte[] bytes,
        String contentType,
        Map<String, String> meta,
        String lockMode,
        String retainUntil) {
      this.id = id;
      this.deleteMarker = deleteMarker;
      this.bytes = bytes;
      this.contentType = contentType;
      this.meta = meta;
      this.lockMode = lockMode;
      this.retainUntil = retainUntil;
      this.lastModified = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    String etag() {
      return "\"" + HexFormat.of().formatHex(digest("MD5", bytes)) + "\"";
    }
  }

  private static final class Bucket {
    final boolean objectLock;
    String versioning;

    /** Key to versions, newest first. */
    final TreeMap<String, List<Version>> keys = new TreeMap<>(KEY_ORDER);

    Bucket(boolean objectLock) {
      this.objectLock = objectLock;
      this.versioning = objectLock ? "Enabled" : null;
    }
  }

  private record Reply(int status, Map<String, String> headers, byte[] body, boolean dropped) {}

  /** An answer that ends the handling of a request. */
  private static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient Reply reply;

    Failure(Reply reply) {
      super(null, null, false, false);
      this.reply = reply;
    }
  }

  private record Request(
      String method,
      String rawPath,
      String rawQuery,
      List<Param> query,
      Map<String, String> headers,
      byte[] body) {
    String line() {
      return method + " " + rawPath + (rawQuery == null ? "" : "?" + rawQuery);
    }

    Optional<String> param(String name) {
      for (Param p : query) {
        if (p.name().equals(name)) {
          return Optional.of(p.value());
        }
      }
      return Optional.empty();
    }

    boolean has(String name) {
      return param(name).isPresent();
    }

    Optional<String> header(String name) {
      return Optional.ofNullable(headers.get(name));
    }

    Set<String> queryNames() {
      Set<String> names = new TreeSet<>();
      for (Param p : query) {
        names.add(p.name());
      }
      return names;
    }
  }

  private final HttpServer http;
  private final ExecutorService executor;
  private final Credentials credentials;
  private final Object lock = new Object();
  private final Map<String, Bucket> buckets = new TreeMap<>();
  private final Set<Fault> faults = EnumSet.noneOf(Fault.class);
  private final List<String> lines = new ArrayList<>();
  private final Map<String, byte[]> olderOnNextPut = new HashMap<>();
  private final Set<String> rankedOldestOnNextPut = new HashSet<>();
  private long versionCounter;

  private FakeS3Server(HttpServer http, ExecutorService executor, Credentials credentials) {
    this.http = http;
    this.executor = executor;
    this.credentials = credentials;
  }

  /** Starts a service bound to the loopback address on a free port. */
  public static FakeS3Server start(String accessKeyId, String secretAccessKey) {
    Credentials credentials = new Credentials(accessKeyId, secretAccessKey);
    try {
      HttpServer http =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      FakeS3Server server = new FakeS3Server(http, executor, credentials);
      http.createContext("/", server::handle);
      http.setExecutor(executor);
      http.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("the fake S3 service cannot start", e);
    }
  }

  /** {@code http://<loopback address>:<port>}. */
  public URI endpoint() {
    String host = http.getAddress().getAddress().getHostAddress();
    String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    return URI.create("http://" + authority + ":" + http.getAddress().getPort());
  }

  /** Switches one fault on or off. */
  public void fault(Fault fault, boolean on) {
    Objects.requireNonNull(fault, "fault");
    synchronized (lock) {
      if (on) {
        faults.add(fault);
      } else {
        faults.remove(fault);
      }
    }
  }

  /** Every request received, in order, as {@code METHOD /raw/path?raw-query}. */
  public List<String> requestLines() {
    synchronized (lock) {
      return List.copyOf(lines);
    }
  }

  /**
   * Stores a new newest version of the key with these bytes, no metadata and no lock, as a writer
   * that is not the adapter would.
   */
  public void addVersionBehindTheBack(String bucket, String key, byte[] bytes) {
    synchronized (lock) {
      Bucket b = requireBucket(bucket);
      store(
          b,
          key,
          new Version(
              nextVersionId(),
              false,
              bytes.clone(),
              "application/octet-stream",
              new TreeMap<>(),
              null,
              null),
          false);
    }
  }

  /** Adds a delete marker as the newest entry of the key. */
  public void addDeleteMarker(String bucket, String key) {
    synchronized (lock) {
      Bucket b = requireBucket(bucket);
      store(
          b,
          key,
          new Version(nextVersionId(), true, new byte[0], "", new TreeMap<>(), null, null),
          false);
    }
  }

  /**
   * Sets the retain-until of the oldest data version of the key to {@code to} (whole seconds) with
   * no check at all, as a principal with the bypass permission could.
   */
  public void shortenLock(String bucket, String key, Instant to) {
    synchronized (lock) {
      Bucket b = requireBucket(bucket);
      List<Version> versions = b.keys.getOrDefault(key, List.of());
      for (int i = versions.size() - 1; i >= 0; i--) {
        Version v = versions.get(i);
        if (!v.deleteMarker) {
          if (v.lockMode == null) {
            throw new IllegalArgumentException("the oldest data version has no lock");
          }
          v.retainUntil = to.truncatedTo(ChronoUnit.SECONDS).toString();
          return;
        }
      }
      throw new IllegalArgumentException("no data version of that key");
    }
  }

  /**
   * The next PUT to that key is stored, then a foreign version of these bytes is inserted below it,
   * so that the foreign version is the oldest and the PUT is not. The foreign version is a copy of
   * that PUT's content type, user metadata and lock, with one change: a user metadata value named
   * {@code regpack-sha256} (the name of the adapter's record of the hash) is replaced by the
   * SHA-256 of the foreign bytes, so that the foreign version is a well-formed object of the
   * adapter. Used up by that PUT.
   */
  public void addOlderVersionOnNextPut(String bucket, String key, byte[] bytes) {
    synchronized (lock) {
      requireBucket(bucket);
      olderOnNextPut.put(bucket + "/" + key, bytes.clone());
    }
  }

  /**
   * The next PUT to that key is stored as the OLDEST version of the key, below the versions that
   * are already there, as when the service ranks an upload first although it finished later (the
   * rank is the server's, probe P13; the fake otherwise ranks by the order in which its lock admits
   * the PUTs, so that it never shows this). Used up by that PUT.
   */
  public void rankNextPutBelowExisting(String bucket, String key) {
    synchronized (lock) {
      requireBucket(bucket);
      rankedOldestOnNextPut.add(bucket + "/" + key);
    }
  }

  @Override
  public void close() {
    http.stop(0);
    executor.shutdownNow();
  }

  // ------------------------------------------------------------------ the request

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      byte[] body = exchange.getRequestBody().readAllBytes();
      Request request;
      try {
        request = read(exchange, body);
      } catch (IllegalArgumentException e) {
        send(exchange, error(400, "InvalidURI", "Could not parse the request."));
        return;
      }
      boolean drop;
      boolean serverError;
      synchronized (lock) {
        lines.add(request.line());
        drop = faults.contains(Fault.DROP_CONNECTION);
        serverError = faults.contains(Fault.SERVER_ERROR);
      }
      if (drop) {
        return; // no answer: closing the exchange closes the connection
      }
      Reply reply;
      if (serverError) {
        reply = error(500, "InternalError", "We encountered an internal error.");
      } else {
        try {
          reply = respond(request);
        } catch (Failure f) {
          reply = f.reply;
        } catch (RuntimeException e) {
          System.err.println("fake S3 service failure: " + e);
          reply = error(500, "InternalError", "The fake service failed.");
        }
      }
      if (reply.dropped()) {
        return;
      }
      send(exchange, reply);
    }
  }

  private static Request read(HttpExchange exchange, byte[] body) {
    URI uri = exchange.getRequestURI();
    String rawPath = uri.getRawPath();
    String rawQuery = uri.getRawQuery();
    List<Param> query = new ArrayList<>();
    if (rawQuery != null) {
      for (String part : rawQuery.split("&")) {
        if (part.isEmpty()) {
          continue;
        }
        int eq = part.indexOf('=');
        query.add(
            eq < 0
                ? new Param(decode(part), "")
                : new Param(decode(part.substring(0, eq)), decode(part.substring(eq + 1))));
      }
    }
    Map<String, String> headers = new TreeMap<>();
    exchange
        .getRequestHeaders()
        .forEach(
            (name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
    return new Request(exchange.getRequestMethod(), rawPath, rawQuery, query, headers, body);
  }

  private static void send(HttpExchange exchange, Reply reply) throws IOException {
    for (Map.Entry<String, String> h : reply.headers().entrySet()) {
      exchange.getResponseHeaders().add(h.getKey(), h.getValue());
    }
    boolean head = "HEAD".equals(exchange.getRequestMethod());
    if (reply.body().length == 0 || head) {
      exchange.sendResponseHeaders(reply.status(), -1);
    } else {
      exchange.sendResponseHeaders(reply.status(), reply.body().length);
      exchange.getResponseBody().write(reply.body());
    }
  }

  /**
   * Percent-decoding only (no plus-to-space), UTF-8. // probe: P9: keys with plus, equals, comma
   * and dollar arrive as %XX and are stored decoded.
   */
  private static String decode(String raw) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '%') {
        if (i + 2 >= raw.length()) {
          throw new IllegalArgumentException("bad percent escape");
        }
        int hi = Character.digit(raw.charAt(i + 1), 16);
        int lo = Character.digit(raw.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) {
          throw new IllegalArgumentException("bad percent escape");
        }
        out.write((hi << 4) | lo);
        i += 2;
      } else if (c < 0x80) {
        out.write(c);
      } else {
        throw new IllegalArgumentException("a raw request target is ASCII");
      }
    }
    return out.toString(UTF_8);
  }

  // ------------------------------------------------------------------ authentication

  private Reply respond(Request r) {
    authenticate(r);
    verifyBody(r);
    String rawPath = r.rawPath();
    if (!rawPath.startsWith("/")) {
      throw fail(400, "InvalidURI", "The path must start with a slash.");
    }
    String rest = rawPath.substring(1);
    int slash = rest.indexOf('/');
    String bucket;
    Optional<String> key;
    try {
      bucket = decode(slash < 0 ? rest : rest.substring(0, slash));
      String rawKey = slash < 0 ? "" : rest.substring(slash + 1);
      key = rawKey.isEmpty() ? Optional.empty() : Optional.of(decode(rawKey));
    } catch (IllegalArgumentException e) {
      throw fail(400, "InvalidURI", "Could not parse the request.");
    }
    synchronized (lock) {
      Reply reply = route(r, bucket, key);
      if (faults.contains(Fault.LOSE_RESPONSE_AFTER_STORE)
          && (r.method().equals("PUT") || r.method().equals("DELETE"))
          && reply.status() >= 200
          && reply.status() < 300) {
        return new Reply(reply.status(), Map.of(), new byte[0], true);
      }
      return reply;
    }
  }

  /** Recomputes the signature from the raw request; throws the answer when it does not match. */
  private void authenticate(Request r) {
    String authorization = r.header("authorization").orElse(null);
    if (authorization == null) {
      throw fail(403, "AccessDenied", "Access Denied.");
    }
    Matcher m = AUTHORIZATION.matcher(authorization);
    if (!m.matches()) {
      throw fail(400, "AuthorizationHeaderMalformed", "The authorization header is malformed.");
    }
    if (!m.group(1).equals(credentials.accessKeyId())) {
      throw fail(403, "InvalidAccessKeyId", "The access key id does not exist.");
    }
    String amzDate = r.header("x-amz-date").orElse(null);
    String payloadHash = r.header("x-amz-content-sha256").orElse(null);
    if (amzDate == null || payloadHash == null || !SHA256_HEX.matcher(payloadHash).matches()) {
      throw fail(400, "InvalidRequest", "Missing or unusable signed headers.");
    }
    Instant at;
    try {
      at = LocalDateTime.parse(amzDate, AMZ_DATE).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      throw fail(400, "AuthorizationHeaderMalformed", "The date is malformed.");
    }
    Set<String> names = new TreeSet<>(Arrays.asList(m.group(4).split(";")));
    if (!names.contains("host") || !names.contains("x-amz-date")) {
      throw fail(400, "AuthorizationHeaderMalformed", "host and x-amz-date must be signed.");
    }
    // not probed: AWS refuses a request with x-amz-* headers, Content-MD5 or Content-Type that the
    // signature does not cover; the fake does the same so that an unsigned header is caught
    for (String name : r.headers().keySet()) {
      boolean mustBeSigned =
          name.startsWith("x-amz-") || name.equals("content-md5") || name.equals("content-type");
      if (mustBeSigned && !names.contains(name)) {
        throw fail(403, "AccessDenied", "There were headers present that were not signed.");
      }
    }
    Map<String, String> signed = new TreeMap<>();
    for (String name : names) {
      if (name.equals("host") || name.equals("x-amz-date")) {
        continue;
      }
      String value = r.headers().get(name);
      if (value == null) {
        throw fail(403, "SignatureDoesNotMatch", "A signed header is missing.");
      }
      signed.put(name, value);
    }
    // probe: P16: the Host header is signed as received, with its port
    Map<String, String> expected;
    try {
      expected =
          SigV4.sign(
              credentials,
              m.group(3),
              "s3",
              at,
              r.method(),
              r.headers().getOrDefault("host", ""),
              r.rawPath(),
              r.query(),
              signed,
              payloadHash);
    } catch (IllegalArgumentException e) {
      throw fail(403, "SignatureDoesNotMatch", "The request could not be canonicalised.");
    }
    if (!expected.get("authorization").equals(authorization)
        || !expected.get("x-amz-date").equals(amzDate)) {
      throw fail(403, "SignatureDoesNotMatch", "The signature does not match.");
    }
  }

  /** The declared payload hash and the Content-MD5 must be those of the body. */
  private static void verifyBody(Request r) {
    String declared = r.headers().get("x-amz-content-sha256");
    // probe: P10: a wrong payload hash is refused and nothing is stored
    if (!sha256Hex(r.body()).equals(declared)) {
      throw fail(400, "XAmzContentSHA256Mismatch", "The provided x-amz-content-sha256 is wrong.");
    }
    String md5 = r.headers().get("content-md5");
    if (md5 != null) {
      byte[] given;
      try {
        given = Base64.getDecoder().decode(md5);
      } catch (IllegalArgumentException e) {
        throw fail(400, "InvalidDigest", "The Content-MD5 is not valid base64.");
      }
      // probe: P10: a wrong Content-MD5 is refused (BadDigest) and nothing is stored
      if (!Arrays.equals(given, digest("MD5", r.body()))) {
        throw fail(400, "BadDigest", "The Content-MD5 does not match.");
      }
    }
  }

  // ------------------------------------------------------------------ routing (under the lock)

  private Reply route(Request r, String bucket, Optional<String> key) {
    if (bucket.isEmpty()) {
      throw notImplemented();
    }
    if (key.isEmpty()) {
      return bucketOperation(r, bucket);
    }
    return objectOperation(r, bucketOrNoSuchBucket(bucket), key.get());
  }

  private Reply bucketOperation(Request r, String name) {
    switch (r.method()) {
      case "PUT" -> {
        if (r.query().isEmpty()) {
          return createBucket(r, name);
        }
        if (r.queryNames().equals(Set.of("versioning"))) {
          return putVersioning(r, bucketOrNoSuchBucket(name));
        }
        throw notImplemented();
      }
      case "HEAD" -> {
        bucketOrNoSuchBucket(name);
        return ok(Map.of(), new byte[0]);
      }
      case "GET" -> {
        Bucket b = bucketOrNoSuchBucket(name);
        Set<String> names = r.queryNames();
        if (names.equals(Set.of("versioning"))) {
          // probe: P1: a bucket made with object lock is versioned
          String status = b.versioning == null ? "" : "<Status>" + b.versioning + "</Status>";
          return xml(200, "VersioningConfiguration", status);
        }
        if (names.equals(Set.of("object-lock"))) {
          if (!b.objectLock) {
            throw fail(
                404,
                "ObjectLockConfigurationNotFoundError",
                "Object Lock configuration does not exist for this bucket.");
          }
          // probe: P1: the lock configuration of a lock bucket says Enabled
          return xml(
              200, "ObjectLockConfiguration", "<ObjectLockEnabled>Enabled</ObjectLockEnabled>");
        }
        if (names.equals(Set.of("location"))) {
          // probe: P15: the API shows a location and nothing about replication
          return xml(200, "LocationConstraint", "");
        }
        if (names.equals(Set.of("replication"))) {
          // probe: P15
          throw fail(
              404,
              "ReplicationConfigurationNotFoundError",
              "The replication configuration was not found.");
        }
        if (names.contains("versions")
            && Set.of("versions", "prefix", "key-marker", "version-id-marker", "max-keys")
                .containsAll(names)) {
          return listVersions(r, name, b);
        }
        throw notImplemented();
      }
      default -> throw notImplemented();
    }
  }

  private Reply createBucket(Request r, String name) {
    if (!BUCKET_NAME.matcher(name).matches()) {
      // not probed: the S3 naming rule, the same one the configuration of the adapter applies
      throw fail(400, "InvalidBucketName", "The specified bucket is not valid.");
    }
    if (buckets.containsKey(name)) {
      throw fail(409, "BucketAlreadyOwnedByYou", "The bucket already exists.");
    }
    // probe: P1: a bucket created with the object-lock header is versioned and locked
    boolean lock = "true".equalsIgnoreCase(r.headers().get("x-amz-bucket-object-lock-enabled"));
    buckets.put(name, new Bucket(lock));
    return ok(Map.of("location", "/" + name), new byte[0]);
  }

  private Reply putVersioning(Request r, Bucket b) {
    Element root = parse(r.body(), "VersioningConfiguration");
    String status = text(root, "Status").orElse("");
    if (!status.equals("Enabled") && !status.equals("Suspended")) {
      throw fail(400, "MalformedXML", "The Status must be Enabled or Suspended.");
    }
    if (b.objectLock && status.equals("Suspended")) {
      // not probed: S3 refuses to suspend versioning on a bucket with object lock
      throw fail(409, "InvalidBucketState", "Versioning cannot be suspended with object lock.");
    }
    b.versioning = status;
    return ok(Map.of(), new byte[0]);
  }

  private Reply objectOperation(Request r, Bucket b, String key) {
    Set<String> names = r.queryNames();
    switch (r.method()) {
      case "PUT" -> {
        if (r.query().isEmpty()) {
          return putObject(r, b, key);
        }
        if (names.equals(Set.of("retention", "versionId")) || names.equals(Set.of("retention"))) {
          return putRetention(r, b, key);
        }
        if (names.equals(Set.of("legal-hold", "versionId")) || names.equals(Set.of("legal-hold"))) {
          return putLegalHold(r, b, key);
        }
        if (names.equals(Set.of("tagging", "versionId")) || names.equals(Set.of("tagging"))) {
          return putTagging(r, b, key);
        }
        throw notImplemented();
      }
      case "GET", "HEAD" -> {
        if (r.method().equals("GET")
            && (names.equals(Set.of("tagging", "versionId")) || names.equals(Set.of("tagging")))) {
          return getTagging(r, b, key);
        }
        if (names.isEmpty() || names.equals(Set.of("versionId"))) {
          return getObject(r, b, key);
        }
        throw notImplemented();
      }
      case "DELETE" -> {
        if (names.isEmpty() || names.equals(Set.of("versionId"))) {
          return deleteObject(r, b, key);
        }
        throw notImplemented();
      }
      default -> throw notImplemented();
    }
  }

  // ------------------------------------------------------------------ objects

  private Reply putObject(Request r, Bucket b, String key) {
    String mode = r.headers().get(MODE_HEADER);
    String until = r.headers().get(UNTIL_HEADER);
    if ((mode == null) != (until == null)) {
      throw fail(400, "InvalidArgument", "The lock mode and the retain-until date go together.");
    }
    if (mode != null) {
      requireLockBucket(b);
      requireMode(mode);
      // probe: P12: a retain-until date in the past is refused with InvalidArgument
      requireFuture(parseInstant(until));
    }
    // probe: P17: an If-None-Match header is ignored; a second PUT to the key makes a second
    // version
    // probe: P11: nothing here asks for a Content-MD5 (XML bodies are accepted without one too)
    Version v =
        new Version(
            nextVersionId(),
            false,
            r.body().clone(),
            r.headers().getOrDefault("content-type", "binary/octet-stream"),
            metaOf(r),
            mode,
            until);
    store(b, key, v, rankedOldestOnNextPut.remove(bucketNameOf(b) + "/" + key));
    Version foreign = null;
    byte[] foreignBytes = olderOnNextPut.remove(bucketNameOf(b) + "/" + key);
    if (foreignBytes != null) {
      foreign = foreignOf(v, foreignBytes);
      store(b, key, foreign, true);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("etag", v.etag());
    // probe: P2: the answer to a PUT carries the version id
    if (!faults.contains(Fault.NO_VERSION_ID)) {
      headers.put(VERSION_HEADER, v.id);
    }
    return ok(headers, new byte[0]);
  }

  /** A copy of the PUT's metadata and lock around other bytes (see addOlderVersionOnNextPut). */
  private Version foreignOf(Version own, byte[] bytes) {
    Map<String, String> meta = new TreeMap<>(own.meta);
    if (meta.containsKey(META_PREFIX + "regpack-sha256")) {
      meta.put(META_PREFIX + "regpack-sha256", sha256Hex(bytes));
    }
    return new Version(
        nextVersionId(), false, bytes, own.contentType, meta, own.lockMode, own.retainUntil);
  }

  private static Map<String, String> metaOf(Request r) {
    Map<String, String> meta = new TreeMap<>();
    for (Map.Entry<String, String> h : r.headers().entrySet()) {
      if (h.getKey().startsWith(META_PREFIX)) {
        // probe: P3: user metadata comes back as sent, under lower-case names
        meta.put(h.getKey(), h.getValue());
      }
    }
    return meta;
  }

  private Reply getObject(Request r, Bucket b, String key) {
    Version v = version(b, key, r.param("versionId"));
    Map<String, String> headers = objectHeaders(v);
    byte[] body = v.bytes;
    if (r.method().equals("GET") && faults.contains(Fault.CORRUPT_BYTES)) {
      body = body.length == 0 ? new byte[] {0} : body.clone();
      if (v.bytes.length > 0) {
        body[0] ^= 0x01;
      }
    }
    return ok(headers, body);
  }

  private Map<String, String> objectHeaders(Version v) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("content-type", v.contentType);
    h.put("etag", v.etag());
    h.put(
        "last-modified",
        DateTimeFormatter.RFC_1123_DATE_TIME.format(v.lastModified.atOffset(ZoneOffset.UTC)));
    h.put(VERSION_HEADER, v.id);
    h.putAll(v.meta);
    if (v.lockMode != null) {
      // probe: P2: HEAD and GET return the mode and the date text that was sent
      h.put(MODE_HEADER, v.lockMode);
      h.put(UNTIL_HEADER, v.retainUntil);
    }
    // probe: P6: the legal-hold header is absent before any hold was set
    if (v.hold != Hold.NEVER_SET) {
      h.put(HOLD_HEADER, v.hold == Hold.ON ? "ON" : "OFF");
    }
    if (!v.tags.isEmpty()) {
      // probe: P4: GET carries the number of tags
      h.put("x-amz-tagging-count", Integer.toString(v.tags.size()));
    }
    return h;
  }

  /**
   * The version a request names: the one with the given id (a delete marker cannot be read by id),
   * or the newest entry of the key, which a delete marker turns into a 404 that says so.
   */
  private Version version(Bucket b, String key, Optional<String> versionId) {
    List<Version> versions = b.keys.get(key);
    if (versionId.isPresent()) {
      Version found = null;
      if (versions != null) {
        for (Version v : versions) {
          if (v.id.equals(versionId.get())) {
            found = v;
          }
        }
      }
      if (found == null) {
        throw fail(404, "NoSuchVersion", "The specified version does not exist.");
      }
      if (found.deleteMarker) {
        throw failWith(
            405, "MethodNotAllowed", "The method is not allowed against a delete marker.", found);
      }
      return found;
    }
    if (versions == null || versions.isEmpty()) {
      throw fail(404, "NoSuchKey", "The specified key does not exist.");
    }
    Version newest = versions.get(0);
    if (newest.deleteMarker) {
      // probe: P7: GET and HEAD after a marker answer 404 with the delete-marker header
      throw failWith(404, "NoSuchKey", "The specified key does not exist.", newest);
    }
    return newest;
  }

  private Reply deleteObject(Request r, Bucket b, String key) {
    Optional<String> versionId = r.param("versionId");
    List<Version> versions = b.keys.get(key);
    if (versionId.isPresent()) {
      Version found = null;
      if (versions != null) {
        for (Version v : versions) {
          if (v.id.equals(versionId.get())) {
            found = v;
          }
        }
      }
      if (found == null) {
        return new Reply(204, Map.of(), new byte[0], false);
      }
      boolean bypass = "true".equalsIgnoreCase(r.headers().get(BYPASS_HEADER));
      if (lockedAgainstDelete(found, bypass)) {
        // probe: P7: the DELETE of a locked version by id is refused
        throw fail(403, "AccessDenied", "Access Denied because object protected by object lock.");
      }
      versions.remove(found);
      if (versions.isEmpty()) {
        b.keys.remove(key);
      }
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put(VERSION_HEADER, found.id);
      if (found.deleteMarker) {
        headers.put(MARKER_HEADER, "true");
      }
      return new Reply(204, headers, new byte[0], false);
    }
    // probe: P7: a DELETE without a version id adds a marker (204), even under lock
    Version marker =
        new Version(nextVersionId(), true, new byte[0], "", new TreeMap<>(), null, null);
    store(b, key, marker, false);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(MARKER_HEADER, "true");
    headers.put(VERSION_HEADER, marker.id);
    return new Reply(204, headers, new byte[0], false);
  }

  private static boolean lockedAgainstDelete(Version v, boolean bypass) {
    if (v.hold == Hold.ON) {
      return true;
    }
    if (v.lockMode == null || !parseInstant(v.retainUntil).isAfter(Instant.now())) {
      return false;
    }
    return v.lockMode.equals(COMPLIANCE) || !bypass;
  }

  // ------------------------------------------------------------------ retention, hold, tags

  private Reply putRetention(Request r, Bucket b, String key) {
    requireLockBucket(b);
    Version v = version(b, key, r.param("versionId"));
    Element root = parse(r.body(), "Retention");
    String mode = text(root, "Mode").orElse("");
    String until = text(root, "RetainUntilDate").orElse("");
    requireMode(mode);
    Instant newUntil = parseInstant(until);
    requireFuture(newUntil);
    if (v.lockMode != null) {
      Instant oldUntil = parseInstant(v.retainUntil);
      if (oldUntil.isAfter(Instant.now())) {
        boolean shorter =
            newUntil.isBefore(oldUntil)
                || (v.lockMode.equals(COMPLIANCE) && !mode.equals(COMPLIANCE));
        boolean bypass = "true".equalsIgnoreCase(r.headers().get(BYPASS_HEADER));
        if (shorter) {
          // probe: P5: a compliance lock cannot be shortened, even with the bypass header
          if (v.lockMode.equals(COMPLIANCE)) {
            throw fail(
                403, "AccessDenied", "Access Denied because object protected by object lock.");
          }
          // probe: P5: a governance lock is shortened only with the bypass header
          if (!bypass) {
            throw fail(
                403, "AccessDenied", "Access Denied because object protected by object lock.");
          }
        }
      }
    }
    // probe: P5: extending works; P12: the date text is kept as sent
    v.lockMode = mode;
    v.retainUntil = until;
    return ok(Map.of(VERSION_HEADER, v.id), new byte[0]);
  }

  private Reply putLegalHold(Request r, Bucket b, String key) {
    requireLockBucket(b);
    Version v = version(b, key, r.param("versionId"));
    Element root = parse(r.body(), "LegalHold");
    String status = text(root, "Status").orElse("");
    if (!status.equals("ON") && !status.equals("OFF")) {
      throw fail(400, "MalformedXML", "The Status must be ON or OFF.");
    }
    // probe: P6: a legal hold can be set and cleared
    v.hold = status.equals("ON") ? Hold.ON : Hold.OFF;
    return ok(Map.of(VERSION_HEADER, v.id), new byte[0]);
  }

  private Reply putTagging(Request r, Bucket b, String key) {
    if (faults.contains(Fault.FAIL_TAGGING)) {
      throw fail(500, "InternalError", "We encountered an internal error.");
    }
    Version v = version(b, key, r.param("versionId"));
    Element root = parse(r.body(), "Tagging");
    Map<String, String> tags = new TreeMap<>();
    for (Element set : elements(root)) {
      if (!"TagSet".equals(set.getLocalName())) {
        continue;
      }
      for (Element tag : elements(set)) {
        if ("Tag".equals(tag.getLocalName())) {
          String name = text(tag, "Key").orElse("");
          if (name.isEmpty() || tags.put(name, text(tag, "Value").orElse("")) != null) {
            throw fail(400, "InvalidTag", "The tag set is not valid.");
          }
        }
      }
    }
    // probe: P4: tags can be written on a locked version and are kept verbatim
    v.tags = tags;
    return ok(Map.of(VERSION_HEADER, v.id), new byte[0]);
  }

  private Reply getTagging(Request r, Bucket b, String key) {
    Version v = version(b, key, r.param("versionId"));
    StringBuilder tags = new StringBuilder("<TagSet>");
    for (Map.Entry<String, String> t : v.tags.entrySet()) {
      tags.append("<Tag><Key>")
          .append(escape(t.getKey()))
          .append("</Key><Value>")
          .append(escape(t.getValue()))
          .append("</Value></Tag>");
    }
    tags.append("</TagSet>");
    return xml(200, "Tagging", tags.toString());
  }

  // ------------------------------------------------------------------ listing

  private Reply listVersions(Request r, String bucketName, Bucket b) {
    String prefix = r.param("prefix").orElse("");
    Optional<String> keyMarker = r.param("key-marker").filter(s -> !s.isEmpty());
    Optional<String> versionMarker = r.param("version-id-marker").filter(s -> !s.isEmpty());
    int maxKeys = MAX_KEYS;
    if (r.has("max-keys")) {
      try {
        maxKeys = Integer.parseInt(r.param("max-keys").orElse(""));
      } catch (NumberFormatException e) {
        throw fail(400, "InvalidArgument", "max-keys must be a number.");
      }
      if (maxKeys < 0) {
        throw fail(400, "InvalidArgument", "max-keys must not be negative.");
      }
    }
    if (versionMarker.isPresent() && keyMarker.isEmpty()) {
      throw fail(400, "InvalidArgument", "A version-id marker needs a key marker.");
    }
    List<Map.Entry<String, Version>> all = new ArrayList<>();
    for (Map.Entry<String, List<Version>> e : b.keys.entrySet()) {
      if (e.getKey().startsWith(prefix)) {
        for (Version v : e.getValue()) {
          all.add(Map.entry(e.getKey(), v));
        }
      }
    }
    int start = 0;
    if (keyMarker.isPresent()) {
      if (versionMarker.isEmpty()) {
        // probe: P8: a key marker without a version-id marker starts strictly after that key
        while (start < all.size()
            && KEY_ORDER.compare(all.get(start).getKey(), keyMarker.get()) <= 0) {
          start++;
        }
      } else {
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
          if (all.get(i).getKey().equals(keyMarker.get())
              && all.get(i).getValue().id.equals(versionMarker.get())) {
            at = i;
          }
        }
        if (at < 0) {
          throw fail(400, "InvalidArgument", "The version-id marker is not valid.");
        }
        start = at + 1;
      }
    }
    int limit = Math.min(maxKeys, MAX_KEYS);
    if (faults.contains(Fault.SHORT_VERSION_PAGES)) {
      limit = Math.min(limit, 2);
    }
    int end = Math.min(all.size(), start + limit);
    List<Map.Entry<String, Version>> page = all.subList(start, end);
    boolean truncated = end < all.size() && limit > 0;
    Optional<String> nextKey = Optional.empty();
    Optional<String> nextVersion = Optional.empty();
    if (!page.isEmpty()) {
      nextKey = Optional.of(page.get(page.size() - 1).getKey());
      nextVersion = Optional.of(page.get(page.size() - 1).getValue().id);
    }
    if (faults.contains(Fault.ENDLESS_TRUNCATION)) {
      truncated = true;
      if (page.isEmpty()) {
        nextKey = Optional.of(keyMarker.orElse("endless"));
        nextVersion = Optional.of(versionMarker.orElse("endless"));
      }
    }
    StringBuilder x = new StringBuilder();
    x.append("<Name>").append(escape(bucketName)).append("</Name>");
    x.append("<Prefix>").append(escape(prefix)).append("</Prefix>");
    x.append("<KeyMarker>").append(escape(keyMarker.orElse(""))).append("</KeyMarker>");
    x.append("<VersionIdMarker>")
        .append(escape(versionMarker.orElse("")))
        .append("</VersionIdMarker>");
    if (truncated) {
      x.append("<NextKeyMarker>").append(escape(nextKey.orElse(""))).append("</NextKeyMarker>");
      x.append("<NextVersionIdMarker>")
          .append(escape(nextVersion.orElse("")))
          .append("</NextVersionIdMarker>");
    }
    x.append("<MaxKeys>").append(maxKeys).append("</MaxKeys>");
    x.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
    for (int i = 0; i < page.size(); i++) {
      String key = page.get(i).getKey();
      Version v = page.get(i).getValue();
      boolean latest = b.keys.get(key).get(0) == v;
      String element = v.deleteMarker ? "DeleteMarker" : "Version";
      x.append('<').append(element).append("><Key>").append(escape(key)).append("</Key>");
      x.append("<VersionId>").append(v.id).append("</VersionId>");
      x.append("<IsLatest>").append(latest).append("</IsLatest>");
      x.append("<LastModified>").append(v.lastModified).append("</LastModified>");
      if (!v.deleteMarker) {
        x.append("<ETag>").append(escape(v.etag())).append("</ETag>");
        x.append("<Size>").append(v.bytes.length).append("</Size>");
        x.append("<StorageClass>STANDARD</StorageClass>");
      }
      x.append("</").append(element).append('>');
    }
    return xml(200, "ListVersionsResult", x.toString());
  }

  // ------------------------------------------------------------------ state helpers

  private Bucket requireBucket(String name) {
    Bucket b = buckets.get(name);
    if (b == null) {
      throw new IllegalArgumentException("no such bucket: " + name);
    }
    return b;
  }

  /** The bucket, or the 404 that names it. */
  private Bucket bucketOrNoSuchBucket(String name) {
    Bucket b = buckets.get(name);
    if (b == null) {
      throw fail(404, "NoSuchBucket", "The specified bucket does not exist.");
    }
    return b;
  }

  private String bucketNameOf(Bucket b) {
    for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
      if (e.getValue() == b) {
        return e.getKey();
      }
    }
    throw new IllegalStateException("bucket without a name");
  }

  /**
   * Inserts a version as the newest of its key, or as the oldest. // probe: P13: concurrent PUTs to
   * one key are all accepted, each gets its own version id, and the order of the versions is the
   * order in which this lock admitted them (the rank is the server's).
   */
  private static void store(Bucket b, String key, Version v, boolean asOldest) {
    List<Version> versions = b.keys.computeIfAbsent(key, k -> new ArrayList<>());
    if (asOldest) {
      versions.add(v);
    } else {
      versions.add(0, v);
    }
  }

  private String nextVersionId() {
    versionCounter++;
    // opaque on purpose: ids that sorted like the versions would hide an adapter that sorts by id
    return HexFormat.of()
        .formatHex(digest("SHA-256", ("fake-version-" + versionCounter).getBytes(UTF_8)), 0, 16);
  }

  private static void requireLockBucket(Bucket b) {
    if (!b.objectLock) {
      throw fail(400, "InvalidRequest", "Bucket is missing Object Lock Configuration.");
    }
  }

  private static void requireMode(String mode) {
    if (!mode.equals(GOVERNANCE) && !mode.equals(COMPLIANCE)) {
      throw fail(400, "MalformedXML", "The mode must be GOVERNANCE or COMPLIANCE.");
    }
  }

  private static void requireFuture(Instant until) {
    if (!until.isAfter(Instant.now())) {
      throw fail(400, "InvalidArgument", "The retain until date must be in the future.");
    }
  }

  private static Instant parseInstant(String text) {
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      throw fail(400, "InvalidArgument", "The date is not valid.");
    }
  }

  // ------------------------------------------------------------------ answers

  private static Reply ok(Map<String, String> headers, byte[] body) {
    return new Reply(200, headers, body, false);
  }

  private static Failure fail(int status, String code, String message) {
    return new Failure(error(status, code, message));
  }

  /** A failure that also carries the delete-marker headers of a marker. */
  private static Failure failWith(int status, String code, String message, Version marker) {
    Reply base = error(status, code, message);
    Map<String, String> headers = new LinkedHashMap<>(base.headers());
    headers.put(MARKER_HEADER, "true");
    headers.put(VERSION_HEADER, marker.id);
    return new Failure(new Reply(status, headers, base.body(), false));
  }

  private static Failure notImplemented() {
    return fail(501, "NotImplemented", "The fake service does not implement this operation.");
  }

  private static Reply error(int status, String code, String message) {
    // probe: P14: an error body is <Error><Code>...</Code><Message>...
    String body =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>"
            + code
            + "</Code><Message>"
            + escape(message)
            + "</Message><RequestId>fake</RequestId></Error>";
    return new Reply(
        status, Map.of("content-type", "application/xml"), body.getBytes(UTF_8), false);
  }

  private static Reply xml(int status, String root, String inner) {
    String body =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><"
            + root
            + " xmlns=\""
            + XMLNS
            + "\">"
            + inner
            + "</"
            + root
            + ">";
    return new Reply(
        status, Map.of("content-type", "application/xml"), body.getBytes(UTF_8), false);
  }

  private static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&apos;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  // ------------------------------------------------------------------ XML in

  private static Element parse(byte[] body, String expectedRoot) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      Document document = builder.parse(new ByteArrayInputStream(body));
      Element root = document.getDocumentElement();
      if (!expectedRoot.equals(root.getLocalName())) {
        throw fail(400, "MalformedXML", "The XML is not what the operation expects.");
      }
      return root;
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw fail(400, "MalformedXML", "The XML could not be parsed.");
    }
  }

  private static Optional<String> text(Element parent, String localName) {
    for (Element child : elements(parent)) {
      if (localName.equals(child.getLocalName())) {
        return Optional.of(child.getTextContent());
      }
    }
    return Optional.empty();
  }

  private static List<Element> elements(Element parent) {
    NodeList nodes = parent.getChildNodes();
    List<Element> out = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node instanceof Element element) {
        out.add(element);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ digests

  private static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
  }

  private static String sha256Hex(byte[] bytes) {
    return HexFormat.of().formatHex(digest("SHA-256", bytes));
  }
}
