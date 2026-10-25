package dev.regpack.evidence.s3.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Client.VersionPage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.helpers.DefaultHandler;

/** The XML side of the client: hostile input, unknown elements, malformed bodies, templates. */
class S3XmlTest {
  private static final String OP = "listObjectVersions";

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  private static void assertS3Exception(Runnable call, int status, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            S3Exception.class,
            e -> {
              assertThat(e.status()).isEqualTo(status);
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.getMessage())
                  .isEqualTo("S3 " + OP + " status=" + status + " code=" + code);
              assertThat(e.getCause()).isNull();
            });
  }

  private static final String EMPTY_LISTING =
      "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
          + "<Name>b</Name><IsTruncated>false</IsTruncated></ListVersionsResult>";

  // ---- hostile input

  @Test
  void aDoctypeIsRefused() {
    String errorWithDoctype =
        "<?xml version=\"1.0\"?><!DOCTYPE Error [<!ENTITY e \"plain\">]>"
            + "<Error><Code>AccessDenied</Code></Error>";
    assertThat(S3Xml.errorCode(bytes(errorWithDoctype))).isEqualTo("unparsable");

    String listingWithDoctype =
        "<?xml version=\"1.0\"?><!DOCTYPE ListVersionsResult SYSTEM \"x.dtd\">" + EMPTY_LISTING;
    assertS3Exception(
        () -> S3Xml.versionPage(OP, 200, bytes(listingWithDoctype)), 200, "unparsable");
    assertS3Exception(
        () -> S3Xml.versioningEnabled(OP, 200, bytes(listingWithDoctype)), 200, "unparsable");
    assertS3Exception(
        () -> S3Xml.objectLockEnabled(OP, 200, bytes(listingWithDoctype)), 200, "unparsable");
    assertS3Exception(() -> S3Xml.tags(OP, 200, bytes(listingWithDoctype)), 200, "unparsable");
  }

  @Test
  void anExternalEntityIsNeverResolved(@TempDir Path dir) throws Exception {
    Path secret = dir.resolve("secret.txt");
    Files.writeString(secret, "MARKER-FILE-CONTENT", UTF_8);
    try (ScriptedHttpServer server = ScriptedHttpServer.start()) {
      server.reply(200, "MARKER-NETWORK-CONTENT");
      String hostile =
          "<?xml version=\"1.0\"?><!DOCTYPE Error ["
              + "<!ENTITY net SYSTEM \""
              + server.endpoint()
              + "/evil\">"
              + "<!ENTITY file SYSTEM \""
              + secret.toUri()
              + "\">"
              + "<!ENTITY % param SYSTEM \""
              + server.endpoint()
              + "/param\">%param;"
              + "]><Error><Code>&net;&file;</Code></Error>";

      // control: a parser with the defaults of the JDK does fetch the entity, so the server and the
      // body below would show a resolution if the client parsed that way
      try {
        DocumentBuilder plain = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder();
        plain.setErrorHandler(new DefaultHandler());
        plain.parse(new ByteArrayInputStream(bytes(hostile)));
      } catch (Exception expectedToFailOnTheDownload) {
        // the control only has to have reached out
      }
      assertThat(server.requests()).as("the control reaches the server").isNotEmpty();
      int afterControl = server.requests().size();

      assertThat(S3Xml.errorCode(bytes(hostile))).isEqualTo("unparsable");
      String listing = hostile.replace("<Error><Code>&net;&file;</Code></Error>", EMPTY_LISTING);
      assertThatThrownBy(() -> S3Xml.versionPage(OP, 200, bytes(listing)))
          .isInstanceOfSatisfying(
              S3Exception.class,
              e -> assertThat(e.getMessage()).doesNotContain("MARKER").doesNotContain("evil"));
      assertThat(server.requests()).as("the client resolved nothing").hasSize(afterControl);
    }
  }

  // ---- tolerance

  @Test
  void unknownElementsAreIgnored() {
    String listing =
        "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\" extra=\"1\">"
            + "<Unknown><Version><Key>nested</Key></Version></Unknown>"
            + "<Name>b</Name><Prefix>p</Prefix><MaxKeys>1000</MaxKeys><Foo>bar</Foo>"
            + "<IsTruncated>false</IsTruncated>"
            + "<Version><Key>k1</Key><VersionId>v1</VersionId><IsLatest>true</IsLatest>"
            + "<LastModified>2040-03-01T00:00:00.000Z</LastModified><ETag>&quot;e&quot;</ETag>"
            + "<Size>3</Size><Owner><ID>o</ID></Owner><StorageClass>STANDARD</StorageClass>"
            + "<Surprise/></Version>"
            + "<CommonPrefixes><Prefix>q/</Prefix></CommonPrefixes>"
            + "</ListVersionsResult>";

    VersionPage page = S3Xml.versionPage(OP, 200, bytes(listing));

    assertThat(page.entries()).containsExactly(new VersionEntry("k1", "v1", true, false));
    assertThat(page.truncated()).isFalse();

    assertThat(
            S3Xml.errorCode(
                bytes(
                    "<Error><Extra>x</Extra><Code>NoSuchKey</Code><Message>m</Message>"
                        + "<Resource>/b/k</Resource><RequestId>r</RequestId></Error>")))
        .isEqualTo("NoSuchKey");
    assertThat(
            S3Xml.versioningEnabled(
                OP,
                200,
                bytes(
                    "<VersioningConfiguration><MFADelete>Disabled</MFADelete>"
                        + "<Status>Enabled</Status></VersioningConfiguration>")))
        .isTrue();
    assertThat(
            S3Xml.tags(
                OP,
                200,
                bytes(
                    "<Tagging><TagSet><Tag><Key>a</Key><Value>1</Value><Z/></Tag></TagSet></Tagging>")))
        .isEqualTo(Map.of("a", "1"));
  }

  @Test
  void aMalformedBodyIsAnS3ExceptionWithItsStatus() {
    for (String body :
        new String[] {
          "",
          "not xml at all",
          "<ListVersionsResult><IsTruncated>true</IsTruncated>",
          "<Other><IsTruncated>false</IsTruncated></Other>",
          "<?xml version=\"1.0\"?><ListVersionsResult></Wrong>"
        }) {
      assertS3Exception(() -> S3Xml.versionPage(OP, 200, bytes(body)), 200, "unparsable");
    }
    assertS3Exception(() -> S3Xml.versionPage(OP, 207, bytes("<<<")), 207, "unparsable");
    assertS3Exception(() -> S3Xml.versioningEnabled(OP, 200, bytes("")), 200, "unparsable");
    assertS3Exception(() -> S3Xml.objectLockEnabled(OP, 200, bytes("x")), 200, "unparsable");
    assertS3Exception(() -> S3Xml.tags(OP, 200, bytes("<Tagging>")), 200, "unparsable");
    // a listing whose entries are incomplete is as unusable as one that is not XML
    for (String entry :
        new String[] {
          "<Version><VersionId>v</VersionId><IsLatest>true</IsLatest></Version>",
          "<Version><Key>k</Key><IsLatest>true</IsLatest></Version>",
          "<Version><Key>k</Key><VersionId>v</VersionId></Version>",
          "<Version><Key>k</Key><VersionId>v</VersionId><IsLatest>yes</IsLatest></Version>",
          "<DeleteMarker><Key></Key><VersionId>v</VersionId><IsLatest>true</IsLatest></DeleteMarker>"
        }) {
      String listing =
          "<ListVersionsResult><IsTruncated>false</IsTruncated>" + entry + "</ListVersionsResult>";
      assertS3Exception(() -> S3Xml.versionPage(OP, 200, bytes(listing)), 200, "unparsable");
    }
    // a page that cannot say whether it is the last is not read as the last
    assertS3Exception(
        () ->
            S3Xml.versionPage(
                OP, 200, bytes("<ListVersionsResult><Name>b</Name></ListVersionsResult>")),
        200,
        "unparsable");
    assertS3Exception(
        () ->
            S3Xml.versionPage(
                OP,
                200,
                bytes("<ListVersionsResult><IsTruncated>maybe</IsTruncated></ListVersionsResult>")),
        200,
        "unparsable");
  }

  // ---- the code of an error body

  @Test
  void theErrorCodeIsReducedToAKnownShape() {
    assertThat(S3Xml.errorCode(new byte[0])).isEqualTo("none");
    assertThat(S3Xml.errorCode(bytes("<Error><Message>m</Message></Error>"))).isEqualTo("none");
    assertThat(S3Xml.errorCode(bytes("<Error><Code></Code></Error>"))).isEqualTo("none");
    assertThat(S3Xml.errorCode(bytes("<Error><Code>NoSuchBucket</Code></Error>")))
        .isEqualTo("NoSuchBucket");
    for (String code :
        new String[] {
          "Access Denied", "Access\tDenied", "Line\nBreak", "x-amz", "::error::x", "Café"
        }) {
      assertThat(S3Xml.errorCode(bytes("<Error><Code>" + code + "</Code></Error>")))
          .as(code)
          .isEqualTo("invalid");
    }
    assertThat(S3Xml.errorCode(bytes("<Error><Code>" + "A".repeat(65) + "</Code></Error>")))
        .isEqualTo("invalid");
    assertThat(S3Xml.errorCode(bytes("<Error><Code>" + "A".repeat(64) + "</Code></Error>")))
        .isEqualTo("A".repeat(64));
    assertThat(S3Xml.errorCode(bytes("<Html><Code>NoSuchKey</Code></Html>")))
        .isEqualTo("unparsable");
  }

  // ---- what the listing holds

  @Test
  void aTruncatedPageCarriesItsMarkersAndAnEmptyElementIsNoMarker() {
    VersionPage page =
        S3Xml.versionPage(
            OP,
            200,
            bytes(
                "<ListVersionsResult><IsTruncated>true</IsTruncated>"
                    + "<KeyMarker/><VersionIdMarker/>"
                    + "<NextKeyMarker>k &amp; 2</NextKeyMarker>"
                    + "<NextVersionIdMarker>v9</NextVersionIdMarker>"
                    + "<DeleteMarker><Key> k1</Key><VersionId>null</VersionId>"
                    + "<IsLatest>true</IsLatest></DeleteMarker>"
                    + "<Version><Key>k0</Key><VersionId>v0</VersionId>"
                    + "<IsLatest>false</IsLatest></Version></ListVersionsResult>"));

    assertThat(page.truncated()).isTrue();
    assertThat(page.nextKeyMarker()).contains("k & 2");
    assertThat(page.nextVersionIdMarker()).contains("v9");
    assertThat(page.entries())
        .containsExactly(
            new VersionEntry(" k1", "null", true, true),
            new VersionEntry("k0", "v0", false, false));

    VersionPage last = S3Xml.versionPage(OP, 200, bytes(EMPTY_LISTING));
    assertThat(last.entries()).isEmpty();
    assertThat(last.nextKeyMarker()).isEqualTo(Optional.empty());
    assertThat(last.nextVersionIdMarker()).isEqualTo(Optional.empty());
  }

  @Test
  void configurationsAreReadFromTheirStatusElements() {
    assertThat(S3Xml.versioningEnabled(OP, 200, bytes("<VersioningConfiguration/>"))).isFalse();
    assertThat(
            S3Xml.versioningEnabled(
                OP,
                200,
                bytes(
                    "<VersioningConfiguration><Status>Suspended</Status></VersioningConfiguration>")))
        .isFalse();
    assertThat(
            S3Xml.objectLockEnabled(
                OP,
                200,
                bytes(
                    "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>"
                        + "<Rule><DefaultRetention><Mode>GOVERNANCE</Mode></DefaultRetention></Rule>"
                        + "</ObjectLockConfiguration>")))
        .isTrue();
    assertThat(S3Xml.objectLockEnabled(OP, 200, bytes("<ObjectLockConfiguration/>"))).isFalse();
    assertThat(S3Xml.tags(OP, 200, bytes("<Tagging><TagSet/></Tagging>"))).isEmpty();
  }

  // ---- what the client writes

  @Test
  void bodiesAreBuiltFromFixedTemplatesWithEscapedValues() {
    String ns = "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"";
    assertThat(new String(S3Xml.retentionBody("GOVERNANCE", "2040-03-01T00:00:00Z"), UTF_8))
        .isEqualTo(
            "<Retention "
                + ns
                + "><Mode>GOVERNANCE</Mode><RetainUntilDate>2040-03-01T00:00:00Z</RetainUntilDate>"
                + "</Retention>");
    assertThat(new String(S3Xml.legalHoldBody(true), UTF_8))
        .isEqualTo("<LegalHold " + ns + "><Status>ON</Status></LegalHold>");
    assertThat(new String(S3Xml.legalHoldBody(false), UTF_8))
        .isEqualTo("<LegalHold " + ns + "><Status>OFF</Status></LegalHold>");

    Map<String, String> tags = new TreeMap<>();
    tags.put("z", "<&>\"'");
    tags.put("a", "é\t\n");
    String tagging = new String(S3Xml.taggingBody(tags), UTF_8);
    assertThat(tagging)
        .isEqualTo(
            "<Tagging "
                + ns
                + "><TagSet><Tag><Key>a</Key><Value>é\t\n</Value></Tag>"
                + "<Tag><Key>z</Key><Value>&lt;&amp;&gt;&quot;&apos;</Value></Tag></TagSet></Tagging>");
    // what was written is read back as it was
    assertThat(S3Xml.tags(OP, 200, bytes(tagging))).isEqualTo(Map.of("a", "é\t\n", "z", "<&>\"'"));
  }

  @Test
  void aValueThatXmlCannotCarryIsRefusedWithoutBeingEchoed() {
    for (String value :
        new String[] {
          "MARKER" + (char) 0x0001, "MARKER" + (char) 0xD800, "MARKER" + (char) 0xFFFF
        }) {
      assertThatThrownBy(() -> S3Xml.taggingBody(Map.of("key", value)))
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e -> assertThat(e.getMessage()).doesNotContain("MARKER").doesNotContain("key"));
    }
    assertThatThrownBy(() -> S3Xml.retentionBody("GOVERNANCE", "2040<"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> S3Xml.retentionBody("OTHER", "2040-03-01T00:00:00Z"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> S3Xml.taggingBody(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDeclarationAndANamespaceAreAccepted() {
    assertThat(
            S3Xml.errorCode(
                bytes(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Error xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                        + "<Code>X</Code></Error>")))
        .isEqualTo("X");
  }

  @Test
  void aCarriageReturnInAValueSurvivesTheRoundTrip() {
    String tagging = new String(S3Xml.taggingBody(Map.of("k", "a\rb")), UTF_8);
    assertThat(tagging).contains("a&#13;b");
    assertThat(S3Xml.tags(OP, 200, bytes(tagging))).isEqualTo(Map.of("k", "a\rb"));
  }
}
