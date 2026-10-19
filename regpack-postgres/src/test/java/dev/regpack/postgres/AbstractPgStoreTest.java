package dev.regpack.postgres;

import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.postgres.migrate.Migrator;
import dev.regpack.testkit.clock.ManualClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The base of every real-store test that works through the adapter (Convention 4): a fresh schema,
 * migrated to the current version and dropped afterwards, a {@link ManualClock} and a {@link
 * PgLedgerStore} over them. Without a server (or with an unreachable one) every test of a subclass
 * is skipped by the assumption in {@link PgTestDatabase#dataSource()}. The class is public so that
 * the schema mutants of T20 (package {@code mutants}) can extend it.
 */
public abstract class AbstractPgStoreTest {
  private DataSource dataSource;
  private String schema;
  private ManualClock clock;
  private PgLedgerStore store;

  @BeforeEach
  void createFreshSchema() {
    dataSource = PgTestDatabase.dataSource();
    schema = PgTestDatabase.freshSchema();
    Migrator.migrate(dataSource, schema);
    clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
    store = new PgLedgerStore(dataSource, clock, Config.forSchema(schema));
  }

  @AfterEach
  void dropFreshSchema() {
    if (schema != null) {
      PgTestDatabase.dropSchema(schema);
      schema = null;
    }
  }

  /** The data source of the server under test. */
  protected final DataSource dataSource() {
    return dataSource;
  }

  /** The schema of the running test, already migrated. */
  protected final String schema() {
    return schema;
  }

  /** The clock of the store under test; it starts at 2026-10-09T08:00:00Z. */
  public final ManualClock clock() {
    return clock;
  }

  /** The adapter under test, over {@link #dataSource()}, {@link #clock()} and {@link #schema()}. */
  public final PgLedgerStore store() {
    return store;
  }

  /** A connection for assertions, in autocommit, with the search path on the test schema. */
  protected final Connection raw() throws SQLException {
    Connection connection = dataSource.getConnection();
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path = " + SqlIdentifier.require(schema));
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
    return connection;
  }

  /** The first column of the first row of a query without parameters, as a number. */
  protected final long count(String sql) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw new IllegalStateException("the query returned no row");
      }
      return rows.getLong(1);
    }
  }

  /** The first column of every row of a query with one text parameter, as text. */
  protected final List<String> strings(String sql, String parameter) throws SQLException {
    try (Connection connection = raw();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        List<String> values = new ArrayList<>();
        while (rows.next()) {
          values.add(rows.getString(1));
        }
        return values;
      }
    }
  }
}
