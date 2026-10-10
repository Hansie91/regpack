package dev.regpack.spikes.r;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.proto.Protocol;
import dev.regpack.spikes.r.SpRRunner.Profile;
import org.junit.jupiter.api.Test;

class ProjectionTest {
  @Test
  void aFullForkSpendsSixCanaryProbesWarmUpAndFifteenWindows() {
    // 6 probes of 1 s, five windows of 2 s of warm-up, fifteen windows of 2 s, 3 s fixed
    assertThat(Projection.forkSeconds(Protocol.full(), 3.0, false)).isEqualTo(3 + 6 + 10 + 30);
    assertThat(Projection.forkSeconds(Protocol.full(), 3.0, true)).isEqualTo(3 + 6 + 60 + 30);
  }

  @Test
  void theWorstCaseIsNeverBelowTheTypicalCase() {
    double typical = Projection.totalMinutes(SpRRunner.plan(Profile.FULL, false), 3.0, 600, false);
    double worst = Projection.totalMinutes(SpRRunner.plan(Profile.FULL, false), 3.0, 600, true);
    assertThat(worst).isGreaterThan(typical);
    assertThat(typical).isGreaterThan(10.0);
  }

  @Test
  void moreForksOrMoreFixedCostTakeLonger() {
    double base = Projection.totalMinutes(SpRRunner.plan(Profile.FULL, false), 3.0, 0, false);
    assertThat(Projection.totalMinutes(SpRRunner.plan(Profile.FULL, false), 6.0, 0, false))
        .isGreaterThan(base);
    assertThat(Projection.totalMinutes(SpRRunner.plan(Profile.FULL, true), 3.0, 0, false))
        .as("the stretch forks add time")
        .isGreaterThan(base);
  }
}
