package dev.regpack.spikes.r;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Verdict;
import dev.regpack.spikes.r.SpRDecisions.Derived;
import dev.regpack.spikes.r.SpRDecisions.Inputs;
import dev.regpack.spikes.r.defaults.BudgetDefaults;
import dev.regpack.spikes.r.defaults.PoisonDefaults;
import dev.regpack.spikes.r.vectors.VectorResult;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpRDecisionsTest {
  private static final BigDecimal GATE = new BigDecimal("10000");

  private static final List<VectorResult> CLEAN =
      List.of(
          new VectorResult("D01", "MUST", "PASS", "T"),
          new VectorResult("D13", "DECIDE", "PASS", "T"),
          new VectorResult("L01", "DECIDE", "FAIL", "COMPILE_ERROR"));

  private static final List<VectorResult> FUNCTIONAL_MUST =
      List.of(
          new VectorResult("D01", "MUST", "FUNCTIONAL", "via `decEq(a, b)`: T"),
          new VectorResult("D13", "DECIDE", "UNSUPPORTED", "no mixed arithmetic"),
          new VectorResult("L01", "DECIDE", "PASS", "T"));

  /** Every required criterion of both layers passes, unless overridden. */
  private static Map<String, Verdict> verdicts() {
    Map<String, Verdict> v = new HashMap<>();
    for (String id : List.of("R-1a", "R-2a", "R-6a", "R-1b", "R-2b", "R-6b")) {
      v.put(id, Verdict.PASS);
    }
    return v;
  }

  private static Inputs inputs(
      Map<String, Verdict> verdicts, List<VectorResult> inhouse, List<VectorResult> cel) {
    return new Inputs(
        verdicts,
        inhouse,
        cel,
        Map.of("inhouse", new BigDecimal("30000"), "cel", new BigDecimal("12000")),
        Map.of("inhouse", new BigDecimal("40000"), "cel", new BigDecimal("15000")),
        4,
        2,
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Map.of());
  }

  @Test
  void layerSelectionCoversTheFourCases() {
    // 1: cel passes everything and needs no function syntax
    assertThat(SpRDecisions.derive(inputs(verdicts(), CLEAN, CLEAN), GATE).decisions())
        .containsEntry("expressionLayer", "CEL_JAVA");

    // 2: cel passes, but a MUST vector only through function syntax
    assertThat(SpRDecisions.derive(inputs(verdicts(), CLEAN, FUNCTIONAL_MUST), GATE).decisions())
        .containsEntry("expressionLayer", "CEL_JAVA_FUNCTIONAL");

    // 3: cel fails a required criterion, the in-house layer passes
    Map<String, Verdict> celFails = verdicts();
    celFails.put("R-1b", Verdict.FAIL);
    assertThat(SpRDecisions.derive(inputs(celFails, CLEAN, CLEAN), GATE).decisions())
        .containsEntry("expressionLayer", "INHOUSE");

    // 4: both layers have a failing required criterion
    Map<String, Verdict> bothFail = verdicts();
    bothFail.put("R-1b", Verdict.FAIL);
    bothFail.put("R-6a", Verdict.FAIL);
    assertThat(SpRDecisions.derive(inputs(bothFail, CLEAN, CLEAN), GATE).decisions())
        .containsEntry("expressionLayer", "NONE");
  }

  @Test
  void aLayerThatWasNotMeasuredCannotBeChosen() {
    Map<String, Verdict> v = verdicts();
    v.put("R-1b", Verdict.INCONCLUSIVE);
    Derived derived = SpRDecisions.derive(inputs(v, CLEAN, CLEAN), GATE);
    assertThat(derived.decisions()).containsEntry("expressionLayer", "INHOUSE");

    v.remove("R-1a");
    Derived none = SpRDecisions.derive(inputs(v, CLEAN, CLEAN), GATE);
    assertThat(none.decisions()).containsEntry("expressionLayer", "NONE");
    assertThat(none.decisions().get("expressionLayerReason").toString())
        .contains("R-1a")
        .contains("R-1b");
  }

  @Test
  void whenNoLayerPassesTheGateTheSemanticCriteriaStillChooseOne() {
    Map<String, Verdict> v = verdicts();
    v.put("R-2a", Verdict.FAIL);
    v.put("R-2b", Verdict.FAIL);
    Derived derived = SpRDecisions.derive(inputs(v, CLEAN, CLEAN), GATE);
    assertThat(derived.decisions())
        .containsEntry("expressionLayer", "CEL_JAVA")
        .containsEntry("executorGate", "FAIL");
    assertThat(derived.decisions().get("expressionLayerReason").toString())
        .contains("semantic criteria");
  }

  @Test
  void theGateIsTheThroughputCriterionOfTheChosenLayer() {
    Map<String, Verdict> v = verdicts();
    v.put("R-2b", Verdict.FAIL); // cel is not chosen: its R-2 does not decide the gate
    Derived inhouse = SpRDecisions.derive(inputs(v, CLEAN, CLEAN), GATE);
    assertThat(inhouse.decisions())
        .containsEntry("expressionLayer", "INHOUSE")
        .containsEntry("executorGate", "PASS");

    Map<String, Verdict> celChosen = verdicts();
    celChosen.put("R-2a", Verdict.FAIL);
    assertThat(SpRDecisions.derive(inputs(celChosen, CLEAN, CLEAN), GATE).decisions())
        .containsEntry("expressionLayer", "CEL_JAVA")
        .containsEntry("executorGate", "PASS");

    Map<String, Verdict> noLayer = verdicts();
    noLayer.put("R-1a", Verdict.FAIL);
    noLayer.put("R-1b", Verdict.FAIL);
    noLayer.put("R-2a", Verdict.FAIL);
    noLayer.put("R-2b", Verdict.FAIL);
    assertThat(SpRDecisions.derive(inputs(noLayer, CLEAN, CLEAN), GATE).decisions())
        .containsEntry("expressionLayer", "NONE")
        .containsEntry("executorGate", "FAIL");

    Map<String, Verdict> unknown = verdicts();
    unknown.put("R-1a", Verdict.FAIL);
    unknown.put("R-1b", Verdict.FAIL);
    unknown.put("R-2a", Verdict.FAIL);
    unknown.put("R-2b", Verdict.INCONCLUSIVE);
    assertThat(SpRDecisions.derive(inputs(unknown, CLEAN, CLEAN), GATE).decisions())
        .containsEntry("executorGate", "INCONCLUSIVE");
  }

  @Test
  void readingDependentFailIsFlagged() {
    Map<String, Verdict> v = verdicts();
    v.put("R-2a", Verdict.FAIL);
    v.put("R-2b", Verdict.FAIL);
    v.put("R-1b", Verdict.FAIL); // the in-house layer is the chosen one
    Inputs in =
        new Inputs(
            v,
            CLEAN,
            CLEAN,
            Map.of("inhouse", new BigDecimal("8000"), "cel", new BigDecimal("2000")),
            Map.of("inhouse", new BigDecimal("11000"), "cel", new BigDecimal("3000")),
            4,
            2,
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Map.of());

    Derived derived = SpRDecisions.derive(in, GATE);

    assertThat(derived.decisions())
        .containsEntry("expressionLayer", "INHOUSE")
        .containsEntry("executorGate", "FAIL")
        .containsEntry("executorGateReadingDependent", true);
    @SuppressWarnings("unchecked")
    Map<String, Object> readings =
        (Map<String, Object>) derived.decisions().get("executorGateReadings");
    assertThat(readings).containsOnlyKeys("t4S2", "t1S2", "physCoreS2");
    assertThat((BigDecimal) readings.get("t4S2")).isEqualByComparingTo("8000");
    assertThat((BigDecimal) readings.get("t1S2")).isEqualByComparingTo("11000");
    assertThat((BigDecimal) readings.get("physCoreS2")).isEqualByComparingTo("16000");
    assertThat(String.join("\n", derived.open()))
        .contains("t4S2")
        .contains("t1S2")
        .contains("physCoreS2")
        .contains("depends on the reading");
  }

  @Test
  void aFailThatEveryReadingConfirmsIsNotCalledReadingDependent() {
    Map<String, Verdict> v = verdicts();
    v.put("R-2a", Verdict.FAIL);
    v.put("R-6b", Verdict.FAIL); // the in-house layer is the only one that can be chosen
    Inputs in =
        new Inputs(
            v,
            CLEAN,
            CLEAN,
            Map.of("inhouse", new BigDecimal("1000"), "cel", new BigDecimal("900")),
            Map.of("inhouse", new BigDecimal("1500"), "cel", new BigDecimal("950")),
            4,
            2,
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Map.of());
    Derived derived = SpRDecisions.derive(in, GATE);
    assertThat(derived.decisions())
        .containsEntry("expressionLayer", "INHOUSE")
        .containsEntry("executorGate", "FAIL")
        .containsEntry("executorGateReadingDependent", false);
  }

  @Test
  void decideVectorsOfBothLayersAreListedAsOpen() {
    Derived derived = SpRDecisions.derive(inputs(verdicts(), CLEAN, FUNCTIONAL_MUST), GATE);
    String open = String.join("\n", derived.open());
    assertThat(open).contains("D13").contains("L01");
    assertThat(open).contains("in-house PASS").contains("cel-java UNSUPPORTED");
    assertThat(open).doesNotContain("D01 ");
  }

  @Test
  void budgetPoisonExplainHotSwapAndCostFiguresAreRecorded() {
    Inputs in =
        new Inputs(
            verdicts(),
            CLEAN,
            CLEAN,
            Map.of("inhouse", new BigDecimal("30000"), "cel", new BigDecimal("12000")),
            Map.of("inhouse", new BigDecimal("40000"), "cel", new BigDecimal("15000")),
            4,
            2,
            Map.of(
                "inhouse", new BudgetDefaults.Result(120_000, 640, 60_000, 20_000, 0),
                "cel", new BudgetDefaults.Result(90_000, 640, 45_000, 15_000, 0)),
            Optional.of(new PoisonDefaults.Choice(3, 30, 0.004, 0.99)),
            Optional.of(new BigDecimal("1.250")),
            Map.of(
                "inhouse.200", new BigDecimal("40.5"),
                "inhouse.5000", new BigDecimal("900.25"),
                "cel.200", new BigDecimal("55.0"),
                "cel.5000", new BigDecimal("1200.0")),
            Map.of(
                "inhouse.50", new BigDecimal("3.1"),
                "inhouse.200", new BigDecimal("25.0"),
                "cel.200", new BigDecimal("83.3")));

    Map<String, Object> d = SpRDecisions.derive(in, GATE).decisions();

    assertThat(d)
        .containsEntry("expressionLayer", "CEL_JAVA")
        .containsEntry("budget.perRecordUnits", 90_000L)
        .containsEntry("poison.n", 3L)
        .containsEntry("poison.m", 30L);
    assertThat((BigDecimal) d.get("explain.tier1P99Ms")).isEqualByComparingTo("1.25");
    assertThat((BigDecimal) d.get("hotswap.p99Ms.200")).isEqualByComparingTo("55.0");
    assertThat((BigDecimal) d.get("hotswap.p99Ms.5000")).isEqualByComparingTo("1200.0");
    assertThat((BigDecimal) d.get("decide.usPerRecord.inhouse.50")).isEqualByComparingTo("3.1");
    // one thread rate of the chosen layer (cel: 15,000 records/s) is 66.667 microseconds
    assertThat((BigDecimal) d.get("decide.usPerRecordWorst")).isEqualByComparingTo("66.667");
  }

  @Test
  void missingMeasurementsLeaveTheirKeysOutInsteadOfInventingThem() {
    Map<String, Object> d =
        SpRDecisions.derive(
                new Inputs(
                    Map.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    Map.of(),
                    4,
                    2,
                    Map.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of(),
                    Map.of()),
                GATE)
            .decisions();
    assertThat(d)
        .containsEntry("expressionLayer", "NONE")
        .containsEntry("executorGate", "INCONCLUSIVE");
    assertThat(d)
        .doesNotContainKeys(
            "budget.perRecordUnits",
            "poison.n",
            "explain.tier1P99Ms",
            "hotswap.p99Ms.200",
            "decide.usPerRecordWorst");
  }
}
