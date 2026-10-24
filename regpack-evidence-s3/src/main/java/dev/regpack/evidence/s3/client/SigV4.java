package dev.regpack.evidence.s3.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for S3-style requests: the canonical URI is the path as the client sends
 * it (encoded once), the payload hash is given by the caller and is the value of the signed {@code
 * x-amz-content-sha256} header where the caller signs that header. Pure functions of their
 * arguments: time comes from the caller.
 */
public final class SigV4 {
  private static final String ALGORITHM = "AWS4-HMAC-SHA256";
  private static final String HMAC = "HmacSHA256";
  private static final DateTimeFormatter AMZ_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
  static final Pattern METHOD = Pattern.compile("[A-Z]{1,16}");
  static final Pattern HEADER_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
  static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern ENCODED_PATH = Pattern.compile("/([A-Za-z0-9._~/-]|%[0-9A-F]{2})*");
  private static final Pattern SCOPE_PART = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final String HEX_UPPER = "0123456789ABCDEF";

  private SigV4() {}

  /** An access key id and its secret. */
  public record Credentials(String accessKeyId, String secretAccessKey) {
    public Credentials {
      Objects.requireNonNull(accessKeyId, "accessKeyId");
      Objects.requireNonNull(secretAccessKey, "secretAccessKey");
      if (accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
        throw new IllegalArgumentException("credentials must not be empty");
      }
    }

    @Override
    public String toString() {
      return "Credentials[accessKeyId=" + accessKeyId + ", secret=***]";
    }
  }

  /** One query parameter, name and value decoded (not percent-encoded). */
  public record Param(String name, String value) {
    public Param {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * Signs a request. Returns {@code x-amz-date} and {@code authorization}.
   *
   * @param hostHeader what the client sends as Host (the port is kept unless it is the default)
   * @param encodedPath the path as sent, percent-encoded once, starting with '/'
   * @param query decoded parameters, in any order
   * @param signedHeaders lower-case names without {@code host} and {@code x-amz-date}, with the
   *     values as sent
   * @param payloadSha256Hex the SHA-256 of the body as 64 lower-case hex digits
   */
  public static Map<String, String> sign(
      Credentials c,
      String region,
      String service,
      Instant at,
      String method,
      String hostHeader,
      String encodedPath,
      List<Param> query,
      Map<String, String> signedHeaders,
      String payloadSha256Hex) {
    Objects.requireNonNull(c, "credentials");
    Objects.requireNonNull(at, "at");
    requireScopePart(region, "region");
    requireScopePart(service, "service");
    String amzDate = AMZ_DATE.format(at);
    String dateStamp = amzDate.substring(0, 8);
    String canonical =
        canonicalRequest(
            method, hostHeader, amzDate, encodedPath, query, signedHeaders, payloadSha256Hex);
    String toSign = stringToSign(amzDate, region, service, canonical);
    byte[] key = signingKey(c.secretAccessKey(), dateStamp, region, service);
    String signature = HexFormat.of().formatHex(hmac(key, toSign.getBytes(UTF_8)));
    String authorization =
        ALGORITHM
            + " Credential="
            + c.accessKeyId()
            + "/"
            + credentialScope(dateStamp, region, service)
            + ", SignedHeaders="
            + signedHeaderNames(signedHeaders)
            + ", Signature="
            + signature;
    return Map.of("x-amz-date", amzDate, "authorization", authorization);
  }

  /** '/' and A-Za-z0-9-._~ are kept, every other UTF-8 byte is written %XX in upper case. */
  public static String encodePath(String path) {
    return encode(path, true);
  }

  /** As {@link #encodePath}, with '/' encoded as well. */
  public static String encodeQueryComponent(String s) {
    return encode(s, false);
  }

  private static String encode(String s, boolean keepSlash) {
    Objects.requireNonNull(s, "s");
    ByteBuffer bytes;
    try {
      bytes =
          UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(s));
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("text is not valid Unicode (unpaired surrogate)");
    }
    StringBuilder out = new StringBuilder(bytes.remaining() + 16);
    while (bytes.hasRemaining()) {
      int b = bytes.get() & 0xFF;
      boolean unreserved =
          (b >= 'A' && b <= 'Z')
              || (b >= 'a' && b <= 'z')
              || (b >= '0' && b <= '9')
              || b == '-'
              || b == '.'
              || b == '_'
              || b == '~'
              || (keepSlash && b == '/');
      if (unreserved) {
        out.append((char) b);
      } else {
        out.append('%').append(HEX_UPPER.charAt(b >> 4)).append(HEX_UPPER.charAt(b & 0xF));
      }
    }
    return out.toString();
  }

