package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.fake.FakeS3Server;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The backend of the {@code *Test} classes of the P-WORM adapter: one {@link FakeS3Server} with a
 * raw {@link S3Client} (test credentials, a manual signing clock, which the fake does not check),
 * and a fresh object-lock bucket on demand. Closing it stops the server. A {@code *IT} class has
 * the same four things over the real service ({@code S3TestService}); fault switches and request
 * counts exist only here.
 */
final class FakeWormBackend implements AutoCloseable {
  static final String REGION = "us-east-1";
  static final String KEY_ID = "AKIAFAKEWORM000001";
  static final String SECRET = "fake-worm-secret-0123456789abcdef";

  private final FakeS3Server server;
  private final ManualClock signingClock;
  private final S3Client raw;

  FakeWormBackend() {
    server = FakeS3Server.start(KEY_ID, SECRET);
    signingClock = new ManualClock(Instant.parse("2040-03-01T00:00:00Z"));
    raw =
        new S3Client(
            server.endpoint(),
            REGION,
            new Credentials(KEY_ID, SECRET),
            signingClock,
            new JdkTransport(Duration.ofSeconds(10)));
  }

  FakeS3Server server() {
    return server;
  }

  /** The raw client: a version behind the adapter's back, a delete marker, a bucket. */
  S3Client raw() {
    return raw;
  }

  ManualClock signingClock() {
    return signingClock;
  }

  S3Settings settings() {
    return new S3Settings(server.endpoint(), REGION, new Credentials(KEY_ID, SECRET));
  }

  /** A fresh bucket with object lock on: {@code rp-} and 12 hex digits of a random UUID. */
  String newBucket() {
    String bucket = "rp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    raw.createBucket(bucket, true);
    return bucket;
  }

  @Override
  public void close() {
    server.close();
  }
}
