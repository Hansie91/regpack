package dev.regpack.postgres;

import dev.regpack.postgres.mutants.NoDecisionHeadKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoDecisionIdemKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoInFlightIndexPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoIngestKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoSubjectKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoTrnLeiKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.SchemaMutantLedgerContract;
import dev.regpack.postgres.mutants.SchemaMutation;
import java.util.List;
import java.util.Set;

/**
 * The schema mutants of T20 and the contract tests that must fail on each (the table of the plan).
 * Only methods of the contract suites can be named: {@code PgDecisionsIT}, {@code PgConstraintsIT}
 * and {@code PgFencingIT} methods are not part of a mutant class. The cross-partition test has no
 * schema mutant; its teeth is the characterisation {@code
 * PgDecisionsIT#theUniqueIndexesAloneCannotSeeAcrossPartitions}. For a constraint that the adapter
 * uses as an {@code ON CONFLICT} arbiter ({@code decision_idem_key}, {@code report_subject_key},
 * {@code ingest_pk}, {@code decision_head_pk}) the mutant fails with a missing-arbiter SQL error,
 * which proves that the adapter relies on the named constraint; {@code delivery_in_flight} and
 * {@code trn_assignment_lei_trn_key} give behavioural teeth.
 */
final class SchemaMutants {
  /**
   * One mutant class, the change it makes and the tests that must fail on it.
   *
   * @param type the mutant class
   * @param mutation the change (the same value as the one the class declares)
   * @param caughtBy method names of the contract suites that must fail
   */
  record Entry(
      Class<? extends SchemaMutantLedgerContract> type,
      SchemaMutation mutation,
      Set<String> caughtBy) {
    Entry {
      caughtBy = Set.copyOf(caughtBy);
    }
  }

  private SchemaMutants() {}

  static List<Entry> all() {
    return List.of(
        new Entry(
            NoInFlightIndexPgLedgerContractIT.class,
            NoInFlightIndexPgLedgerContractIT.MUTATION,
            Set.of(
                "inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey",
                "concurrentBeginSendingForSameBusinessKeyAppliesExactlyOnce")),
        new Entry(
            NoTrnLeiKeyPgLedgerContractIT.class,
            NoTrnLeiKeyPgLedgerContractIT.MUTATION,
            Set.of("aSecondSubjectWithTheSameTrnAndLeiIsRejectedAtomically")),
        new Entry(
            NoSubjectKeyPgLedgerContractIT.class,
            NoSubjectKeyPgLedgerContractIT.MUTATION,
            Set.of("subjectKeyIsScopedByRegime", "legsGetTheirOwnSubjectsAndChains")),
        new Entry(
            NoDecisionIdemKeyPgLedgerContractIT.class,
            NoDecisionIdemKeyPgLedgerContractIT.MUTATION,
            Set.of(
                "redeliveryOfTheFirstDecisionAfterATradeDateChangeIsNoop",
                "redeliveryOfTheUnitIsANoopAndReturnsExistingVersions")),
        new Entry(
            NoDecisionHeadKeyPgLedgerContractIT.class,
            NoDecisionHeadKeyPgLedgerContractIT.MUTATION,
            Set.of("concurrentDecisionCommitsWithTheSameExpectedHeadHaveExactlyOneWinner")),
        new Entry(
            NoIngestKeyPgLedgerContractIT.class,
            NoIngestKeyPgLedgerContractIT.MUTATION,
            Set.of(
                "concurrentBatchesWithTheSameIngestKeyCreateExactlyOneVersion",
                "sameVersionSameHashIsNoop")));
  }
}
