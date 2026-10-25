package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.Tag;

/**
 * T10: the {@code EvidenceStore} contract suite and the journal-format contract suite, unchanged,
 * against P-WORM on the real S3-compatible object-lock service of the job {@code evidence-worm}
 * (governance mode, one declared failure domain, graded {@code P-WORM/governance (unverified)}).
 * Every method comes from {@link AbstractWormContractTest}; this class adds none and overrides none
 * of them. It skips when no service is described by the {@code REGPACK_S3_*} variables and fails
 * when {@code REGPACK_S3_REQUIRED=true} and the service is missing ({@link S3TestService}). The
 * fake-backed twin is {@link WormEvidenceStoreContractTest}.
 */
@Tag("worm")
class WormEvidenceStoreContractIT extends AbstractWormContractTest {
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
