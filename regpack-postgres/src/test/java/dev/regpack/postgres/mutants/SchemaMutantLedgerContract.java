package dev.regpack.postgres.mutants;

import dev.regpack.postgres.AbstractPgLedgerContract;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * A deliberately flawed schema (T20): the ledger contract suites run on a migrated schema from
 * which one constraint or index has been removed, so that the contract tests that depend on that
 * constraint must fail ({@code PgSchemaTeethIT} expects exactly the tests it names). The change is
 * applied in a {@code @BeforeEach} of this class, which JUnit runs after the {@code @BeforeEach} of
 * the superclasses that create and migrate the schema; the schema is dropped afterwards by the base
 * class, so a mutant leaves nothing behind. Tagged {@code mutant}: the build never runs these
 * classes by itself.
 */
@Tag("mutant")
public abstract class SchemaMutantLedgerContract extends AbstractPgLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public abstract SchemaMutation mutation();

  @BeforeEach
  void applyTheSchemaMutation() throws SQLException {
    try (Connection connection = raw()) {
      mutation().applyTo(connection, schema());
    }
  }
}
