package dev.regpack.inmemory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.security.SecretNotFoundException;
import dev.regpack.ports.security.SecretRef;
import dev.regpack.ports.security.SecretValue;
import org.junit.jupiter.api.Test;

class InMemorySecretsTest {
  @Test
  void resolvesStoredSecret() {
    InMemorySecrets secrets =
        new InMemorySecrets().put("vault://kv/prod/sftp", "pw".getBytes(UTF_8));
    try (SecretValue value = secrets.resolve(SecretRef.parse("vault://kv/prod/sftp"))) {
      assertThat(value.utf8()).isEqualTo("pw");
    }
  }

  @Test
  void missingSecretThrowsSecretNotFound() {
    InMemorySecrets secrets = new InMemorySecrets();
    assertThatThrownBy(() -> secrets.resolve(SecretRef.parse("env://MISSING")))
        .isInstanceOf(SecretNotFoundException.class);
  }

  @Test
  void supportsOnlyTheSpecSchemes() {
    InMemorySecrets secrets = new InMemorySecrets();
    for (String scheme :
        new String[] {"vault", "openbao", "awssm", "azkv", "gcpsm", "file", "env"}) {
      assertThat(secrets.supports(scheme)).isTrue();
    }
    assertThat(secrets.supports("http")).isFalse();
    assertThat(secrets.supports("")).isFalse();
  }
}
