package dev.regpack.ports.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretValueTest {
  @Test
  void toStringIsRedacted() {
    SecretValue value = SecretValue.of("hunter2".getBytes(UTF_8));
    assertThat(value.toString()).isEqualTo("SecretValue[REDACTED]");
  }

  @Test
  void closeZeroesTheHeldBytes() {
    SecretValue value = SecretValue.of("hunter2".getBytes(UTF_8));
    assertThat(value.utf8()).isEqualTo("hunter2");
    value.close();
    assertThatThrownBy(value::copyBytes).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(value::utf8).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void copyBytesReturnsACopy() {
    byte[] source = "abc".getBytes(UTF_8);
    SecretValue value = SecretValue.of(source);
    byte[] copy = value.copyBytes();
    copy[0] = 'z';
    assertThat(value.utf8()).isEqualTo("abc");
    source[1] = 'z';
    assertThat(value.utf8()).isEqualTo("abc");
  }
}
