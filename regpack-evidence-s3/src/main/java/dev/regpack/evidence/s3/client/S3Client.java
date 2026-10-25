package dev.regpack.evidence.s3.client;

import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.client.SigV4.Param;
import dev.regpack.ports.Clock;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A small S3 client: path-style requests signed with {@link SigV4}, sent through an {@link
 * S3Transport}. It knows the calls the evidence store needs and nothing else; it issues no retry of
 * its own (the JDK HttpClient under {@link JdkTransport} may retry an idempotent request once when
 * the connection is closed before any byte of the answer: recollection of the JDK behaviour, not
 * pinned by a test), decides nothing about durability or retention, and never sends the
 * governance-bypass header or a delete request (the raw {@link #send} takes whatever method and
 * headers its caller names, which is how the tests and the probe reach those).
 *
 * <p>Every call ends in its documented success or in an {@link S3Exception}: an answer with any
 * other status (every 3xx, 4xx and 5xx, and a 2xx the call does not expect), an answer that cannot
 * be read, or an {@link IOException} of the transport (a status outside 100 to 599 is one, from
 * {@link JdkTransport}: status 0, code {@code ProtocolException}). The one absence is a 404 for an
 * object or a version in {@link #get} and {@link #head}. Two things are not an {@link S3Exception}:
 * an argument the client refuses is an {@link IllegalArgumentException} (or a {@link
 * NullPointerException}), and a {@link RuntimeException} thrown by a custom transport is passed on,
 * not wrapped; a caller that must turn every failure into its own exception catches both. Operation
 * names, which appear in the messages, are the S3 API names: {@code createBucket}, {@code
 * getBucketVersioning}, {@code getObjectLockConfiguration}, {@code putObject}, {@code headObject},
 * {@code getObject}, {@code listObjectVersions}, {@code putObjectRetention}, {@code
 * putObjectLegalHold}, {@code getObjectTagging}, {@code putObjectTagging}; the raw {@link #send}
 * names itself by its method.
 *
 * <p>Each request carries {@code x-amz-content-sha256} (the SHA-256 of the body, or the value the
 * caller of the raw {@link #send} gives), and every header of the request is signed. The calls that
 * take a body of their own, {@link #putObject} and the three that send XML, add a {@code
 * Content-MD5}: a transport checksum, not a security control. AWS S3 asks for it on these XML
 * bodies (recollection of the AWS documentation, not checked here); Scality CloudServer 9.5.0 did
 * not require it (probe results P5, a retention extension accepted without it, and P11 of
 * docs/evidence/p3b2-probe.md). Immutable and thread-safe.
 */
public final class S3Client {
  private static final String SERVICE = "s3";
  private static final byte[] NO_BODY = new byte[0];
  private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
  private static final Pattern REGION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final String CONTENT_MD5 = "content-md5";
  private static final String CONTENT_SHA256 = "x-amz-content-sha256";
  private static final int MAX_KEYS = 1000;

  /** Headers the client or the HTTP stack sets itself. */
  private static final Set<String> RESERVED =
      Set.of(
          "host",
          "x-amz-date",
          "authorization",
          "content-length",
          "connection",
          "expect",
          "upgrade");

  /** One entry of a version listing: a data version or a delete marker. */
  public record VersionEntry(String key, String versionId, boolean latest, boolean deleteMarker) {
    public VersionEntry {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(versionId, "versionId");
    }
  }

  /**
   * One page of a version listing, entries in the order the server sent them. A truncated page
   * carries the markers for the next request.
   */
  public record VersionPage(
      List<VersionEntry> entries,
      boolean truncated,
      Optional<String> nextKeyMarker,
      Optional<String> nextVersionIdMarker) {
    public VersionPage {
      Objects.requireNonNull(entries, "entries");
      Objects.requireNonNull(nextKeyMarker, "nextKeyMarker");
      Objects.requireNonNull(nextVersionIdMarker, "nextVersionIdMarker");
      entries = List.copyOf(entries);
    }
  }

  private final String origin;
  private final String hostHeader;
  private final String region;
  private final Credentials credentials;
  private final Clock signingClock;
  private final S3Transport transport;

  /**
   * @param endpoint {@code scheme://host[:port]} only: no user info, path, query or fragment
   * @param signingClock the time of {@code X-Amz-Date}; it must be real time against a real server
   */
  public S3Client(
      URI endpoint,
      String region,
      Credentials credentials,
      Clock signingClock,
      S3Transport transport) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(region, "region");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.signingClock = Objects.requireNonNull(signingClock, "signingClock");
    this.transport = Objects.requireNonNull(transport, "transport");
    if (!REGION.matcher(region).matches()) {
      throw new IllegalArgumentException("region is not a valid credential-scope part");
    }
    this.region = region;
    String scheme =
        endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
    String rawPath = endpoint.getRawPath();
    if (!(scheme.equals("http") || scheme.equals("https"))
        || endpoint.getHost() == null
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || !(rawPath == null || rawPath.isEmpty())) {
      throw new IllegalArgumentException(
          "endpoint must be scheme://host[:port] with http or https");
    }
    int port = endpoint.getPort();
    if (port != -1 && (port < 1 || port > 65535)) {
      throw new IllegalArgumentException("the endpoint port must be 1 to 65535");
    }
    boolean defaultPort = port == -1 || (scheme.equals("https") ? port == 443 : port == 80);
    this.hostHeader = defaultPort ? endpoint.getHost() : endpoint.getHost() + ":" + port;
    this.origin = scheme + "://" + endpoint.getHost() + (port == -1 ? "" : ":" + port);
  }

  // ---- the raw call

  /**
   * Signs and sends one request. A 2xx answer is returned, anything else is an {@link S3Exception}.
   * The headers are the caller's (lower-case names, printable ASCII values) and none is added but
   * {@code x-amz-content-sha256}, which the caller may give itself; a probe uses that to send a
   * wrong hash, a missing {@code content-md5} or a bypass header.
   */
  public S3Response send(
      String method,
      String bucket,
      Optional<String> key,
      List<Param> query,
      Map<String, String> headers,
      byte[] body) {
    Objects.requireNonNull(method, "method");
    if (!SigV4.METHOD.matcher(method).matches()) {
      throw new IllegalArgumentException("method must be upper-case letters");
    }
    S3Response answer = exchange(method, method, bucket, key, query, headers, body);
    if (answer.status() >= 200 && answer.status() < 300) {
      return answer;
    }
    throw error(method, answer);
  }

  // ---- buckets

  /** Creates a bucket; with {@code objectLock} the bucket is created with object lock on. */
  public void createBucket(String bucket, boolean objectLock) {
    String op = "createBucket";
    Map<String, String> headers =
        objectLock ? Map.of("x-amz-bucket-object-lock-enabled", "true") : Map.of();
    expect(op, exchange(op, "PUT", bucket, Optional.empty(), List.of(), headers, NO_BODY), 200);
  }

  /** True when the bucket's versioning status is {@code Enabled}. */
  public boolean versioningEnabled(String bucket) {
    String op = "getBucketVersioning";
    S3Response answer =
        expect(
            op,
            exchange(
                op,
                "GET",
                bucket,
                Optional.empty(),
                List.of(new Param("versioning", "")),
                Map.of(),
                NO_BODY),
            200);
    return S3Xml.versioningEnabled(op, answer.status(), answer.body());
  }

  /**
   * True when the bucket has object lock enabled. A bucket without an object-lock configuration
   * answers 404 {@code ObjectLockConfigurationNotFoundError} on S3 [R]: that is false. Every other
   * failure, a missing bucket included, is an {@link S3Exception}.
   */
  public boolean objectLockEnabled(String bucket) {
    String op = "getObjectLockConfiguration";
    S3Response answer =
        exchange(
            op,
            "GET",
            bucket,
            Optional.empty(),
            List.of(new Param("object-lock", "")),
            Map.of(),
            NO_BODY);
    if (answer.status() == 404
        && S3Xml.errorCode(answer.body()).equals("ObjectLockConfigurationNotFoundError")) {
      return false;
    }
    expect(op, answer, 200);
    return S3Xml.objectLockEnabled(op, answer.status(), answer.body());
  }

  // ---- objects

  /**
   * Puts an object with a {@code Content-MD5} and returns the version id the server assigned, or
   * nothing when the answer carries none. The caller's headers carry the content type, the lock
   * headers and the user metadata; they must not name {@code content-md5} or {@code
   * x-amz-content-sha256}.
   */
  public Optional<String> putObject(
      String bucket, String key, byte[] body, Map<String, String> headers) {
    String op = "putObject";
    Objects.requireNonNull(body, "body");
    S3Response answer =
        expect(
            op,
            exchange(op, "PUT", bucket, Optional.of(key), List.of(), withMd5(headers, body), body),
            200);
    return Optional.ofNullable(answer.headers().get("x-amz-version-id")).filter(v -> !v.isEmpty());
  }

  /** HEAD of one version: its answer, or nothing for a 404 (which carries no body to look at). */
  public Optional<S3Response> head(String bucket, String key, String versionId) {
    String op = "headObject";
    S3Response answer =
        exchange(op, "HEAD", bucket, Optional.of(key), versionQuery(versionId), Map.of(), NO_BODY);
    if (answer.status() == 404) {
      return Optional.empty();
    }
    return Optional.of(expect(op, answer, 200));
  }

  /**
   * GET of one version: its answer, or nothing when the object or version is not there (404 with
   * the code {@code NoSuchKey} or {@code NoSuchVersion}, or without a body). A 404 for a bucket, or
   * one that cannot be read, is an {@link S3Exception}: a missing bucket is never an absent object.
   */
  public Optional<S3Response> get(String bucket, String key, String versionId) {
    String op = "getObject";
    S3Response answer =
        exchange(op, "GET", bucket, Optional.of(key), versionQuery(versionId), Map.of(), NO_BODY);
    if (answer.status() == 404) {
      // an empty body is the one 404 that names nothing; an error document without a code is not
      // that, it is a 404 that cannot be read
      if (answer.body().length == 0) {
        return Optional.empty();
      }
      String code = S3Xml.errorCode(answer.body());
      if (code.equals("NoSuchKey") || code.equals("NoSuchVersion")) {
        return Optional.empty();
      }
    }
    return Optional.of(expect(op, answer, 200));
  }

  // ---- listing

  /**
   * One page of ListObjectVersions: keys with {@code prefix} (none when empty), after the markers,
   * at most {@code maxKeys} (1 to 1,000; the server counts versions and delete markers).
   */
  public VersionPage listVersions(
      String bucket,
      String prefix,
      Optional<String> keyMarker,
      Optional<String> versionIdMarker,
      int maxKeys) {
    String op = "listObjectVersions";
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(keyMarker, "keyMarker");
    Objects.requireNonNull(versionIdMarker, "versionIdMarker");
    if (maxKeys < 1 || maxKeys > MAX_KEYS) {
      throw new IllegalArgumentException("maxKeys must be 1 to " + MAX_KEYS);
    }
    if (keyMarker.isPresent() && keyMarker.get().isEmpty()) {
      throw new IllegalArgumentException("a key marker must not be empty");
    }
    if (versionIdMarker.isPresent() && (keyMarker.isEmpty() || versionIdMarker.get().isEmpty())) {
      throw new IllegalArgumentException("a version-id marker needs a key marker and a value");
    }
    List<Param> query = new ArrayList<>();
    query.add(new Param("versions", ""));
    query.add(new Param("max-keys", Integer.toString(maxKeys)));
    if (!prefix.isEmpty()) {
      query.add(new Param("prefix", prefix));
    }
    keyMarker.ifPresent(k -> query.add(new Param("key-marker", k)));
    versionIdMarker.ifPresent(v -> query.add(new Param("version-id-marker", v)));
    S3Response answer =
        expect(op, exchange(op, "GET", bucket, Optional.empty(), query, Map.of(), NO_BODY), 200);
    return S3Xml.versionPage(op, answer.status(), answer.body());
  }

  // ---- retention, legal hold, tags

  /** PutObjectRetention on one version; {@code mode} is GOVERNANCE or COMPLIANCE. */
  public void putRetention(
      String bucket, String key, String versionId, String mode, String isoDate) {
    String op = "putObjectRetention";
    byte[] body = S3Xml.retentionBody(mode, isoDate);
    putXml(op, bucket, key, "retention", versionId, body);
  }

  /** PutObjectLegalHold on one version. */
  public void putLegalHold(String bucket, String key, String versionId, boolean on) {
    putXml("putObjectLegalHold", bucket, key, "legal-hold", versionId, S3Xml.legalHoldBody(on));
  }

  /** The tags of one version. */
  public Map<String, String> tags(String bucket, String key, String versionId) {
    String op = "getObjectTagging";
    List<Param> query = new ArrayList<>();
    query.add(new Param("tagging", ""));
    query.addAll(versionQuery(versionId));
    S3Response answer =
        expect(op, exchange(op, "GET", bucket, Optional.of(key), query, Map.of(), NO_BODY), 200);
    return S3Xml.tags(op, answer.status(), answer.body());
  }

  /** PutObjectTagging on one version: replaces the whole tag set. */
  public void putTags(String bucket, String key, String versionId, Map<String, String> tags) {
    putXml("putObjectTagging", bucket, key, "tagging", versionId, S3Xml.taggingBody(tags));
  }

  private void putXml(
      String op, String bucket, String key, String subresource, String versionId, byte[] body) {
    List<Param> query = new ArrayList<>();
    query.add(new Param(subresource, ""));
    query.addAll(versionQuery(versionId));
    expect(
        op,
        exchange(op, "PUT", bucket, Optional.of(key), query, withMd5(Map.of(), body), body),
        200);
  }

  // ---- machinery

  private static List<Param> versionQuery(String versionId) {
    Objects.requireNonNull(versionId, "versionId");
    if (versionId.isEmpty()) {
      throw new IllegalArgumentException("a version id must not be empty");
    }
    return List.of(new Param("versionId", versionId));
  }

  private static Map<String, String> withMd5(Map<String, String> headers, byte[] body) {
    Objects.requireNonNull(headers, "headers");
    if (headers.containsKey(CONTENT_MD5) || headers.containsKey(CONTENT_SHA256)) {
      throw new IllegalArgumentException("the client sets the body checksums itself");
    }
    Map<String, String> out = new TreeMap<>(headers);
    try {
      out.put(
          CONTENT_MD5,
          Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is not available", e);
    }
    return out;
  }

  private static S3Response expect(String op, S3Response answer, int expected) {
    if (answer.status() == expected) {
      return answer;
    }
    throw error(op, answer);
  }

  private static S3Exception error(String op, S3Response answer) {
    return new S3Exception(op, answer.status(), S3Xml.errorCode(answer.body()));
  }

  /** Builds, signs and sends a request; returns whatever answer comes back. */
  private S3Response exchange(
      String op,
      String method,
      String bucket,
      Optional<String> key,
      List<Param> query,
      Map<String, String> headers,
      byte[] body) {
    Objects.requireNonNull(bucket, "bucket");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(body, "body");
    if (!BUCKET.matcher(bucket).matches()) {
      throw new IllegalArgumentException("bucket name is not usable in a path");
    }
    if (key.isPresent() && key.get().isEmpty()) {
      throw new IllegalArgumentException("a key must not be empty");
    }
    for (Param p : query) {
      if (p.name().isEmpty()) {
        throw new IllegalArgumentException("a query parameter needs a name");
      }
    }
    Map<String, String> signed = checkedHeaders(headers);
    String payloadHash = signed.get(CONTENT_SHA256);
    if (payloadHash == null) {
      payloadHash = sha256Hex(body);
      signed.put(CONTENT_SHA256, payloadHash);
    } else if (!SigV4.SHA256_HEX.matcher(payloadHash).matches()) {
      throw new IllegalArgumentException("a payload hash is 64 lower-case hex digits");
    }

    String encodedPath = SigV4.encodePath("/" + bucket + key.map(k -> "/" + k).orElse(""));
    Instant at = signingClock.now();
    Map<String, String> auth =
        SigV4.sign(
            credentials,
            region,
            SERVICE,
            at,
            method,
            hostHeader,
            encodedPath,
            query,
            signed,
            payloadHash);
    Map<String, String> all = new TreeMap<>(signed);
    all.put("host", hostHeader);
    all.put("x-amz-date", auth.get("x-amz-date"));
    all.put("authorization", auth.get("authorization"));

    URI uri = URI.create(origin + encodedPath + queryString(query));
    try {
      return Objects.requireNonNull(
          transport.send(new S3Request(method, uri, all, body)),
          "the transport returned no answer");
    } catch (IOException e) {
      // not chained: its message may name the host or echo parts of the request
      throw new S3Exception(op, 0, e.getClass().getSimpleName());
    }
  }

  /** Headers checked and copied into a sorted map the caller may extend. */
  private static Map<String, String> checkedHeaders(Map<String, String> headers) {
    Objects.requireNonNull(headers, "headers");
    Map<String, String> out = new TreeMap<>();
    for (Map.Entry<String, String> h : headers.entrySet()) {
      String name = Objects.requireNonNull(h.getKey(), "header name");
      String value = Objects.requireNonNull(h.getValue(), "header value");
      if (!SigV4.HEADER_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("header names are lower-case tokens");
      }
      if (RESERVED.contains(name)) {
        throw new IllegalArgumentException("a header is set by the client or the HTTP stack");
      }
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c < 0x20 || c > 0x7E) {
          throw new IllegalArgumentException("header values are printable ASCII");
        }
      }
      out.put(name, value);
    }
    return out;
  }

  /**
   * The query as it goes on the wire: sorted by encoded name then value, a bare name when empty.
   */
  private static String queryString(List<Param> query) {
    if (query.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder("?");
    boolean first = true;
    for (SigV4.EncodedParam e : SigV4.sortedEncodedQuery(query)) {
      if (!first) {
        out.append('&');
      }
      first = false;
      out.append(e.name());
      if (!e.value().isEmpty()) {
        out.append('=').append(e.value());
      }
    }
    return out.toString();
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
