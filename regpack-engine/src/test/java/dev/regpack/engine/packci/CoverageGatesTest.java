package dev.regpack.engine.packci;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.pack.PackBuilder;
import dev.regpack.engine.pack.SamplePacks;
import dev.regpack.engine.packci.CoverageGates.CaseOutcome;
import dev.regpack.engine.packci.CoverageGates.CoverageResult;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.LoadedPack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec 6.5 step 2 and review focus PF15: the coverage gate is never vacuous. An empty corpus fails,
 * a failing case exercises nothing, and consistency-only cases exercise but never count (O3).
 */
class CoverageGatesTest {
  private static final String N03 = "S-N03-novation";
  private static final String D14 = "S-D14-natural-person";
  private static final String N08 = "S-N08-equity-on-venue";
  private static final String DEFAULT = "S-DEFAULT-catch-all";

  private static final String R_N03 = "SAMPLE.N03.EXCLUDED";
  private static final String R_D14 = "SAMPLE.N13.NATURAL_PERSON";
  private static final String R_N08 = "SAMPLE.N08.IN_SCOPE";
  private static final String R_DEFAULT = "SAMPLE.DEFAULT.NO_RULE_HIT";

  private final LoadedPack pack = SamplePacks.load();

  private static CaseOutcome passed(String id, String rule, String reason) {
    return new CaseOutcome(id, true, List.of(rule), reason, false);
  }

  private static CaseOutcome failed(String id, String rule, String reason) {
    return new CaseOutcome(id, false, List.of(rule), reason, true);
  }

  private static List<CaseOutcome> all() {
    return List.of(
        passed("GC-N03-1", N03, R_N03),
        passed("GC-N13-1", D14, R_D14),
        passed("GC-N08-1", N08, R_N08),
        passed("GC-DEFAULT-1", DEFAULT, R_DEFAULT));
  }

  private static List<String> codes(CoverageResult result) {
    List<String> codes = new ArrayList<>();
    for (PackProblem p : result.problems()) {
      codes.add(p.code() + " " + p.message());
    }
    return codes;
  }

  @Test
  void emptyCorpusFails() {
    CoverageResult result = CoverageGates.evaluate(pack, List.of());

    assertThat(result.problems())
        .extracting(PackProblem::code)
        .containsExactly("COVERAGE_NO_CASES");
    assertThat(result.coverage().rowsTotal()).isEqualTo(4);
    assertThat(result.coverage().rowsHit()).isZero();
    assertThat(result.coverage().reasonsReached()).isZero();
    assertThat(result.coverage().provisionsExercised()).isZero();
  }

  @Test
  void aFailingCaseExercisesNothing() {
    List<CaseOutcome> outcomes =
        List.of(
            failed("GC-N03-1", N03, R_N03),
            passed("GC-N13-1", D14, R_D14),
            passed("GC-N08-1", N08, R_N08),
            passed("GC-DEFAULT-1", DEFAULT, R_DEFAULT));

    CoverageResult result = CoverageGates.evaluate(pack, outcomes);

    assertThat(codes(result))
        .containsExactlyInAnyOrder(
            "COVERAGE_ROW_NOT_HIT " + N03,
            "COVERAGE_REASON_NOT_REACHED " + R_N03,
            "COVERAGE_PROVISION_NOT_EXERCISED sample-law/sample/1");
    assertThat(result.coverage().rowsHit()).isEqualTo(3);
    assertThat(result.coverage().reasonsReached()).isEqualTo(3);
    assertThat(result.coverage().provisionsExercised()).isEqualTo(3);
    // the failing case claims counting, which a failed case never earns
    assertThat(result.coverage().countingRowsHit()).isZero();
  }

