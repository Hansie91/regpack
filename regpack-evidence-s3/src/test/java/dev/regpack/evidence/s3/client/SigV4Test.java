package dev.regpack.evidence.s3.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.client.SigV4.Param;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The signer against five vectors. The expected values were recomputed independently during plan
 * adjudication: if the implementation differs from them, the implementation is wrong.
 */
class SigV4Test {
  private static final String EMPTY_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private static final Credentials SERVICE_CREDENTIALS =
      new Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
  private static final Credentials S3_CREDENTIALS =
      new Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
  private static final String S3_HOST = "examplebucket.s3.amazonaws.com";
  private static final Instant S3_DATE = Instant.parse("2013-05-24T00:00:00Z");

  private static String sha256Hex(String text) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String signatureOf(Map<String, String> signed) {
    String authorization = signed.get("authorization");
    int at = authorization.lastIndexOf("Signature=");
    assertThat(at).isPositive();
    return authorization.substring(at + "Signature=".length());
  }

  // ---- V1 (suite get-vanilla)

  private static Map<String, String> v1() {
    return SigV4.sign(
        SERVICE_CREDENTIALS,
        "us-east-1",
        "service",
        Instant.parse("2015-08-30T12:36:00Z"),
        "GET",
        "example.amazonaws.com",
        "/",
        List.of(),
        Map.of(),
        EMPTY_SHA256);
  }

