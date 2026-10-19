package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (T20): the unique constraint on (regime, event identity, leg) of report_subject is
 * dropped. PgSchemaTeethIT expects the contract tests it names for this class to fail.
 */
@Tag("mutant")
public class NoSubjectKeyPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropConstraint("report_subject", "report_subject_key");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
