package dev.regpack.postgres;

import dev.regpack.postgres.mutants.NoDecisionHeadKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoDecisionIdemKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoInFlightIndexPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoIngestKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoManifestKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoSubjectKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.NoTrnLeiKeyPgLedgerContractIT;
import dev.regpack.postgres.mutants.SchemaMutantLedgerContract;
import dev.regpack.postgres.mutants.SchemaMutation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The schema mutants of T20 (six) and T12 (the seventh, {@code object_manifest_pk}) and the
 * contract tests that must fail on each (the table of the plan). Only methods of the contract
 * suites can be named: {@code PgDecisionsIT}, {@code PgConstraintsIT} and {@code PgFencingIT}
 * methods are not part of a mutant class. The cross-partition test has no schema mutant; its teeth
 * is the characterisation {@code PgDecisionsIT#theUniqueIndexesAloneCannotSeeAcrossPartitions}. For
 * a constraint that the adapter uses as an {@code ON CONFLICT} arbiter ({@code decision_idem_key},
 * {@code report_subject_key}, {@code ingest_pk}, {@code decision_head_pk}, {@code
 * object_manifest_pk}) the mutant fails with a missing-arbiter SQL error, which proves that the
 * adapter relies on the named constraint; {@code delivery_in_flight} and {@code
 * trn_assignment_lei_trn_key} give behavioural teeth.
 */
final class SchemaMutants {
  /**
   * One mutant class, the change it makes and the tests that must fail on it.
   *
   * @param type the mutant class
   * @param mutation the change (the same value as the one the class declares)
   * @param caughtBy method names of the contract suites that must fail
   * @param expectedFailure a fragment that the failure text of every named test must contain, so
   *     that a test that fails for another reason (a typo in a statement of the adapter, say) is
   *     not reported as a caught mutant; empty: any failure counts
   */
  record Entry(
      Class<? extends SchemaMutantLedgerContract> type,
      SchemaMutation mutation,
      Set<String> caughtBy,
      String expectedFailure) {
    Entry {
      caughtBy = Set.copyOf(caughtBy);
      Objects.requireNonNull(expectedFailure, "expectedFailure");
    }

    /** An entry for which any failure of the named tests counts. */
    Entry(
        Class<? extends SchemaMutantLedgerContract> type,
        SchemaMutation mutation,
        Set<String> caughtBy) {
      this(type, mutation, caughtBy, "");
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
                "sameVersionSameHashIsNoop")),
        new Entry(
            NoManifestKeyPgLedgerContractIT.class,
            NoManifestKeyPgLedgerContractIT.MUTATION,
            Set.of("recordingTheSameKeyWithAnotherHashIsAConflictAndStoresNothingOfTheCall"),
            // the insert names the constraint as its arbiter: without it the server answers 42704
            // (undefined object), which PgErrors writes as sqlstate=42704 into the message of the
            // LedgerException that the first statement of the test throws
            MISSING_ARBITER));
  }

  /** The text that PgErrors.wrap gives a missing arbiter (SQLSTATE 42704, undefined object). */
  static final String MISSING_ARBITER = "sqlstate=42704";

  /**
   * What is wrong with the run of one mutant, empty when it was caught as the entry says: the
   * launcher found the named tests, each of them failed, and each failure text holds the fragment
   * that the entry expects. The text of a failure is quoted only for an entry that expects one.
   */
  static List<String> misses(Entry entry, MutantRunner.Outcome outcome) {
    String mutant = entry.type().getSimpleName();
    List<String> misses = new ArrayList<>();
    if (outcome.found() != entry.caughtBy().size()) {
      misses.add(
          mutant + " ran " + outcome.found() + " tests, but names " + entry.caughtBy().size());
    }
    for (String name : new TreeSet<>(entry.caughtBy())) {
      if (!outcome.failed().contains(name)) {
        misses.add(mutant + " was not caught by " + name);
      } else if (!entry.expectedFailure().isEmpty()
          && !outcome.failures().get(name).contains(entry.expectedFailure())) {
        misses.add(
            mutant
                + " failed in "
                + name
                + " but not with \""
                + entry.expectedFailure()
                + "\": "
                + outcome.failures().get(name));
      }
    }
    return misses;
  }
}
