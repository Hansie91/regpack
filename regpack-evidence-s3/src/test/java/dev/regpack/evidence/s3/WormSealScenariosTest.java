package dev.regpack.evidence.s3;

import static dev.regpack.testkit.contract.SealScenarioContract.reportOf;
import static dev.regpack.testkit.contract.SealScenarioContract.request;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.fake.FakeS3Server.Fault;
import dev.regpack.evidence.seal.EpochMarker;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * T9: the 25 seal-and-send scenarios of {@code SealScenarioContract} (the 24 of P3b and, since P3c1
 * T9, the SEAL-1 scenario; I1, first-byte limb) on an in-memory ledger and P-WORM on the in-JVM
 * fake S3 service, and the two methods of {@link AbstractWormSealTest}. The fake is a stand-in: the
 * run that counts as evidence for P-WORM is the one against the real service ({@code
 * WormSealScenariosIT}, job {@code evidence-worm}). The one method of this class needs a fault
 * switch of the fake and so has no twin there.
 */
class WormSealScenariosTest extends AbstractWormSealTest {
  private FakeWormBackend backend;

  private FakeWormBackend backend() {
    if (backend == null) {
      backend = new FakeWormBackend();
    }
    return backend;
  }

  @AfterEach
  void stopBackend() {
    if (backend != null) {
      backend.close();
    }
  }

  /**
   * The number of requests of one seal on the fake, per case, in the order of {@link
   * SealPathReport#CASES}, counted after a first seal in the bucket (OQ-1, P3c1 T15-2; the pin
   * "Request counts of one DurableSeal.seal on the fake" of the lens before CP-2, measured again).
   * These are counts on the fake, not a measurement of the service. The raw store: new bytes 6 and
   * 9, bytes that are already sealed 5 and 10 (a day later, so that the retention extension is
   * real).
   */
  static final Map<String, Integer> RAW_REQUESTS =
      Map.of(
          SealPathReport.NEW_WITHOUT, 6,
          SealPathReport.NEW_WITH, 9,
          SealPathReport.SHARED_WITHOUT, 5,
          SealPathReport.SHARED_WITH, 10);

  /** The same under the decorator: the decorated put of a key that exists reads it twice. */
  static final Map<String, Integer> ENCRYPTED_REQUESTS =
      Map.of(
          SealPathReport.NEW_WITHOUT, 8,
          SealPathReport.NEW_WITH, 11,
          SealPathReport.SHARED_WITHOUT, 8,
          SealPathReport.SHARED_WITH, 13);

  /**
   * The fake-backed twins assert the counts and print nothing; the service classes only print them.
   * The extension of the retention (one PutObjectRetention and one PutObjectTagging) is in the
   * shared case with the registrar and nowhere else.
   */
  @Override
  void takeSealPath(String binding, List<SealPathReport.Figure> figures) {
    assertThat(figures)
        .as(
            "the seal path of the "
                + binding
                + " binding was measured (why not: "
                + sealPathFailure()
                + ")")
        .hasSize(4);
    Map<String, Integer> expected = binding.equals("raw") ? RAW_REQUESTS : ENCRYPTED_REQUESTS;
    for (SealPathReport.Figure figure : figures) {
      assertThat(figure.byKind().values().stream().mapToInt(Integer::intValue).sum())
          .as("requests of the case " + figure.name() + " of the " + binding + " binding")
          .isEqualTo(expected.get(figure.name()));
      boolean extended = figure.name().equals(SealPathReport.SHARED_WITH);
      assertThat(figure.byKind().getOrDefault("PUT-retention", 0))
          .as("PutObjectRetention in " + figure.name())
          .isEqualTo(extended ? 1 : 0);
      assertThat(figure.byKind().getOrDefault("PUT-tagging", 0))
          .as("PutObjectTagging in " + figure.name())
          .isEqualTo(extended ? 1 : 0);
      assertThat(figure.byKind().getOrDefault("other", 0))
          .as("a request of a kind the adapter is not known to send in " + figure.name())
          .isZero();
    }
    assertThat(figures).extracting(SealPathReport.Figure::name).isEqualTo(SealPathReport.CASES);
  }

  @Override
  S3Client rawClient() {
    return backend().raw();
  }

  @Override
  String newBucket() {
    return backend().newBucket();
  }

  @Override
  Clock signingClock() {
    return backend().signingClock();
  }

  @Override
  S3Settings settings() {
    return backend().settings();
  }

  /**
   * Shows that the caller finds the highest epoch marker over a store whose service pages: the fake
   * lists two entries per page, the marker of the highest epoch lies behind five full pages of the
   * listing, and the send of a token of that epoch is cleared only if {@code EpochMarker.current()}
   * found it (a stale lower epoch would be {@code EPOCH_MARKER_DIFFERS}). {@code
   * EpochMarker.current()} reads to an empty page, so this does not show the adapter's own paging:
   * that the adapter answers a full page over short service pages is shown by the explicit check
   * below (the adapter's {@code list} of the epoch markers returns the ten keys asked for under the
   * fault) and by {@code listFollowsTheMarkersUntilThePageIsFull} and {@code
   * aKeyWhoseEntriesSpanPagesIsCountedOnce}. The teeth for the caller itself are those of {@code
   * EpochMarkerTest} (T5).
   */
  @Test
  void aMarkerBehindTheAdaptersPagingIsFound() {
    backend().server().fault(Fault.SHORT_VERSION_PAGES, true);
    ledger().declareRestoreEpoch(12);
    EndpointKey endpoint = LedgerFixtures.endpoint("PAGE9");
    LeaseToken token = SealScenarioContract.lease(ledger(), endpoint, "A");
    assertThat(token.restoreEpoch()).isEqualTo(12);
    var delivery = SealScenarioContract.queued(ledger(), endpoint, "BK-PAGE9");
    SealOutcome sealed =
        sealing(ledger(), evidence(), false)
            .seal(request(endpoint, token, delivery, "BK-PAGE9", reportOf("BK-PAGE9")));
    assertThat(sealed.status()).isEqualTo(SealStatus.SEALED);

    EpochMarker marker = new EpochMarker(evidence(), RETENTION, clock());
    for (long epoch = 1; epoch <= 12; epoch++) {
      marker.declare(epoch);
    }
    // the adapter's own paging: ten keys asked for, ten answered, over pages of two entries
    assertThat(evidence().list("control/epoch/", Optional.empty(), 10)).hasSize(10);
    int before = backend().server().requestLines().size();

    assertThat(marker.current()).isEqualTo(12);
    long versionPages =
        backend().server().requestLines().stream()
            .skip(before)
            .filter(line -> line.startsWith("GET ") && line.contains("versions"))
            .count();
    assertThat(versionPages)
        .as("twelve markers at two entries a page: the fault made the listing page")
        .isGreaterThanOrEqualTo(6);
    assertThat(
            sealing(ledger(), evidence(), false)
                .beforeFirstByte(endpoint, sealed.attempt().orElseThrow(), token))
        .isEqualTo(PreSendStatus.CLEAR);
  }
}
