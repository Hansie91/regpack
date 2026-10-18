package dev.regpack.spikes.x;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Verdict;
import dev.regpack.spikes.x.SpXDecisions.Derived;
import dev.regpack.spikes.x.SpXDecisions.Inputs;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpXDecisionsTest {
  /** Every required criterion of the two policy groups passes, unless overridden. */
  private static Map<String, Verdict> verdicts() {
    Map<String, Verdict> v = new HashMap<>();
    for (String id : new String[] {"X-1a", "X-2a", "X-1b", "X-2b", "X-3"}) {
      v.put(id, Verdict.PASS);
    }
    return v;
  }

  private static Inputs inputs(Map<String, Verdict> verdicts) {
    return new Inputs(
        verdicts,
        Map.of(
            "ALL", Map.of(1_000, new BigDecimal("900.500"), 5_000, new BigDecimal("4400.000")),
            "SAMPLED_10", Map.of(1_000, new BigDecimal("120.250"), 5_000, new BigDecimal("600"))),
        Map.of("xml.write.mbPerSec", new BigDecimal("55.000")));
  }

  @Test
  void policyPrefersAllThenSampledThenNone() {
    // both groups pass: the cheaper-to-reason-about policy ALL wins
    assertThat(SpXDecisions.derive(inputs(verdicts())).decisions()).containsEntry("policy", "ALL");

    // ALL fails its model criterion, SAMPLED_10 passes
    Map<String, Verdict> allFails = verdicts();
    allFails.put("X-2a", Verdict.FAIL);
    assertThat(SpXDecisions.derive(inputs(allFails)).decisions())
        .containsEntry("policy", "SAMPLED_10");

    // ALL is inconclusive (not passed), SAMPLED_10 passes
    Map<String, Verdict> allInconclusive = verdicts();
    allInconclusive.put("X-1a", Verdict.INCONCLUSIVE);
    assertThat(SpXDecisions.derive(inputs(allInconclusive)).decisions())
        .containsEntry("policy", "SAMPLED_10");

    // a model miss blocks the sampled branch only (X-3 is in its group)
    Map<String, Verdict> modelMiss = verdicts();
    modelMiss.put("X-1a", Verdict.FAIL);
    modelMiss.put("X-3", Verdict.FAIL);
    Derived none = SpXDecisions.derive(inputs(modelMiss));
    assertThat(none.decisions()).containsEntry("policy", "NONE");
    assertThat(String.join("\n", none.open())).contains("no policy meets X-1").contains("X-3 FAIL");

    // a criterion that was not measured is not passed
    Map<String, Verdict> absent = verdicts();
    absent.remove("X-1a");
    absent.remove("X-3");
    assertThat(SpXDecisions.derive(inputs(absent)).decisions()).containsEntry("policy", "NONE");
    assertThat(SpXDecisions.derive(inputs(new HashMap<>())).decisions())
        .containsEntry("policy", "NONE");

    // ALL passes while the sampled group is unmeasured: ALL
    Map<String, Verdict> onlyAll = new HashMap<>();
    onlyAll.put("X-1a", Verdict.PASS);
    onlyAll.put("X-2a", Verdict.PASS);
    assertThat(SpXDecisions.derive(inputs(onlyAll)).decisions()).containsEntry("policy", "ALL");
  }

  @Test
  void latencyAndFiguresFollowTheChosenPolicy() {
    Map<String, Verdict> allFails = verdicts();
    allFails.put("X-2a", Verdict.FAIL);
    Map<String, Object> sampled = SpXDecisions.derive(inputs(allFails)).decisions();
    assertThat((BigDecimal) sampled.get("assemble.latencyMs.1000")).isEqualByComparingTo("120.25");
    assertThat((BigDecimal) sampled.get("assemble.latencyMs.5000")).isEqualByComparingTo("600");
    assertThat((BigDecimal) sampled.get("assemble.latencyMs.ALL.1000"))
        .isEqualByComparingTo("900.5");
    assertThat((BigDecimal) sampled.get("assemble.latencyMs.SAMPLED_10.5000"))
        .isEqualByComparingTo("600");
    assertThat((BigDecimal) sampled.get("xml.write.mbPerSec")).isEqualByComparingTo("55");

    // with no policy chosen the latency of the full per-record gate is the one recorded
    Map<String, Object> none = SpXDecisions.derive(inputs(new HashMap<>())).decisions();
    assertThat((BigDecimal) none.get("assemble.latencyMs.1000")).isEqualByComparingTo("900.5");
  }
}
