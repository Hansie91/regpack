package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.EvidenceStoreContract;
import dev.regpack.testkit.contract.JournalContract;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/**
 * Binds the shared suites {@link EvidenceStoreContract} and {@link JournalContract} to {@link
 * WormEvidenceStore} (T8a). The backend comes from four abstract methods, the seam of T8a, T9 and
 * T10: a {@code *Test} subclass implements them over a fresh {@code FakeS3Server}, a {@code *IT}
 * subclass over the real service. The store's transport is an {@link OutageTransport} around the
 * JDK transport; {@link #simulateOutage} switches it, {@link #reopen} opens a new store on the same
 * bucket (a second object of the class, as a second process would have). The data clock is a {@link
 * ManualClock} at {@code 2040-03-01T00:00:00Z}, so that every retain-until (2045) stays in the
 * server's future (D-W13). Methods that need a fault switch of the fake live in the {@code *Test}
 * subclasses only.
 *
 * <p>A subclass creates its backend lazily (the first call of a seam method), because this class's
 * {@code @BeforeEach} runs before the one of a subclass.
 */
abstract class AbstractWormContractTest implements EvidenceStoreContract, JournalContract {
  static final Instant START = Instant.parse("2040-03-01T00:00:00Z");

  private ManualClock dataClock;
  private OutageTransport transport;
  private WormConfig config;
  private WormEvidenceStore store;

  /** The raw client of the service, with the test credentials. */
  abstract S3Client rawClient();

  /** A fresh bucket with object lock on. */
  abstract String newBucket();

  /** The clock of {@code X-Amz-Date}: real time against a real service, manual on the fake. */
  abstract Clock signingClock();

  /** The endpoint, region and credentials of the service. */
  abstract S3Settings settings();

  @BeforeEach
  void openStore() {
    dataClock = new ManualClock(START);
    transport = new OutageTransport(new JdkTransport(Duration.ofSeconds(60)));
    S3Settings settings = settings();
    config =
        WormConfig.governance(
            settings.endpoint(),
            settings.region(),
            newBucket(),
            settings.credentials(),
            new Durability(true, 1));
    store = WormEvidenceStore.open(config, dataClock, signingClock(), transport);
  }

  @Override
  public EvidenceStore store() {
    return store;
  }

  @Override
  public ManualClock clock() {
    return dataClock;
  }

  @Override
  public void simulateOutage(boolean down) {
    transport.setDown(down);
  }

  @Override
  public EvidenceStore reopen() {
    return WormEvidenceStore.open(config, dataClock, signingClock(), transport);
  }

  /** The configuration of the store under test (its bucket and endpoint). */
  WormConfig config() {
    return config;
  }
}
