package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.AfterEach;

/**
 * P3c1 T13: {@code verify} over the encrypted tier on P-WORM, and the AT-14 half of I10, over the
 * in-JVM fake S3 service. Every method comes from {@link AbstractWormEncryptedVerifyTest}; this
 * class adds none. The fake is a stand-in: the run that counts as evidence for P-WORM is the one
 * against the real service ({@link WormEncryptedVerifyIT}, job {@code evidence-worm}).
 */
class WormEncryptedVerifyTest extends AbstractWormEncryptedVerifyTest {
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
}
