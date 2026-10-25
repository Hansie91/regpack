package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.Tag;

/**
 * T10: the 24 seal-and-send scenarios of {@code SealScenarioContract} (I1, first-byte limb) and the
 * two own methods of {@link AbstractWormSealTest}, on an in-memory ledger and P-WORM on the real
 * S3-compatible object-lock service of the job {@code evidence-worm}. The method that needs a fault
 * switch of the fake ({@code aMarkerBehindTheAdaptersPagingIsFound}) has no twin here. The
 * fake-backed twin is {@link WormSealScenariosTest}; this class is the P-WORM half of the evidence
 * for I1, and the source-file limb of I1 is P7a.
 */
@Tag("worm")
class WormSealScenariosIT extends AbstractWormSealTest {
  private S3Settings settings;
  private S3Client raw;

  @Override
  S3Settings settings() {
    if (settings == null) {
      settings = S3TestService.settings();
    }
    return settings;
  }

  @Override
  S3Client rawClient() {
    if (raw == null) {
      raw = S3TestService.rawClient(settings());
    }
    return raw;
  }

  @Override
  String newBucket() {
    return S3TestService.newBucket(rawClient());
  }

  @Override
  Clock signingClock() {
    return S3TestService.signingClock();
  }
}
