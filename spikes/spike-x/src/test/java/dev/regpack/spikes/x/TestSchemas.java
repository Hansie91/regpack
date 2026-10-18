package dev.regpack.spikes.x;

import java.io.IOException;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.xml.sax.SAXException;

/** Test helper: the synthetic schemas as files and as compiled JDK schemas. */
public final class TestSchemas {
  public static final Path TXN = Path.of("src/main/resources/synthetic/txn-1.xsd");
  public static final Path PROBE = Path.of("src/main/resources/synthetic/xsd11-probe.xsd");

  private TestSchemas() {}

  /** The XSD 1.0 validator of the JDK with secure processing on and no external access. */
  public static SchemaFactory factory() throws SAXException {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory;
  }

  public static Schema compile(Path xsd) throws SAXException, IOException {
    return factory().newSchema(new StreamSource(xsd.toFile()));
  }

  public static Schema txn() throws SAXException, IOException {
    return compile(TXN);
  }
}
