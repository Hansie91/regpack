package dev.regpack.postgres.mutants;

import dev.regpack.postgres.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * One change of the migrated schema of a schema mutant (T20): a single named constraint or index is
 * dropped. The schema, the table and the object are identifiers checked by {@link SqlIdentifier}
 * (the only text that reaches the statement); the statement is qualified with the schema, so it can
 * never touch another schema. The change runs on a connection with a lock timeout and a statement
 * timeout, so a wait is bounded.
 *
 * @param kind whether the object is a constraint (dropped with its table) or a plain index
 * @param table the table that owns the object
 * @param name the name of the constraint or index, explicit in {@code V001} (Convention 6)
 */
public record SchemaMutation(Kind kind, String table, String name) {
  /** What is dropped. */
  public enum Kind {
    CONSTRAINT,
    INDEX
  }

  private static final String LOCK_TIMEOUT = "SET lock_timeout = '10s'";
  private static final String STATEMENT_TIMEOUT = "SET statement_timeout = '30s'";
  private static final String CONSTRAINT_EXISTS =
      "SELECT count(*) FROM pg_constraint c"
          + " JOIN pg_class t ON t.oid = c.conrelid"
          + " JOIN pg_namespace n ON n.oid = t.relnamespace"
          + " WHERE n.nspname = ? AND t.relname = ? AND c.conname = ?";
  private static final String INDEX_EXISTS =
      "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND tablename = ? AND indexname = ?";

  public SchemaMutation {
    Objects.requireNonNull(kind, "kind");
    table = SqlIdentifier.require(table);
    name = SqlIdentifier.require(name);
  }

  /** Drops the constraint {@code name} of {@code table}, and the index behind it if it has one. */
  public static SchemaMutation dropConstraint(String table, String name) {
    return new SchemaMutation(Kind.CONSTRAINT, table, name);
  }

  /** Drops the plain (partial) unique index {@code name} of {@code table}. */
  public static SchemaMutation dropIndex(String table, String name) {
    return new SchemaMutation(Kind.INDEX, table, name);
  }

  /** The one statement of the mutation, qualified with {@code schema}. */
  public String ddl(String schema) {
    String qualifier = SqlIdentifier.require(schema);
    return switch (kind) {
      case CONSTRAINT -> "ALTER TABLE " + qualifier + "." + table + " DROP CONSTRAINT " + name;
      case INDEX -> "DROP INDEX " + qualifier + "." + name;
    };
  }

  /** Whether {@code schema} holds the named object on the named table (a catalogue lookup). */
  public boolean existsIn(Connection connection, String schema) throws SQLException {
    String sql =
        switch (kind) {
          case CONSTRAINT -> CONSTRAINT_EXISTS;
          case INDEX -> INDEX_EXISTS;
        };
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, SqlIdentifier.require(schema));
      statement.setString(2, table);
      statement.setString(3, name);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1) > 0;
      }
    }
  }

  /**
   * Applies the mutation to {@code schema} on {@code connection} (autocommit, as the connection of
   * a test is): a lock timeout of 10 seconds and a statement timeout of 30 seconds are set on the
   * connection first, so that the change cannot wait for ever behind a leaked transaction.
   */
  public void applyTo(Connection connection, String schema) throws SQLException {
    String statement = ddl(schema);
    try (Statement bounded = connection.createStatement()) {
      bounded.execute(LOCK_TIMEOUT);
      bounded.execute(STATEMENT_TIMEOUT);
      bounded.execute(statement);
    }
  }
}
