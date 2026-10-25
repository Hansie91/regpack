package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.AfterEach;

/**
 * T8a and T8b: the {@code EvidenceStore} contract suite (31 methods) and the journal-format
 * contract suite (11), unchanged, against P-WORM on the in-JVM fake S3 service (spec 14.1: the same
 * suite on every evidence adapter). Every method comes from the two shared interfaces; this class
 * adds none and overrides none.
 */
class WormEvidenceStoreContractTest extends AbstractWormContractTest {
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
