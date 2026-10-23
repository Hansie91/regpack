package dev.regpack.ports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetentionPolicyTest {
  private static final Instant AT = Instant.parse("2026-10-19T12:00:00.123456Z");

  @Test
  void fixedDaysRetainsTheNamedClassForThatManyDaysFromTheWriteTime() {
    EvidenceStore.Retention retention = RetentionPolicy.fixedDays(1826).retentionFor("journal", AT);
    assertThat(retention.retentionClass().name()).isEqualTo("journal");
    assertThat(retention.retainUntil()).isEqualTo(AT.plus(Duration.ofDays(1826)));
    assertThat(RetentionPolicy.fixedDays(1).retentionFor("sealed", AT).retainUntil())
        .isEqualTo(AT.plus(Duration.ofDays(1)));
  }

  @Test
  void fixedDaysRefusesAPeriodThatRetainsNothing() {
    assertThatThrownBy(() -> RetentionPolicy.fixedDays(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetentionPolicy.fixedDays(-5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullClassOrWriteTimeIsRefused() {
    RetentionPolicy policy = RetentionPolicy.fixedDays(10);
    assertThatThrownBy(() -> policy.retentionFor(null, AT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> policy.retentionFor("journal", null))
        .isInstanceOf(NullPointerException.class);
  }
}
