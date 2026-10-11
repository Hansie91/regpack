package dev.regpack.spikes.x.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.spikes.x.validate.PolicyModel.Component;
import dev.regpack.spikes.x.validate.PolicyModel.Policy;
import org.junit.jupiter.api.Test;

class PolicyModelTest {
  /** Per thread: 20,000 records/s written, 20,000 validated per record, 40,000 through the gate. */
  private static final Component FAST_XSD = new Component(20_000, 20_000, 40_000, 1.0, 4);

  /** The same, with a slow per-record validation (500 records/s) and a cheap bisect. */
  private static final Component SLOW_XSD = new Component(20_000, 500, 40_000, 0.01, 4);

  @Test
  void capacityFollowsTheFormula() {
    // 4 threads; writer 10,000/s, per-record XSD 5,000/s, gate 20,000/s, bisect 2 s per defect
    Component c = new Component(10_000, 5_000, 20_000, 2.0, 4);

    // ALL, no defects: 1/10,000 + 1/5,000 + 1/20,000 = 3.5e-4 s per record
    assertThat(PolicyModel.capacity(c, PolicyModel.ALL, 0.0)).isCloseTo(4 / 3.5e-4, within(1e-6));

    // ALL keeps no defect alive, so a defect rate changes nothing
    assertThat(PolicyModel.capacity(c, PolicyModel.ALL, 0.01)).isCloseTo(4 / 3.5e-4, within(1e-6));

    // SAMPLED_10, defect rate 1e-4:
    //   base   = 1/10,000 + 0.1/5,000 + 1/20,000                  = 1.7e-4
    //   bisect = 1e-4 x 0.9 x 2                                    = 1.8e-4
    //   pFail  = 1 - (1 - 9e-5)^100000 (independent computation)
    //   rebuild = pFail x (1/10,000 + 1/20,000)
    double pFail = 1 - Math.pow(1 - 9e-5, 100_000);
    double expected = 4 / (1.7e-4 + 1.8e-4 + pFail * 1.5e-4);
    assertThat(PolicyModel.capacity(c, PolicyModel.SAMPLED_10, 1e-4))
        .isCloseTo(expected, within(expected * 1e-9));
    // the rebuild is nearly certain here: pFail is about 0.99988
    assertThat(pFail).isBetween(0.9998, 0.9999);
  }

  @Test
  void allWinsWhenCapacityIsAmple() {
    for (double rate : new double[] {0.001, 0.01}) {
      double all = PolicyModel.capacity(FAST_XSD, PolicyModel.ALL, rate);
      double sampled = PolicyModel.capacity(FAST_XSD, PolicyModel.SAMPLED_10, rate);
      assertThat(all).as("ALL at defect rate %s", rate).isGreaterThan(3_472);
      assertThat(all).isGreaterThan(sampled);
    }
  }

  @Test
  void samplingHelpsOnlyWhenPerRecordCostDominates() {
    double slowAll = PolicyModel.capacity(SLOW_XSD, PolicyModel.ALL, 0.0);
    double slowSampled = PolicyModel.capacity(SLOW_XSD, PolicyModel.SAMPLED_10, 0.0);
    assertThat(slowSampled / slowAll).as("slow per-record XSD").isGreaterThan(5.0);

    double fastAll = PolicyModel.capacity(FAST_XSD, PolicyModel.ALL, 0.0);
    double fastSampled = PolicyModel.capacity(FAST_XSD, PolicyModel.SAMPLED_10, 0.0);
    assertThat(fastSampled / fastAll).as("fast per-record XSD").isLessThan(2.0);

    // and a defect rate takes the advantage away again, because every unsampled defect is bisected
    double slowAllDefects = PolicyModel.capacity(SLOW_XSD, PolicyModel.ALL, 0.01);
    double slowSampledDefects = PolicyModel.capacity(SLOW_XSD, PolicyModel.SAMPLED_10, 0.01);
    assertThat(slowSampledDefects).isLessThan(slowSampled);
    assertThat(slowAllDefects).isEqualTo(slowAll, within(1e-9));
  }

  @Test
  void rebuildTermAppearsOnlyWhenUnsampledDefectsExist() {
    // a free bisect isolates the rebuild term
    Component freeBisect = new Component(20_000, 20_000, 40_000, 0.0, 4);
    assertThat(PolicyModel.capacity(freeBisect, PolicyModel.ALL, 0.01))
        .as("every record validated: no rebuild")
        .isEqualTo(PolicyModel.capacity(freeBisect, PolicyModel.ALL, 0.0), within(1e-9));
    double clean = PolicyModel.capacity(freeBisect, PolicyModel.SAMPLED_10, 0.0);
    double dirty = PolicyModel.capacity(freeBisect, PolicyModel.SAMPLED_10, 1e-4);
    assertThat(dirty).as("unsampled defects force a rebuild").isLessThan(clean);
    // one defect in a million records: pFail is about 9%, so the loss is small but real
    double slight = PolicyModel.capacity(freeBisect, PolicyModel.SAMPLED_10, 1e-6);
    assertThat(slight).isLessThan(clean).isGreaterThan(dirty);
  }

  @Test
  void deviationIsRelative() {
    assertThat(PolicyModel.relDeviation(110.0, 100.0)).isEqualTo(0.10, within(1e-12));
    assertThat(PolicyModel.relDeviation(90.0, 100.0)).isEqualTo(-0.10, within(1e-12));
    assertThat(PolicyModel.relDeviation(5_500.0, 5_000.0)).isEqualTo(0.10, within(1e-12));
    assertThat(PolicyModel.relDeviation(100.0, 100.0)).isZero();
    assertThatThrownBy(() -> PolicyModel.relDeviation(1.0, 0.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidInputsAreRejected() {
    assertThatThrownBy(() -> new Component(0, 1, 1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Component(1, 1, 1, -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Component(1, 1, 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PolicyModel.capacity(FAST_XSD, PolicyModel.ALL, -0.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PolicyModel.capacity(FAST_XSD, PolicyModel.ALL, 1.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PolicyModel.ALL).isEqualTo(new Policy("ALL", 1.0));
    assertThat(PolicyModel.SAMPLED_10).isEqualTo(new Policy("SAMPLED_10", 0.10));
  }
}
