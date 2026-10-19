package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** What the workflow can set on the capacity probe (T21): environment variables only. */
class ProbeSettingsTest {
  @Test
  void theDefaultsAreTheSpLShape() {
    ProbeSettings settings = ProbeSettings.parse(Map.of());

    assertThat(settings.variant()).isEqualTo("local");
    assertThat(settings.batchSizes()).containsExactly(5_000, 10_000);
    assertThat(settings.writers()).isEqualTo(4);
    assertThat(settings.ratePerSecond()).isEqualTo(3_470);
    assertThat(settings.seconds()).isEqualTo(1_200);
    assertThat(settings.memoryLimitBytes()).isEqualTo(OptionalLong.empty());
    assertThat(settings.forceSynchronousCommit()).isTrue();
  }

  @Test
  void everyVariableIsRead() {
    ProbeSettings settings =
        ProbeSettings.parse(
            Map.of(
                "REGPACK_CAPACITY_VARIANT", "C",
                "REGPACK_CAPACITY_BATCH_SIZES", "2000, 3000",
                "REGPACK_CAPACITY_SECONDS", "30",
                "REGPACK_CAPACITY_RATE", "1000",
                "REGPACK_CAPACITY_WRITERS", "2",
                "REGPACK_CAPACITY_MEMORY_LIMIT_MB", "1363",
                "REGPACK_CAPACITY_SYNCHRONOUS_COMMIT", "off"));

    assertThat(settings.variant()).isEqualTo("C");
    assertThat(settings.batchSizes()).containsExactly(2_000, 3_000);
    assertThat(settings.seconds()).isEqualTo(30);
    assertThat(settings.ratePerSecond()).isEqualTo(1_000);
    assertThat(settings.writers()).isEqualTo(2);
    assertThat(settings.memoryLimitBytes()).isEqualTo(OptionalLong.of(1_363L * 1024 * 1024));
    assertThat(settings.forceSynchronousCommit()).isFalse();
  }

  @Test
  void theCgroupDirectoryDefaultsToTheProcessAndCanBePointedAtTheContainer() {
    assertThat(ProbeSettings.parse(Map.of()).cgroupDirectory())
        .isEqualTo(Sampler.DEFAULT_CGROUP_DIRECTORY);
    assertThat(
            ProbeSettings.parse(
                    Map.of(
                        "REGPACK_CAPACITY_CGROUP_DIR",
                        " /sys/fs/cgroup/system.slice/docker-abc.scope "))
                .cgroupDirectory())
        .isEqualTo(java.nio.file.Path.of("/sys/fs/cgroup/system.slice/docker-abc.scope"));
    assertThat(ProbeSettings.parse(Map.of("REGPACK_CAPACITY_CGROUP_DIR", " ")).cgroupDirectory())
        .isEqualTo(Sampler.DEFAULT_CGROUP_DIRECTORY);
  }

  @Test
  void anEmptyValueIsTheSameAsUnset() {
    ProbeSettings settings =
        ProbeSettings.parse(
            Map.of("REGPACK_CAPACITY_MEMORY_LIMIT_MB", "", "REGPACK_CAPACITY_SECONDS", " "));

    assertThat(settings.memoryLimitBytes()).isEmpty();
    assertThat(settings.seconds()).isEqualTo(1_200);
  }

  @Test
  void aBadValueIsRefusedAndNamesItsVariable() {
    assertRefused("REGPACK_CAPACITY_VARIANT", "../etc");
    assertRefused("REGPACK_CAPACITY_BATCH_SIZES", "5000,x");
    assertRefused("REGPACK_CAPACITY_BATCH_SIZES", "0");
    assertRefused("REGPACK_CAPACITY_SECONDS", "0");
    assertRefused("REGPACK_CAPACITY_SECONDS", "-5");
    assertRefused("REGPACK_CAPACITY_RATE", "abc");
    assertRefused("REGPACK_CAPACITY_WRITERS", "0");
    assertRefused("REGPACK_CAPACITY_WRITERS", "99");
    assertRefused("REGPACK_CAPACITY_MEMORY_LIMIT_MB", "tiny");
    assertRefused("REGPACK_CAPACITY_SYNCHRONOUS_COMMIT", "maybe");
  }

  private static void assertRefused(String variable, String value) {
    assertThatThrownBy(() -> ProbeSettings.parse(Map.of(variable, value)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(variable);
  }
}
