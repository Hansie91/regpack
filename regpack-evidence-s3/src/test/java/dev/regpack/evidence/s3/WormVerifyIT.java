package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.Tag;

/**
 * T10: {@code verify} and the storage half of I10 on P-WORM over the real S3-compatible object-lock
 * service of the job {@code evidence-worm}. Every method comes from {@link AbstractWormVerifyTest};
 * this class adds none. The fake-backed twin is {@link WormVerifyTest}. A hidden object, a damaged
 * object and a lock shortened behind the back are made through the raw client (the bypass header
 * lives in test code only); on the service they only shorten a lock, none deletes a version.
 */
@Tag("worm")
class WormVerifyIT extends AbstractWormVerifyTest {
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
