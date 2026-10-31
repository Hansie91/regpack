package dev.regpack.model.ident;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MicTest {
  @Test
  void shape() {
    assertThat(Mic.hasShape("XPAR")).isTrue();
    assertThat(Mic.hasShape("XAMS")).isTrue();
    assertThat(Mic.hasShape("A1B2")).isTrue();
    assertThat(Mic.hasShape("0000")).isTrue();
    assertThat(Mic.hasShape("xpar")).isFalse();
    assertThat(Mic.hasShape("XPA")).isFalse();
    assertThat(Mic.hasShape("XPARR")).isFalse();
    assertThat(Mic.hasShape("XP R")).isFalse();
    assertThat(Mic.hasShape("XP-R")).isFalse();
    assertThat(Mic.hasShape("")).isFalse();
    assertThat(Mic.hasShape(null)).isFalse();
    assertThat(Mic.hasShape("ＸPAR")).isFalse();
    assertThat(Mic.hasShape("XPA２")).isFalse();
  }
}
