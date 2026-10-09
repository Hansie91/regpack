package dev.regpack.testkit.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  void crashAtRejectsNonPositiveHit() {
    assertThatThrownBy(() -> FaultPlan.crashAt("A.x:BEFORE", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FaultPlan.crashAt("A.x:BEFORE", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
