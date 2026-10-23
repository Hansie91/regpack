package dev.regpack.evidence.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import org.junit.jupiter.api.Test;

class SealedKeysTest {
  @Test
  void theKeyIsTheShardOfTheFirstTwoHexDigitsAndTheFullHash() {
    // SHA-256 of the empty input
    Sha256 empty = Sha256.of(new byte[0]);
    assertThat(empty.hex())
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(SealedKeys.of(empty).path())
        .isEqualTo("sealed/e3/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void theSameBytesAreOneKeyAndOtherBytesAreAnother() {
    assertThat(SealedKeys.of(Sha256.ofUtf8("a"))).isEqualTo(SealedKeys.of(Sha256.ofUtf8("a")));
    assertThat(SealedKeys.of(Sha256.ofUtf8("a"))).isNotEqualTo(SealedKeys.of(Sha256.ofUtf8("b")));
  }

  @Test
  void aNullHashIsRefused() {
    assertThatThrownBy(() -> SealedKeys.of(null)).isInstanceOf(NullPointerException.class);
  }
}
