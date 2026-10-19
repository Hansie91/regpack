package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (T20): the idempotency key of decision is dropped. PgSchemaTeethIT expects the
 * contract tests it names for this class to fail.
 */
@Tag("mutant")
public class NoDecisionIdemKeyPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropConstraint("decision", "decision_idem_key");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
