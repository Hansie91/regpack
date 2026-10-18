package dev.regpack.spikes.l.load;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import org.postgresql.PGConnection;

/**
 * Loads rows into one table inside the open transaction of a connection: either with {@code COPY
 * ... FROM STDIN} or with batched {@code INSERT} statements (the driver rewrites them into
 * multi-row inserts when {@code reWriteBatchedInserts} is set, which {@code PgFixture} does).
 */
interface BulkLoader {
  /** Loads the rows; {@code table} is already qualified and quoted. */
  void load(String table, List<String> columns, List<Object[]> rows) throws SQLException;

  /** {@code INSERT INTO table (a, b) VALUES (?, ?)}. */
  static String insertSql(String table, List<String> columns) {
    return "INSERT INTO "
        + table
        + " ("
        + String.join(", ", columns)
        + ") VALUES ("
        + String.join(", ", columns.stream().map(c -> "?").toList())
        + ")";
  }

  /** The COPY protocol of the driver. */
  static BulkLoader copy(Connection connection) {
    return (table, columns, rows) -> {
      StringBuilder text = new StringBuilder(rows.size() * 96);
      for (Object[] row : rows) {
        text.append(CopyText.line(row));
      }
      try {
        PGConnection pg = connection.unwrap(PGConnection.class);
        pg.getCopyAPI().copyIn(CopyText.copySql(table, columns), new StringReader(text.toString()));
      } catch (IOException e) {
        throw new SQLException("COPY into " + table + " failed", e);
      }
    };
  }

  /** One prepared INSERT per table and call, executed as a batch. */
  static BulkLoader batchedInsert(Connection connection) {
    return (table, columns, rows) -> {
      try (PreparedStatement ps = connection.prepareStatement(insertSql(table, columns))) {
        for (Object[] row : rows) {
          for (int i = 0; i < row.length; i++) {
            bind(ps, i + 1, row[i]);
          }
          ps.addBatch();
        }
        ps.executeBatch();
      }
    };
  }

  private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
    switch (value) {
      case null -> ps.setNull(index, Types.NULL);
      case Long v -> ps.setLong(index, v);
      case Integer v -> ps.setInt(index, v);
      case Short v -> ps.setShort(index, v);
      case Boolean v -> ps.setBoolean(index, v);
      case LocalDate v -> ps.setObject(index, v);
      case String v -> ps.setString(index, v);
      case MicroBatchWriter.Jsonb v -> ps.setObject(index, v.text(), Types.OTHER);
      case byte[] v -> ps.setBytes(index, v);
      default ->
          throw new IllegalArgumentException(
              "cannot bind a value of type " + value.getClass().getName());
    }
  }
}
