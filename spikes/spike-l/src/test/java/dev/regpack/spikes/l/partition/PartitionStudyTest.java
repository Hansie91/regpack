package dev.regpack.spikes.l.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The parts of the study that decide a number and need no server: the p99 of a block, the argument
 * checks and the window of populated days. The queries themselves are proven on the runner
 * (PartitionIT).
 */
class PartitionStudyTest {
  @Test
  void blockP99UsesTheNearestRankOfEachBlock() {
    long[] nanos = new long[400];
    for (int i = 0; i < 200; i++) {
      nanos[i] = i + 1; // 1 .. 200: nearest rank 198 of 200 is 198
      nanos[200 + i] = 5;
    }
    double[] p99 = PartitionStudy.blockP99(nanos, 2, 200);
    assertThat(p99).containsExactly(198.0, 5.0);
  }

  @Test
  void blockP99NeedsExactlyBlocksTimesLookups() {
    assertThatThrownBy(() -> PartitionStudy.blockP99(new long[399], 2, 200))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSinglePointIsCheckedLikeTheWholeStudy() {
    // daily: the count must hold the populated days; monthly: the months must reach back over them
    PartitionStudy.validatePoint(PartitionPlan.Granularity.DAILY, 1_826, 30, 300_000, 15, 200);
    PartitionStudy.validatePoint(PartitionPlan.Granularity.MONTHLY, 60, 30, 300_000, 15, 200);
    assertThatThrownBy(
            () ->
                PartitionStudy.validatePoint(PartitionPlan.Granularity.DAILY, 20, 30, 1_000, 3, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("populated");
    assertThatThrownBy(
            () ->
                PartitionStudy.validatePoint(
                    PartitionPlan.Granularity.MONTHLY, 1, 40, 1_000, 3, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("monthly");
    assertThatThrownBy(
            () -> PartitionStudy.validatePoint(PartitionPlan.Granularity.DAILY, 30, 10, 0, 3, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PartitionStudy.runPoint(
                    null, PartitionPlan.Granularity.DAILY, 30, 10, 1_000, 0, 10))
        .as("the arguments are checked before the server is touched")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theWindowOfPopulatedDaysEndsOnTheLastDay() {
    assertThat(PartitionStudy.LAST_DAY).isEqualTo(LocalDate.of(2026, 9, 30));
    assertThat(PartitionStudy.windowStart(30)).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(PartitionStudy.windowStart(1)).isEqualTo(LocalDate.of(2026, 9, 30));
  }

  @Test
  void theArgumentsAreChecked() {
    // fine: the planned study of the plan
    PartitionStudy.validate(new int[] {30, 365, 1_826}, 60, 30, 300_000, 15, 200);
    // the populated days must exist in every daily plan
    assertThatThrownBy(() -> PartitionStudy.validate(new int[] {20}, 60, 30, 1_000, 3, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("populated");
    // and the monthly plan must reach back over them: 30 days ending on 30 September are one month
    PartitionStudy.validate(new int[] {30}, 1, 30, 1_000, 3, 10);
    assertThatThrownBy(() -> PartitionStudy.validate(new int[] {60}, 1, 40, 1_000, 3, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("monthly");
    assertThatThrownBy(() -> PartitionStudy.validate(new int[] {30}, 3, 10, 0, 3, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PartitionStudy.validate(new int[] {30}, 3, 10, 1_000, 0, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PartitionStudy.validate(new int[] {}, 3, 10, 1_000, 3, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSchemaNamesAreLowerCaseIdentifiers() {
    assertThat(PartitionStudy.schemaName(PartitionPlan.Granularity.DAILY, 1_826))
        .isEqualTo("sp_part_d1826");
    assertThat(PartitionStudy.schemaName(PartitionPlan.Granularity.MONTHLY, 60))
        .isEqualTo("sp_part_m60");
  }

  @Test
  void theRowGeneratorIsASingleStatementPerDayAndKeysAreReproducibleOnTheServer() {
    // the same text builds the rows and the lookup keys: a key that differs by one character finds
    // nothing
    assertThat(PartitionStudy.IDENTITY_EXPR_ROWS)
        .isEqualTo("substr(md5(p.d::text || ':' || g::text), 1, 16)");
    assertThat(PartitionStudy.IDENTITY_EXPR_KEYS)
        .isEqualTo("substr(md5(t.d::text || ':' || t.g::text), 1, 16)");
    assertThat(PartitionStudy.INSERT_DECISIONS_SQL)
        .contains("INSERT INTO decision")
        .contains(PartitionStudy.IDENTITY_EXPR_ROWS)
        .contains("generate_series(1, p.n)");
    assertThat(PartitionStudy.INSERT_HEADS_SQL)
        .contains("INSERT INTO decision_head")
        .contains("as_at");
  }
}
