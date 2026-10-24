package dev.regpack.evidence.s3.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Client.VersionPage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * The XML of the S3 API: reading what the server sends and writing the few request bodies the
 * client needs. Reading is hostile-input safe: a document type declaration (and with it every
 * entity and every external reference) is refused, XInclude is off, secure processing is on, and
 * unknown elements are ignored. Writing uses fixed templates with escaped values.
 */
final class S3Xml {
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,64}");
  private static final Pattern DATE_TEXT = Pattern.compile("[0-9A-Za-z:.+-]{1,64}");
  private static final String NAMESPACE = " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"";
  private static final String UNPARSABLE = "unparsable";

  private S3Xml() {}

  // ---- reading

  /**
   * The code of an error body, reduced to a known shape and never throwing: {@code none} for an
   * empty body or one without a code, {@code unparsable} for a body that is not an S3 error
   * document, {@code invalid} for a code outside {@code [A-Za-z0-9]{1,64}}, else the code.
   */
  static String errorCode(byte[] body) {
    if (body.length == 0) {
      return "none";
    }
    Element root;
    try {
      root = parse(body).getDocumentElement();
    } catch (SAXException | IOException e) {
      return UNPARSABLE;
    }
    if (!"Error".equals(root.getLocalName())) {
      return UNPARSABLE;
    }
    Optional<String> code = childText(root, "Code");
    if (code.isEmpty() || code.get().isEmpty()) {
      return "none";
    }
    return CODE.matcher(code.get()).matches() ? code.get() : "invalid";
  }

  /** A ListObjectVersions answer. Every entry needs its key, version id and IsLatest. */
  static VersionPage versionPage(String operation, int status, byte[] body) {
    Element root = root(operation, status, body, "ListVersionsResult");
    boolean truncated =
        bool(
            operation,
            status,
            childText(root, "IsTruncated").orElseThrow(() -> bad(operation, status)));
    List<VersionEntry> entries = new ArrayList<>();
    for (Element child : elements(root)) {
      String name = child.getLocalName();
      boolean deleteMarker = "DeleteMarker".equals(name);
      if (deleteMarker || "Version".equals(name)) {
        String key = required(operation, status, child, "Key");
        String versionId = required(operation, status, child, "VersionId");
        boolean latest =
            bool(
                operation,
                status,
                childText(child, "IsLatest").orElseThrow(() -> bad(operation, status)));
        entries.add(new VersionEntry(key, versionId, latest, deleteMarker));
      }
    }
    return new VersionPage(
        entries,
        truncated,
        childText(root, "NextKeyMarker").filter(s -> !s.isEmpty()),
        childText(root, "NextVersionIdMarker").filter(s -> !s.isEmpty()));
  }

  /** True when the bucket's versioning status is {@code Enabled}; a missing status is false. */
  static boolean versioningEnabled(String operation, int status, byte[] body) {
    Element root = root(operation, status, body, "VersioningConfiguration");
    return childText(root, "Status").map("Enabled"::equals).orElse(false);
  }

  /** True when the object-lock configuration says {@code ObjectLockEnabled} is {@code Enabled}. */
  static boolean objectLockEnabled(String operation, int status, byte[] body) {
    Element root = root(operation, status, body, "ObjectLockConfiguration");
    return childText(root, "ObjectLockEnabled").map("Enabled"::equals).orElse(false);
  }

  /** The tag set of a Tagging document; a missing set is empty, a repeated key is unreadable. */
  static Map<String, String> tags(String operation, int status, byte[] body) {
    Element root = root(operation, status, body, "Tagging");
    Map<String, String> tags = new LinkedHashMap<>();
    for (Element set : elements(root)) {
      if (!"TagSet".equals(set.getLocalName())) {
        continue;
      }
      for (Element tag : elements(set)) {
        if (!"Tag".equals(tag.getLocalName())) {
          continue;
        }
        String key = required(operation, status, tag, "Key");
        if (tags.put(key, childText(tag, "Value").orElse("")) != null) {
          throw bad(operation, status);
        }
      }
    }
    return Map.copyOf(tags);
  }

  private static S3Exception bad(String operation, int status) {
    return new S3Exception(operation, status, UNPARSABLE);
  }

  private static boolean bool(String operation, int status, String text) {
    return switch (text) {
      case "true" -> true;
      case "false" -> false;
      default -> throw bad(operation, status);
    };
  }

  private static String required(String operation, int status, Element parent, String name) {
    String text = childText(parent, name).orElseThrow(() -> bad(operation, status));
    if (text.isEmpty()) {
      throw bad(operation, status);
    }
    return text;
  }

  private static Element root(String operation, int status, byte[] body, String expected) {
    Element root;
    try {
      root = parse(body).getDocumentElement();
    } catch (SAXException | IOException e) {
      throw bad(operation, status);
    }
    if (!expected.equals(root.getLocalName())) {
      throw bad(operation, status);
    }
    return root;
  }

  /** The text of the first child element with this local name. */
  private static Optional<String> childText(Element parent, String localName) {
    for (Element child : elements(parent)) {
      if (localName.equals(child.getLocalName())) {
        return Optional.of(child.getTextContent());
      }
    }
    return Optional.empty();
  }

  private static List<Element> elements(Element parent) {
    NodeList nodes = parent.getChildNodes();
    List<Element> out = new ArrayList<>(nodes.getLength());
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node instanceof Element element) {
        out.add(element);
      }
    }
    return out;
  }

  private static Document parse(byte[] body) throws SAXException, IOException {
    return newBuilder().parse(new ByteArrayInputStream(body));
  }

  private static DocumentBuilder newBuilder() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new Strict());
      return builder;
    } catch (ParserConfigurationException | IllegalArgumentException e) {
      throw new IllegalStateException("the XML parser cannot be configured safely");
    }
  }

  /** Every error is fatal and nothing is printed. */
  private static final class Strict implements ErrorHandler {
    @Override
    public void warning(SAXParseException e) {}

    @Override
    public void error(SAXParseException e) throws SAXException {
      throw e;
    }

    @Override
    public void fatalError(SAXParseException e) throws SAXException {
      throw e;
    }
  }

  // ---- writing

  /** {@code PutObjectRetention} body; the mode and the date are checked, not echoed on failure. */
  static byte[] retentionBody(String mode, String isoDate) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(isoDate, "isoDate");
    if (!mode.equals("GOVERNANCE") && !mode.equals("COMPLIANCE")) {
      throw new IllegalArgumentException("retention mode must be GOVERNANCE or COMPLIANCE");
    }
    if (!DATE_TEXT.matcher(isoDate).matches()) {
      throw new IllegalArgumentException("retain-until date has an unusable shape");
    }
    return ("<Retention"
            + NAMESPACE
            + "><Mode>"
            + mode
            + "</Mode><RetainUntilDate>"
            + escape(isoDate)
            + "</RetainUntilDate></Retention>")
        .getBytes(UTF_8);
  }

  /** {@code PutObjectLegalHold} body. */
  static byte[] legalHoldBody(boolean on) {
    return ("<LegalHold" + NAMESPACE + "><Status>" + (on ? "ON" : "OFF") + "</Status></LegalHold>")
        .getBytes(UTF_8);
  }

  /** {@code PutObjectTagging} body, tags in key order; at least one tag, every key non-empty. */
  static byte[] taggingBody(Map<String, String> tags) {
    Objects.requireNonNull(tags, "tags");
    if (tags.isEmpty()) {
      throw new IllegalArgumentException("a tag set needs at least one tag");
    }
    StringBuilder out = new StringBuilder("<Tagging").append(NAMESPACE).append("><TagSet>");
    for (Map.Entry<String, String> tag : new TreeMap<>(tags).entrySet()) {
      if (tag.getKey().isEmpty()) {
        throw new IllegalArgumentException("a tag key must not be empty");
      }
      out.append("<Tag><Key>")
          .append(escape(tag.getKey()))
          .append("</Key><Value>")
          .append(escape(Objects.requireNonNull(tag.getValue(), "tag value")))
          .append("</Value></Tag>");
    }
    return out.append("</TagSet></Tagging>").toString().getBytes(UTF_8);
  }

  /**
   * Escapes text content. A character that XML 1.0 cannot carry (a control character other than
   * tab, line feed and carriage return, an unpaired surrogate, U+FFFE, U+FFFF) is refused without
   * being echoed.
   */
  private static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length() + 16);
    text.codePoints()
        .forEach(
            cp -> {
              boolean valid =
                  cp == 0x9
                      || cp == 0xA
                      || cp == 0xD
                      || (cp >= 0x20 && cp <= 0xD7FF)
                      || (cp >= 0xE000 && cp <= 0xFFFD)
                      || (cp >= 0x10000 && cp <= 0x10FFFF);
              if (!valid) {
                throw new IllegalArgumentException("text holds a character that XML cannot carry");
              }
              switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                case '\r' -> out.append("&#13;");
                default -> out.appendCodePoint(cp);
              }
            });
    return out.toString();
  }
}
