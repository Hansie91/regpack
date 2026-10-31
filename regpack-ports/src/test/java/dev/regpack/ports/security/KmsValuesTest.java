package dev.regpack.ports.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** M82: {@link KmsSignature} and {@link WrappedKey} compare by content and print lengths only. */
class KmsValuesTest {
  @Test
  void wrappedKeyAndSignatureCompareByContent() {
    byte[] bytes = "0123456789abcdef".getBytes(UTF_8);

    WrappedKey wrapped = new WrappedKey("kek-1", bytes);
    WrappedKey same = new WrappedKey("kek-1", bytes.clone());
    assertThat(wrapped).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(new HashSet<>(Set.of(wrapped))).contains(same);
    assertThat(wrapped.ciphertext()).isEqualTo(wrapped.ciphertext());
    byte[] flipped = bytes.clone();
    flipped[15] ^= 1;
    assertThat(wrapped).isNotEqualTo(new WrappedKey("kek-1", flipped));
    assertThat(wrapped).isNotEqualTo(new WrappedKey("kek-2", bytes));
    assertThat(wrapped).isNotEqualTo(new WrappedKey("kek-1", new byte[0]));
    assertThat(wrapped).isNotEqualTo(null);
    assertThat(wrapped).isNotEqualTo("kek-1");

    KmsSignature signature = new KmsSignature("key-1", "Ed25519", bytes);
    KmsSignature sameSignature = new KmsSignature("key-1", "Ed25519", bytes.clone());
    assertThat(signature).isEqualTo(sameSignature).hasSameHashCodeAs(sameSignature);
    assertThat(new HashSet<>(Set.of(signature))).contains(sameSignature);
    assertThat(signature).isNotEqualTo(new KmsSignature("key-1", "Ed25519", flipped));
    assertThat(signature).isNotEqualTo(new KmsSignature("key-2", "Ed25519", bytes));
    assertThat(signature).isNotEqualTo(new KmsSignature("key-1", "RSA", bytes));
    assertThat(signature).isNotEqualTo(null);
    assertThat(signature).isNotEqualTo("key-1");
  }

  @Test
  void aMutatedSourceArrayDoesNotChangeEqualityOrHash() {
    byte[] bytes = "0123456789abcdef".getBytes(UTF_8);
    WrappedKey wrapped = new WrappedKey("kek-1", bytes);
    KmsSignature signature = new KmsSignature("key-1", "Ed25519", bytes);
    int wrappedHash = wrapped.hashCode();
    int signatureHash = signature.hashCode();
    bytes[0] ^= 1;
    assertThat(wrapped.hashCode()).isEqualTo(wrappedHash);
    assertThat(signature.hashCode()).isEqualTo(signatureHash);
    assertThat(wrapped.ciphertext()[0]).isEqualTo((byte) '0');
    assertThat(signature.value()[0]).isEqualTo((byte) '0');
  }

  @Test
  void aMutatedReturnedArrayDoesNotChangeTheValue() {
    WrappedKey wrapped = new WrappedKey("kek-1", "0123456789abcdef".getBytes(UTF_8));
    KmsSignature signature =
        new KmsSignature("key-1", "Ed25519", "0123456789abcdef".getBytes(UTF_8));
    int wrappedHash = wrapped.hashCode();
    int signatureHash = signature.hashCode();

    wrapped.ciphertext()[0] ^= 1;
    signature.value()[0] ^= 1;

    assertThat(wrapped.ciphertext()[0]).isEqualTo((byte) '0');
    assertThat(signature.value()[0]).isEqualTo((byte) '0');
    assertThat(wrapped.hashCode()).isEqualTo(wrappedHash);
    assertThat(signature.hashCode()).isEqualTo(signatureHash);
  }

  @Test
  void toStringShowsLengthsOnly() {
    byte[] bytes = "SECRETSECRETSECRETSECRET".getBytes(UTF_8);
    String wrapped = new WrappedKey("kek-secret-id", bytes).toString();
    assertThat(wrapped).isEqualTo("WrappedKey[ciphertextLength=24]");
    String signature = new KmsSignature("key-secret-id", "Ed25519", bytes).toString();
    assertThat(signature).isEqualTo("KmsSignature[valueLength=24]");
    for (String text : new String[] {wrapped, signature}) {
      assertThat(text).doesNotContain("SECRET", "secret-id", "[B@");
    }
  }
}