  @Test
  void v1GetVanillaSignature() {
    Map<String, String> signed = v1();

    assertThat(signatureOf(signed))
        .isEqualTo("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    assertThat(signed).containsOnlyKeys("x-amz-date", "authorization");
    assertThat(signed.get("x-amz-date")).isEqualTo("20150830T123600Z");
    assertThat(signed.get("authorization"))
        .isEqualTo(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                + "SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
  }

  @Test
  void v1GetVanillaCanonicalRequestHash() {
    String canonical =
        SigV4.canonicalRequest(
            "GET",
            "example.amazonaws.com",
            "20150830T123600Z",
            "/",
            List.of(),
            Map.of(),
            EMPTY_SHA256);

    assertThat(sha256Hex(canonical))
        .isEqualTo("bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63");
    assertThat(canonical)
        .isEqualTo(
            "GET\n/\n\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\n"
                + "host;x-amz-date\n"
                + EMPTY_SHA256);
  }

  // ---- V2 (S3 GET with a range)

  private static final Map<String, String> V2_HEADERS =
      Map.of("range", "bytes=0-9", "x-amz-content-sha256", EMPTY_SHA256);

  @Test
  void v2S3GetWithRangeSignature() {
    Map<String, String> signed =
        SigV4.sign(
            S3_CREDENTIALS,
            "us-east-1",
            "s3",
            S3_DATE,
            "GET",
            S3_HOST,
            "/test.txt",
            List.of(),
            V2_HEADERS,
            EMPTY_SHA256);

    assertThat(signatureOf(signed))
        .isEqualTo("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    assertThat(signed.get("authorization"))
        .contains(
            "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request",
            "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date");
  }

  @Test
  void v2S3GetWithRangeCanonicalRequestHash() {
    String canonical =
        SigV4.canonicalRequest(
            "GET", S3_HOST, "20130524T000000Z", "/test.txt", List.of(), V2_HEADERS, EMPTY_SHA256);

    assertThat(sha256Hex(canonical))
        .isEqualTo("7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972");
  }

  // ---- V3 (S3 PUT, a dollar sign in the key)

  @Test
  void v3S3PutWithADollarInTheKey() {
    Map<String, String> headers =
        Map.of(
            "date", "Fri, 24 May 2013 00:00:00 GMT",
            "x-amz-storage-class", "REDUCED_REDUNDANCY",
            "x-amz-content-sha256",
                "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");

    Map<String, String> signed =
        SigV4.sign(
            S3_CREDENTIALS,
            "us-east-1",
            "s3",
            S3_DATE,
            "PUT",
            S3_HOST,
            SigV4.encodePath("/test$file.text"),
            List.of(),
            headers,
            "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");

    assertThat(SigV4.encodePath("/test$file.text")).isEqualTo("/test%24file.text");
    assertThat(signatureOf(signed))
        .isEqualTo("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd");
  }

  // ---- V4 (S3 list)

  @Test
  void v4S3ListWithTwoQueryParameters() {
    // given out of order on purpose: the signer sorts
    Map<String, String> signed =
        SigV4.sign(
            S3_CREDENTIALS,
            "us-east-1",
            "s3",
            S3_DATE,
            "GET",
            S3_HOST,
            "/",
            List.of(new Param("prefix", "J"), new Param("max-keys", "2")),
            Map.of("x-amz-content-sha256", EMPTY_SHA256),
            EMPTY_SHA256);

    assertThat(signatureOf(signed))
        .isEqualTo("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7");
  }

  // ---- V5 (S3 sub-resource with an empty value)

  @Test
  void aValuelessQueryParameterIsSignedAsNameEqualsEmpty() {
    Map<String, String> headers = Map.of("x-amz-content-sha256", EMPTY_SHA256);
    List<Param> query = List.of(new Param("lifecycle", ""));

    Map<String, String> signed =
        SigV4.sign(
            S3_CREDENTIALS,
            "us-east-1",
            "s3",
            S3_DATE,
            "GET",
            S3_HOST,
            "/",
            query,
            headers,
            EMPTY_SHA256);
    String canonical =
        SigV4.canonicalRequest(
            "GET", S3_HOST, "20130524T000000Z", "/", query, headers, EMPTY_SHA256);

    assertThat(canonical).startsWith("GET\n/\nlifecycle=\n");
    assertThat(sha256Hex(canonical))
        .isEqualTo("9766c798316ff2757b517bc739a67f6213b4ab36dd5da2f94eaebf79c77395ca");
    assertThat(signatureOf(signed))
        .isEqualTo("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543");
  }

  // ---- encoding and canonical form

  @Test
  void encodePathKeepsSlashAndUnreservedAndEncodesPlusEqualsCommaDollarAndNonAscii() {
    assertThat(SigV4.encodePath("a/b=c,d+e$f")).isEqualTo("a/b%3Dc%2Cd%2Be%24f");
    assertThat(SigV4.encodePath("é")).isEqualTo("%C3%A9");
    assertThat(SigV4.encodePath("/A-Za-z0-9-._~/")).isEqualTo("/A-Za-z0-9-._~/");
    assertThat(SigV4.encodePath("a b")).isEqualTo("a%20b");
    assertThat(SigV4.encodePath("100%")).isEqualTo("100%25");
    // a code point outside the BMP is four UTF-8 bytes
    assertThat(SigV4.encodePath("😀")).isEqualTo("%F0%9F%98%80");
    assertThat(SigV4.encodeQueryComponent("a/b=c d")).isEqualTo("a%2Fb%3Dc%20d");
    assertThatThrownBy(() -> SigV4.encodePath("a\uD83Db"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SigV4.encodeQueryComponent("\uDE00"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void queryIsSortedByEncodedNameThenValueAndSpaceIsPercent20() {
    List<Param> query =
        List.of(
            new Param("b", "2"),
            new Param("a", "z y"),
            new Param("a", "x"),
            new Param("a b", "1"),
            new Param("a/b", "/"));

    String canonical =
        SigV4.canonicalRequest("GET", "h", "20130524T000000Z", "/", query, Map.of(), EMPTY_SHA256);

    assertThat(canonical.split("\n")[2]).isEqualTo("a=x&a=z%20y&a%20b=1&a%2Fb=%2F&b=2");
  }

  @Test
  void headerValuesAreTrimmedAndInnerRunsOfSpacesCollapsed() {
    String canonical =
        SigV4.canonicalRequest(
            "GET",
            "h",
            "20130524T000000Z",
            "/",
            List.of(),
            Map.of("x-amz-meta-a", "  one    two  three  "),
            EMPTY_SHA256);

    assertThat(canonical).contains("\nx-amz-meta-a:one two three\n");
    Map<String, String> plain = signWith(Map.of("x-amz-meta-a", "one two three"), EMPTY_SHA256);
    Map<String, String> padded =
        signWith(Map.of("x-amz-meta-a", "  one    two  three  "), EMPTY_SHA256);
    assertThat(padded).isEqualTo(plain);
  }

  private static Map<String, String> signWith(Map<String, String> headers, String payload) {
    return SigV4.sign(
        S3_CREDENTIALS,
        "us-east-1",
        "s3",
        S3_DATE,
        "PUT",
        S3_HOST,
        "/k",
        List.of(),
        headers,
        payload);
  }

  @Test
  void theSameInputGivesTheSameSignature() {
    assertThat(v1()).isEqualTo(v1());
    assertThat(signWith(Map.of("x-amz-meta-a", "v"), EMPTY_SHA256))
        .isEqualTo(signWith(Map.of("x-amz-meta-a", "v"), EMPTY_SHA256));
  }

  @Test
  void aDifferentPayloadHashChangesTheSignature() {
    String other = sha256Hex("other");
    assertThat(signatureOf(signWith(Map.of(), EMPTY_SHA256)))
        .isNotEqualTo(signatureOf(signWith(Map.of(), other)));
  }

  @Test
  void aDifferentInstantMethodHostPathOrHeaderChangesTheSignature() {
    String base = signatureOf(signWith(Map.of("x-amz-meta-a", "v"), EMPTY_SHA256));
    assertThat(
            signatureOf(
                SigV4.sign(
                    S3_CREDENTIALS,
                    "us-east-1",
                    "s3",
                    S3_DATE.plusSeconds(1),
                    "PUT",
                    S3_HOST,
                    "/k",
                    List.of(),
                    Map.of("x-amz-meta-a", "v"),
                    EMPTY_SHA256)))
        .isNotEqualTo(base);
    assertThat(signatureOf(signWith(Map.of("x-amz-meta-a", "w"), EMPTY_SHA256))).isNotEqualTo(base);
    assertThat(signatureOf(signWith(Map.of("x-amz-meta-b", "v"), EMPTY_SHA256))).isNotEqualTo(base);
    assertThat(
            signatureOf(
                SigV4.sign(
                    S3_CREDENTIALS,
                    "us-east-1",
                    "s3",
                    S3_DATE,
                    "GET",
                    S3_HOST,
                    "/k",
                    List.of(),
                    Map.of("x-amz-meta-a", "v"),
                    EMPTY_SHA256)))
        .isNotEqualTo(base);
    assertThat(
            signatureOf(
                SigV4.sign(
                    S3_CREDENTIALS,
                    "us-east-1",
                    "s3",
                    S3_DATE,
                    "PUT",
                    "127.0.0.1:8000",
                    "/k",
                    List.of(),
                    Map.of("x-amz-meta-a", "v"),
                    EMPTY_SHA256)))
        .isNotEqualTo(base);
  }

  @Test
  void credentialsToStringHidesTheSecret() {
    Credentials c = new Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");

    assertThat(c.toString()).isEqualTo("Credentials[accessKeyId=AKIDEXAMPLE, secret=***]");
    assertThat(c.toString()).doesNotContain("wJalr");
    assertThat(c.accessKeyId()).isEqualTo("AKIDEXAMPLE");
    assertThat(c.secretAccessKey()).startsWith("wJalr");
  }

  @Test
  void inputsThatCannotBeSignedAreRefused() {
    Credentials c = S3_CREDENTIALS;
    List<Param> none = List.of();
    // host and x-amz-date are added by the signer, never given
    assertThatThrownBy(
            () ->
                SigV4.sign(
                    c,
                    "r",
                    "s3",
                    S3_DATE,
                    "GET",
                    S3_HOST,
                    "/",
                    none,
                    Map.of("host", "x"),
                    EMPTY_SHA256))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SigV4.sign(
                    c,
                    "r",
                    "s3",
                    S3_DATE,
                    "GET",
                    S3_HOST,
                    "/",
                    none,
                    Map.of("x-amz-date", "x"),
                    EMPTY_SHA256))
        .isInstanceOf(IllegalArgumentException.class);
    // header names are lower case
    assertThatThrownBy(
            () ->
                SigV4.sign(
                    c,
                    "r",
                    "s3",
                    S3_DATE,
                    "GET",
                    S3_HOST,
                    "/",
                    none,
                    Map.of("Range", "x"),
                    EMPTY_SHA256))
        .isInstanceOf(IllegalArgumentException.class);
    // a header value cannot carry a line break
    assertThatThrownBy(
            () ->
                SigV4.sign(
                    c,
                    "r",
                    "s3",
                    S3_DATE,
                    "GET",
                    S3_HOST,
                    "/",
                    none,
                    Map.of("x-a", "a\nb"),
                    EMPTY_SHA256))
        .isInstanceOf(IllegalArgumentException.class);
    // the payload hash is 64 lower-case hex digits
    assertThatThrownBy(
            () -> SigV4.sign(c, "r", "s3", S3_DATE, "GET", S3_HOST, "/", none, Map.of(), "ABC"))
        .isInstanceOf(IllegalArgumentException.class);
    // the path is given encoded and starts with a slash
    assertThatThrownBy(
            () ->
                SigV4.sign(
                    c, "r", "s3", S3_DATE, "GET", S3_HOST, "k", none, Map.of(), EMPTY_SHA256))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SigV4.sign(
                    c, "r", "s3", S3_DATE, "GET", S3_HOST, "/a b", none, Map.of(), EMPTY_SHA256))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SigV4.sign(
                    c, "r", "s3", S3_DATE, "get", S3_HOST, "/", none, Map.of(), EMPTY_SHA256))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Credentials("", "secret"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
