package dev.regpack.spikes.l.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.spikes.l.partition.PartitionPlan.Bound;
import dev.regpack.spikes.l.partition.PartitionPlan.Granularity;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartitionPlanTest {
  @Test
  void boundsAreContiguousDailyAndMonthly() {
    LocalDate first = LocalDate.of(2021, 10, 1);
    List<Bound> daily = PartitionPlan.plan(first, 1_826, Granularity.DAILY);
    assertThat(daily).hasSize(1_826);
    assertThat(daily.get(0).from()).isEqualTo(first);
    for (int i = 0; i < daily.size(); i++) {
      Bound b = daily.get(i);
      assertThat(ChronoUnit.DAYS.between(b.from(), b.to())).as("day " + i).isEqualTo(1);
      if (i > 0) {
        assertThat(b.from()).as("contiguous at " + i).isEqualTo(daily.get(i - 1).to());
      }
    }
    // 1,826 daily partitions span exactly 1,826 days
    assertThat(ChronoUnit.DAYS.between(daily.get(0).from(), daily.get(1_825).to()))
        .isEqualTo(1_826);
    assertThat(daily.get(0).name()).isEqualTo("decision_20211001");
    assertThat(daily.get(1_825).name())
        .isEqualTo("decision_" + first.plusDays(1_825).toString().replace("-", ""));

    // 2027-12 to 2028-03 holds the leap day of February 2028
    List<Bound> monthly = PartitionPlan.plan(LocalDate.of(2027, 12, 1), 4, Granularity.MONTHLY);
    assertThat(monthly.stream().map(Bound::name).toList())
        .containsExactly(
            "decision_202712", "decision_202801", "decision_202802", "decision_202803");
    assertThat(monthly.stream().map(b -> ChronoUnit.DAYS.between(b.from(), b.to())).toList())
        .containsExactly(31L, 31L, 29L, 31L);
    for (int i = 1; i < monthly.size(); i++) {
      assertThat(monthly.get(i).from()).isEqualTo(monthly.get(i - 1).to());
      assertThat(monthly.get(i).from().getDayOfMonth()).isEqualTo(1);
    }
    // 60 monthly partitions span five years
    List<Bound> five = PartitionPlan.plan(LocalDate.of(2021, 10, 1), 60, Granularity.MONTHLY);
    assertThat(five.get(59).to()).isEqualTo(LocalDate.of(2026, 10, 1));
  }

  @Test
  void aMonthlyPlanStartsOnTheFirstOfAMonth() {
    assertThatThrownBy(() -> PartitionPlan.plan(LocalDate.of(2026, 9, 15), 3, Granularity.MONTHLY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("first of a month");
  }

  @Test
  void aPlanNeedsAPositiveCount() {
    assertThatThrownBy(() -> PartitionPlan.plan(LocalDate.of(2026, 9, 1), 0, Granularity.DAILY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theCreateStatementNamesTheParentAndTheRange() {
    Bound b = PartitionPlan.plan(LocalDate.of(2026, 9, 30), 1, Granularity.DAILY).get(0);
    assertThat(PartitionPlan.createStatement(b))
        .isEqualTo(
            "CREATE TABLE decision_20260930 PARTITION OF decision"
                + " FOR VALUES FROM ('2026-09-30') TO ('2026-10-01')");
  }
}