  @Test
  void onlyFailingCasesGiveZeroCoverage() {
    List<CaseOutcome> outcomes =
        List.of(
            failed("GC-N03-1", N03, R_N03),
            failed("GC-N13-1", D14, R_D14),
            failed("GC-N08-1", N08, R_N08),
            failed("GC-DEFAULT-1", DEFAULT, R_DEFAULT));

    CoverageResult result = CoverageGates.evaluate(pack, outcomes);

    assertThat(result.coverage().rowsHit()).isZero();
    assertThat(result.coverage().reasonsReached()).isZero();
    assertThat(result.coverage().provisionsExercised()).isZero();
    assertThat(result.problems()).hasSize(12);
  }

  @Test
  void aRowNoCaseHitsFails() {
    List<CaseOutcome> outcomes =
        List.of(
            passed("GC-N03-1", N03, R_N03),
            passed("GC-N13-1", D14, R_D14),
            passed("GC-DEFAULT-1", DEFAULT, R_DEFAULT));

    CoverageResult result = CoverageGates.evaluate(pack, outcomes);

    assertThat(codes(result)).contains("COVERAGE_ROW_NOT_HIT " + N08);
    assertThat(result.coverage().rowsHit()).isEqualTo(3);
    assertThat(result.coverage().rowsTotal()).isEqualTo(4);
  }

  @Test
  void aReasonNoCaseReachesFails() {
    // every row is hit, but no passed case produced the natural-person reason
    List<CaseOutcome> outcomes =
        List.of(
            passed("GC-N03-1", N03, R_N03),
            passed("GC-N13-1", D14, R_N08),
            passed("GC-N08-1", N08, R_N08),
            passed("GC-DEFAULT-1", DEFAULT, R_DEFAULT));

    CoverageResult result = CoverageGates.evaluate(pack, outcomes);

    assertThat(codes(result)).containsExactly("COVERAGE_REASON_NOT_REACHED " + R_D14);
    assertThat(result.coverage().rowsHit()).isEqualTo(4);
    assertThat(result.coverage().reasonsReached()).isEqualTo(3);
    assertThat(result.coverage().reasonsTotal()).isEqualTo(4);
  }

  @Test
  void aReasonThatNoRowUsesIsNeverReached() {
    LoadedPack withSpare =
        SamplePacks.loadSealed(
            Map.of(
                "reasons.yaml",
                SamplePacks.files().get("reasons.yaml")
                    + "  - {code: SAMPLE.N08.SPARE, label: SPARE, category: INCLUSION,"
                    + " default_outcome: REPORT, owner: agent.p2a-sample, valid: {from: 2018-01-03}}\n"));

    CoverageResult result = CoverageGates.evaluate(withSpare, all());

    assertThat(codes(result)).containsExactly("COVERAGE_REASON_NOT_REACHED SAMPLE.N08.SPARE");
    assertThat(result.coverage().reasonsTotal()).isEqualTo(5);
    assertThat(result.coverage().reasonsReached()).isEqualTo(4);
  }

  @Test
  void aProvisionNoCaseExercisesFails() {
    List<CaseOutcome> outcomes =
        List.of(
            passed("GC-N03-1", N03, R_N03),
            passed("GC-N13-1", D14, R_D14),
            passed("GC-DEFAULT-1", DEFAULT, R_DEFAULT));

    CoverageResult result = CoverageGates.evaluate(pack, outcomes);

    assertThat(codes(result)).contains("COVERAGE_PROVISION_NOT_EXERCISED sample-law/sample/2");
    assertThat(result.coverage().provisionsCited()).isEqualTo(4);
    assertThat(result.coverage().provisionsExercised()).isEqualTo(3);
  }

  @Test
  void consistencyOnlyCasesExerciseButNeverCount() {
    CoverageResult result = CoverageGates.evaluate(pack, all());

    assertThat(result.problems()).isEmpty();
    assertThat(result.coverage().rowsHit()).isEqualTo(4);
    assertThat(result.coverage().countingRowsHit()).isZero();

    List<CaseOutcome> oneCounts =
        List.of(
            new CaseOutcome("GC-N03-1", true, List.of(N03), R_N03, true),
            passed("GC-N13-1", D14, R_D14),
            passed("GC-N08-1", N08, R_N08),
            passed("GC-DEFAULT-1", DEFAULT, R_DEFAULT));
    CoverageResult counted = CoverageGates.evaluate(pack, oneCounts);

    assertThat(counted.problems()).isEmpty();
    assertThat(counted.coverage().countingRowsHit()).isEqualTo(1);
  }

