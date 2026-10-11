package dev.regpack.spikes.x;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.proto.Protocol;
import dev.regpack.spikes.x.Projection.Observed;
import dev.regpack.spikes.x.Projection.Result;
import dev.regpack.spikes.x.SpXRunner.Plan;
import dev.regpack.spikes.x.SpXRunner.Profile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectionTest {
  private static final Plan FULL = SpXRunner.plan(Profile.FULL, false);

  /** Every forked step of the plan at the given smoke rate. */
  private static Map<String, Double> rates(double rate) {
    Map<String, Double> rates = new HashMap<>();
    FULL.steps().stream().filter(SpXRunner.Step::forked).forEach(s -> rates.put(s.id(), rate));
    return rates;
  }

  private static Observed observed(Map<String, Double> rates) {
    return new Observed(3.0, 0.0001, rates, 0.0005, 1.0);
  }

  @Test
  void aWindowForkSpendsSixCanaryProbesWarmUpAndFifteenWindows() {
    // 6 probes of 1 s, five windows of 2 s of warm-up, fifteen windows of 2 s, 3 s fixed
    assertThat(Projection.windowForkSeconds(Protocol.informative(), 3.0, false))
        .isEqualTo(3 + 6 + 10 + 30);
    assertThat(Projection.windowForkSeconds(Protocol.informative(), 3.0, true))
        .isEqualTo(3 + 6 + 60 + 30);
  }

  @Test
  void theWorstCaseIsNeverBelowTheTypicalCase() {
    Result result = Projection.project(FULL, Profile.SMOKE.sizes(), observed(rates(20_000.0)));
    assertThat(result.worstMinutes()).isGreaterThan(result.typicalMinutes());
    assertThat(result.typicalMinutes()).isGreaterThan(10.0);
    assertThat(result.assumed()).isEmpty();
  }

  @Test
  void slowerCyclesTakeLonger() {
    double fast =
        Projection.project(FULL, Profile.SMOKE.sizes(), observed(rates(40_000.0))).typicalMinutes();
    double slow =
        Projection.project(FULL, Profile.SMOKE.sizes(), observed(rates(10_000.0))).typicalMinutes();
    assertThat(slow).isGreaterThan(fast);
  }

  @Test
  void aMissingSmokeRateIsAssumedAndNamed() {
    Map<String, Double> rates = rates(20_000.0);
    rates.remove("gate-t4");
    rates.remove("assemble-ALL");
    Result result = Projection.project(FULL, Profile.SMOKE.sizes(), observed(rates));
    assertThat(result.assumed()).containsExactlyInAnyOrder("gate-t4", "assemble-ALL");
    Result complete = Projection.project(FULL, Profile.SMOKE.sizes(), observed(rates(20_000.0)));
    assertThat(result.typicalMinutes())
        .as("the assumed rate is far below the measured one")
        .isGreaterThan(complete.typicalMinutes());
    assertThat(List.of(result.worstMinutes())).allMatch(m -> m >= result.typicalMinutes());
  }
}
