package dev.regpack.postgres;

import dev.regpack.postgres.migrate.Migrator;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Skeleton of the binding of the ledger contract suites to the PostgreSQL adapter (Convention 4):
 * every test gets a fresh schema, migrated to the current version, and drops it afterwards. It
 * grows in T15 to T18, where the adapter exists and the contract interfaces of the testkit are
 * implemented here; without a server (or with an unreachable one) every test of a subclass is
 * skipped by the assumption in {@link PgTestDatabase#dataSource()}.
 */
abstract class AbstractPgLedgerContract {
  private DataSource dataSource;
  private String schema;

  @BeforeEach
  void createFreshSchema() {
    dataSource = PgTestDatabase.dataSource();
    schema = PgTestDatabase.freshSchema();
    Migrator.migrate(dataSource, schema);
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
}
