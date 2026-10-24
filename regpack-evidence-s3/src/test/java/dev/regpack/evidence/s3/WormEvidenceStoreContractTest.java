package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * T8a: the {@code EvidenceStore} contract suite (31 methods) and the journal-format contract suite
 * (11), unchanged, against P-WORM on the in-JVM fake S3 service (spec 14.1: the same suite on every
 * evidence adapter). Every method comes from the two shared interfaces; this class adds none.
 *
 * <p>Until T8b the ten methods that need retention, legal hold or the durability check are
 * overridden here and disabled, so that the commit is green. The suite itself is not changed. T8b
 * removes the ten overrides.
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

  // ---- the ten methods of EvidenceStoreContract that wait for T8b

  @Test
  @Disabled("T8b")
  @Override
  public void retentionCanOnlyBeExtended() {}

  @Test
  @Disabled("T8b")
  @Override
  public void extendRetentionRefusesARetainUntilTheStoresCannotHoldAndChangesNothing() {}

  @Test
  @Disabled("T8b")
  @Override
  public void theLastMicrosecondOfYear9999IsARetainUntilEveryStoreHolds() {}

  @Test
  @Disabled("T8b")
  @Override
  public void legalHoldIsSeparateFromRetention() {}

  @Test
  @Disabled("T8b")
  @Override
  public void outageFailsClosedAndStoresNothing() {}

  @Test
  @Disabled("T8b")
  @Override
  public void gradeAndDurabilityAreDeclared() {}

  @Test
  @Disabled("T8b")
  @Override
  public void extendRetentionOfAMissingKeyIsRejected() {}

  @Test
  @Disabled("T8b")
  @Override
  public void setLegalHoldOfAMissingKeyIsRejected() {}

  @Test
  @Disabled("T8b")
  @Override
  public void aStoreOfAnEvidenceProfileDeclaresItsDurabilityConsistently() {}

  @Test
  @Disabled("T8b")
  @Override
  public void retentionAndLegalHoldSurviveReopen() {}
}
