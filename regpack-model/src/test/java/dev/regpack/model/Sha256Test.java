package dev.regpack.model;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Sha256Test {
  @Test
  void knownVectors() {
    assertThat(Sha256.ofUtf8("").hex())
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(Sha256.ofUtf8("abc").hex())
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(Sha256.of("hello".getBytes(UTF_8)).hex())
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
  }

  @Test
  void rejectsUppercaseAndWrongLength() {
    assertThatThrownBy(
            () -> new Sha256("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Sha256("abc")).isInstanceOf(IllegalArgumentException.class);
  }
}
