package dev.regpack.engine.packci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EngineBuild;
import org.junit.jupiter.api.Test;

/** Spec 6.3: Tier 2 runs on the current engine only when the stored build is replay-compatible. */
class Tier2PlanTest {
  private static final EngineBuild CURRENT = new EngineBuild(1, 2, 3, 1);

  @Test
  void compatibleBuildsReplayOnTheCurrentEngine() {
    // same major and library version; minor and patch never matter, in either direction
    assertThat(Tier2Plan.decide("1.2.3+lib1", CURRENT)).isEqualTo(Tier2Plan.CURRENT_ENGINE);
    assertThat(Tier2Plan.decide("1.0.0+lib1", CURRENT)).isEqualTo(Tier2Plan.CURRENT_ENGINE);
    assertThat(Tier2Plan.decide("1.9.9+lib1", CURRENT)).isEqualTo(Tier2Plan.CURRENT_ENGINE);
  }

  @Test
  void aDifferentMajorNeedsTheArchivedBuild() {
    assertThat(Tier2Plan.decide("2.2.3+lib1", CURRENT))
        .isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
    assertThat(Tier2Plan.decide("0.2.3+lib1", CURRENT))
        .isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
  }

  @Test
  void aDifferentLibraryVersionNeedsTheArchivedBuild() {
    assertThat(Tier2Plan.decide("1.2.3+lib2", CURRENT))
        .isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
    assertThat(Tier2Plan.decide("1.2.3+lib0", CURRENT))
        .isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
  }

  @Test
  void anUnparsableStoredBuildFailsClosed() {
    // "p0" is the stored build text of the P0 skeleton decisions
    assertThat(Tier2Plan.decide("p0", CURRENT)).isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
    assertThat(Tier2Plan.decide("", CURRENT)).isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
    assertThat(Tier2Plan.decide("1.2.3", CURRENT)).isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
    assertThat(Tier2Plan.decide("1.2.3+lib1\n", CURRENT))
        .isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
    assertThat(Tier2Plan.decide("01.2.3+lib1", CURRENT))
        .isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
    assertThat(Tier2Plan.decide("1.2.3+lib1".repeat(1000), CURRENT))
        .isEqualTo(Tier2Plan.ARCHIVED_BUILD_REQUIRED);
  }

  @Test
  void aMissingArgumentIsAProgrammingError() {
    assertThatThrownBy(() -> Tier2Plan.decide(null, CURRENT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Tier2Plan.decide("1.2.3+lib1", null))
        .isInstanceOf(NullPointerException.class);
  }
}
