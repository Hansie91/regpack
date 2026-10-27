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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T13: the {@code EvidenceStore} contract suite and the journal-format contract suite,
 * unchanged, against the {@link dev.regpack.evidence.crypto.EncryptingEvidenceStore} (every key
 * encrypted, one KEK) over P-WORM on the real S3-compatible object-lock service of the job {@code
 * evidence-worm} (governance mode, one declared failure domain, graded {@code P-WORM/governance
 * (unverified)}). The shared methods come from {@link WormEvidenceStoreContractIT} and so from the
 * two shared interfaces; the one method below shows that the store the suites run on encrypts. It
 * skips when no service is described by the {@code REGPACK_S3_*} variables and fails when {@code
 * REGPACK_S3_REQUIRED=true} and the service is missing ({@link S3TestService}). The fake-backed
 * twin is {@link EncryptedWormContractTest}. NOT RUN LOCALLY (no service on the development
 * machine): its first run is the checkpoint CP-2.
 */
@Tag("worm")
class WormEncryptedContractIT extends WormEvidenceStoreContractIT {
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
