package dev.regpack.spikes.x.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;

class StaxRecordWriterTest {
  private static final String NAMESPACE = "urn:regpack:spike:synthetic:txn:1";
  private static final String TRICKY = "a & b < c > d \"q\" é 😀 end";

  private static XMLStreamReader reader(byte[] bytes) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
  }

  /**
   * Start elements as {@code name} and text as {@code =text}, attributes as {@code @name=value}.
   */
  private static List<String> events(byte[] bytes) throws XMLStreamException {
    XMLStreamReader in = reader(bytes);
    List<String> out = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    while (in.hasNext()) {
      int event = in.next();
      if (event == XMLStreamConstants.CHARACTERS) {
        text.append(in.getText());
        continue;
      }
      if (text.length() > 0) {
        out.add("=" + text);
        text.setLength(0);
      }
      if (event == XMLStreamConstants.START_ELEMENT) {
        out.add(in.getLocalName());
        for (int i = 0; i < in.getAttributeCount(); i++) {
          out.add("@" + in.getAttributeLocalName(i) + "=" + in.getAttributeValue(i));
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        out.add("/" + in.getLocalName());
      }
    }
    return out;
  }

  @Test
  void outputIsWellFormedUtf8WithEscaping() throws XMLStreamException {
    TxModel model =
        new TxModel(
            List.of("block/first", "block/second", "block/third"),
            List.of(TRICKY, "plain", "x"),
            List.of("", "note=" + TRICKY, ""));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.startDocument("m", 0L);
    writer.writeTx(model);
    writer.endDocument();
    byte[] out = bytes.toByteArray();
    XMLStreamReader in = reader(out);
    assertThat(in.getEncoding()).isEqualToIgnoringCase("UTF-8");
    assertThat(events(out))
        .contains("first", "=" + TRICKY, "second", "@note=" + TRICKY, "=plain", "third", "=x");
    // the accented letter really is UTF-8 on the wire, not a character reference
    assertThat(new String(out, java.nio.charset.StandardCharsets.UTF_8)).contains("é");
  }

  @Test
  void headerComesBeforeTheFirstTx() throws XMLStreamException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.startDocument("batch-7", 1_000_002L);
    writer.writeTx(new TxModel(List.of("a/b"), List.of("1"), List.of("")));
    writer.endDocument();
    assertThat(events(bytes.toByteArray()))
        .containsExactly(
            "transaction_file",
            "file_header",
            "message_id",
            "=batch-7",
            "/message_id",
            "created_at",
            "=1970-01-01T00:00:01.000002Z",
            "/created_at",
            "/file_header",
            "transactions",
            "transaction_record",
            "a",
            "b",
            "=1",
            "/b",
            "/a",
            "/transaction_record",
            "/transactions",
            "/transaction_file");
    XMLStreamReader in = reader(bytes.toByteArray());
    in.nextTag();
    assertThat(in.getNamespaceURI()).isEqualTo(NAMESPACE);
  }

  @Test
  void creationTimeHasSixFractionalDigitsAlsoBeforeTheEpoch() throws XMLStreamException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.startDocument("m", -1L);
    writer.endDocument();
    assertThat(events(bytes.toByteArray())).contains("=1969-12-31T23:59:59.999999Z");
    bytes.reset();
    writer = new StaxRecordWriter(bytes);
    writer.startDocument("m", 1_700_000_000_000_000L);
    writer.endDocument();
    assertThat(events(bytes.toByteArray())).contains("=2023-11-14T22:13:20.000000Z");
  }

  @Test
  void repeatedGroupsBecomeSeparateElementsAndSiblingsShareTheirParent() throws XMLStreamException {
    TxModel model =
        new TxModel(
            List.of("g/alias#1/x", "g/alias#1/y", "g/alias#2/x", "g/other"),
            List.of("1", "2", "3", "4"),
            List.of("", "", "", ""));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.writeTx(model);
    assertThat(events(bytes.toByteArray()).stream().filter(e -> !e.startsWith("@")))
        .containsExactly(
            "transaction_record",
            "g",
            "alias",
            "x",
            "=1",
            "/x",
            "y",
            "=2",
            "/y",
            "/alias",
            "alias",
            "x",
            "=3",
            "/x",
            "/alias",
            "other",
            "=4",
            "/other",
            "/g",
            "/transaction_record");
  }

  @Test
  void aRecordWithoutADocumentDeclaresTheNamespaceItself() throws XMLStreamException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.writeTx(new TxModel(List.of("a"), List.of("v"), List.of("")));
    XMLStreamReader in = reader(bytes.toByteArray());
    in.nextTag();
    assertThat(in.getLocalName()).isEqualTo("transaction_record");
    assertThat(in.getNamespaceURI()).isEqualTo(NAMESPACE);
  }

  @Test
  void byteCountMatchesTheCountingStream() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    assertThat(writer.bytesWritten()).isZero();
    writer.startDocument("m", 5L);
    TxModel model = new TxModel(List.of("a/b"), List.of(TRICKY), List.of(""));
    for (int i = 0; i < 50; i++) {
      writer.writeTx(model);
    }
    assertThat(writer.bytesWritten()).as("mid-stream").isEqualTo(bytes.size()).isPositive();
    writer.endDocument();
    assertThat(writer.bytesWritten()).isEqualTo(bytes.size());
  }

  @Test
  void flushPutsTheWholeRecordIntoTheStreamAndIsHarmlessAfterTheEnd() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.startDocument("m", 5L);
    writer.writeTx(new TxModel(List.of("a/b"), List.of("v"), List.of("")));
    writer.flush();
    assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
        .as("the record is complete in the stream after the flush")
        .endsWith("</transaction_record>");
    writer.endDocument();
    int size = bytes.size();
    writer.flush();
    assertThat(bytes.size()).isEqualTo(size);
  }

  @Test
  void modelWithMismatchedListsIsRejected() {
    assertThatThrownBy(() -> new TxModel(List.of("a", "b"), List.of("1"), List.of("", "")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TxModel(List.of("a"), List.of("1"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
