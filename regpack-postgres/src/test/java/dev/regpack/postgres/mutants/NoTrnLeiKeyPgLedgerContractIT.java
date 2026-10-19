package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (T20): the unique constraint on (executing LEI, TRN) is dropped. PgSchemaTeethIT
 * expects the contract tests it names for this class to fail.
 */
@Tag("mutant")
public class NoTrnLeiKeyPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropConstraint("trn_assignment", "trn_assignment_lei_trn_key");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
