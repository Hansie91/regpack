package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.Rulings;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLimits;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class PackBudgetTest {
  private static final String REPORT = "sample.s0";

  private static CompiledPack compile(Map<String, String> overrides) {
    LoadedPack pack = SamplePacks.loadSealed(overrides);
    CompiledPack.CompileResult result = CompiledPack.compile(pack, REPORT, Rulings.provisional());
    assertThat(result.problems()).isEmpty();
    return result.pack().orElseThrow();
  }

  @Test
  void sampleBudgetIsFortyUnits() {
    PackBudget budget = compile(Map.of()).budget();
    assertThat(budget.staticWorstCaseUnits()).isEqualTo(20);
    assertThat(budget.perRecordUnits()).isEqualTo(40);
    assertThat(budget.largestRuleNodes()).isEqualTo(5);
    assertThat(budget.recommendedRuleCeiling()).isEqualTo(64);
  }

  @Test
  void aDeclaredRecordBudgetWins() {
    Map<String, String> declared =
        Map.of(
            "pack.yaml",
            SamplePacks.replaced(
                "pack.yaml",
                "engine_min: 1.0.0+lib1\n",
                "engine_min: 1.0.0+lib1\nlimits: {record_budget: 6}\n"));
    PackBudget budget = compile(declared).budget();
    assertThat(budget.perRecordUnits()).isEqualTo(6);
    assertThat(budget.staticWorstCaseUnits()).isEqualTo(20);
  }

  @Test
  void theBudgetGrowsWithTheNumberOfRows() {
    String extraRow =
        """
          - id: S-X-extra
            node: N08
            priority: 900
            when: "instrument.tradedOnVenue"
            outcome: DO_NOT_REPORT
            reason: SAMPLE.N03.EXCLUDED
            cite: [{source_id: sample-law, provision_path: sample/1, role: BASIS}]
            rationale: RAT-0001
            valid: {legal: {from: 2018-01-03}}
          - id: S-DEFAULT-catch-all
        """;
    Map<String, String> grown =
        Map.of(
            "scope/sample-scope.yaml",
            SamplePacks.replaced(
                "scope/sample-scope.yaml", "  - id: S-DEFAULT-catch-all\n", extraRow));
    PackBudget before = compile(Map.of()).budget();
    PackBudget after = compile(grown).budget();
    // one more row of one node costs 1 (the row) + 1 (the node)
    assertThat(after.staticWorstCaseUnits()).isEqualTo(before.staticWorstCaseUnits() + 2);
    assertThat(after.perRecordUnits()).isEqualTo(before.perRecordUnits() + 4);
    assertThat(after.largestRuleNodes()).isEqualTo(before.largestRuleNodes());
  }

  @Test
  void theRecommendedCeilingFollowsTheLargestRule() {
    CompiledPack sample = compile(Map.of());
    PackBudget budget = PackBudget.of(sample.tables(), PackLimits.defaults());
    assertThat(budget).isEqualTo(sample.budget());
    assertThat(PackBudget.of(List.of(), PackLimits.defaults()))
        .isEqualTo(new PackBudget(0, 0, 0, 64));
    PackLimits declared = new PackLimits(512, 256, 4096, OptionalLong.of(7));
    assertThat(PackBudget.of(sample.tables(), declared).perRecordUnits()).isEqualTo(7);
  }

  @Test
  void aBudgetCannotBeNegative() {
    assertThatThrownBy(() -> new PackBudget(-1, 0, 0, 64))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PackBudget(0, -1, 0, 64))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
