package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.Tag;

/**
 * Invariant V and the other writers (D-W2 to D-W4; WF2, WF3, WF4, WF6) on P-WORM over the real
 * S3-compatible object-lock service of the job {@code evidence-worm}. Every method comes from
 * {@link AbstractWormVersionsTest}; this class adds none. The fake-backed twin is {@link
 * WormStoreVersionsTest}, which also holds the methods that need a hook or a fault of the fake. A
 * version behind the adapter's back is made through the raw client and counted through it; nothing
 * is deleted from a locked bucket.
 */
@Tag("worm")
class WormVersionsIT extends AbstractWormVersionsTest {
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
