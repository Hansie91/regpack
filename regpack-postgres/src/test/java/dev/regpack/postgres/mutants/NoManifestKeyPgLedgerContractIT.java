package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (T12): the primary key of the object manifest is dropped. PgSchemaTeethIT expects
 * the contract test it names for this class to fail: the adapter names the constraint as the
 * arbiter of its insert ({@code ON CONFLICT ON CONSTRAINT object_manifest_pk}), so without it the
 * statement fails with a missing-arbiter error (42704) instead of the conflict that the contract
 * expects.
 */
@Tag("mutant")
public class NoManifestKeyPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropConstraint("object_manifest", "object_manifest_pk");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
