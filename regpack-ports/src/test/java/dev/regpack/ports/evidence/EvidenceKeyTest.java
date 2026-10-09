package dev.regpack.ports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EvidenceKeyTest {
  @Test
  void acceptsSegmentsAndJoinsWithSlash() {
    assertThat(EvidenceKey.of("landing", "ab", "1").path()).isEqualTo("landing/ab/1");
  }

  @Test
  void rejectsDotDotEmptyAndOverlong() {
    assertThatThrownBy(() -> EvidenceKey.of("a", ".."))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EvidenceKey.of("a", ".")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EvidenceKey.of("a", "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EvidenceKey.of("a b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EvidenceKey.of()).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EvidenceKey("a//b")).isInstanceOf(IllegalArgumentException.class);
    String segment = "x".repeat(200);
    assertThatThrownBy(() -> EvidenceKey.of(segment, segment, segment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(EvidenceKey.of("x".repeat(512)).path()).hasSize(512);
  }

  @Test
  void namespaceIsFirstSegment() {
    assertThat(EvidenceKey.of("landing", "ab", "1").namespace()).isEqualTo("landing");
    assertThat(EvidenceKey.of("solo").namespace()).isEqualTo("solo");
  }
}
