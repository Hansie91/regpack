package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T13: the {@code EvidenceStore} contract suite (31 methods) and the journal-format contract
 * suite (11), unchanged, against the {@link dev.regpack.evidence.crypto.EncryptingEvidenceStore}
 * (every key encrypted, one KEK) over P-WORM on the in-JVM fake S3 service. The shared methods come
 * from {@link WormEvidenceStoreContractTest} and so from the two shared interfaces; the one method
 * below shows that the store the suites run on encrypts. The decorator is made once per test, in a
 * {@code BeforeEach} method that runs after the one of the superclass (the racing methods of the
 * suite call {@code store()} from several threads); {@code reopen} opens P-WORM again on the same
 * bucket below a new decorator over the same key service, as a second process would. The outage is
 * the transport's, below the decorator, as in the raw binding. The service twin is {@link
 * WormEncryptedContractIT}.
 */
class EncryptedWormContractTest extends WormEvidenceStoreContractTest {
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

  @Test
  void theBindingReallyEncryptsWhatTheSuitesStore() {
    EvidenceKey key = EvidenceKey.of("landing", "binding-check");
    byte[] plaintext = "not stored in the clear".getBytes(StandardCharsets.UTF_8);
    Retention retention =
        new Retention(new RetentionClass("landing"), clock().now().plus(Duration.ofDays(1826)));

    assertThat(store().put(new PutRequest(key, plaintext, "text/plain", retention)))
        .isEqualTo(PutOutcome.CREATED);

    byte[] stored = super.store().get(key).orElseThrow().bytes();
    assertThat(Envelope.isEnvelope(stored)).as("P-WORM holds an envelope").isTrue();
    assertThat(stored).as("and not the plaintext").isNotEqualTo(plaintext);
    assertThat(super.store().get(key).orElseThrow().sha256())
        .as("the hash the service recorded is that of the envelope")
        .isEqualTo(Sha256.of(stored))
        .isNotEqualTo(Sha256.of(plaintext));
    assertThat(store().get(key).orElseThrow().bytes()).isEqualTo(plaintext);
    assertThat(store().get(key).orElseThrow().sha256()).isEqualTo(Sha256.of(plaintext));
  }
}