  static String canonicalRequest(
      String method,
      String hostHeader,
      String amzDate,
      String encodedPath,
      List<Param> query,
      Map<String, String> signedHeaders,
      String payloadSha256Hex) {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(hostHeader, "hostHeader");
    Objects.requireNonNull(amzDate, "amzDate");
    Objects.requireNonNull(encodedPath, "encodedPath");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(payloadSha256Hex, "payloadSha256Hex");
    if (!METHOD.matcher(method).matches()) {
      throw new IllegalArgumentException("method must be upper-case letters");
    }
    if (!ENCODED_PATH.matcher(encodedPath).matches()) {
      throw new IllegalArgumentException("path must start with '/' and be percent-encoded");
    }
    if (!SHA256_HEX.matcher(payloadSha256Hex).matches()) {
      throw new IllegalArgumentException("payload hash must be 64 lower-case hex digits");
    }
    TreeMap<String, String> headers = normalisedHeaders(signedHeaders);
    headers.put("host", normaliseValue(hostHeader));
    headers.put("x-amz-date", normaliseValue(amzDate));

    StringBuilder out = new StringBuilder(256);
    out.append(method).append('\n').append(encodedPath).append('\n');
    out.append(canonicalQuery(query)).append('\n');
    for (Map.Entry<String, String> h : headers.entrySet()) {
      out.append(h.getKey()).append(':').append(h.getValue()).append('\n');
    }
    out.append('\n').append(String.join(";", headers.keySet())).append('\n');
    out.append(payloadSha256Hex);
    return out.toString();
  }

  /** One query parameter, percent-encoded. */
  record EncodedParam(String name, String value) {}

  /**
   * The parameters percent-encoded and sorted by encoded name, then encoded value. The canonical
   * query and the query on the wire are both written from this one list.
   */
  static List<EncodedParam> sortedEncodedQuery(List<Param> query) {
    List<EncodedParam> encoded = new ArrayList<>(query.size());
    for (Param p : query) {
      encoded.add(
          new EncodedParam(encodeQueryComponent(p.name()), encodeQueryComponent(p.value())));
    }
    encoded.sort(Comparator.comparing(EncodedParam::name).thenComparing(EncodedParam::value));
    return encoded;
  }

  private static String canonicalQuery(List<Param> query) {
    StringBuilder out = new StringBuilder();
    for (EncodedParam e : sortedEncodedQuery(query)) {
      if (out.length() > 0) {
        out.append('&');
      }
      out.append(e.name()).append('=').append(e.value());
    }
    return out.toString();
  }

  /** The given headers, checked, with trimmed values; host and x-amz-date are not allowed. */
  private static TreeMap<String, String> normalisedHeaders(Map<String, String> signedHeaders) {
    Objects.requireNonNull(signedHeaders, "signedHeaders");
    TreeMap<String, String> headers = new TreeMap<>();
    for (Map.Entry<String, String> h : signedHeaders.entrySet()) {
      String name = Objects.requireNonNull(h.getKey(), "header name");
      if (!HEADER_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("header names are lower-case tokens: " + name);
      }
      if (name.equals("host") || name.equals("x-amz-date")) {
        throw new IllegalArgumentException("the signer adds " + name + " itself");
      }
      headers.put(name, normaliseValue(Objects.requireNonNull(h.getValue(), "header value")));
    }
    return headers;
  }

  /** Trims the value and collapses each run of spaces to one; a control character is refused. */
  private static String normaliseValue(String value) {
    StringBuilder out = new StringBuilder(value.length());
    boolean pendingSpace = false;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch < 0x20 || ch == 0x7F) {
        throw new IllegalArgumentException("a header value holds a control character");
      }
      if (ch == ' ') {
        pendingSpace = out.length() > 0;
      } else {
        if (pendingSpace) {
          out.append(' ');
          pendingSpace = false;
        }
        out.append(ch);
      }
    }
    return out.toString();
  }

  private static String signedHeaderNames(Map<String, String> signedHeaders) {
    TreeMap<String, String> headers = normalisedHeaders(signedHeaders);
    headers.put("host", "");
    headers.put("x-amz-date", "");
    return String.join(";", headers.keySet());
  }

  static String stringToSign(
      String amzDate, String region, String service, String canonicalRequest) {
    String dateStamp = amzDate.substring(0, 8);
    return ALGORITHM
        + "\n"
        + amzDate
        + "\n"
        + credentialScope(dateStamp, region, service)
        + "\n"
        + sha256Hex(canonicalRequest.getBytes(UTF_8));
  }

  static byte[] signingKey(String secret, String dateStamp, String region, String service) {
    byte[] dateKey = hmac(("AWS4" + secret).getBytes(UTF_8), dateStamp.getBytes(UTF_8));
    byte[] regionKey = hmac(dateKey, region.getBytes(UTF_8));
    byte[] serviceKey = hmac(regionKey, service.getBytes(UTF_8));
    return hmac(serviceKey, "aws4_request".getBytes(UTF_8));
  }

  private static String credentialScope(String dateStamp, String region, String service) {
    return dateStamp + "/" + region + "/" + service + "/aws4_request";
  }

  private static void requireScopePart(String value, String what) {
    Objects.requireNonNull(value, what);
    if (!SCOPE_PART.matcher(value).matches()) {
      throw new IllegalArgumentException(what + " is not a valid credential-scope part");
    }
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(key, HMAC));
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }

  private static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
