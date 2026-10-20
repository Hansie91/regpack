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
    // A single segment over 100 characters is refused even when the 512-character total holds.
    assertThatThrownBy(() -> EvidenceKey.of("x".repeat(512)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EvidenceKey("x".repeat(512)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSegmentOf100CharactersIsAcceptedAndOf101Refused() {
    assertThat(EvidenceKey.of("x".repeat(100)).path()).hasSize(100);
    assertThat(new EvidenceKey("a/" + "y".repeat(100)).path()).hasSize(102);
    assertThatThrownBy(() -> EvidenceKey.of("x".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EvidenceKey.of("a", "y".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EvidenceKey("a/" + "y".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMaximumLengthKeyOfSegmentsWithinTheLimitIsAccepted() {
    String segment = "z".repeat(100);
    EvidenceKey key = EvidenceKey.of(segment, segment, segment, segment, segment, "q".repeat(7));
    assertThat(key.path()).hasSize(512);
    assertThatThrownBy(
            () -> EvidenceKey.of(segment, segment, segment, segment, segment, "q".repeat(8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namespaceIsFirstSegment() {
    assertThat(EvidenceKey.of("landing", "ab", "1").namespace()).isEqualTo("landing");
    assertThat(EvidenceKey.of("solo").namespace()).isEqualTo("solo");
  }
}
