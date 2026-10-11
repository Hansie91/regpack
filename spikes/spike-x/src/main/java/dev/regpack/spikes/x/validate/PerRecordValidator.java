package dev.regpack.spikes.x.validate;

import dev.regpack.spikes.x.xml.StaxRecordWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

/**
 * The early quarantine gate: one record, wrapped in the smallest valid file around it, is checked
 * against the whole synthetic schema, so the per-record cost is the cost of the real schema and not
 * of a reduced one.
 *
 * <p>One JDK {@link Validator} per instance, reset before every record, secure processing on and no
 * external access. The error handler counts and never throws, so a record with several defects is
 * read to its end (the same work as a gate that reports all errors); a record that is not well
 * formed counts as one error. This is stated in the report.
 *
 * <p>Not thread-safe: one instance per thread; the {@link Schema} may be shared.
 */
public final class PerRecordValidator {
  private static final byte[] PREFIX =
      ("<transaction_file xmlns=\""
              + StaxRecordWriter.NAMESPACE
              + "\"><file_header><message_id>wrapper</message_id>"
              + "<created_at>1970-01-01T00:00:00.000000Z</created_at></file_header><transactions>")
          .getBytes(StandardCharsets.UTF_8);

  private static final byte[] SUFFIX =
      "</transactions></transaction_file>".getBytes(StandardCharsets.UTF_8);

  private final Validator validator;
  private final CountingHandler handler = new CountingHandler();

  public PerRecordValidator(Schema schema) {
    this.validator = Objects.requireNonNull(schema, "schema").newValidator();
    configure();
  }

  /** The bytes of one record element inside the smallest valid file. */
  public byte[] wrap(byte[] txBytes) {
    return wrap(txBytes, 0, txBytes.length);
  }

  /** The same for {@code length} bytes of a larger buffer, from {@code offset}. */
  public byte[] wrap(byte[] source, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, source.length);
    byte[] wrapped = new byte[PREFIX.length + length + SUFFIX.length];
    System.arraycopy(PREFIX, 0, wrapped, 0, PREFIX.length);
    System.arraycopy(source, offset, wrapped, PREFIX.length, length);
    System.arraycopy(SUFFIX, 0, wrapped, PREFIX.length + length, SUFFIX.length);
    return wrapped;
  }

  /** The number of validation errors of a wrapped record; zero means valid. */
  public int validate(byte[] wrappedRecord) {
    validator.reset();
    configure();
    handler.clear();
    try {
      validator.validate(new StreamSource(new ByteArrayInputStream(wrappedRecord)));
    } catch (SAXException e) {
      // a fatal error is counted by the handler first; this is only the safety net
      if (handler.errors == 0) {
        handler.errors = 1;
        handler.first = String.valueOf(e.getMessage());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return handler.errors;
  }

  /** The first message of the last record that failed; empty when the last record was valid. */
  public String firstError() {
    return handler.first;
  }

  private void configure() {
    try {
      validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
      throw new IllegalStateException("the JDK validator cannot be secured: " + e.getMessage(), e);
    }
    validator.setErrorHandler(handler);
  }

  private static final class CountingHandler implements ErrorHandler {
    int errors;
    String first = "";

    void clear() {
      errors = 0;
      first = "";
    }

    private void count(SAXParseException e) {
      if (errors++ == 0) {
        first = String.valueOf(e.getMessage());
      }
    }

    @Override
    public void warning(SAXParseException e) {}

    @Override
    public void error(SAXParseException e) {
      count(e);
    }

    @Override
    public void fatalError(SAXParseException e) {
      count(e);
    }
  }
}
