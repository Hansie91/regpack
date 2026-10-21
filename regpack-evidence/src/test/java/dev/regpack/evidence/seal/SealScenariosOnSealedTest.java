package dev.regpack.evidence.seal;

import static dev.regpack.testkit.contract.SealScenarioContract.request;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T14a: the crash-point harness and the I1 scenarios of {@code SealScenarioContract} on an
 * in-memory ledger and P-SEALED over two directories of a {@code @TempDir}, on the real file system
 * of the machine (NTFS on the development machine, ext4 and NTFS in CI). The shared methods come
 * from the interface; the methods below are about what the loss of a volume does to the evidence of
 * a seal and to the seal itself, and need real directories.
 */
class SealScenariosOnSealedTest extends AbstractSealScenarioTest {
  private static final Duration TTL = Duration.ofSeconds(60);

  @TempDir Path root;

  private Path volumeA;
  private Path volumeB;
  private SealedEvidenceStore.Config config;

  /** Opens the store over the two volumes; the bindings of a faulty store override this. */
  SealedEvidenceStore openStore(SealedEvidenceStore.Config volumes) {
    return SealedEvidenceStore.open(volumes);
  }

  @Override
  EvidenceStore createEvidence(ManualClock clock) throws IOException {
    Path base = root.toRealPath();
    volumeA = base.resolve("vol-a");
    volumeB = base.resolve("vol-b");
    config = new SealedEvidenceStore.Config(volumeA, volumeB, "vol-a", "vol-b", clock);
    return openStore(config);
  }

  private static void deleteTree(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private LeaseToken lease(EndpointKey endpoint) {
    LeaseResult result = ledger().acquire(endpoint, "A", TTL);
    if (!(result instanceof LeaseResult.Granted granted)) {
      throw new AssertionError("the lease was not granted: " + result);
    }
    return granted.grant().token();
  }

  private static byte[] reportOf(String businessKey) {
    return ("<report>" + businessKey + "</report>").getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void whatTheSealAcknowledgedSurvivesTheLossOfVolumeA() throws IOException {
    acknowledgedEvidenceSurvivesTheLossOf(volumeA, "LOSSA");
  }

  @Test
  void whatTheSealAcknowledgedSurvivesTheLossOfVolumeB() throws IOException {
    acknowledgedEvidenceSurvivesTheLossOf(volumeB, "LOSSB");
  }

  /**
   * The seal is acknowledged when both volumes hold both objects (spec 5.4: it survives the loss of
   * one disk). The disk is lost after the seal, and the other volume alone must hold every object
   * that the manifest names, with the hash and the size of the manifest. The store is opened again
   * with the real file operations, as after the replacement of a disk.
   */
  private void acknowledgedEvidenceSurvivesTheLossOf(Path lost, String name) throws IOException {
    EndpointKey endpoint = LedgerFixtures.endpoint(name);
    LeaseToken token = lease(endpoint);
    DeliveryId delivery = LedgerFixtures.queuedDelivery(ledger(), endpoint, "BK-LOSS", 1);
    SealOutcome outcome =
        sealing(ledger(), evidence(), false)
            .seal(request(endpoint, token, delivery, "BK-LOSS", reportOf("BK-LOSS")));
    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    List<ManifestRow> rows = ledger().manifestRows("", Optional.empty(), 1_000);
    assertThat(rows).hasSize(2);

    // the disk is lost: the directory is gone and an empty one is mounted
    deleteTree(lost);
    SealedEvidenceStore replaced = SealedEvidenceStore.open(config);

    for (ManifestRow row : rows) {
      EvidenceStore.StoredObject stored =
          replaced
              .get(row.entry().key())
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "the surviving volume does not hold " + row.entry().key().path()));
      assertThat(stored.sha256()).as(row.entry().key().path()).isEqualTo(row.entry().sha256());
      assertThat((long) stored.bytes().length).isEqualTo(row.entry().sizeBytes());
    }
    assertThat(replaced.degraded()).as("the replaced volume is empty").isTrue();
  }

  /**
   * A store that holds evidence of an earlier day and has lost volume b: it can be read and no
   * longer written. (A store that holds nothing yet opens healthy over a replaced disk, by design.)
   */
  private SealedEvidenceStore storeThatLostVolumeB() throws IOException {
    evidence()
        .put(
            new EvidenceStore.PutRequest(
                new EvidenceKey("landing/earlier"),
                "earlier".getBytes(StandardCharsets.UTF_8),
                "text/plain",
                new EvidenceStore.Retention(
                    new EvidenceStore.RetentionClass("landing"),
                    clock().now().plus(Duration.ofDays(30)))));
    deleteTree(volumeB);
    SealedEvidenceStore degraded = SealedEvidenceStore.open(config);
    assertThat(degraded.degraded()).isTrue();
    return degraded;
  }

  private void assertNothingSealed(SealedEvidenceStore degraded, DeliveryId delivery) {
    assertThat(degraded.list("", Optional.empty(), 1_000)).as("only the earlier object").hasSize(1);
    assertThat(ledger().manifestRows("", Optional.empty(), 1_000)).isEmpty();
    assertThat(ledger().delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger().attempt(new AttemptId(1))).isEmpty();
  }

  @Test
  void aLostVolumeStopsTheSealBeforeTheLedgerChanges() throws IOException {
    EndpointKey endpoint = LedgerFixtures.endpoint("LOSS3");
    LeaseToken token = lease(endpoint);
    DeliveryId delivery = LedgerFixtures.queuedDelivery(ledger(), endpoint, "BK-LOSS", 1);
    SealRequest request = request(endpoint, token, delivery, "BK-LOSS", reportOf("BK-LOSS"));
    SealedEvidenceStore degraded = storeThatLostVolumeB();

    // without a requirement on the evidence store the put itself fails: nobody acknowledges
    SealOutcome refused = sealing(ledger(), degraded, false).seal(request);

    assertThat(refused.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(refused.detail()).doesNotContain("vol-b").doesNotContain("BK-LOSS");
    assertNothingSealed(degraded, delivery);
  }

  @Test
  void aLostVolumeClosesAGateThatAsksForADurableAcknowledgement() throws IOException {
    EndpointKey endpoint = LedgerFixtures.endpoint("LOSS4");
    LeaseToken token = lease(endpoint);
    DeliveryId delivery = LedgerFixtures.queuedDelivery(ledger(), endpoint, "BK-LOSS", 1);
    SealRequest request = request(endpoint, token, delivery, "BK-LOSS", reportOf("BK-LOSS"));
    SealedEvidenceStore degraded = storeThatLostVolumeB();
    SendGate evidenceMustAcknowledgeDurably =
        new SendGate(new SendGate.Requirement(false, true, 0));

    // the seal does not even start: no attempt id, no put
    SealOutcome closed = create(ledger(), degraded, evidenceMustAcknowledgeDurably).seal(request);

    assertThat(closed.status()).isEqualTo(SealStatus.GATE_CLOSED);
    assertThat(closed.detail()).contains(SendGate.EVIDENCE_NOT_SYNCHRONOUS);
    assertNothingSealed(degraded, delivery);
    assertThat(ledger().allocateAttemptId())
        .as("no attempt id was taken")
        .isEqualTo(new AttemptId(1));
  }
}
