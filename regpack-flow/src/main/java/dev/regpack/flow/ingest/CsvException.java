package dev.regpack.flow.ingest;

import java.util.Objects;

/**
 * A CSV file is refused. It carries a code, a 1-based line and a column, and nothing of the file:
 * no cell value, no excerpt, no text of a library (SF4, SF8). The column is the 1-based number of
 * the cell where that is meaningful, the number of cells found for a {@link #RAGGED_ROW}, and 0
 * where it is not.
 */
public final class CsvException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The file starts with a byte order mark. */
  public static final String BOM = "BOM";

  /** The bytes are not UTF-8. */
  public static final String BAD_ENCODING = "BAD_ENCODING";

  /** A quote opens and never closes; the line is the line where it opened. */
  public static final String UNTERMINATED_QUOTE = "UNTERMINATED_QUOTE";

  /** A quote inside an unquoted cell, text after a closing quote, or a lone carriage return. */
  public static final String BAD_QUOTE = "BAD_QUOTE";

  /** A cell is longer than the limit. */
  public static final String TOO_LONG_CELL = "TOO_LONG_CELL";

  /** The file has more data rows than the limit. */
  public static final String TOO_MANY_ROWS = "TOO_MANY_ROWS";

  /** The header has more columns than the limit. */
  public static final String TOO_MANY_COLUMNS = "TOO_MANY_COLUMNS";

  /** Two header cells have the same name. */
  public static final String DUPLICATE_COLUMN = "DUPLICATE_COLUMN";

  /** A row has another number of cells than the header. */
  public static final String RAGGED_ROW = "RAGGED_ROW";

  /** The file has no bytes. */
  public static final String EMPTY_FILE = "EMPTY_FILE";

  private final String code;
  private final int line;
  private final int column;

  public CsvException(String code, int line, int column) {
    super(
        "CSV refused: "
            + Objects.requireNonNull(code, "code")
            + " at line "
            + line
            + ", column "
            + column);
    this.code = code;
    this.line = line;
    this.column = column;
  }

  public String code() {
    return code;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }
}
