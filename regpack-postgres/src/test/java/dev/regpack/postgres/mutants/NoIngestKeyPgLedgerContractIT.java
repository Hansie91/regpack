package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (T20): the primary key of ingest is dropped. PgSchemaTeethIT expects the contract
 * tests it names for this class to fail.
 */
@Tag("mutant")
public class NoIngestKeyPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropConstraint("ingest", "ingest_pk");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
