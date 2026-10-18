package dev.regpack.spikes.x.xml;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Writes a file of the synthetic schema through the JDK StAX writer: UTF-8, the default namespace
 * of the synthetic schema, no indentation. A record written without a document declares the
 * namespace on the record element itself, so that it can be validated on its own.
 *
 * <p>Not thread-safe; one writer per thread and file. The caller owns the stream and closes it.
 */
public final class StaxRecordWriter {
  /** The namespace of the synthetic schema. */
  public static final String NAMESPACE = "urn:regpack:spike:synthetic:txn:1";

  private static final String RECORD = "transaction_record";

  private final CountingStream sink;
  private final XMLStreamWriter xml;
  private final Map<String, Path> parsed = new HashMap<>();
  private String[] open = new String[8];
  private int depth;
  private boolean documentStarted;
  private boolean ended;

  public StaxRecordWriter(OutputStream out) {
    this.sink = new CountingStream(Objects.requireNonNull(out, "out"));
    try {
      this.xml = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(sink, "UTF-8");
    } catch (XMLStreamException e) {
      throw failure(e);
    }
  }

  /** Writes the declaration, the root element, the header block and opens the set of records. */
  public void startDocument(String messageId, long creationMicros) {
    Objects.requireNonNull(messageId, "messageId");
    if (documentStarted) {
      throw new IllegalStateException("the document has started");
    }
    try {
      xml.writeStartDocument("UTF-8", "1.0");
      xml.writeStartElement("transaction_file");
      xml.writeDefaultNamespace(NAMESPACE);
      xml.writeStartElement("file_header");
      xml.writeStartElement("message_id");
      xml.writeCharacters(messageId);
      xml.writeEndElement();
      xml.writeStartElement("created_at");
      xml.writeCharacters(XmlFormat.dateTime(creationMicros));
      xml.writeEndElement();
      xml.writeEndElement();
      xml.writeStartElement("transactions");
    } catch (XMLStreamException e) {
      throw failure(e);
    }
    documentStarted = true;
  }

  /** Writes one record element. */
  public void writeTx(TxModel model) {
    if (ended) {
      throw new IllegalStateException("the document has ended");
    }
    List<String> names = model.names();
    List<String> values = model.values();
    List<String> attributes = model.attributes();
    try {
      xml.writeStartElement(RECORD);
      if (!documentStarted) {
        xml.writeDefaultNamespace(NAMESPACE);
      }
      depth = 0;
      for (int i = 0; i < names.size(); i++) {
        Path path = parsed.computeIfAbsent(names.get(i), Path::of);
        int containers = path.segments.length - 1;
        int common = 0;
        while (common < depth
            && common < containers
            && open[common].equals(path.segments[common])) {
          common++;
        }
        while (depth > common) {
          xml.writeEndElement();
          depth--;
        }
        for (int c = common; c < containers; c++) {
          xml.writeStartElement(path.elements[c]);
          push(path.segments[c]);
        }
        xml.writeStartElement(path.elements[containers]);
        String attribute = attributes.get(i);
        if (!attribute.isEmpty()) {
          int equals = attribute.indexOf('=');
          xml.writeAttribute(attribute.substring(0, equals), attribute.substring(equals + 1));
        }
        xml.writeCharacters(values.get(i));
        xml.writeEndElement();
      }
      while (depth > 0) {
        xml.writeEndElement();
        depth--;
      }
      xml.writeEndElement();
    } catch (XMLStreamException e) {
      throw failure(e);
    }
  }

  /** Closes the set of records, the root element and the document, and flushes. */
  public void endDocument() {
    if (!documentStarted || ended) {
      throw new IllegalStateException("there is no open document");
    }
    try {
      xml.writeEndElement();
      xml.writeEndElement();
      xml.writeEndDocument();
      xml.flush();
      xml.close();
    } catch (XMLStreamException e) {
      throw failure(e);
    }
    ended = true;
  }

  /**
   * Pushes what the XML writer has buffered into the stream, so that the bytes of the elements
   * written so far are all in the stream. A caller that reads a record's bytes from the stream (the
   * per-record validation) calls this first, so that it does not depend on how much the JDK
   * implementation buffers. Nothing happens once the document has ended (the end of the document
   * flushes).
   */
  public void flush() {
    if (!ended) {
      try {
        xml.flush();
      } catch (XMLStreamException e) {
        throw failure(e);
      }
    }
  }

  /** The bytes that have gone to the stream; the writer is flushed first, so this is exact. */
  public long bytesWritten() {
    flush();
    return sink.count;
  }

  private void push(String segment) {
    if (depth == open.length) {
      open = java.util.Arrays.copyOf(open, depth * 2);
    }
    open[depth++] = segment;
  }

  private static RuntimeException failure(XMLStreamException e) {
    if (e.getCause() instanceof IOException io) {
      return new UncheckedIOException(io);
    }
    return new IllegalStateException("cannot write XML: " + e.getMessage(), e);
  }

  /** A model path split once: the segments as written and the element names without {@code #n}. */
  private record Path(String[] segments, String[] elements) {
    static Path of(String path) {
      List<String> parts = new ArrayList<>();
      int start = 0;
      for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', start)) {
        parts.add(path.substring(start, i));
        start = i + 1;
      }
      parts.add(path.substring(start));
      String[] segments = parts.toArray(new String[0]);
      String[] elements = new String[segments.length];
      for (int i = 0; i < segments.length; i++) {
        int hash = segments[i].indexOf('#');
        elements[i] = hash < 0 ? segments[i] : segments[i].substring(0, hash);
      }
      return new Path(segments, elements);
    }
  }

  /** Counts what goes through; the one place where the byte count is defined. */
  private static final class CountingStream extends OutputStream {
    private final OutputStream target;
    private long count;

    CountingStream(OutputStream target) {
      this.target = target;
    }

    @Override
    public void write(int b) throws IOException {
      target.write(b);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      target.write(bytes, offset, length);
      count += length;
    }

    @Override
    public void flush() throws IOException {
      target.flush();
    }
  }
}
