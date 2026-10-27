package dev.regpack.evidence.seal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.crypto.EncryptedFixtures;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.evidence.sealed.SealedTamper;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P3c1 T6: the shared seal-and-send scenarios of {@code SealScenarioContract}, unchanged, with the
 * {@link dev.regpack.evidence.crypto.EncryptingEvidenceStore} (every key encrypted) over P-SEALED
 * on two directories of a {@code @TempDir}. It extends {@link AbstractSealScenarioTest} directly
 * and not {@link SealScenariosOnSealedTest}, whose own tests compare the raw stored hash and size
 * with the manifest row (those of the encrypted tier differ by design: the row names the
 * plaintext), so it runs the shared scenarios only.
 */
class SealScenariosEncryptedOnSealedTest extends AbstractSealScenarioTest {
  @TempDir Path root;

  private final EncryptedFixtures fixtures = EncryptedFixtures.create();
  private Path volumeA;
  private Path volumeB;

  @Override
  EvidenceStore createEvidence(ManualClock clock) throws IOException {
    Path base = root.toRealPath();
    volumeA = base.resolve("vol-a");
    volumeB = base.resolve("vol-b");
    return fixtures.encrypt(
        SealedEvidenceStore.open(
            new SealedEvidenceStore.Config(volumeA, volumeB, "vol-a", "vol-b", clock)),
        clock);
  }

  @Test
  void neitherVolumeHoldsThePlaintextOfASealedFile() throws IOException {
    EndpointKey endpoint = LedgerFixtures.endpoint("ENC2");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    DeliveryId delivery = SealScenarioContract.queued(ledger(), endpoint, "BK-ENC2");
    byte[] report = SealScenarioContract.reportOf("BK-ENC2");

    SealOutcome outcome =
        sealing(ledger(), evidence(), false)
            .seal(SealScenarioContract.request(endpoint, token, delivery, "BK-ENC2", report));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(report));
    // the decorated view serves the plaintext and the manifest names its hash
    assertThat(evidence().get(sealedKey).orElseThrow().bytes()).isEqualTo(report);
    assertThat(ledger().manifestRow(sealedKey).orElseThrow().entry().sha256())
        .isEqualTo(Sha256.of(report));
    // and no file of either volume holds the report, while both hold the sealed object
    for (Path volume : List.of(volumeA, volumeB)) {
      assertThat(SealedTamper.objectPath(volume, sealedKey)).exists();
      try (Stream<Path> files = Files.walk(volume)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          assertThat(contains(Files.readAllBytes(file), report))
              .as("the plaintext in " + volume.relativize(file))
              .isFalse();
        }
      }
    }
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
