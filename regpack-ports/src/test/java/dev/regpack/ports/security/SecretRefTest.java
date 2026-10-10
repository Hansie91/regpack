package dev.regpack.ports.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretRefTest {
  @Test
  void parsesSchemeAndPath() {
    assertThat(SecretRef.parse("env://HOME")).isEqualTo(new SecretRef("env", "HOME"));
    assertThat(SecretRef.parse("vault://kv/prod/sftp"))
        .isEqualTo(new SecretRef("vault", "kv/prod/sftp"));
  }

  @Test
  void rejectsUnknownSchemeAndMalformedUris() {
    assertThatThrownBy(() -> SecretRef.parse("http://x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SecretRef.parse("env:/x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SecretRef.parse("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsEverySpecScheme() {
    for (String scheme :
        new String[] {"vault", "openbao", "awssm", "azkv", "gcpsm", "file", "env"}) {
      assertThat(SecretRef.parse(scheme + "://some/path").scheme()).isEqualTo(scheme);
    }
  }
}
