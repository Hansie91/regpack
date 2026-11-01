package dev.regpack.flow.ingest;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A strict reader of comma separated text (RFC 4180 in its plain form): UTF-8 without a byte order
 * mark, a comma between cells, a line feed or a carriage return and line feed between rows, a cell
 * quoted with {@code "} when it holds a comma, a quote or a line end, and a quote inside a quoted
 * cell written twice. Row 1 is the header. Anything else is refused with a {@link CsvException}
 * that names a code, a line and a column and never a cell value: a quote inside an unquoted cell,
 * text after a closing quote, a carriage return that does not start a line end, a row with another
 * number of cells than the header (an empty line is such a row, it is not skipped), a header name
 * that occurs twice, and a limit exceeded (SF4, SF9).
 *
 * <p>The caller checks the size of the file before it reads it; the limits here bound the rows, the
 * columns and the cells of whatever was read.
 */
public final class CsvReader {
  /**
   * One data row.
   *
   * @param line the 1-based physical line on which the row starts
   * @param cells the cells, as many as the header has
   */
  public record Row(int line, List<String> cells) {
    public Row {
      cells = List.copyOf(cells);
    }
  }

  /**
   * A read file.
   *
   * @param header the column names, unique
   * @param rows the data rows in file order
   */
  public record Table(List<String> header, List<Row> rows) {
    public Table {
      header = List.copyOf(header);
      rows = List.copyOf(rows);
    }
  }

  private CsvReader() {}

  /**
   * Reads {@code bytes} as a table.
   *
   * @param maxCellChars the longest cell in UTF-16 characters
   * @param maxRows the most data rows (the header is not counted)
   * @param maxColumns the most columns
   * @throws CsvException when the bytes are not a table of this form or exceed a limit
   */
  public static Table read(byte[] bytes, int maxCellChars, int maxRows, int maxColumns) {
    Objects.requireNonNull(bytes, "bytes");
    if (maxCellChars < 1 || maxRows < 0 || maxColumns < 1) {
      throw new IllegalArgumentException("CSV limits must be positive");
    }
    if (bytes.length == 0) {
      throw new CsvException(CsvException.EMPTY_FILE, 1, 0);
    }
    if (bytes.length >= 3
        && bytes[0] == (byte) 0xEF
        && bytes[1] == (byte) 0xBB
        && bytes[2] == (byte) 0xBF) {
      throw new CsvException(CsvException.BOM, 1, 1);
    }
    return new Parse(decode(bytes), maxCellChars, maxRows, maxColumns).table();
  }

  private static String decode(byte[] bytes) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(bytes);
    CharBuffer out = CharBuffer.allocate(bytes.length);
    CoderResult result = decoder.decode(in, out, true);
    if (result.isError()) {
      throw new CsvException(CsvException.BAD_ENCODING, lineAt(bytes, in.position()), 0);
    }
    result = decoder.flush(out);
    if (result.isError() || !result.isUnderflow()) {
      throw new CsvException(CsvException.BAD_ENCODING, lineAt(bytes, bytes.length), 0);
    }
    out.flip();
    return out.toString();
  }

  /** The 1-based line of the byte at {@code position}. */
  private static int lineAt(byte[] bytes, int position) {
    int line = 1;
    for (int i = 0; i < position && i < bytes.length; i++) {
      if (bytes[i] == '\n') {
        line++;
      }
    }
    return line;
  }

  /** One pass over the decoded text. */
  private static final class Parse {
    private final String text;
    private final int maxCellChars;
    private final int maxRows;
    private final int maxColumns;
    private int pos;
    private int line = 1;

    Parse(String text, int maxCellChars, int maxRows, int maxColumns) {
      this.text = text;
      this.maxCellChars = maxCellChars;
      this.maxRows = maxRows;
      this.maxColumns = maxColumns;
    }

    /** The text is not empty (the caller refuses an empty file): its first record is the header. */
    Table table() {
      List<String> header = checkedHeader(record(true));
      List<Row> rows = new ArrayList<>();
      while (pos < text.length()) {
        int rowLine = line;
        if (rows.size() >= maxRows) {
          throw new CsvException(CsvException.TOO_MANY_ROWS, rowLine, 0);
        }
        List<String> cells = record(false);
        if (cells.size() != header.size()) {
          throw new CsvException(CsvException.RAGGED_ROW, rowLine, cells.size());
        }
        rows.add(new Row(rowLine, cells));
      }
      return new Table(header, rows);
    }

    private static List<String> checkedHeader(List<String> cells) {
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < cells.size(); i++) {
        if (!seen.add(cells.get(i))) {
          throw new CsvException(CsvException.DUPLICATE_COLUMN, 1, i + 1);
        }
      }
      return cells;
    }

    /** One record: its cells, up to and including its line end. */
    private List<String> record(boolean isHeader) {
      List<String> cells = new ArrayList<>();
      while (true) {
        if (isHeader && cells.size() >= maxColumns) {
          throw new CsvException(CsvException.TOO_MANY_COLUMNS, line, cells.size() + 1);
        }
        cells.add(cell(cells.size() + 1));
        if (pos >= text.length()) {
          return cells;
        }
        char c = text.charAt(pos);
        if (c == ',') {
          pos++;
          continue;
        }
        if (c == '\n') {
          pos++;
          line++;
          return cells;
        }
        // c is a carriage return followed by a line feed: cell() stops at no other character
        pos += 2;
        line++;
        return cells;
      }
    }

    /** One cell; stops before the comma or line end that follows it, or at the end of the text. */
    private String cell(int column) {
      if (pos < text.length() && text.charAt(pos) == '"') {
        return quoted(column);
      }
      StringBuilder out = new StringBuilder();
      while (pos < text.length()) {
        char c = text.charAt(pos);
        if (c == ',' || c == '\n') {
          break;
        }
        if (c == '\r') {
          if (pos + 1 < text.length() && text.charAt(pos + 1) == '\n') {
            break;
          }
          throw new CsvException(CsvException.BAD_QUOTE, line, column);
        }
        if (c == '"') {
          throw new CsvException(CsvException.BAD_QUOTE, line, column);
        }
        out.append(c);
        pos++;
        if (out.length() > maxCellChars) {
          throw new CsvException(CsvException.TOO_LONG_CELL, line, column);
        }
      }
      return out.toString();
    }

    private String quoted(int column) {
      int openLine = line;
      pos++; // the opening quote
      StringBuilder out = new StringBuilder();
      while (true) {
        if (pos >= text.length()) {
          throw new CsvException(CsvException.UNTERMINATED_QUOTE, openLine, column);
        }
        char c = text.charAt(pos);
        if (c == '"') {
          if (pos + 1 < text.length() && text.charAt(pos + 1) == '"') {
            out.append('"');
            pos += 2;
          } else {
            pos++;
            break;
          }
        } else {
          if (c == '\n') {
            line++;
          }
          out.append(c);
          pos++;
        }
        if (out.length() > maxCellChars) {
          throw new CsvException(CsvException.TOO_LONG_CELL, openLine, column);
        }
      }
      if (pos < text.length()) {
        char c = text.charAt(pos);
        boolean lineEnd = c == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n';
        if (c != ',' && c != '\n' && !lineEnd) {
          throw new CsvException(CsvException.BAD_QUOTE, line, column);
        }
      }
      return out.toString();
    }
  }
}
