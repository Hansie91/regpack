package dev.regpack.spikes.g;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.g.CoexistenceCheck.ReportSubjects;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The SP-G coexistence check (criterion G-4) on the P0 in-memory ledger, and proof that it has
 * teeth: every deliberate fault of the ledger or of the modelled keys is detected by exactly the
 * check that owns it.
 */
class CoexistenceCheckTest {
  private static boolean failed(CoexistenceCheck.Result result, String check) {
    return result.failed().stream().anyMatch(f -> f.equals(check) || f.startsWith(check + ": "));
  }

  @Test
  void bothChainsStartAtOneAndAdvanceIndependently() {
    CoexistenceCheck.Result result = CoexistenceCheck.run();
    assertThat(result.failed()).isEmpty();
    assertThat(result.failures()).isZero();
    assertThat(result.checks()).isEqualTo(CoexistenceCheck.CHECKS.size());
    assertThat(CoexistenceCheck.CHECKS).contains(CoexistenceCheck.CHAINS_ADVANCE_INDEPENDENTLY);

    CoexistenceCheck.Result blind =
        CoexistenceCheck.run(RegimeBlindLedger::new, ReportSubjects.Flaw.NONE);
    assertThat(failed(blind, CoexistenceCheck.CHAINS_ADVANCE_INDEPENDENTLY))
        .as("one chain for both regimes cannot start the second at seq 1")
        .isTrue();
  }

  @Test
  void supersedingOneRegimeLeavesTheOtherUntouched() {
    assertThat(CoexistenceCheck.run().failed()).isEmpty();

    CoexistenceCheck.Result blind =
        CoexistenceCheck.run(RegimeBlindLedger::new, ReportSubjects.Flaw.NONE);
    assertThat(failed(blind, CoexistenceCheck.SUPERSEDING_LEAVES_OTHER_REGIME)).isTrue();
  }

  @Test
  void reportSubjectKeysAreRegimeScoped() {
    CoexistenceCheck.Result global =
        CoexistenceCheck.run(
            CoexistenceCheck.inMemoryLedgers(), ReportSubjects.Flaw.GLOBAL_SUBJECT_KEY);
    assertThat(global.failed())
        .as("a key without the regime refuses the second regime")
        .hasSize(1)
        .allSatisfy(f -> assertThat(f).startsWith(CoexistenceCheck.SUBJECT_KEYS_REGIME_SCOPED));
    assertThat(global.failures()).isEqualTo(1);

    // a duplicate under one regime must be refused: a model that accepts everything fails too
    CoexistenceCheck.Result lax =
        CoexistenceCheck.run(
            CoexistenceCheck.inMemoryLedgers(), ReportSubjects.Flaw.ACCEPT_DUPLICATE_SUBJECT);
    assertThat(failed(lax, CoexistenceCheck.SUBJECT_KEYS_REGIME_SCOPED)).isTrue();
  }

  @Test
  void emirRowsCarryNoTrnAndATrnIsUniquePerExecutingLei() {
    for (ReportSubjects.Flaw flaw :
        List.of(
            ReportSubjects.Flaw.EMIR_TRN_ALLOWED,
            ReportSubjects.Flaw.TRN_REUSE_ALLOWED,
            ReportSubjects.Flaw.TRN_UNIQUE_GLOBALLY)) {
      CoexistenceCheck.Result result =
          CoexistenceCheck.run(CoexistenceCheck.inMemoryLedgers(), flaw);
      assertThat(result.failed())
          .as("fault %s", flaw)
          .hasSize(1)
          .allSatisfy(f -> assertThat(f).startsWith(CoexistenceCheck.TRN_RULES));
    }
    assertThat(CoexistenceCheck.run().failed()).isEmpty();
  }

  @Test
  void aBrokenAdapterIsDetected() {
    CoexistenceCheck.Result blind =
        CoexistenceCheck.run(RegimeBlindLedger::new, ReportSubjects.Flaw.NONE);
    assertThat(blind.failures()).isPositive();
    assertThat(blind.failed()).hasSize(blind.failures());
    assertThat(blind.checks()).isEqualTo(CoexistenceCheck.CHECKS.size());
    // the modelled-key checks do not depend on the ledger and stay green
    assertThat(failed(blind, CoexistenceCheck.SUBJECT_KEYS_REGIME_SCOPED)).isFalse();
    assertThat(failed(blind, CoexistenceCheck.TRN_RULES)).isFalse();
  }
}
