package dev.regpack.postgres.mutants;

import org.junit.jupiter.api.Tag;

/**
 * Schema mutant (T20): the partial unique in-flight index of delivery is dropped. PgSchemaTeethIT
 * expects the contract tests it names for this class to fail.
 */
@Tag("mutant")
public class NoInFlightIndexPgLedgerContractIT extends SchemaMutantLedgerContract {
  /** The change this mutant makes to the migrated schema. */
  public static final SchemaMutation MUTATION =
      SchemaMutation.dropIndex("delivery", "delivery_in_flight");

  @Override
  public SchemaMutation mutation() {
    return MUTATION;
  }
}
