package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (P3c1 T11): the primary key of the retention rows is dropped. PgSchemaTeethIT
 * expects the contract test it names for this class to fail: the adapter names the constraint as
 * the arbiter of its upsert ({@code ON CONFLICT ON CONSTRAINT retention_pk}), so without it the
 * statement fails with a missing-arbiter error (42704) instead of the merge that the contract
 * expects.
 */
@Tag("mutant")
public class NoRetentionKeyPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropConstraint("retention", "retention_pk");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
