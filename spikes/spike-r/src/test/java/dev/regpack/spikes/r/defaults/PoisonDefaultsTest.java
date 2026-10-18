package dev.regpack.spikes.r.defaults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.spikes.r.defaults.PoisonDefaults.Choice;
import org.junit.jupiter.api.Test;

class PoisonDefaultsTest {
  private static final long RECORDS_PER_DAY = 50_000_000L;
  private static final double BACKGROUND = 1e-4;
  private static final double DEFECTIVE = 0.20;
  private static final double MIN_DETECTION = 0.99;
  private static final double MAX_FALSE_OPEN = 0.01;
  private static final int[] N_GRID = {2, 3, 4, 5, 8, 10, 20};
  private static final int[] M_GRID = {20, 30, 40, 50, 60, 80, 100, 200, 500, 1000};

  private static Choice choose() {
    return PoisonDefaults.choose(
        RECORDS_PER_DAY, BACKGROUND, DEFECTIVE, MIN_DETECTION, MAX_FALSE_OPEN, N_GRID, M_GRID);
  }

  /** The fraction of {@code trials} windows of {@code m} records with at least {@code n} errors. */
  private static double simulate(int m, int n, long numerator, long denominator, int trials) {
    SplitMix64 rng = new SplitMix64(21L);
    int opened = 0;
    for (int t = 0; t < trials; t++) {
      int errors = 0;
      for (int i = 0; i < m; i++) {
        if (rng.chance(numerator, denominator)) {
          errors++;
        }
      }
      if (errors >= n) {
        opened++;
      }
    }
    return opened / (double) trials;
  }

  @Test
  void tailProbabilityMatchesKnownValues() {
    assertThat(PoisonDefaults.windowOpenProbability(2, 0.5, 1)).isCloseTo(0.75, within(1e-12));
    assertThat(PoisonDefaults.windowOpenProbability(3, 0.5, 2)).isCloseTo(0.5, within(1e-12));
    assertThat(PoisonDefaults.windowOpenProbability(10, 0.1, 0)).isEqualTo(1.0);
    assertThat(PoisonDefaults.windowOpenProbability(5, 0.0, 1)).isEqualTo(0.0);
    // 1 - 0.8^20 - 20 * 0.2 * 0.8^19
    assertThat(PoisonDefaults.windowOpenProbability(20, 0.2, 2))
        .isCloseTo(1 - Math.pow(0.8, 20) - 20 * 0.2 * Math.pow(0.8, 19), within(1e-12));
    // a tiny upper tail is not lost to cancellation: the terms from k = 3 up, summed directly
    double tail = 0;
    double binomial = 1;
    for (int k = 0; k <= 20; k++) {
      if (k > 0) {
        binomial = binomial * (20 - k + 1) / k;
      }
      if (k >= 3) {
        tail += binomial * Math.pow(1e-4, k) * Math.pow(1 - 1e-4, 20 - k);
      }
    }
    assertThat(PoisonDefaults.windowOpenProbability(20, 1e-4, 3)).isCloseTo(tail, within(1e-18));
    assertThat(tail).isBetween(1.1e-9, 1.2e-9);
  }

  @Test
  void chosenPairDetectsADefectiveRuleAndStaysUnderTheFalseOpenLimit() {
    Choice choice = choose();

    assertThat(choice.detectionProbability()).isGreaterThanOrEqualTo(MIN_DETECTION);
    assertThat(choice.falseOpenPerDay()).isLessThanOrEqualTo(MAX_FALSE_OPEN);
    assertThat(choice.n()).isBetween(2, 20);
    assertThat(choice.m()).isBetween(20, 1000);

    // the formula against a simulation of 10,000 windows of a rule that errors on 20% of records
    double simulated = simulate(choice.m(), choice.n(), 1, 5, 10_000);
    assertThat(simulated).isCloseTo(choice.detectionProbability(), within(0.01));

    // the window probability that drives the false-open formula, at a rate a simulation can see
    double formula = PoisonDefaults.windowOpenProbability(30, 0.05, 3);
    assertThat(simulate(30, 3, 1, 20, 10_000)).isCloseTo(formula, within(0.01));
    assertThat(choice.falseOpenPerDay())
        .isCloseTo(
            RECORDS_PER_DAY
                / (double) choice.m()
                * PoisonDefaults.windowOpenProbability(choice.m(), BACKGROUND, choice.n()),
            within(1e-12));
  }

  @Test
  void nextSmallerMViolatesAConstraint() {
    Choice choice = choose();
    for (int m : M_GRID) {
      for (int n : N_GRID) {
        if (n > m) {
          continue;
        }
        boolean feasible =
            PoisonDefaults.detectionProbability(m, n, DEFECTIVE) >= MIN_DETECTION
                && PoisonDefaults.falseOpenPerDay(RECORDS_PER_DAY, m, n, BACKGROUND)
                    <= MAX_FALSE_OPEN;
        if (m < choice.m()) {
          assertThat(feasible).as("M=" + m + " N=" + n + " is smaller in M yet feasible").isFalse();
        }
        if (m == choice.m() && n < choice.n()) {
          assertThat(feasible).as("N=" + n + " is smaller in N yet feasible").isFalse();
        }
      }
    }
  }

  @Test
  void clusteredScenarioIsReportedSeparately() {
    Choice choice = choose();
    double clustered =
        PoisonDefaults.clusteredFalseOpenPerDay(
            RECORDS_PER_DAY, BACKGROUND, 5_000L, 0.05, choice.n(), choice.m());

    double expected =
        (RECORDS_PER_DAY - 5_000L)
                / (double) choice.m()
                * PoisonDefaults.windowOpenProbability(choice.m(), BACKGROUND, choice.n())
            + 5_000L
                / (double) choice.m()
                * PoisonDefaults.windowOpenProbability(choice.m(), 0.05, choice.n());
    assertThat(clustered).isCloseTo(expected, within(1e-9));
    assertThat(clustered)
        .as("a burst of 5% errors opens the circuit: informational, it is no constraint")
        .isGreaterThan(choice.falseOpenPerDay());
    assertThat(choose()).as("the choice does not depend on the burst").isEqualTo(choice);
  }

  @Test
  void failsLoudlyWhenNoPairQualifies() {
    assertThatThrownBy(
            () ->
                PoisonDefaults.choose(
                    RECORDS_PER_DAY,
                    BACKGROUND,
                    DEFECTIVE,
                    MIN_DETECTION,
                    MAX_FALSE_OPEN,
                    new int[] {2},
                    new int[] {20}))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsNonsenseInputs() {
    assertThatThrownBy(() -> PoisonDefaults.windowOpenProbability(0, 0.1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PoisonDefaults.windowOpenProbability(10, 1.5, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