  @Test
  void coverageByCountingCasesNeverGates() {
    // no case counts (gate G1 is open) and the gate still passes: only all-case coverage gates
    assertThat(CoverageGates.evaluate(pack, all()).problems()).isEmpty();
  }

  @Test
  void fullCoverageHasNoProblems() {
    CoverageResult result = CoverageGates.evaluate(pack, all());

    assertThat(result.problems()).isEmpty();
    assertThat(result.coverage().rowsTotal()).isEqualTo(4);
    assertThat(result.coverage().rowsHit()).isEqualTo(4);
    assertThat(result.coverage().reasonsTotal()).isEqualTo(4);
    assertThat(result.coverage().reasonsReached()).isEqualTo(4);
    assertThat(result.coverage().provisionsCited()).isEqualTo(4);
    assertThat(result.coverage().provisionsExercised()).isEqualTo(4);
  }

  private static final String SECOND_CATCH_ALL = "sample.s0.scope2-catch-all";

  private static LoadedPack chainPack() {
    return PackBuilder.sample()
        .chain(
            List.of(
                PackBuilder.Rule.of("S-N03-novation", "event.kind in ['NOVATION','ASSIGNMENT']")
                    .outcome("N03", "DO_NOT_REPORT", R_N03),
                PackBuilder.Rule.of("S-D14-natural-person", "client.kind == 'NATURAL_PERSON'")
                    .outcome("N13", "UNDETERMINED", R_D14)),
            List.of(
                PackBuilder.Rule.of(
                    "S-N08-equity-on-venue",
                    "instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue")))
        .load();
  }

  @Test
  void aChainIsFullyCoveredWhenThePassedThroughCatchAllIsCredited() {
    LoadedPack chain = chainPack();
    List<CaseOutcome> outcomes =
        List.of(
            passed("GC-N03-1", N03, R_N03),
            passed("GC-N13-1", D14, R_D14),
            // the equity case matched the first table's catch-all and decided in the second table
            new CaseOutcome("GC-N08-1", true, List.of(DEFAULT, N08), R_N08, false),
            new CaseOutcome(
                "GC-DEFAULT-1", true, List.of(DEFAULT, SECOND_CATCH_ALL), R_DEFAULT, false));

    CoverageResult result = CoverageGates.evaluate(chain, outcomes);

    assertThat(result.problems()).isEmpty();
    assertThat(result.coverage().rowsTotal()).isEqualTo(5);
    assertThat(result.coverage().rowsHit()).isEqualTo(5);
  }

  @Test
  void aPassedThroughCatchAllNoCaseMatchedIsNotHit() {
    List<CaseOutcome> outcomes =
        List.of(
            passed("GC-N03-1", N03, R_N03),
            passed("GC-N13-1", D14, R_D14),
            passed("GC-N08-1", N08, R_N08),
            passed("GC-DEFAULT-1", SECOND_CATCH_ALL, R_DEFAULT));

    CoverageResult result = CoverageGates.evaluate(chainPack(), outcomes);

    assertThat(codes(result)).containsExactly("COVERAGE_ROW_NOT_HIT " + DEFAULT);
    assertThat(result.coverage().rowsHit()).isEqualTo(4);
  }

  @Test
  void aCaseThatNamesNoRowOfThePackExercisesNothing() {
    List<CaseOutcome> outcomes =
        List.of(new CaseOutcome("GC-X", true, List.of("NO-SUCH-ROW"), R_N03, false));

    CoverageResult result = CoverageGates.evaluate(pack, outcomes);

    assertThat(result.coverage().rowsHit()).isZero();
    assertThat(codes(result)).contains("COVERAGE_ROW_NOT_HIT " + N03);
  }
}
