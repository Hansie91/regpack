package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.AfterEach;

/**
 * T9: {@code verify} and the storage half of I10 on P-WORM over the in-JVM fake S3 service. The
 * fake is a stand-in: the run that counts as evidence is the one against the real service ({@code
 * WormVerifyIT}, job {@code evidence-worm}). Every method comes from {@link
 * AbstractWormVerifyTest}; this class adds none.
 */
class WormVerifyTest extends AbstractWormVerifyTest {
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
