package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.Tag;

/**
 * P3c1 T13: {@code verify} over the encrypted tier on P-WORM, and the AT-14 half of I10, over the
 * real S3-compatible object-lock service of the job {@code evidence-worm}. Every method comes from
 * {@link AbstractWormEncryptedVerifyTest}; this class adds none. The key service is the in-memory
 * KMS, a fake; the destruction of a KEK there is the only destruction shown. The fake-backed twin
 * is {@link WormEncryptedVerifyTest}. NOT RUN LOCALLY (no service on the development machine): its
 * first run is the checkpoint CP-2.
 */
@Tag("worm")
class WormEncryptedVerifyIT extends AbstractWormEncryptedVerifyTest {
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
