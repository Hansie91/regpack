package dev.regpack.spikes.l.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Installs the ledger sketch ({@code ledger-sketch.sql}) into a schema of a connection and creates
 * the daily partitions of {@code decision} and {@code ingest}. The sketch is disposable; the
 * installer exists so that every SP-L study and constraint check starts from the same tables.
 *
 * <p>Each statement runs in the connection's current commit mode. With auto-commit on, every
 * statement is its own transaction on purpose: one transaction that creates or drops thousands of
 * partitions would need more locks than {@code max_locks_per_transaction} gives.
 */
public final class SchemaInstaller {
  /** E1 is the compact encoding (small integers, bytea); E3 adds {@code path jsonb} to decision. */
  public enum Variant {
    E1_COMPACT,
    E3_VERBOSE
  }

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
  private static final String SKETCH = "/ledger-sketch.sql";
  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

  /**
   * The tables that inherit from another relation in a schema. {@code pg_inherits} also records
   * index inheritance (each partition index is a child of the partitioned index), so the listing is
   * restricted to ordinary and partitioned tables; an index would be gone with its table, and
   * {@code DROP TABLE} on an index fails.
   */
  static final String DROP_CHILDREN_SQL =
      "SELECT c.relname FROM pg_inherits i"
          + " JOIN pg_class c ON c.oid = i.inhrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = ? AND c.relkind IN ('r', 'p') ORDER BY c.relname";

  private SchemaInstaller() {}

  /**
   * Creates {@code schema}, makes it the search path of the connection (so unqualified names
   * resolve to it; the caller resets the search path or closes the connection), creates the sketch
   * and {@code days} daily partitions of {@code decision} (by {@code as_at}) and of {@code ingest}
   * (by {@code trade_date}) from {@code firstDay}.
   *
   * @throws IllegalArgumentException if the schema name is not a lower-case identifier or {@code
   *     days} is not positive
   */
  public static void install(
      Connection c, String schema, Variant variant, LocalDate firstDay, int days)
      throws SQLException {
    List<String> partitions = new ArrayList<>(partitionStatements("decision", firstDay, days));
    partitions.addAll(partitionStatements("ingest", firstDay, days));
    installTables(c, schema, variant);
    try (Statement st = c.createStatement()) {
      for (String statement : partitions) {
        st.execute(statement);
      }
    }
  }

  /**
   * Creates {@code schema} and the tables of the sketch, with no partition of {@code decision} or
   * {@code ingest} (the partition study creates its own), and makes the schema the search path of
   * the connection. Everything else is as for {@link #install}.
   *
   * @throws IllegalArgumentException if the schema name is not a lower-case identifier
   */
  public static void installTables(Connection c, String schema, Variant variant)
      throws SQLException {
    String quoted = quote(schema);
    try (Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA " + quoted);
      st.execute("SET search_path TO " + quoted);
      for (String statement : statements(sketch())) {
        st.execute(statement);
      }
      if (variant == Variant.E3_VERBOSE) {
        st.execute("ALTER TABLE decision ADD COLUMN path jsonb");
      }
    }
  }

  /**
   * Drops the schema with everything in it. Partitions are dropped one statement at a time first,
   * so that a schema with thousands of partitions does not need thousands of locks in one
   * transaction. Resets the search path of the connection.
   */
  public static void drop(Connection c, String schema) throws SQLException {
    String quoted = quote(schema);
    List<String> children = new ArrayList<>();
    try (Statement st = c.createStatement()) {
      st.execute("RESET search_path");
      try (PreparedStatement ps = c.prepareStatement(DROP_CHILDREN_SQL)) {
        ps.setString(1, schema);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            children.add(rs.getString(1));
          }
        }
      }
      for (String child : children) {
        st.execute(dropChildStatement(quoted, child));
      }
      st.execute("DROP SCHEMA IF EXISTS " + quoted + " CASCADE");
    }
  }

  /**
   * The schema name in double quotes; only lower-case identifiers of at most 63 characters pass.
   */
  static String quote(String schema) {
    if (!IDENTIFIER.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "a schema name must match [a-z][a-z0-9_]{0,62}: '" + schema + "'");
    }
    return "\"" + schema + "\"";
  }

  /** Drops one child table; {@code IF EXISTS} because dropping a parent can take its children. */
  static String dropChildStatement(String quotedSchema, String child) {
    return "DROP TABLE IF EXISTS " + quotedSchema + "." + quoteIdentifier(child);
  }

  private static String quoteIdentifier(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /**
   * Splits the text of a SQL file into statements: a statement ends at a line that ends with a
   * semicolon; lines that start with two hyphens are comments; blank lines between statements are
   * dropped. The statement keeps its inner line breaks and indentation.
   *
   * @throws IllegalArgumentException if the text ends inside a statement
   */
  static List<String> statements(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : sql.split("\n", -1)) {
      String stripped = line.strip();
      if (stripped.startsWith("--") || (stripped.isEmpty() && current.isEmpty())) {
        continue;
      }
      if (stripped.endsWith(";")) {
        int semicolon = line.lastIndexOf(';');
        current.append(line, 0, semicolon);
        out.add(current.toString());
        current.setLength(0);
      } else {
        current.append(line).append('\n');
      }
    }
    if (!current.toString().isBlank()) {
      throw new IllegalArgumentException("unterminated statement: " + current.toString().strip());
    }
    return List.copyOf(out);
  }

  /**
   * The statements that create {@code days} contiguous daily partitions of {@code table} from
   * {@code first}, named {@code <table>_YYYYMMDD}.
   */
  static List<String> partitionStatements(String table, LocalDate first, int days) {
    if (days <= 0) {
      throw new IllegalArgumentException("the number of partitions must be positive: " + days);
    }
    List<String> out = new ArrayList<>(days);
    for (int i = 0; i < days; i++) {
      LocalDate from = first.plusDays(i);
      LocalDate to = from.plusDays(1);
      out.add(
          "CREATE TABLE "
              + table
              + "_"
              + DAY.format(from)
              + " PARTITION OF "
              + table
              + " FOR VALUES FROM ('"
              + from
              + "') TO ('"
              + to
              + "')");
    }
    return List.copyOf(out);
  }

  private static String sketch() {
    try (InputStream in = SchemaInstaller.class.getResourceAsStream(SKETCH)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + SKETCH);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
