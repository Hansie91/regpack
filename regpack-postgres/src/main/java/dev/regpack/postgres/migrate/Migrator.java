package dev.regpack.postgres.migrate;

import dev.regpack.postgres.SqlIdentifier;
import dev.regpack.postgres.migrate.MigrationException.Kind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * The forward-only plain-SQL migrator (D-L7, review focus LF13). Scripts are listed in {@code
 * db/migration/index.txt}; the SHA-256 of every applied script is stored in {@code
 * schema_migration} and compared on every run. The migrator refuses a changed applied script
 * ({@code DRIFT}), stored versions that do not run 1, 2, 3 ... ({@code GAP}) and a database that
 * holds a version the code does not know ({@code NEWER_DATABASE}). Every script runs in one
 * transaction of its own, under the same transaction-level advisory lock that the first transaction
 * takes (Convention 12, purpose 1, keyed by the schema), so concurrent migrators apply each script
 * once and a failed script leaves nothing behind.
 */
public final class Migrator {
  /** The advisory-lock purpose of the migrator (Convention 12). */
  private static final int LOCK_PURPOSE = 1;

  private static final String NO_SCRIPT = "none";

  /** What a call did: the versions it applied, in order, and the version the schema is at. */
  public record Report(List<Integer> applied, int currentVersion) {
    public Report {
      applied = List.copyOf(applied);
    }
  }

  /** One row of {@code schema_migration}. */
  private record Stored(int version, String name, String sha256) {}

  private Migrator() {}

  /**
   * Creates the schema when it is missing, sets the search path to it and applies every script that
   * is not yet applied. Idempotent.
   *
   * @throws MigrationException on drift, a gap, a newer database or a failing script
   */
  public static Report migrate(DataSource dataSource, String schema) {
    return migrate(dataSource, schema, ScriptCatalogue.fromClasspath());
  }

  static Report migrate(DataSource dataSource, String schema, ScriptCatalogue catalogue) {
    String identifier = SqlIdentifier.require(schema);
    List<Integer> applied = new ArrayList<>();
    try (Connection connection = open(dataSource)) {
      prepare(connection, identifier, catalogue);
      for (ScriptCatalogue.Script script : catalogue.scripts()) {
        if (apply(connection, identifier, catalogue, script)) {
          applied.add(script.version());
        }
      }
      // every transaction is committed here, so a pooled connection goes back as it came; after a
      // failure it is not touched: switching autocommit on would commit a transaction left open
      restoreAutoCommitQuietly(connection);
      return new Report(applied, catalogue.latestVersion());
    } catch (SQLException e) {
      // only closing the connection can get here
      throw new MigrationException(Kind.SCRIPT_FAILED, NO_SCRIPT, 0, "sqlstate=" + sqlState(e), e);
    }
  }

  private static Connection open(DataSource dataSource) {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      connection.setAutoCommit(false);
      return connection;
    } catch (SQLException e) {
      closeQuietly(connection);
      throw new MigrationException(
          Kind.SCRIPT_FAILED, NO_SCRIPT, 0, "statement=0 sqlstate=" + sqlState(e), e);
    }
  }

  private static void restoreAutoCommitQuietly(Connection connection) {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException ignored) {
      // the migration result stands; closing the connection follows
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
      // the original failure is the one to report
    }
  }

  /** The first transaction: lock, create the schema, read and check the stored history. */
  private static void prepare(Connection connection, String schema, ScriptCatalogue catalogue) {
    try {
      enter(connection, schema);
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
      }
      setSearchPath(connection, schema);
      verify(readStored(connection), catalogue);
      connection.commit();
    } catch (SQLException e) {
      rollbackQuietly(connection);
      throw new MigrationException(
          Kind.SCRIPT_FAILED, NO_SCRIPT, 0, "statement=0 sqlstate=" + sqlState(e), e);
    } catch (MigrationException e) {
      rollbackQuietly(connection);
      throw e;
    }
  }

  /**
   * Applies one script in its own transaction; returns false when another migrator applied it
   * first. The stored history is read and checked again under the lock.
   */
  private static boolean apply(
      Connection connection,
      String schema,
      ScriptCatalogue catalogue,
      ScriptCatalogue.Script script) {
    int statementNumber = 0;
    try {
      enter(connection, schema);
      setSearchPath(connection, schema);
      Map<Integer, Stored> stored = readStored(connection);
      verify(stored, catalogue);
      if (stored.containsKey(script.version())) {
        connection.commit();
        return false;
      }
      for (String sql : script.statements()) {
        statementNumber++;
        try (Statement statement = connection.createStatement()) {
          statement.execute(sql);
        }
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO schema_migration (version, name, sha256) VALUES (?, ?, ?)")) {
        insert.setInt(1, script.version());
        insert.setString(2, script.name());
        insert.setString(3, script.sha256());
        insert.executeUpdate();
      }
      connection.commit();
      return true;
    } catch (SQLException e) {
      rollbackQuietly(connection);
      throw new MigrationException(
          Kind.SCRIPT_FAILED,
          script.name(),
          script.version(),
          "statement=" + statementNumber + " sqlstate=" + sqlState(e),
          e);
    } catch (MigrationException e) {
      rollbackQuietly(connection);
      throw e;
    }
  }

  /** The advisory lock, held to the end of the transaction. The schema name is a bind value. */
  private static void enter(Connection connection, String schema) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
      lock.setInt(1, LOCK_PURPOSE);
      lock.setString(2, schema);
      lock.execute();
    }
  }

  private static void setSearchPath(Connection connection, String schema) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL search_path = " + schema);
    }
  }

  /** The stored history by version; empty while {@code schema_migration} does not exist yet. */
  private static Map<Integer, Stored> readStored(Connection connection) throws SQLException {
    boolean present;
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT to_regclass('schema_migration') IS NOT NULL")) {
      present = rows.next() && rows.getBoolean(1);
    }
    Map<Integer, Stored> stored = new TreeMap<>();
    if (!present) {
      return stored;
    }
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT version, name, sha256 FROM schema_migration")) {
      while (rows.next()) {
        stored.put(
            rows.getInt(1), new Stored(rows.getInt(1), rows.getString(2), rows.getString(3)));
      }
    }
    return stored;
  }

  /** Gap first, then a newer database, then drift; each refusal names the version concerned. */
  private static void verify(Map<Integer, Stored> stored, ScriptCatalogue catalogue) {
    int expected = 1;
    for (int version : stored.keySet()) {
      if (version != expected) {
        throw new MigrationException(Kind.GAP, NO_SCRIPT, expected);
      }
      expected++;
    }
    for (Stored row : stored.values()) {
      if (catalogue.find(row.version()).isEmpty()) {
        throw new MigrationException(Kind.NEWER_DATABASE, NO_SCRIPT, row.version());
      }
    }
    for (Stored row : stored.values()) {
      ScriptCatalogue.Script script = catalogue.find(row.version()).orElseThrow();
      if (!script.sha256().equals(row.sha256())) {
        throw new MigrationException(Kind.DRIFT, script.name(), script.version());
      }
    }
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // the original failure is the one to report; closing the connection ends the transaction
    }
  }

  private static String sqlState(SQLException e) {
    String state = e.getSQLState();
    return state == null ? "none" : state;
  }
}
