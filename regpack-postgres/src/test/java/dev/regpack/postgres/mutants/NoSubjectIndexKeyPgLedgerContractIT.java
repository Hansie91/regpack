package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (P3c1 T12): the primary key of the data-subject index is dropped. PgSchemaTeethIT
 * expects the contract test it names for this class to fail: the adapter names the constraint as
 * the arbiter of its insert ({@code ON CONFLICT ON CONSTRAINT subject_index_pk DO NOTHING}), so
 * without it the statement fails with a missing-arbiter error (42704) instead of the idempotent
 * insert that the contract expects.
 */
@Tag("mutant")
public class NoSubjectIndexKeyPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropConstraint("subject_index", "subject_index_pk");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
