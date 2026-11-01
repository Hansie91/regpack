package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.ingest.CsvReader.Row;
import dev.regpack.flow.ingest.CsvReader.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {
  private static final int CELL = 256;
  private static final int ROWS = 10_000;
  private static final int COLUMNS = 64;

  private static Table read(String text) {
    return CsvReader.read(text.getBytes(UTF_8), CELL, ROWS, COLUMNS);
  }

  private static CsvException refused(byte[] bytes, int maxCell, int maxRows, int maxColumns) {
    try {
      CsvReader.read(bytes, maxCell, maxRows, maxColumns);
    } catch (CsvException e) {
      return e;
    }
    throw new AssertionError("the file was accepted");
  }

  private static CsvException refused(String text) {
    return refused(text.getBytes(UTF_8), CELL, ROWS, COLUMNS);
  }

  @Test
  void readsAHeaderAndRowsWithTheirLines() {
    Table table = read("a,b,c\n1,2,3\n4,5,6\n");
    assertThat(table.header()).containsExactly("a", "b", "c");
    assertThat(table.rows())
        .containsExactly(new Row(2, List.of("1", "2", "3")), new Row(3, List.of("4", "5", "6")));
  }

  @Test
  void aQuotedCommaADoubledQuoteAndAnEmbeddedNewlineAreCellContent() {
    Table table = read("a,b,c\n\"x,1\",\"he said \"\"hi\"\"\",\"l1\nl2\"\nnext,2,3\n");
    assertThat(table.rows().get(0).cells()).containsExactly("x,1", "he said \"hi\"", "l1\nl2");
    // the second row starts on physical line 4: the embedded newline is counted
    assertThat(table.rows().get(1).line()).isEqualTo(4);
    assertThat(table.rows().get(1).cells()).containsExactly("next", "2", "3");
  }

  @Test
  void crlfAndLfGiveTheSameTable() {
    assertThat(read("a,b\r\n1,2\r\n3,4\r\n")).isEqualTo(read("a,b\n1,2\n3,4\n"));
  }

  @Test
  void aQuotedCellKeepsItsCrlfAndTheLastLineMayLackItsLineEnd() {
    Table table = read("a,b\r\n\"x\r\ny\",2");
    assertThat(table.rows()).hasSize(1);
    assertThat(table.rows().get(0).cells()).containsExactly("x\r\ny", "2");
  }

  @Test
  void emptyCellsAreEmptyStringsAndAHeaderOnlyFileHasNoRows() {
    assertThat(read("a,b,c\n1,,\n").rows().get(0).cells()).containsExactly("1", "", "");
    assertThat(read("a,b,c\n").rows()).isEmpty();
  }

  @Test
  void nonAsciiTextIsReadAsUtf8() {
    Table table = read("a\nä€😀\n");
    assertThat(table.rows().get(0).cells()).containsExactly("ä€😀");
  }

  @Test
  void aByteOrderMarkIsRefused() {
    byte[] bytes = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '\n', '1', '\n'};
    CsvException e = refused(bytes, CELL, ROWS, COLUMNS);
    assertThat(e.code()).isEqualTo("BOM");
    assertThat(e.line()).isEqualTo(1);
  }

  @Test
  void invalidUtf8IsRefusedWithItsLine() {
    byte[] bytes = new byte[] {'a', ',', 'b', '\n', '1', ',', (byte) 0xC3, (byte) 0x28, '\n'};
    CsvException e = refused(bytes, CELL, ROWS, COLUMNS);
    assertThat(e.code()).isEqualTo("BAD_ENCODING");
    assertThat(e.line()).isEqualTo(2);
    byte[] later = new byte[] {'a', '\n', '1', '\n', '2', '\n', (byte) 0xFF, '\n'};
    assertThat(refused(later, CELL, ROWS, COLUMNS).line()).isEqualTo(4);
  }

  @Test
  void aRaggedRowNamesItsLineAndItsColumnCount() {
    CsvException e = refused("a,b,c\n1,2,3\n4,5\n");
    assertThat(e.code()).isEqualTo("RAGGED_ROW");
    assertThat(e.line()).isEqualTo(3);
    assertThat(e.column()).isEqualTo(2);
    assertThat(refused("a,b\n1,2,3\n").column()).isEqualTo(3);
  }

  @Test
  void aBlankLineIsARaggedRowNotSilentlySkipped() {
    CsvException e = refused("a,b\n1,2\n\n3,4\n");
    assertThat(e.code()).isEqualTo("RAGGED_ROW");
    assertThat(e.line()).isEqualTo(3);
  }

  @Test
  void anUnterminatedQuoteNamesTheLineWhereItOpened() {
    CsvException e = refused("a,b\n1,2\n3,\"never closed\n4,5\n");
    assertThat(e.code()).isEqualTo("UNTERMINATED_QUOTE");
    assertThat(e.line()).isEqualTo(3);
    assertThat(e.column()).isEqualTo(2);
  }

  @Test
  void aQuoteInsideAnUnquotedCellOrTextAfterAClosingQuoteIsABadQuote() {
    CsvException inside = refused("a,b\n1,x\"y\n");
    assertThat(inside.code()).isEqualTo("BAD_QUOTE");
    assertThat(inside.line()).isEqualTo(2);
    assertThat(inside.column()).isEqualTo(2);
    assertThat(refused("a,b\n1,\"x\"y\n").code()).isEqualTo("BAD_QUOTE");
    // a carriage return that does not start a CRLF is no line end
    assertThat(refused("a,b\n1,2\r3,4\n").code()).isEqualTo("BAD_QUOTE");
  }

  @Test
  void aCellOverTheLimitIsRefusedAtThatCellAndTheLimitItselfIsAccepted() {
    byte[] bytes = "a,b\n1,123456\n".getBytes(UTF_8);
    CsvException e = refused(bytes, 5, ROWS, COLUMNS);
    assertThat(e.code()).isEqualTo("TOO_LONG_CELL");
    assertThat(e.line()).isEqualTo(2);
    assertThat(e.column()).isEqualTo(2);
    assertThat(CsvReader.read("a,b\n1,12345\n".getBytes(UTF_8), 5, ROWS, COLUMNS).rows())
        .hasSize(1);
    // a quoted cell counts its content, not its quotes
    assertThat(CsvReader.read("a\n\"12345\"\n".getBytes(UTF_8), 5, ROWS, COLUMNS).rows())
        .hasSize(1);
    assertThat(refused("a\n\"123456\"\n".getBytes(UTF_8), 5, ROWS, COLUMNS).code())
        .isEqualTo("TOO_LONG_CELL");
  }

  @Test
  void anEmptyFileIsRefused() {
    assertThat(refused(new byte[0], CELL, ROWS, COLUMNS).code()).isEqualTo("EMPTY_FILE");
  }

  @Test
  void tooManyRowsAndTooManyColumnsAreRefusedAndTheLimitsThemselvesAreAccepted() {
    byte[] threeRows = "a\n1\n2\n3\n".getBytes(UTF_8);
    CsvException rows = refused(threeRows, CELL, 2, COLUMNS);
    assertThat(rows.code()).isEqualTo("TOO_MANY_ROWS");
    assertThat(rows.line()).isEqualTo(4);
    assertThat(CsvReader.read(threeRows, CELL, 3, COLUMNS).rows()).hasSize(3);
    byte[] threeColumns = "a,b,c\n1,2,3\n".getBytes(UTF_8);
    CsvException columns = refused(threeColumns, CELL, ROWS, 2);
    assertThat(columns.code()).isEqualTo("TOO_MANY_COLUMNS");
    assertThat(columns.line()).isEqualTo(1);
    assertThat(columns.column()).isEqualTo(3);
    assertThat(CsvReader.read(threeColumns, CELL, ROWS, 3).header()).hasSize(3);
  }

  @Test
  void aDuplicateHeaderNameIsRefusedAtTheSecondOccurrence() {
    CsvException e = refused("a,b,a\n1,2,3\n");
    assertThat(e.code()).isEqualTo("DUPLICATE_COLUMN");
    assertThat(e.line()).isEqualTo(1);
    assertThat(e.column()).isEqualTo(3);
  }

  @Test
  void noMessageHoldsACellValue() {
    String secret = "SECRET-CELL-VALUE";
    List<byte[]> bad =
        List.of(
            ("a,b\n" + secret + "\n").getBytes(UTF_8),
            ("a,b\n1,\"" + secret + "\n").getBytes(UTF_8),
            ("a,b\n1,x\"" + secret + "\n").getBytes(UTF_8),
            ("a,b\n1,\"x\"" + secret + "\n").getBytes(UTF_8),
            ("a,b\n1," + secret + secret + secret + "\n").getBytes(UTF_8),
            ("a,b\n1," + secret + "\r2,3\n").getBytes(UTF_8),
            (secret + "," + secret + "\n1,2\n").getBytes(UTF_8),
            ("a\n" + secret + "\n" + secret + "\n" + secret + "\n").getBytes(UTF_8),
            ("a,b,c,d,e,f,g,h,i,j\n" + secret + ",2\n").getBytes(UTF_8));
    for (byte[] bytes : bad) {
      assertThatThrownBy(() -> CsvReader.read(bytes, 20, 2, 8))
          .isInstanceOf(CsvException.class)
          .satisfies(
              t -> {
                assertThat(t.getMessage()).doesNotContain("SECRET");
                assertThat(t.toString()).doesNotContain("SECRET");
                assertThat(t.getCause()).isNull();
              });
    }
    byte[] badEncoding = ("a\n" + secret + "\n").getBytes(UTF_8);
    badEncoding[3] = (byte) 0xFF;
    assertThatThrownBy(() -> CsvReader.read(badEncoding, CELL, ROWS, COLUMNS))
        .isInstanceOf(CsvException.class)
        .satisfies(t -> assertThat(t.getMessage()).doesNotContain("SECRET"));
  }
}
