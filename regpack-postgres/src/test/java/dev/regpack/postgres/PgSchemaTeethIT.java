package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.postgres.SchemaMutants.Entry;
import dev.regpack.postgres.migrate.Migrator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The teeth of the real-store contract (T20, review focus LF1): each schema mutant is the migrated
 * schema with exactly one constraint or index removed, bound to the same contract suites as {@link
 * PgLedgerStoreContractIT}, and the contract tests that name that constraint must fail on it. A
 * mutant that is not caught means the adapter pre-checks in Java what the schema should enforce, or
 * that the contract test does not need the constraint: fix the adapter or the test, never the
 * mutant.
 *
 * <p>Only the named tests are run per mutant (see {@link MutantRunner}): the rest of a mutant class
 * is a complete binding that may be run by hand, but a whole class per mutant would run about 100
 * tests once per mutant, and under a missing constraint every concurrent test would wait for its
 * own timeout. Every DDL step has a lock and a statement timeout, each test has a timeout, and
 * every schema is dropped afterwards ({@link AbstractPgStoreTest}, {@link
 * PgTestDatabase#dropSchema(String)}). The unmutated binding is {@link PgLedgerStoreContractIT},
 * which the same job runs: a named test that fails there makes the job red. Skipped without {@code
 * REGPACK_PG_URL}; a failure with {@code REGPACK_PG_REQUIRED=true} and no server, so that a missing
 * server is never read as every mutant caught.
 */
class PgSchemaTeethIT {
  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void droppingAConstraintMakesItsNamedContractTestsFail() {
    PgTestDatabase.dataSource();
    List<String> misses = new ArrayList<>();
    for (Entry entry : SchemaMutants.all()) {
      misses.addAll(SchemaMutants.misses(entry, MutantRunner.run(entry.type(), entry.caughtBy())));
    }
    assertThat(misses).isEmpty();
  }

  /**
   * Without this the previous test could pass vacuously: if the mutation itself failed (a wrong
   * constraint name, say), every test of the mutant would fail in its set-up and look caught. Here
   * each object is there before its mutation, gone after it, and the others are untouched.
   */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void everyMutationRemovesTheObjectItNamesAndNoOther() throws SQLException {
    DataSource dataSource = PgTestDatabase.dataSource();
    String schema = PgTestDatabase.freshSchema();
    try {
      Migrator.migrate(dataSource, schema);
      List<Entry> entries = SchemaMutants.all();
      try (Connection connection = dataSource.getConnection()) {
        for (Entry entry : entries) {
          assertThat(entry.mutation().existsIn(connection, schema))
              .as(entry.mutation().name() + " exists after the migration")
              .isTrue();
        }
        for (int applied = 0; applied < entries.size(); applied++) {
          entries.get(applied).mutation().applyTo(connection, schema);
          for (int i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i).mutation().existsIn(connection, schema))
                .as(
                    entries.get(i).mutation().name()
                        + " after the mutation of "
                        + entries.get(applied).mutation().name())
                .isEqualTo(i > applied);
          }
        }
      }
    } finally {
      PgTestDatabase.dropSchema(schema);
    }
  }
}
