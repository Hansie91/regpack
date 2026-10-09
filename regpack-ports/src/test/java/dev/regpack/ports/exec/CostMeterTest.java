package dev.regpack.ports.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CostMeterTest {
  @Test
  void chargesUpToTheLimitInclusive() {
    CostMeter meter = new CostMeter(new CostBudget(10));
    meter.enter("R1");
    for (int i = 0; i < 10; i++) {
      meter.charge(1);
    }
    assertThat(meter.used()).isEqualTo(10);
  }

  @Test
  void exceedingThrowsAndKeepsUsedUnchanged() {
    CostMeter meter = new CostMeter(new CostBudget(10));
    meter.enter("R1");
    for (int i = 0; i < 10; i++) {
      meter.charge(1);
    }
    assertThatThrownBy(() -> meter.charge(1))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            e -> {
              assertThat(e.ruleId()).isEqualTo("R1");
              assertThat(e.used()).isEqualTo(10);
              assertThat(e.limit()).isEqualTo(10);
            });
    assertThat(meter.used()).isEqualTo(10);
  }

  @Test
  void negativeChargeIsRejected() {
    CostMeter meter = new CostMeter(new CostBudget(10));
    assertThatThrownBy(() -> meter.charge(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThat(meter.used()).isZero();
  }

  @Test
  void enterSetsTheCurrentRule() {
    CostMeter meter = new CostMeter(new CostBudget(10));
    meter.enter("R1");
    assertThat(meter.currentRule()).isEqualTo("R1");
    meter.enter("R2");
    assertThat(meter.currentRule()).isEqualTo("R2");
  }
}
