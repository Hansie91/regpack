package dev.regpack.spikes.x.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.xml.sax.SAXException;

/**
 * The self-authored synthetic schemas as compiled JDK schemas, read from the class path (a run
 * starts from a jar). The factory has secure processing on and no external access, so a schema that
 * wanted to reach outside would fail to compile.
 */
public final class SyntheticSchema {
  /** The transaction schema of the measurements. */
  public static final String TXN = "synthetic/txn-1.xsd";

  /** The probe with one XSD 1.1 construct (criterion X-5). */
  public static final String PROBE = "synthetic/xsd11-probe.xsd";

  private SyntheticSchema() {}

  /** The transaction schema, compiled. */
  public static Schema txn() {
    try {
      return compile(TXN);
    } catch (SAXException e) {
      throw new IllegalStateException(
          "the synthetic schema does not compile: " + e.getMessage(), e);
    }
  }

  /**
   * Compiles a schema of the class path.
   *
   * @throws SAXException when the JDK validator rejects the schema
   */
  public static Schema compile(String resource) throws SAXException {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    try (InputStream in = SyntheticSchema.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("resource not found: " + resource);
      }
      return factory.newSchema(new StreamSource(in));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Criterion X-5: how many of the self-authored XSD 1.1 constructs the JDK validator rejects. The
   * probe holds one construct (an assertion); the JDK implements XSD 1.0 only, so it rejects it.
   */
  public static long xsd11ConstructsRejected() {
    try {
      compile(PROBE);
      return 0;
    } catch (SAXException e) {
      return 1;
    }
  }
}
