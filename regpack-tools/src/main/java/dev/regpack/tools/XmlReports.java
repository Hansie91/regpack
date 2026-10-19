package dev.regpack.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Hardened parsing of the {@code TEST-*.xml} reports that the build produces. */
final class XmlReports {
  private XmlReports() {}

  /**
   * Parses a report with DOCTYPE, external entities and XInclude refused.
   *
   * @return the document, empty when the file is unreadable or malformed
   */
  static Optional<Document> parse(Path file) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return Optional.of(factory.newDocumentBuilder().parse(file.toFile()));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      return Optional.empty();
    }
  }
}
