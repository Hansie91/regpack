package dev.regpack.testkit.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FaultPlanTest {
  private static final List<String> POINTS = List.of("A.x:BEFORE", "A.y:AFTER", "B.z:AFTER");

  @Test
  void seededPlanIsStableAcrossRuns() {
    String first = FaultPlan.seeded(42, POINTS, 3).describe();
    for (int i = 0; i < 100; i++) {
      assertThat(FaultPlan.seeded(42, POINTS, 3).describe()).isEqualTo(first);
    }
    assertThat(FaultPlan.seeded(42, List.copyOf(POINTS), 3).describe()).isEqualTo(first);
  }

  @Test
  void differentSeedsCoverMoreThanOnePlan() {
    Set<String> descriptions = new HashSet<>();
    for (long seed = 0; seed < 20; seed++) {
      descriptions.add(FaultPlan.seeded(seed, POINTS, 3).describe());
    }
    assertThat(descriptions.size()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void describeIsLocaleIndependent() {
    assertThat(FaultPlan.crashAt("IngestLedger.commitIngest:AFTER", 2).describe())
        .isEqualTo("crash IngestLedger.commitIngest:AFTER #2");
  }

  @Test
  void seededPlanFollowsTheSpecifiedAlgorithm() {
    // new SplittableRandom(seed): the point is drawn first, then hit = 1 + nextInt(maxHit).
    assertThat(FaultPlan.seeded(42, POINTS, 3).describe()).isEqualTo("crash B.z:AFTER #1");
    assertThat(FaultPlan.seeded(7, POINTS, 3).describe()).isEqualTo("crash A.y:AFTER #1");
    assertThat(FaultPlan.seeded(0, POINTS, 3).describe()).isEqualTo("crash A.y:AFTER #2");
    assertThat(FaultPlan.seeded(123456789L, POINTS, 3).describe()).isEqualTo("crash A.x:BEFORE #2");
  }

  @Test
  void seededHitIsWithinOneAndMaxHitAndGuardsReject() {
    for (int maxHit : new int[] {1, 2, 5}) {
      Set<Integer> hits = new HashSet<>();
      for (long seed = 0; seed < 200; seed++) {
        String described = FaultPlan.seeded(seed, POINTS, maxHit).describe();
        int hit = Integer.parseInt(described.substring(described.lastIndexOf('#') + 1));
        assertThat(hit).isBetween(1, maxHit);
        hits.add(hit);
      }
      assertThat(hits).hasSize(maxHit);
    }
    assertThatThrownBy(() -> FaultPlan.seeded(1, List.of(), 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FaultPlan.seeded(1, POINTS, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FaultPlan.seeded(1, POINTS, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void describeCoversEveryKindAndNone() {
    assertThat(FaultPlan.none().describe()).isEqualTo("none");
    assertThat(FaultPlan.failAt("X.y:BEFORE", 1, new IllegalStateException("boom")).describe())
        .isEqualTo("fail X.y:BEFORE #1 IllegalStateException");
    assertThat(FaultPlan.advanceClockAt("B.z:AFTER", 2, Duration.ofSeconds(30)).describe())
        .isEqualTo("advance-clock B.z:AFTER #2 PT30S");
  }

  @Test
  void thenKeepsBothPlansInOrderAndLeavesTheOriginalsUnchanged() {
    FaultPlan crash = FaultPlan.crashAt("A.x:BEFORE", 1);
    FaultPlan advance = FaultPlan.advanceClockAt("B.z:AFTER", 2, Duration.ofSeconds(30));
    FaultPlan both = crash.then(advance);
    assertThat(both.describe()).isEqualTo("crash A.x:BEFORE #1; advance-clock B.z:AFTER #2 PT30S");
    assertThat(advance.then(crash).describe())
        .isEqualTo("advance-clock B.z:AFTER #2 PT30S; crash A.x:BEFORE #1");
    assertThat(crash.describe()).isEqualTo("crash A.x:BEFORE #1");
    assertThat(advance.describe()).isEqualTo("advance-clock B.z:AFTER #2 PT30S");
    assertThat(both).isNotSameAs(crash).isNotSameAs(advance);
    assertThat(crash.then(FaultPlan.none()).describe()).isEqualTo("crash A.x:BEFORE #1");
    assertThat(FaultPlan.none().then(crash).describe()).isEqualTo("crash A.x:BEFORE #1");
    assertThat(FaultPlan.none().then(FaultPlan.none()).describe()).isEqualTo("none");
  }

  @Test
  void crashAtRejectsNonPositiveHit() {
    assertThatThrownBy(() -> FaultPlan.crashAt("A.x:BEFORE", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FaultPlan.crashAt("A.x:BEFORE", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
