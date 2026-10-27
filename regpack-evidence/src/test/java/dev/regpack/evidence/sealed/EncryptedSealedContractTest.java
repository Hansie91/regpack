package dev.regpack.evidence.sealed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.crypto.EncryptedFixtures;
import dev.regpack.model.Sha256;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T6: the {@code EvidenceStore} and journal contract suites, unchanged, against the {@link
 * dev.regpack.evidence.crypto.EncryptingEvidenceStore} (every key encrypted) over P-SEALED on two
 * real directories. The shared methods come from the two interfaces; the methods below are about
 * what the two volumes hold and need the real directories. {@code simulateOutage} acts on the file
 * operations of the inner store, as in the raw binding; {@code reopen} opens the same directories
 * again below a new decorator over the same key service.
 */
class EncryptedSealedContractTest extends AbstractSealedContractTest {
  private static final EvidenceKey SECOND_COPY = EvidenceKey.of("landing", "second-copy");
  private static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);

  private final EncryptedFixtures fixtures = EncryptedFixtures.create();
  private EvidenceStore encrypted;

  @BeforeEach
  void encryptTheStore() {
    encrypted = fixtures.encrypt(super.store(), clock());
  }

  @Override
  public EvidenceStore store() {
    return encrypted;
  }

  @Override
  public EvidenceStore reopen() {
    return fixtures.encrypt(super.reopen(), clock());
  }

  private Path volume(String name) throws IOException {
    return root.toRealPath().resolve(name);
  }

  private PutRequest hello(EvidenceKey key) {
    return new PutRequest(
        key,
        HELLO,
        "text/plain",
        new Retention(new RetentionClass("landing"), clock().now().plus(Duration.ofDays(1826))));
  }

  @Test
  void bothVolumesHoldTheEnvelopeAndNeitherThePlaintext() throws IOException {
    assertThat(store().put(hello(SECOND_COPY))).isEqualTo(PutOutcome.CREATED);

    for (String name : new String[] {"vol-a", "vol-b"}) {
      byte[] file = Files.readAllBytes(SealedTamper.objectPath(volume(name), SECOND_COPY));
      assertThat(contains(file, HELLO)).as("the plaintext on " + name).isFalse();
      assertThat(contains(file, "RPEN".getBytes(StandardCharsets.US_ASCII)))
          .as("the envelope magic on " + name)
          .isTrue();
    }
    assertThat(store().get(SECOND_COPY).orElseThrow().bytes()).isEqualTo(HELLO);
  }

  @Test
  void aSecondCopyThatIsMissingIsCompletedByAPutOfTheSamePlaintext() throws IOException {
    store().put(hello(SECOND_COPY));
    byte[] onA = Files.readAllBytes(SealedTamper.objectPath(volume("vol-a"), SECOND_COPY));
    SealedTamper.deleteObject(volume("vol-b"), SECOND_COPY);

    // the decorator puts the stored envelope again: it does not seal a new one that would differ
    assertThat(store().put(hello(SECOND_COPY))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);

    assertThat(Files.readAllBytes(SealedTamper.objectPath(volume("vol-b"), SECOND_COPY)))
        .isEqualTo(onA);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volume("vol-a"), SECOND_COPY)))
        .isEqualTo(onA);
    assertThat(store().get(SECOND_COPY).orElseThrow().bytes()).isEqualTo(HELLO);
  }

  @Test
  void aDamagedCopyOfTheStoreIsStillACorruptionAlarmThroughTheDecorator() throws IOException {
    store().put(hello(SECOND_COPY));
    SealedTamper.flipByte(volume("vol-a"), SECOND_COPY);

    // the alarm of the inner store is neither swallowed nor turned into "already present" (KF25)
    assertThatThrownBy(() -> store().put(hello(SECOND_COPY)))
        .isInstanceOf(EvidenceCorruptionException.class);
    PutRequest other =
        new PutRequest(
            SECOND_COPY,
            "other".getBytes(StandardCharsets.UTF_8),
            "text/plain",
            hello(SECOND_COPY).retention());
    // other plaintext is refused on the readable copy before the put reaches the damaged one: the
    // alarm is the integrity alarm of the key, and nothing is acknowledged either
    assertThatThrownBy(() -> store().put(other)).isInstanceOf(EvidenceIntegrityException.class);
    // the other volume masks the damaged copy for a read, as it does without the decorator
    assertThat(store().get(SECOND_COPY).orElseThrow().bytes()).isEqualTo(HELLO);
    assertThat(Envelope.isEnvelope(rawBytes(SECOND_COPY))).isTrue();
  }

  @Test
  void theHashOfTheDecoratedObjectIsThatOfThePlaintext() {
    store().put(hello(SECOND_COPY));

    EvidenceStore.StoredObject viaDecorator = store().get(SECOND_COPY).orElseThrow();
    EvidenceStore.StoredObject viaStore = super.store().get(SECOND_COPY).orElseThrow();

    assertThat(viaDecorator.sha256()).isEqualTo(Sha256.of(HELLO));
    assertThat(viaStore.sha256()).isNotEqualTo(Sha256.of(HELLO));
    assertThat(viaStore.sha256()).isEqualTo(Sha256.of(viaStore.bytes()));
  }

  private byte[] rawBytes(EvidenceKey key) {
    return super.store().get(key).orElseThrow().bytes();
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      int j = 0;
      while (j < needle.length && haystack[i + j] == needle[j]) {
        j++;
      }
      if (j == needle.length) {
        return true;
      }
    }
    return false;
  }
}
